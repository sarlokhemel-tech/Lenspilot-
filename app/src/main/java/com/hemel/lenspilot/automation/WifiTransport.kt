package com.hemel.lenspilot.automation

import android.net.Network
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** ESP32-র Access Point (সাধারণত 192.168.4.1) এর সাথে WiFi দিয়ে কথা বলে। [network] দিয়ে সকেট ওই WiFi
 * নেটওয়ার্কেই বাঁধা হয় — ESP32-র AP-তে ইন্টারনেট নেই বলে অ্যান্ড্রয়েড অন্যথায় মোবাইল ডেটায় পাঠাতে চাইত। */
class WifiTransport(
    private val network: Network?,
    private val host: String,
    private val port: Int = 80
) : DeviceTransport {

    override val label = "WiFi"
    @Volatile private var closed = false

    override fun roundTrip(req: DeviceRequest): DeviceResponse {
        if (closed) throw IOException("সংযোগ বন্ধ")
        val sock: Socket = if (network != null) network.socketFactory.createSocket() else Socket()
        sock.use { s ->
            s.soTimeout = 20_000
            try {
                s.connect(InetSocketAddress(host, port), 8_000)
            } catch (e: IOException) {
                throw IOException("ESP32-র সাথে যোগাযোগ হচ্ছে না ($host) — WiFi/ডিভাইস চালু আছে?")
            }
            val sb = StringBuilder()
            sb.append(req.method).append(' ').append(req.path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append("\r\n")
            sb.append("Connection: close\r\n")
            for ((k, v) in req.headers) sb.append(k).append(": ").append(v).append("\r\n")
            if (req.body.isNotEmpty() || req.method == "POST" || req.method == "PUT") {
                sb.append("Content-Length: ").append(req.body.size).append("\r\n")
            }
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (req.body.isNotEmpty()) out.write(req.body)
            out.flush()
            return HttpWire.readResponse(s.getInputStream(), req.method == "HEAD")
        }
    }

    override fun close() { closed = true }
}
