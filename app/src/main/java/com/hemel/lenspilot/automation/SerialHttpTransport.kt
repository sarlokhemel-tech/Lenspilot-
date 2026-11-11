package com.hemel.lenspilot.automation

import java.io.IOException

/**
 * ব্লুটুথ ও OTG-র জন্য "সিরিয়াল-HTTP ব্রিজ"। WiFi-তে যা HTTP রিকোয়েস্ট, সিরিয়ালে সেটাই এক লাইনের হেডার + বডি:
 *
 *   অ্যাপ → ESP32:  LPQ<TAB>METHOD<TAB>/path<TAB>content-type-বা-"-"<TAB>token-বা-"-"<TAB>বডি-বাইট\n  + বডি
 *   ESP32 → অ্যাপ:  LPR<TAB>status<TAB>content-type<TAB>বডি-বাইট\n  + বডি
 *
 * ফলে DeviceSession (অ্যাপ-লক), LocalProxyServer আর WebView আগের মতোই কাজ করে — ট্রান্সপোর্ট বদলেছে শুধু।
 * "LPR\t" দিয়ে শুরু নয় এমন যেকোনো লাইন (ESP32-র বুট-লগ, Serial.print-এর ডিবাগ) এড়িয়ে যাওয়া হয়।
 * দেখো AUTOMATION_FRAMEWORK.md-এর রেফারেন্স ফার্মওয়্যার।
 */
class SerialHttpTransport(
    private val link: SerialLink,
    private val idleTimeoutMs: Long = 15_000
) : DeviceTransport {

    override val label: String get() = link.label
    @Volatile private var closed = false

    override fun roundTrip(req: DeviceRequest): DeviceResponse {
        if (closed) throw IOException("Connection closed")
        synchronized(this) {
            link.rx.clear()
            val ctype = headerOf(req, "Content-Type") ?: "-"
            val token = headerOf(req, "X-LP-Token")?.ifEmpty { null } ?: "-"
            val head = "LPQ\t${req.method}\t${req.path}\t$ctype\t$token\t${req.body.size}\n"
            link.write(head.toByteArray(Charsets.ISO_8859_1) + req.body)
            return readResponse()
        }
    }

    private fun headerOf(req: DeviceRequest, name: String): String? =
        req.headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun readResponse(): DeviceResponse {
        while (true) {
            val line = link.rx.readLine(idleTimeoutMs)
                ?: throw IOException("No reply from the ESP32. Does the firmware have the serial bridge (LPQ/LPR)? Is the ${link.label} connection working?")
            if (!line.startsWith("LPR\t")) continue
            val f = line.split('\t')
            if (f.size < 4) continue
            val status = f[1].trim().toIntOrNull() ?: continue
            val len = f[3].trim().toIntOrNull() ?: continue
            if (len < 0 || len > HttpWire.MAX_BODY) throw IOException("The device response is too large")
            val body = link.rx.readN(len, idleTimeoutMs)
            val ctype = f[2].trim().ifEmpty { "application/octet-stream" }
            return DeviceResponse(status, reasonFor(status), listOf("Content-Type" to ctype), body)
        }
    }

    private fun reasonFor(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    override fun close() {
        closed = true
        link.close()
    }
}
