package com.hemel.lenspilot.tools.browser

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import com.hemel.lenspilot.tools.StepLog
import com.hemel.lenspilot.tools.ToolsAsync
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.runner.FileDownloaded
import com.hemel.lenspilot.tools.runner.ToolsEvents
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** RealBrowser-এর ডাউনলোড-পথে বসানো হুক: সেট থাকলে সিস্টেম Downloads-এ নয়, এখানে আসে। */
interface DownloadSink {
    /** সাধারণ http/https ডাউনলোড। সবসময় true (সেশন-মোডে সিস্টেম Downloads-এ কখনো যায় না)। */
    fun fromUrl(url: String, userAgent: String?, contentDisposition: String?, mime: String?, referer: String?): Boolean

    /** blob: (পেজ থেকে বাইট এসে data: হয়ে) ও data: URL। */
    fun fromDataUrl(dataUrl: String): Boolean
}

/**
 * টুলস-সেশনের ডাউনলোড: sessions/<id>/workspace/downloads/।
 *  - নাম পরিষ্কার + ডুপ্লিকেট এড়ানো ("a.pdf", "a (1).pdf", …)।
 *  - লেখা হয় "<নাম>.part"-এ, শেষে rename — তাই file_appears detector আধা-ফাইল দেখে না।
 *  - শেষে StepLog + [FileDownloaded] ঘটনা; [waitForDownload] দিয়ে অর্কেস্ট্রেটর অপেক্ষা করতে পারে।
 */
class ToolsDownloads(context: Context, val sessionId: String) : DownloadSink {

    companion object {
        const val DIR_NAME = "downloads"
        private const val MAX_BYTES = 500L * 1024 * 1024
        private const val MAX_DATA_URL_CHARS = 140_000_000
        private const val PENDING_TTL_MS = 120_000L

        fun dirOf(ctx: Context, sessionId: String): File =
            File(ToolsPaths.workspaceDir(ctx, sessionId), DIR_NAME)

        /** পাথ-বিভাজক/কন্ট্রোল অক্ষর বাদ, লুকানো-ফাইলের ডট বাদ, দৈর্ঘ্য সীমিত। */
        fun cleanName(raw: String?): String {
            var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            n = n.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim().trimStart('.')
            if (n.isEmpty()) n = "download"
            if (n.length > 120) {
                val dot = n.lastIndexOf('.')
                val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
                n = n.substring(0, 100) + ext
            }
            return n
        }
    }

    private val app: Context = context.applicationContext
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private class Done(val rel: String, val at: Long)
    private val pending = java.util.ArrayDeque<Done>()

    // ------------------------------------------------------------------ DownloadSink
    override fun fromUrl(url: String, userAgent: String?, contentDisposition: String?, mime: String?, referer: String?): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            toast("এই ধরনের ডাউনলোড লিংক সমর্থিত নয়")
            return true
        }
        val name = cleanName(URLUtil.guessFileName(url, contentDisposition, mime))
        toast("ডাউনলোড শুরু: $name")
        Thread { httpDownload(url, userAgent, referer, name) }.start()
        return true
    }

    override fun fromDataUrl(dataUrl: String): Boolean {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0 || dataUrl.length > MAX_DATA_URL_CHARS) {
            toast("ফাইলটি সংরক্ষণ করা গেল না")
            return true
        }
        Thread { saveDataUrl(dataUrl, comma) }.start()
        return true
    }

    // ------------------------------------------------------------------ অপেক্ষা
    /** নতুন (ধরা-পড়েনি এমন) ডাউনলোড শেষ হলে তার workspace-আপেক্ষিক পাথ; সময় পেরোলে null। */
    suspend fun waitForDownload(timeoutS: Int): String? {
        val end = SystemClock.elapsedRealtime() + timeoutS.coerceIn(1, 3600) * 1000L
        while (SystemClock.elapsedRealtime() < end) {
            takePending()?.let { return it }
            delay(300)
        }
        return takePending()
    }

    private fun takePending(): String? = synchronized(lock) {
        val now = System.currentTimeMillis()
        while (pending.isNotEmpty() && now - pending.peekFirst().at > PENDING_TTL_MS) pending.pollFirst()
        pending.pollFirst()?.rel
    }

    // ------------------------------------------------------------------ http
    private fun httpDownload(url: String, ua: String?, referer: String?, name: String) {
        var reserved: String? = null
        var part: File? = null
        var conn: HttpURLConnection? = null
        try {
            val dir = dirOf(app, sessionId).apply { mkdirs() }
            val finalName = reserve(dir, name)
            reserved = finalName
            val partFile = File(dir, "$finalName.part")
            part = partFile
            // সেশন ৮: রিডাইরেক্ট হাতে ফলো — প্রতি হপে শুধু সেই URL-এর কুকি যায় (অন্য হোস্টে কুকি ফাঁস ঠেকাতে);
            // শুধু http/https (file:, ftp: নয়); সর্বোচ্চ ৫ হপ।
            var cur = url
            var hops = 0
            var last: HttpURLConnection? = null
            while (true) {
                val u = URL(cur)
                val sch = u.protocol.lowercase()
                if (sch != "http" && sch != "https") throw IOException("bad_scheme")
                val first = hops == 0
                val cc = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    val cookie = try { CookieManager.getInstance().getCookie(cur) } catch (e: Exception) { null }
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                    if (!ua.isNullOrBlank()) setRequestProperty("User-Agent", ua)
                    if (first && !referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                }
                conn = cc
                last = cc
                val rc = cc.responseCode
                if (rc == 301 || rc == 302 || rc == 303 || rc == 307 || rc == 308) {
                    val loc = cc.getHeaderField("Location")
                    try { cc.disconnect() } catch (e: Exception) { }
                    hops++
                    if (loc.isNullOrBlank() || hops > 5) throw IOException("too_many_redirects")
                    cur = URL(u, loc).toString()
                    continue
                }
                break
            }
            val c = last ?: throw IOException("no_connection")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("http_$code")
            var total = 0L
            c.inputStream.use { ins ->
                FileOutputStream(partFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("too_large")
                        out.write(buf, 0, n)
                    }
                }
            }
            finish(dir, partFile, finalName, total)
        } catch (e: Exception) {
            part?.delete()
            failed(reserved, e.message)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------------ data:/blob:
    private fun saveDataUrl(dataUrl: String, comma: Int) {
        var reserved: String? = null
        var part: File? = null
        try {
            val meta = dataUrl.substring(5, comma)
            val isB64 = meta.contains(";base64")
            val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
            val payload = dataUrl.substring(comma + 1)
            val bytes = if (isB64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            if (bytes.size > MAX_BYTES) throw IOException("too_large")
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val name = "download_" + System.currentTimeMillis() + (if (ext.isNullOrEmpty()) "" else ".$ext")
            val dir = dirOf(app, sessionId).apply { mkdirs() }
            val finalName = reserve(dir, cleanName(name))
            reserved = finalName
            val partFile = File(dir, "$finalName.part")
            part = partFile
            FileOutputStream(partFile).use { it.write(bytes) }
            finish(dir, partFile, finalName, bytes.size.toLong())
        } catch (e: Exception) {
            part?.delete()
            failed(reserved, e.message)
        }
    }

    // ------------------------------------------------------------------ নাম ও শেষ ধাপ
    /** "a.pdf" আছে বা নামছে হলে "a (1).pdf", "a (2).pdf"…; নামটা in-flight সেটে ধরে রাখে। */
    private fun reserve(dir: File, name: String): String = synchronized(lock) {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var cand = name
        var i = 1
        while (File(dir, cand).exists() || File(dir, "$cand.part").exists() || cand in inFlight) {
            cand = "$base ($i)$ext"
            i++
        }
        inFlight.add(cand)
        cand
    }

    private fun finish(dir: File, part: File, finalName: String, size: Long) {
        val target = File(dir, finalName)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            if (!part.renameTo(target)) throw IOException("rename_failed")
        }
        val rel = "$DIR_NAME/$finalName"
        synchronized(lock) {
            inFlight.remove(finalName)
            pending.addLast(Done(rel, System.currentTimeMillis()))
        }
        StepLog.append(app, sessionId, JSONObject().put("type", "download").put("path", rel).put("size", size))
        ToolsEvents.publish(FileDownloaded(sessionId, rel, size))
        toast("সংরক্ষিত: $rel")
    }

    private fun failed(reserved: String?, why: String?) {
        if (reserved != null) synchronized(lock) { inFlight.remove(reserved) }
        StepLog.append(app, sessionId, JSONObject().put("type", "download_failed").put("why", (why ?: "error").take(80)))
        toast("ডাউনলোড ব্যর্থ হয়েছে")
    }

    private fun toast(msg: String) {
        ToolsAsync.ui { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }
}
