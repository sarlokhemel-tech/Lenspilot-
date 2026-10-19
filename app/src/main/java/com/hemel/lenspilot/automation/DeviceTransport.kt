package com.hemel.lenspilot.automation

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** ESP32-র কাছে পাঠানো একটা HTTP-ধাঁচের রিকোয়েস্ট। ট্রান্সপোর্ট (WiFi / ব্লুটুথ / OTG) যাই হোক, উপরের স্তর
 * (DeviceSession, LocalProxyServer) একই DeviceRequest/DeviceResponse নিয়ে কাজ করে। */
class DeviceRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

class DeviceResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
}

interface DeviceTransport {
    val label: String

    /** ব্লকিং — ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। */
    @Throws(IOException::class)
    fun roundTrip(req: DeviceRequest): DeviceResponse

    fun close()
}

/** ব্লুটুথ / OTG — ২য় অংশে (সিরিয়াল-HTTP ব্রিজ + ফ্ল্যাশ ইঞ্জিনের সাথে) আসবে। */
class NotReadyTransport(override val label: String, private val why: String) : DeviceTransport {
    override fun roundTrip(req: DeviceRequest): DeviceResponse = throw IOException(why)
    override fun close() {}
}

/** ছোট হাতে-লেখা HTTP/1.x পার্সার — ESP32-র WebServer/AsyncWebServer-এর উত্তর (Content-Length, chunked,
 * বা কানেকশন-বন্ধ পর্যন্ত) আর লোকাল প্রক্সির রিকোয়েস্ট পড়ার জন্য। OkHttp ব্যবহার না করার কারণ: Android-এর
 * cleartext নীতি আর নির্দিষ্ট Network-এ সকেট বাঁধা — raw সকেটে যেকোনো ap_host-এ কাজ করে। */
object HttpWire {
    const val MAX_BODY = 8 * 1024 * 1024

    fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16 * 1024) throw IOException("হেডার লাইন অতিরিক্ত বড়")
        }
        return sb.toString()
    }

    fun readHeaders(inp: InputStream): MutableList<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return headers
    }

    fun readN(inp: InputStream, n: Int): ByteArray {
        if (n < 0 || n > MAX_BODY) throw IOException("বডি অতিরিক্ত বড়")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) break // ESP32 মাঝে মাঝে কম পাঠায় — যা পেয়েছি তাই
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    fun readAll(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = inp.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_BODY) throw IOException("উত্তর অতিরিক্ত বড়")
        }
        return out.toByteArray()
    }

    private fun readChunked(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(inp) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("খারাপ chunk")
            if (size == 0) {
                while (true) { val t = readLine(inp) ?: break; if (t.isEmpty()) break }
                break
            }
            out.write(readN(inp, size))
            readLine(inp) // chunk-এর শেষের CRLF
            if (out.size() > MAX_BODY) throw IOException("উত্তর অতিরিক্ত বড়")
        }
        return out.toByteArray()
    }

    fun readResponse(raw: InputStream, headOnly: Boolean): DeviceResponse {
        val inp = BufferedInputStream(raw)
        val statusLine = readLine(inp) ?: throw IOException("ডিভাইস থেকে খালি উত্তর")
        val parts = statusLine.split(" ", limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("ডিভাইসের উত্তর বোঝা যায়নি")
        val status = parts[1].toIntOrNull() ?: throw IOException("ডিভাইসের উত্তর বোঝা যায়নি")
        val reason = parts.getOrElse(2) { "" }
        val headers = readHeaders(inp)
        val te = headers.firstOrNull { it.first.equals("Transfer-Encoding", true) }?.second ?: ""
        val cl = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull()
        val body = when {
            headOnly || status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
            te.contains("chunked", true) -> readChunked(inp)
            cl != null -> readN(inp, cl)
            else -> readAll(inp)
        }
        return DeviceResponse(status, reason, headers, body)
    }
}
