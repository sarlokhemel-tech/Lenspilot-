package com.hemel.lenspilot.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v31 — ক্লাস নোট PDF।
 *
 * ক্লাসের ব্লাকবোর্ডে যা-যা লেখা উঠেছিল (এবং যে ছবি/ডায়াগ্রাম এসেছিল) চালানোর ক্রমে সুন্দর A4 PDF-এ সাজায়।
 * Android-এর নিজস্ব [PdfDocument] + [StaticLayout] — ইন্টারনেট/সার্ভার লাগে না, বাংলা অক্ষর ফোনের নিজের ফন্টেই ঠিকঠাক
 * (যুক্তাক্ষর সহ) আঁকা হয়। Note Force চালু থাকলে AI-র লেখা ■ শিরোনাম / • পয়েন্ট / "উদাহরণ:" লাইনগুলো এখানে আলাদা
 * স্টাইলে (রঙিন শিরোনাম, ঝুলন্ত বুলেট, বোল্ড লেবেল) ফুটে ওঠে; সাধারণ ক্লাসের ছোট লাইনগুলো সাধারণ অনুচ্ছেদে।
 */
object ClassNotesPdf {
    /** বোর্ডের একটা জিনিস: লেখা, অথবা ছবি (JPEG bytes)। */
    class NoteItem(val text: String? = null, val jpeg: ByteArray? = null)

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_X = 44f
    private const val MARGIN_TOP = 52f
    private const val MARGIN_BOTTOM = 56f

    private val COLOR_INK = Color.parseColor("#111827")
    private val COLOR_MUTED = Color.parseColor("#64748B")
    private val COLOR_ACCENT = Color.parseColor("#1D4ED8")
    private val COLOR_LABEL = Color.parseColor("#0F766E")
    private val COLOR_RULE = Color.parseColor("#CBD5E1")

    private val LABELS = listOf(
        "উদাহরণ", "মনে রাখো", "সাধারণ ভুল", "সূত্র", "সংজ্ঞা", "নিয়ম", "সমাধান", "উত্তর", "ধাপ",
        "Example", "Note", "Remember", "Definition", "Formula", "Common mistake", "Solution", "Answer", "Tip"
    )
    private val LABEL_RE = Regex("^(" + LABELS.joinToString("|") { Regex.escape(it) } + ")(\\s*[\\d০-৯]*)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val BULLET_RE = Regex("^\\s*(?:[•●▪◦‣·\\-–—*]|[\\d০-৯]{1,2}[.)])\\s+")

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, extra: Float = 3f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(40))
            .setLineSpacing(extra, 1f)
            .setIncludePad(false)
            .build()

    /** PDF-এর পেজ-লেখক: পেজ ভরে গেলে নিজেই নতুন পেজ খোলে, প্রতি পেজে ফুটার বসায়। */
    private class PageWriter(val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = MARGIN_TOP
        var pageNo = 0
        private val footerPaint = paint(9f, COLOR_MUTED)

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            val pg = doc.startPage(info)
            page = pg
            canvas = pg.canvas
            y = MARGIN_TOP
            // ফুটার: বামে নাম, ডানে পেজ নম্বর
            val base = PAGE_H - 28f
            canvas.drawText("Lenspilot · Class Notes", MARGIN_X, base, footerPaint)
            val num = pageNo.toString()
            canvas.drawText(num, PAGE_W - MARGIN_X - footerPaint.measureText(num), base, footerPaint)
            val rule = Paint().apply { color = COLOR_RULE; strokeWidth = 0.6f }
            canvas.drawLine(MARGIN_X, base - 14f, PAGE_W - MARGIN_X, base - 14f, rule)
        }

        val bottom: Float get() = PAGE_H - MARGIN_BOTTOM

        fun ensure(h: Float) {
            if (page == null || y + h > bottom) newPage()
        }

        fun close() {
            page?.let { doc.finishPage(it) }
            page = null
        }

        /** একটা লেআউট (একাধিক লাইন) আঁকে; পেজে না ধরলে লাইনের সীমানায় ভেঙে পরের পেজে নেয়। */
        fun drawLayout(l: StaticLayout, x: Float, gapAfter: Float) {
            if (page == null) newPage()
            var line = 0
            val count = l.lineCount
            while (line < count) {
                var avail = bottom - y
                val firstH = (l.getLineBottom(line) - l.getLineTop(line)).toFloat()
                if (avail < firstH) {
                    newPage()
                    avail = bottom - y
                }
                var end = line
                while (end + 1 < count && (l.getLineBottom(end + 1) - l.getLineTop(line)) <= avail) end++
                val blockH = (l.getLineBottom(end) - l.getLineTop(line)).toFloat()
                canvas.save()
                canvas.clipRect(x, y, x + l.width, y + blockH)
                canvas.translate(x, y - l.getLineTop(line))
                l.draw(canvas)
                canvas.restore()
                y += blockH
                line = end + 1
            }
            y += gapAfter
        }
    }

    /**
     * নোট-আইটেমগুলো থেকে PDF বানিয়ে [out]-এ লেখে। সফল হলে true। ব্লকিং (ছবি/ফাইল) — IO/Default থ্রেডে ডাকো।
     * [noteForce] > 0 হলে শিরোনামের নিচে "Note Force n/10" লেখা থাকে।
     */
    fun build(
        context: Context,
        title: String,
        topic: String,
        items: List<NoteItem>,
        noteForce: Int,
        out: File
    ): Boolean {
        if (items.isEmpty()) return false
        val doc = PdfDocument()
        try {
            val w = PageWriter(doc)
            w.newPage()
            val contentW = (PAGE_W - 2 * MARGIN_X).toInt()

            // ---- শিরোনাম-অংশ ----
            val heading = title.ifBlank { topic }.ifBlank { "Class notes" }
            w.drawLayout(layout(heading, paint(23f, COLOR_INK, bold = true), contentW, 4f), MARGIN_X, 6f)
            if (topic.isNotBlank() && topic.trim() != heading.trim()) {
                w.drawLayout(layout("Question: " + topic.trim(), paint(11.5f, COLOR_MUTED), contentW), MARGIN_X, 2f)
            }
            val date = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
            val meta = buildString {
                append(date)
                if (noteForce > 0) append("  ·  Note Force $noteForce/10")
            }
            w.drawLayout(layout(meta, paint(10.5f, COLOR_MUTED), contentW), MARGIN_X, 8f)
            w.ensure(10f)
            w.canvas.drawLine(MARGIN_X, w.y, PAGE_W - MARGIN_X, w.y, Paint().apply { color = COLOR_ACCENT; strokeWidth = 1.6f })
            w.y += 14f

            val bodyPaint = paint(12.5f, COLOR_INK)
            val headPaint = paint(15f, COLOR_ACCENT, bold = true)

            for (item in items) {
                val text = item.text
                val jpeg = item.jpeg
                if (text != null) {
                    drawTextItem(w, text, bodyPaint, headPaint, contentW)
                    w.y += 8f
                } else if (jpeg != null) {
                    drawImage(w, jpeg, contentW)
                }
            }
            w.close()
            FileOutputStream(out).use { doc.writeTo(it) }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            try { doc.close() } catch (_: Throwable) {}
        }
    }

    private fun drawTextItem(w: PageWriter, text: String, body: TextPaint, head: TextPaint, contentW: Int) {
        val lines = text.split("\n").map { it.trimEnd() }.filter { it.isNotBlank() }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("■") || line.startsWith("#") -> {
                    val t = line.trimStart('■', '#', ' ')
                    w.y += 6f
                    val l = layout(t, head, contentW - 12, 3f)
                    // শিরোনামের বাঁয়ে রঙিন দাগ
                    w.ensure((l.getLineBottom(0) - l.getLineTop(0)).toFloat() + 2f)
                    val top = w.y
                    w.drawLayout(l, MARGIN_X + 12f, 4f)
                    val bar = Paint().apply { color = COLOR_ACCENT; style = Paint.Style.FILL }
                    if (w.y - top > 0f) w.canvas.drawRect(MARGIN_X, top, MARGIN_X + 4f, w.y - 4f, bar)
                }
                BULLET_RE.containsMatchIn(line) -> {
                    val m = BULLET_RE.find(line)!!
                    val marker = m.value.trim().let { if (it.length == 1 && it[0] in "-–—*·●▪◦‣") "•" else it }
                    val rest = line.substring(m.value.length)
                    val indent = 16f
                    val l = layout(styled(rest, body), body, contentW - indent.toInt(), 3f)
                    w.ensure((l.getLineBottom(0) - l.getLineTop(0)).toFloat())
                    // ensure() আগেই পেজ ঠিক করে দিয়েছে, তাই বুলেট চিহ্ন ও প্রথম লাইন একই পেজে পড়বে
                    val markerBaseline = w.y + l.getLineBaseline(0).toFloat()
                    w.canvas.drawText(marker, MARGIN_X + 2f, markerBaseline, body)
                    w.drawLayout(l, MARGIN_X + indent, 3f)
                }
                else -> {
                    val l = layout(styled(line, body), body, contentW, 3f)
                    w.drawLayout(l, MARGIN_X, 4f)
                }
            }
        }
    }

    /** "উদাহরণ:", "মনে রাখো:" ইত্যাদি লেবেল বোল্ড+রঙিন। */
    private fun styled(line: String, body: TextPaint): CharSequence {
        val m = LABEL_RE.find(line) ?: return line
        val sb = SpannableStringBuilder(line)
        val end = m.value.trimEnd().length
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(COLOR_LABEL), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun drawImage(w: PageWriter, jpeg: ByteArray, contentW: Int) {
        var bmp: Bitmap? = null
        try {
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            val maxW = contentW * 0.82f
            val maxH = 290f
            val scale = minOf(maxW / bmp.width, maxH / bmp.height, 1.6f)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            w.ensure(dh + 18f)
            val left = MARGIN_X + (contentW - dw) / 2f
            val rect = RectF(left, w.y, left + dw, w.y + dh)
            val border = Paint().apply { color = COLOR_RULE; style = Paint.Style.STROKE; strokeWidth = 1f }
            w.canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            w.canvas.drawRect(rect, border)
            w.y += dh + 14f
        } catch (_: Throwable) {
            // একটা ছবি আঁকা না গেলে বাকি নোট চলবে
        } finally {
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }
}
