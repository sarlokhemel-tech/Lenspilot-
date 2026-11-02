package com.hemel.lenspilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * v77: "+" বাটনে ছবির পাশাপাশি যেকোনো ফাইল। সবকিছু ফোনেই পড়া হয় — সার্ভারে নতুন কিছু লাগে না,
 * তাই Gemini/Groq দুটোতেই, সব মোডে একইভাবে কাজ করে:
 *  - টেক্সট/কোড/CSV/JSON/মার্কডাউন… → লেখা হিসেবে
 *  - DOCX / PPTX / XLSX / ODT → ভেতরের লেখা বের করে
 *  - PDF → প্রথম কয়েক পাতা ছবি করে (AI ছবি দেখে পড়ে — স্ক্যান করা PDF-ও চলে)
 */
object AttachmentExtractor {

    sealed class Result {
        data class Text(val name: String, val text: String, val truncated: Boolean) : Result()
        data class Pdf(val name: String, val bitmap: Bitmap, val totalPages: Int, val shownPages: Int) : Result()
        data class Unsupported(val name: String) : Result()
        data class Failed(val name: String) : Result()
    }

    const val MAX_CHARS = 40_000
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val PDF_MAX_PAGES = 3
    private const val PDF_PAGE_WIDTH = 900
    private const val PDF_MAX_HEIGHT = 4000

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yml", "yaml", "ini", "toml",
        "sql", "sh", "css", "tex", "srt", "vtt", "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h",
        "cs", "go", "rs", "php", "rb", "swift", "dart", "gradle", "properties", "env", "conf", "bat"
    )

    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** ব্লকিং — IO থ্রেডে ডাকুন। */
    fun extract(ctx: Context, uri: Uri): Result {
        val name = displayName(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = (ctx.contentResolver.getType(uri) ?: "").lowercase()
        val size = sizeOf(ctx, uri)
        if (size > MAX_BYTES) return Result.Failed(name)
        return try {
            when {
                ext == "pdf" || mime == "application/pdf" -> extractPdf(ctx, uri, name)
                ext == "docx" -> textResult(name, docx(ctx, uri))
                ext == "pptx" -> textResult(name, pptx(ctx, uri))
                ext == "xlsx" -> textResult(name, xlsx(ctx, uri))
                ext == "odt" || ext == "ods" || ext == "odp" -> textResult(name, odf(ctx, uri))
                mime.startsWith("text/") || ext in TEXT_EXT || mime == "application/json" || mime == "application/xml" ->
                    textResult(name, readText(ctx, uri))
                else -> Result.Unsupported(name)
            }
        } catch (e: Throwable) {
            Result.Failed(name)
        }
    }

    private fun textResult(name: String, raw: String?): Result {
        val t = raw?.replace("\r\n", "\n")?.trim().orEmpty()
        if (t.isBlank()) return Result.Failed(name)
        return if (t.length > MAX_CHARS) Result.Text(name, t.take(MAX_CHARS), true) else Result.Text(name, t, false)
    }

    private fun readText(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }

    // ---------------- PDF → ছবি ----------------

    private fun extractPdf(ctx: Context, uri: Uri, name: String): Result {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return Result.Failed(name)
        val renderer = PdfRenderer(pfd)
        try {
            val total = renderer.pageCount
            if (total <= 0) return Result.Failed(name)
            val show = minOf(total, PDF_MAX_PAGES)
            val pages = ArrayList<Bitmap>()
            for (i in 0 until show) {
                val page = renderer.openPage(i)
                try {
                    val h = (PDF_PAGE_WIDTH.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(PDF_PAGE_WIDTH, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bmp)
                } finally {
                    page.close()
                }
            }
            val totalH = pages.sumOf { it.height }
            val stitched = Bitmap.createBitmap(PDF_PAGE_WIDTH, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)
            canvas.drawColor(Color.WHITE)
            var y = 0f
            for (p in pages) {
                canvas.drawBitmap(p, 0f, y, null)
                y += p.height
                p.recycle()
            }
            val out = if (stitched.height > PDF_MAX_HEIGHT) {
                val s = PDF_MAX_HEIGHT.toFloat() / stitched.height
                Bitmap.createScaledBitmap(stitched, (stitched.width * s).toInt(), PDF_MAX_HEIGHT, true)
                    .also { if (it !== stitched) stitched.recycle() }
            } else stitched
            return Result.Pdf(name, out, total, show)
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    // ---------------- Office/ODF (zip + xml) ----------------

    private fun zipEntries(ctx: Context, uri: Uri, want: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && want(e.name)) {
                        val bos = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 12 * 1024 * 1024) break
                            bos.write(buf, 0, n)
                        }
                        out[e.name] = bos.toString("UTF-8")
                    }
                    e = zis.nextEntry
                }
            }
        }
        return out
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")

    private fun docx(ctx: Context, uri: Uri): String? {
        val xml = zipEntries(ctx, uri) { it == "word/document.xml" }["word/document.xml"] ?: return null
        val s = xml.replace("</w:p>", "\n").replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br\\s*/>"), "\n").replace(Regex("<[^>]+>"), "")
        return unescape(s)
    }

    private fun odf(ctx: Context, uri: Uri): String? {
        val xml = zipEntries(ctx, uri) { it == "content.xml" }["content.xml"] ?: return null
        val s = xml.replace(Regex("</text:(p|h)>"), "\n").replace(Regex("</table:table-row>"), "\n")
            .replace(Regex("</table:table-cell>"), " | ").replace(Regex("<[^>]+>"), "")
        return unescape(s)
    }

    private fun pptx(ctx: Context, uri: Uri): String? {
        val slides = zipEntries(ctx, uri) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        if (slides.isEmpty()) return null
        val sorted = slides.entries.sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val sb = StringBuilder()
        var n = 1
        for ((_, xml) in sorted) {
            sb.append("--- Slide ").append(n++).append(" ---\n")
            val t = xml.replace("</a:p>", "\n").replace(Regex("<[^>]+>"), "")
            sb.append(unescape(t).trim()).append("\n\n")
            if (sb.length > MAX_CHARS + 2000) break
        }
        return sb.toString()
    }

    private fun xlsx(ctx: Context, uri: Uri): String? {
        val files = zipEntries(ctx, uri) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { xml ->
            for (si in Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val txt = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1])
                    .joinToString("") { it.groupValues[1] }
                shared.add(unescape(txt))
            }
        }
        val sheets = files.filterKeys { it.startsWith("xl/worksheets/") }.entries
            .sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return null
        val sb = StringBuilder()
        var idx = 1
        for ((_, xml) in sheets) {
            sb.append("--- Sheet ").append(idx++).append(" ---\n")
            for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val cells = ArrayList<String>()
                for (c in Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[2]
                    val type = Regex("\\bt=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                    val value = when (type) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1).orEmpty())
                        else -> unescape(v.orEmpty())
                    }
                    cells.add(value)
                }
                if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString(" | ").trimEnd(' ', '|')).append('\n')
                if (sb.length > MAX_CHARS + 2000) return sb.toString()
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
