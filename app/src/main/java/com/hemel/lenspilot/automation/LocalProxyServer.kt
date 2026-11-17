package com.hemel.lenspilot.automation

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * "লোকাল হোস্ট": অ্যাপের ভেতরের WebView ESP32-কে সরাসরি না ধরে http://127.0.0.1:<port> ধরে — এই ছোট
 * প্রক্সি সেটা [DeviceSession]-এর মাধ্যমে ESP32-তে (অ্যাপ-লকসহ) পাঠায়। ফলে ট্রান্সপোর্ট WiFi/ব্লুটুথ/OTG
 * যাই হোক, পেজ একই ঠিকানায় আসে।
 *
 * ফোনের অন্য অ্যাপ/ব্রাউজার যেন এই লোকাল পোর্টে ঢুকে ডিভাইস চালাতে না পারে: (১) শুধু 127.0.0.1-এ বাঁধা,
 * (২) Host হেডার যাচাই (DNS-rebinding ঠেকাতে), (৩) এন্ট্রি-URL-এ থাকা এলোমেলো sid থেকে বসানো কুকি ছাড়া
 * সব ৪০৩।
 */
class LocalProxyServer(private val session: DeviceSession) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sid: String = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var running = false
    @Volatile var port: Int = 0
        private set

    /** এন্ট্রি URL ফেরত দেয় — WebView-তে প্রথমে এটাই লোড করতে হবে। */
    fun start(): String {
        val ss = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        running = true
        pool.execute {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: IOException) {
                    break
                }
            }
        }
        return "http://127.0.0.1:$port/__lp_enter/$sid"
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 30_000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val reqLine = HttpWire.readLine(inp) ?: return
                val rl = reqLine.split(" ")
                if (rl.size < 2) return
                val method = rl[0].uppercase()
                val path = rl[1]
                val headers = HttpWire.readHeaders(inp)
                fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second

                val host = h("Host") ?: ""
                if (host != "127.0.0.1:$port" && host != "localhost:$port") {
                    write(out, 403, "Forbidden", "text/plain; charset=utf-8", "403".toByteArray()); return
                }
                val cl = h("Content-Length")?.trim()?.toIntOrNull() ?: 0
                if (h("Transfer-Encoding")?.contains("chunked", true) == true) {
                    write(out, 411, "Length Required", "text/plain; charset=utf-8", "411".toByteArray()); return
                }
                val body = if (cl > 0) HttpWire.readN(inp, cl) else ByteArray(0)

                if (path == "/__lp_enter/$sid") {
                    val extra = listOf(
                        "Set-Cookie" to "lpsid=$sid; Path=/; HttpOnly; SameSite=Strict",
                        "Location" to "/"
                    )
                    write(out, 302, "Found", "text/plain; charset=utf-8", ByteArray(0), extra); return
                }
                val cookie = h("Cookie") ?: ""
                if (!cookie.split(";").any { it.trim() == "lpsid=$sid" }) {
                    write(out, 403, "Forbidden", "text/plain; charset=utf-8",
                        "This page can only be opened from inside the LensPilot app.".toByteArray()); return
                }
                if (path.startsWith("/lp/")) { // অ্যাপ-লকের নিজস্ব এন্ডপয়েন্ট পেজ থেকে ছোঁয়া যাবে না
                    write(out, 404, "Not Found", "text/plain; charset=utf-8", "404".toByteArray()); return
                }

                val fwd = LinkedHashMap<String, String>()
                h("Content-Type")?.let { fwd["Content-Type"] = it }
                h("Accept")?.let { fwd["Accept"] = it }
                val resp = try {
                    session.forward(DeviceRequest(method, path, fwd, body))
                } catch (e: IOException) {
                    val msg = "<meta charset=utf-8><body style='font-family:sans-serif;padding:24px'>" +
                        "<h3>Communication with the ESP32 failed</h3><p>${(e.message ?: "").replace("<", "&lt;")}</p>\n"
                    write(out, 502, "Bad Gateway", "text/html; charset=utf-8", msg.toByteArray()); return
                }
                val extra = ArrayList<Pair<String, String>>()
                for ((k, v) in resp.headers) {
                    when (k.lowercase()) {
                        "content-encoding", "cache-control" -> extra.add(k to v)
                        "location" -> extra.add(k to v.replaceFirst(Regex("^https?://[^/]+"), ""))
                    }
                }
                write(out, resp.status, resp.reason.ifEmpty { "OK" },
                    resp.header("Content-Type") ?: "application/octet-stream", resp.body, extra,
                    headOnly = method == "HEAD")
            } catch (e: Exception) {
                // সংযোগ ভেঙে গেলে চুপচাপ বন্ধ
            }
        }
    }

    private fun write(
        out: java.io.OutputStream, status: Int, reason: String, contentType: String, body: ByteArray,
        extra: List<Pair<String, String>> = emptyList(), headOnly: Boolean = false
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        if (extra.none { it.first.equals("Cache-Control", true) }) sb.append("Cache-Control: no-store\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
