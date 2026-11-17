package com.hemel.lenspilot.automation

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * OTG দিয়ে ESP32 ফ্ল্যাশ — esptool.py-র ROM-বুটলোডার প্রোটোকলের Kotlin রূপ (SLIP + SYNC + FLASH_DEFL_*, MD5 যাচাইসহ)।
 * ধাপ: বুটলোডারে ঢোকানো (DTR/RTS) → SYNC → চিপ চেনা → (সম্ভব হলে) বড-রেট বাড়ানো → প্রতিটা পার্ট zlib-এ চেপে
 * লেখা → ফ্ল্যাশের MD5 লোকাল ফাইলের সাথে মেলানো → হার্ড রিসেট। ব্লকিং; ব্যাকগ্রাউন্ড থ্রেডে চালাতে হবে।
 * ROM-লোডারের পথ (স্টাব নেই) — তাই সবচেয়ে সরল ও নির্ভরযোগ্য; গতি ৪৬০৮০০ বডে সাধারণত ১ MB ≈ ১৫-২৫ সেকেন্ড।
 */
class EspFlasher(private val link: UsbLink, private val log: (String) -> Unit) {

    class Part(val offset: Long, val data: ByteArray, val name: String)

    /** (পার্ট নম্বর ১-ভিত্তিক, মোট পার্ট, ওই পার্টের শতাংশ, বার্তা) */
    fun interface Progress { fun update(partNo: Int, partCount: Int, percent: Int, msg: String) }

    @Volatile var cancelled = false

    private var chip = "unknown"
    private var currentBaud = 115200

    // ---------- SLIP ----------

    private fun slipEncode(p: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(p.size + 16)
        out.write(0xC0)
        for (b in p) {
            when (b.toInt() and 0xFF) {
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    /** একটা পূর্ণ SLIP ফ্রেম; [timeoutMs]-এর মধ্যে না এলে null। ফ্রেমের বাইরের বাইট (বুট-লগ) বাদ। */
    private fun readFrame(timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        val out = ByteArrayOutputStream()
        var inFrame = false
        var esc = false
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val b = link.rx.read(left)
            if (b < 0) return null
            if (!inFrame) {
                if (b == 0xC0) { inFrame = true; out.reset(); esc = false }
                continue
            }
            if (b == 0xC0) {
                if (out.size() == 0) continue
                return out.toByteArray()
            }
            if (esc) {
                esc = false
                when (b) {
                    0xDC -> out.write(0xC0)
                    0xDD -> out.write(0xDB)
                    else -> out.write(b)
                }
            } else if (b == 0xDB) esc = true
            else out.write(b)
        }
    }

    // ---------- কমান্ড ----------

    private class Resp(val value: Long, val data: ByteArray)

    private fun le(vararg v: Long): ByteArray {
        val b = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putInt(x.toInt())
        return b.array()
    }

    private fun xorChecksum(data: ByteArray): Int {
        var c = 0xEF
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }

    private fun command(op: Int, data: ByteArray = ByteArray(0), chk: Int = 0, timeoutMs: Long = 3_000): Resp {
        val pkt = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        pkt.put(0.toByte())
        pkt.put(op.toByte())
        pkt.putShort(data.size.toShort())
        pkt.putInt(chk)
        pkt.put(data)
        link.write(slipEncode(pkt.array()))
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (cancelled) throw IOException("Flashing was cancelled")
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw IOException("The ESP32 did not respond (command 0x%02X)".format(op))
            val f = readFrame(left) ?: throw IOException("The ESP32 did not respond (command 0x%02X)".format(op))
            if (f.size < 8) continue
            if ((f[0].toInt() and 0xFF) != 1) continue
            if ((f[1].toInt() and 0xFF) != op) continue
            val value = ByteBuffer.wrap(f, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            return Resp(value, f.copyOfRange(8, f.size))
        }
    }

    /** উত্তরের data = [payloadLen বাইট পে-লোড] + স্ট্যাটাস (২ বা ৪ বাইট: প্রথমটা ≠ 0 হলে ত্রুটি, দ্বিতীয়টা কারণ)। */
    private fun check(what: String, r: Resp, payloadLen: Int = 0): Resp {
        if (r.data.size > payloadLen && r.data[payloadLen].toInt() != 0) {
            val reason = if (r.data.size > payloadLen + 1) r.data[payloadLen + 1].toInt() and 0xFF else 0
            throw IOException("$what failed (ESP32 error code 0x%02X)".format(reason))
        }
        return r
    }

    // ---------- বুটলোডারে ঢোকা ----------

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun resetClassic() {
        link.setLines(false, true); sleep(100)     // EN=LOW
        link.setLines(true, false); sleep(50)      // IO0=LOW, EN=HIGH → ডাউনলোড মোড
        link.setLines(false, false)
    }

    private fun resetUsbJtag() {
        link.setLines(false, false); sleep(100)
        link.setLines(true, false); sleep(100)
        link.setLines(false, true)
        link.setLines(false, true); sleep(100)
        link.setLines(false, false)
    }

    private fun hardReset() {
        link.setLines(false, true); sleep(100)
        link.setLines(false, false)
    }

    private fun trySync(): Boolean {
        val payload = ByteArray(36)
        payload[0] = 0x07; payload[1] = 0x07; payload[2] = 0x12; payload[3] = 0x20
        for (i in 4 until 36) payload[i] = 0x55
        for (i in 0 until 5) {
            link.rx.clear()
            try {
                command(0x08, payload, 0, 150)
                sleep(60)
                link.rx.clear()     // ROM আরও কয়েকটা SYNC-উত্তর পাঠায় — ফেলে দাও
                return true
            } catch (e: IOException) {
                if (cancelled) throw e
            }
        }
        return false
    }

    private fun connect() {
        val order = if (link.isNativeUsb) listOf(true, false, true) else listOf(false, true, false)
        for ((i, jtag) in order.withIndex()) {
            log(if (i == 0) "Entering the bootloader…" else "Retrying (${i + 1})…")
            if (jtag) resetUsbJtag() else resetClassic()
            sleep(50)
            if (trySync()) return
        }
        throw IOException("The ESP32 did not enter the bootloader. Hold the ESP32 BOOT button and try again (boards without an auto-reset circuit need this).")
    }

    private fun detectChip(): String {
        val v = check("Detecting chip", command(0x0A, le(0x40001000L))).value
        return when (v) {
            0x00F01D83L -> "esp32"
            0x000007C6L -> "esp32s2"
            0x00000009L -> "esp32s3"
            0x6921506FL, 0x1B31506FL, 0x4881606FL, 0x4361606FL -> "esp32c3"
            0x6F51306FL, 0x7C41A06FL -> "esp32c2"
            0x2CE0806FL -> "esp32c6"
            0xD7B73E80L -> "esp32h2"
            else -> "unknown"
        }
    }

    private fun changeBaud(target: Int) {
        check("Changing baud rate", command(0x0F, le(target.toLong(), 0)))
        sleep(50)
        link.setBaud(target)
        currentBaud = target
        link.rx.clear()
        sleep(50)
        check("Verifying baud rate", command(0x0A, le(0x40001000L), timeoutMs = 1_500))
    }

    // ---------- লেখা ----------

    private fun timeoutPerMb(secPerMb: Int, bytes: Long): Long =
        maxOf(3_000L, (secPerMb * 1000L * bytes / 1_000_000L))

    private fun zlib(data: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(16384)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun md5Hex(b: ByteArray) =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun flashPart(part: Part, partNo: Int, partCount: Int, progress: Progress) {
        var image = part.data
        if (image.size % 4 != 0) {
            image += ByteArray(4 - image.size % 4) { 0xFF.toByte() }
        }
        val uncSize = image.size
        val compressed = zlib(image)
        val blockSize = 0x400
        val numBlocks = (compressed.size + blockSize - 1) / blockSize
        val eraseBlocks = (uncSize + blockSize - 1) / blockSize
        val writeSize = eraseBlocks.toLong() * blockSize
        val wantsEncArg = chip != "esp32"      // নতুন চিপের ROM-এ ৫ম শব্দ (encrypted-flag) লাগে

        log("${part.name} → 0x%X  (${uncSize / 1024} KB, ${compressed.size / 1024} KB after compression)".format(part.offset))
        progress.update(partNo, partCount, 0, "${part.name}: erasing flash…")
        val begin = if (wantsEncArg) le(writeSize, numBlocks.toLong(), blockSize.toLong(), part.offset, 0)
        else le(writeSize, numBlocks.toLong(), blockSize.toLong(), part.offset)
        check("Starting flash", command(0x10, begin, 0, timeoutPerMb(30, writeSize) * 2 + 3_000))

        for (seq in 0 until numBlocks) {
            if (cancelled) throw IOException("Flashing was cancelled")
            val from = seq * blockSize
            val block = compressed.copyOfRange(from, minOf(from + blockSize, compressed.size))
            val payload = le(block.size.toLong(), seq.toLong(), 0, 0) + block
            check("Writing data (block ${seq + 1}/$numBlocks)", command(0x11, payload, xorChecksum(block), 8_000))
            if (seq % 8 == 0 || seq == numBlocks - 1) {
                progress.update(partNo, partCount, ((seq + 1) * 100L / numBlocks).toInt(), "${part.name}: writing…")
            }
        }

        progress.update(partNo, partCount, 100, "${part.name}: verifying…")
        val r = check(
            "MD5 verification",
            command(0x13, le(part.offset, uncSize.toLong(), 0, 0), 0, timeoutPerMb(8, uncSize.toLong()) + 3_000),
            payloadLen = 32
        )
        if (r.data.size < 32) throw IOException("MD5 verification: the ESP32 response is too short")
        val got = String(r.data, 0, 32, Charsets.US_ASCII).lowercase()
        val want = md5Hex(image)
        if (got != want) throw IOException("${part.name}: the data written to flash does not match (MD5). Please try again.")
    }

    /**
     * সব পার্ট ফ্ল্যাশ করে রিসেট। [baud] সম্ভব না হলে ১১৫২০০-তেই চলে। ফেরত: চিপের নাম।
     * ব্যর্থ হলে IOException (বাংলা বার্তাসহ)।
     */
    fun flashAll(parts: List<Part>, baud: Int, progress: Progress): String {
        if (parts.isEmpty()) throw IOException("There is no file to flash")
        link.setBaud(115200)
        currentBaud = 115200
        connect()
        chip = detectChip()
        log("Chip: $chip")

        if (baud > 115200 && !link.isNativeUsb) {
            try {
                log("Raising the baud rate to $baud…")
                changeBaud(baud)
            } catch (e: Exception) {
                if (cancelled) throw IOException("Flashing was cancelled")
                log("Could not raise the baud rate. Continuing at 115200.")
                link.setBaud(115200)
                currentBaud = 115200
                connect()
            }
        }

        runCatching { command(0x0D, le(0, 0)) }                                   // SPI_ATTACH (ব্যর্থ হলে সমস্যা নেই)
        runCatching { command(0x0B, le(0, 0x1000000, 0x10000, 0x1000, 0x100, 0xFFFF)) }  // SPI_SET_PARAMS

        parts.forEachIndexed { i, p -> flashPart(p, i + 1, parts.size, progress) }

        log("Resetting…")
        hardReset()
        if (currentBaud != 115200) {
            runCatching { link.setBaud(115200) }
        }
        return chip
    }
}
