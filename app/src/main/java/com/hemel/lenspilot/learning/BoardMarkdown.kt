package com.hemel.lenspilot.learning

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TabStopSpan
import android.text.style.TypefaceSpan

/**
 * v50: ব্ল্যাকবোর্ডের লেখা এখন "আধুনিক AI-এর মতো" সাজানো — সার্ভার (app.py) বোর্ডের `text`-এ ছোট একটা
 * মার্কডাউন-সাবসেট লেখে, এখানে সেটা চক-স্টাইলের Spannable-এ রেন্ডার হয়।
 *
 * সমর্থিত:
 *   # / ## / ###  শিরোনাম (আলাদা রং + আকার)      ■ শিরোনাম (পুরনো ধরনও চলে)
 *   **বোল্ড**   *ইটালিক*   `কোড`
 *   - / * / •   বুলেট          1. 2. 3.  ক্রমিক তালিকা
 *   > লেখা       বক্স (Note:/Remember:/Example:/Common mistake: দেখে আলাদা রঙের বক্স)
 *   | a | b |   টেবিল (হেডার সারি + ডিভাইডার; ঘরের লেখা বড় হলে লাইন-ধরে সাজানো তালিকা)
 *   ---         আড়াআড়ি রেখা
 *
 * সবকিছু একটাই TextView-র Spannable — তাই typewriter, highlight (BackgroundColorSpan) আর স্ক্রল আগের মতোই চলে।
 * মনে রাখো: রেন্ডার-করা টেক্সটে মার্কার-চিহ্ন (**, #, |) থাকে না — অফসেট সবসময় রেন্ডার-করা টেক্সটের।
 */
object BoardMarkdown {

    // প্যালেট — ডিফল্ট চক (গাঢ় বোর্ড); light=true হলে চ্যাটের হালকা (Claude-স্টাইল) প্যালেট
    private var C_TEXT = Color.parseColor("#F5F7FA")
    private var C_H1 = Color.parseColor("#FDE68A")   // হলদে চক
    private var C_H2 = Color.parseColor("#93C5FD")   // নীল চক
    private var C_H3 = Color.parseColor("#86EFAC")   // সবুজ চক
    private var C_BOLD = Color.parseColor("#FFFFFF")
    private var C_ITALIC = Color.parseColor("#D8DEE9")
    private var C_CODE_BG = Color.parseColor("#33FFFFFF")
    private var C_BULLET = Color.parseColor("#FBBF24")
    private var C_RULE = Color.parseColor("#4B5563")
    private var C_HDR_BG = Color.parseColor("#33FDE68A")
    private var C_ZEBRA = Color.parseColor("#14FFFFFF")
    private var C_CARD = Color.parseColor("#1AFFFFFF")

    private data class BoxTone(val bg: Int, val bar: Int)

    private var TONE_NOTE = BoxTone(Color.parseColor("#26FBBF24"), Color.parseColor("#FBBF24"))
    private var TONE_EXAMPLE = BoxTone(Color.parseColor("#2660A5FA"), Color.parseColor("#60A5FA"))
    private var TONE_WARN = BoxTone(Color.parseColor("#26F87171"), Color.parseColor("#F87171"))
    private var TONE_DEFAULT = BoxTone(Color.parseColor("#2634D399"), Color.parseColor("#34D399"))

    private fun setLight(light: Boolean) {
        fun c(h: String) = Color.parseColor(h)
        if (light) {
            C_TEXT = c("#1F1E1D"); C_H1 = c("#1F1E1D"); C_H2 = c("#1F1E1D"); C_H3 = c("#3D3A36")
            C_BOLD = c("#141413"); C_ITALIC = c("#3D3A36"); C_CODE_BG = c("#14000000")
            C_BULLET = c("#C96442"); C_RULE = c("#D9D4C7"); C_HDR_BG = c("#1AC96442")
            C_ZEBRA = c("#0A000000"); C_CARD = c("#0F000000")
            TONE_NOTE = BoxTone(c("#FFF3D1"), c("#D97706")); TONE_EXAMPLE = BoxTone(c("#E3EEFF"), c("#2563EB"))
            TONE_WARN = BoxTone(c("#FDE4E4"), c("#DC2626")); TONE_DEFAULT = BoxTone(c("#E3F4EC"), c("#059669"))
        } else {
            C_TEXT = c("#F5F7FA"); C_H1 = c("#FDE68A"); C_H2 = c("#93C5FD"); C_H3 = c("#86EFAC")
            C_BOLD = c("#FFFFFF"); C_ITALIC = c("#D8DEE9"); C_CODE_BG = c("#33FFFFFF")
            C_BULLET = c("#FBBF24"); C_RULE = c("#4B5563"); C_HDR_BG = c("#33FDE68A")
            C_ZEBRA = c("#14FFFFFF"); C_CARD = c("#1AFFFFFF")
            TONE_NOTE = BoxTone(c("#26FBBF24"), c("#FBBF24")); TONE_EXAMPLE = BoxTone(c("#2660A5FA"), c("#60A5FA"))
            TONE_WARN = BoxTone(c("#26F87171"), c("#F87171")); TONE_DEFAULT = BoxTone(c("#2634D399"), c("#34D399"))
        }
    }

    private fun toneFor(text: String): BoxTone {
        val t = text.trimStart().lowercase()
        return when {
            t.startsWith("remember") || t.startsWith("note") || t.startsWith("tip") ||
                t.startsWith("মনে রাখ") || t.startsWith("লক্ষ্য") || t.startsWith("টিপ") -> TONE_NOTE
            t.startsWith("example") || t.startsWith("উদাহরণ") -> TONE_EXAMPLE
            t.startsWith("common mistake") || t.startsWith("mistake") || t.startsWith("warning") ||
                t.startsWith("ভুল") || t.startsWith("সাবধান") -> TONE_WARN
            else -> TONE_DEFAULT
        }
    }

    /** [paint] = বোর্ডের TextView-র paint (ঘরের মাপ মাপতে), [widthPx] = পাওয়া যাবে এমন লেখার প্রস্থ। */
    fun render(src: String, paint: TextPaint, widthPx: Int, density: Float, light: Boolean = false): SpannableStringBuilder {
        setLight(light)
        val out = SpannableStringBuilder()
        val lines = src.replace("\r", "").split("\n")
        var i = 0
        var first = true

        fun gap() {
            if (out.isEmpty()) return
            val s = out.length
            out.append("\n")
            out.setSpan(RelativeSizeSpan(0.45f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun startBlock(spaced: Boolean) {
            if (out.isNotEmpty()) {
                out.append("\n")
                if (spaced) gap().also { }
            }
            first = false
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            if (line.isEmpty()) { i++; continue }

            // ---- টেবিল ----
            if (line.startsWith("|") && line.count { it == '|' } >= 2) {
                val tbl = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { tbl += lines[i].trim(); i++ }
                startBlock(spaced = true)
                renderTable(out, tbl, paint, widthPx, density)
                continue
            }

            // ---- আড়াআড়ি রেখা ----
            if (Regex("^(-{3,}|_{3,}|\\*{3,})$").matches(line)) {
                startBlock(spaced = false)
                val s = out.length
                out.append(" ")
                out.setSpan(RuleSpan(C_RULE, density), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            // ---- শিরোনাম ----
            val hm = Regex("^(#{1,3})\\s+(.*)$").find(line)
            val sq = line.startsWith("■")
            if (hm != null || sq) {
                val level = hm?.groupValues?.get(1)?.length ?: 2
                val body = if (hm != null) hm.groupValues[2] else line.removePrefix("■").trim()
                startBlock(spaced = true)
                val s = out.length
                appendInline(out, body)
                val e = out.length
                val (size, color) = when (level) {
                    1 -> 1.32f to C_H1
                    2 -> 1.18f to C_H2
                    else -> 1.07f to C_H3
                }
                out.setSpan(RelativeSizeSpan(size), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (level <= 2) out.setSpan(RuleUnderSpan(if (light) C_RULE else (color and 0x00FFFFFF or 0x55000000), density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            // ---- বক্স (> লাইনগুলো একসাথে) ----
            if (line.startsWith(">")) {
                val q = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q += lines[i].trim().removePrefix(">").trim(); i++
                }
                startBlock(spaced = true)
                val s = out.length
                q.forEachIndexed { idx, ql ->
                    if (idx > 0) out.append("\n")
                    appendInline(out, ql)
                }
                val e = out.length
                val tone = toneFor(q.firstOrNull().orEmpty())
                // "Note:" ইত্যাদি লেবেল বোল্ড
                val colon = out.indexOf(":", s)
                if (colon in s until minOf(e, s + 22)) {
                    out.setSpan(StyleSpan(Typeface.BOLD), s, colon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(tone.bar), s, colon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out.setSpan(PadSpan((12 * density).toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BoxSpan(tone.bg, tone.bar, density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            // ---- বুলেট ----
            val bm = Regex("^([-*•])\\s+(.*)$").find(line)
            if (bm != null) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = Regex("^([-*•])\\s+(.*)$").find(lines[i].trim()) ?: break
                    items += m.groupValues[2]; i++
                }
                startBlock(spaced = false)
                items.forEachIndexed { idx, it ->
                    if (idx > 0) out.append("\n")
                    val s = out.length
                    appendInline(out, it)
                    out.setSpan(HangSpan("•", C_BULLET, (18 * density).toInt()), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                continue
            }

            // ---- ক্রমিক তালিকা ----
            val nm = Regex("^(\\d{1,2})[.)]\\s+(.*)$").find(line)
            if (nm != null) {
                val items = mutableListOf<Pair<String, String>>()
                while (i < lines.size) {
                    val m = Regex("^(\\d{1,2})[.)]\\s+(.*)$").find(lines[i].trim()) ?: break
                    items += m.groupValues[1] to m.groupValues[2]; i++
                }
                startBlock(spaced = false)
                items.forEachIndexed { idx, (n, t) ->
                    if (idx > 0) out.append("\n")
                    val s = out.length
                    appendInline(out, t)
                    out.setSpan(HangSpan("$n.", C_BULLET, (24 * density).toInt()), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                continue
            }

            // ---- সাধারণ প্যারাগ্রাফ ("Example:" / "Remember:" লেবেল-লাইন নিজে থেকেই বক্স) ----
            val labelTone = Regex("^(remember|note|tip|example|common mistake|মনে রাখ[োো]?|উদাহরণ|ভুল)\\b[^:]{0,12}:", RegexOption.IGNORE_CASE)
            startBlock(spaced = first.not())
            val s = out.length
            appendInline(out, line)
            val e = out.length
            if (labelTone.containsMatchIn(line)) {
                val tone = toneFor(line)
                val colon = out.indexOf(":", s)
                if (colon in s until e) {
                    out.setSpan(StyleSpan(Typeface.BOLD), s, colon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(tone.bar), s, colon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out.setSpan(PadSpan((12 * density).toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BoxSpan(tone.bg, tone.bar, density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i++
        }
        // শেষে ঝুলে-থাকা নতুন লাইন নয়
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    /** মার্কডাউন-চিহ্ন বাদ দিয়ে সাধারণ টেক্সট (PDF নোট, history, AI-কনটেক্সটের জন্য)। */
    fun toPlain(src: String): String {
        val sb = StringBuilder()
        for (raw in src.replace("\r", "").split("\n")) {
            var l = raw.trim()
            if (l.isEmpty()) { sb.append("\n"); continue }
            if (Regex("^\\|?[\\s:|-]+\\|?$").matches(l) && l.contains("-")) continue
            if (l.startsWith("|")) {
                l = splitRow(l).joinToString("  |  ")
            } else {
                l = l.replace(Regex("^#{1,3}\\s+"), "■ ").replace(Regex("^>\\s?"), "")
                    .replace(Regex("^[-*]\\s+"), "• ")
            }
            l = l.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("`([^`]+)`"), "$1")
            sb.append(l).append("\n")
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---------------------------------------------------------------- টেবিল

    private fun splitRow(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|")) r = r.substring(0, r.length - 1)
        return r.split("|").map { it.trim() }
    }

    private fun isSeparator(row: String): Boolean =
        splitRow(row).all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }

    private fun plainCell(c: String) = c.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace("`", "")

    private fun renderTable(out: SpannableStringBuilder, rows: List<String>, paint: TextPaint, widthPx: Int, density: Float) {
        val header = splitRow(rows.first())
        val body = rows.drop(1).filterNot { isSeparator(it) }.map { splitRow(it) }
        val cols = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        fun cell(r: List<String>, c: Int) = r.getOrElse(c) { "" }

        // প্রতিটি কলামের দরকারি প্রস্থ মাপি
        val pad = 14 * density
        val need = FloatArray(cols) { c ->
            val all = listOf(plainCell(cell(header, c))) + body.map { plainCell(cell(it, c)) }
            // হেডার বোল্ড — একটু বেশি জায়গা
            all.maxOf { paint.measureText(it) } * 1.08f + pad
        }
        val inner = (widthPx - 20 * density).coerceAtLeast(120f)
        val fits = need.sum() <= inner && cols <= 4

        if (fits) {
            // কলাম-সমান করে ট্যাব-স্টপে সাজাই
            val stops = FloatArray(cols)
            var acc = 10 * density
            for (c in 0 until cols) { stops[c] = acc; acc += need[c] }
            fun writeRow(r: List<String>, isHeader: Boolean, zebra: Boolean) {
                val s = out.length
                for (c in 0 until cols) {
                    if (c > 0) out.append("\t")
                    appendInline(out, cell(r, c))
                }
                val e = out.length
                for (c in 1 until cols) out.setSpan(TabStopSpan.Standard(stops[c].toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(PadSpan((10 * density).toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (isHeader) {
                    out.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(C_H1), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RowSpan(C_HDR_BG, true, density, C_H1), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    out.setSpan(RowSpan(if (zebra) C_ZEBRA else Color.TRANSPARENT, false, density, C_H1), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            writeRow(header, true, false)
            body.forEachIndexed { idx, r -> out.append("\n"); writeRow(r, false, idx % 2 == 0) }
        } else {
            // বড় ঘর — প্রতিটি সারি একটা ছোট কার্ড: প্রথম ঘর বোল্ড, বাকিরা "হেডার: মান"
            body.forEachIndexed { idx, r ->
                if (idx > 0) out.append("\n")
                val s = out.length
                appendInline(out, cell(r, 0))
                val e0 = out.length
                out.setSpan(StyleSpan(Typeface.BOLD), s, e0, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(C_H2), s, e0, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (c in 1 until cols) {
                    val v = cell(r, c)
                    if (v.isBlank()) continue
                    out.append("\n")
                    val ls = out.length
                    out.append(plainCell(cell(header, c)) + ": ")
                    out.setSpan(ForegroundColorSpan(C_BULLET), ls, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    appendInline(out, v)
                    out.setSpan(HangSpan("", C_BULLET, (14 * density).toInt()), ls, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out.setSpan(PadSpan((12 * density).toInt()), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BoxSpan(C_CARD, C_BULLET, density), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ---------------------------------------------------------------- ইনলাইন (**বোল্ড**, *ইটালিক*, `কোড`)

    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])")

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var cursor = 0
        for (m in INLINE.findAll(text)) {
            out.append(text.substring(cursor, m.range.first))
            val s = out.length
            when {
                m.groups[1] != null -> {
                    out.append(m.groupValues[1])
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(C_BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[2] != null -> {
                    out.append(m.groupValues[2])
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(C_CODE_BG), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(m.groupValues[3])
                    out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(C_ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) out.append(text.substring(cursor))
    }

    // ---------------------------------------------------------------- কাস্টম স্প্যান

    /** বাঁ-দিকে ফাঁকা জায়গা (বক্স/সারির ভেতরের প্যাডিং)। */
    private class PadSpan(private val px: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = px
        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
        ) = Unit
    }

    /** ঝুলানো-ইন্ডেন্টের বুলেট/নম্বর — প্রথম লাইনে চিহ্ন আঁকে, পরের লাইন ভেতরে সরে থাকে। */
    private class HangSpan(private val label: String, private val color: Int, private val indent: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = indent
        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
        ) {
            if (!first || label.isEmpty()) return
            val old = p.color
            val oldBold = p.isFakeBoldText
            p.color = color
            p.isFakeBoldText = true
            c.drawText(label, x.toFloat(), baseline.toFloat(), p)
            p.color = old
            p.isFakeBoldText = oldBold
        }
    }

    private fun lineIsFirst(text: CharSequence, span: Any, start: Int): Boolean =
        start <= (text as Spanned).getSpanStart(span)

    private fun lineIsLast(text: CharSequence, span: Any, end: Int): Boolean =
        end >= (text as Spanned).getSpanEnd(span)

    /** টিন্ট-করা গোল-কোণা বক্স + বাঁ-পাশে রঙিন বার। */
    private class BoxSpan(private val bg: Int, private val bar: Int, private val density: Float) : LineBackgroundSpan {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun drawBackground(
            c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            val first = lineIsFirst(text, this, start)
            val last = lineIsLast(text, this, end)
            val r = 10 * density
            val l = left.toFloat(); val rt = right.toFloat()
            val t = top.toFloat() - if (first) 2 * density else 0f
            val b = bottom.toFloat() + if (last) 2 * density else 0f
            c.save()
            c.clipRect(l, t, rt, b)
            paint.color = bg
            c.drawRoundRect(RectF(l, if (first) t else t - r, rt, if (last) b else b + r), r, r, paint)
            paint.color = bar
            c.drawRect(l, t, l + 3 * density, b, paint)
            c.restore()
        }
    }

    /** টেবিলের সারি: হেডারে নিচে রেখা, বাকিতে হালকা জেব্রা-রং। */
    private class RowSpan(private val bg: Int, private val header: Boolean, private val density: Float, private val lineColor: Int) : LineBackgroundSpan {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun drawBackground(
            c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            if (bg != Color.TRANSPARENT) {
                paint.color = bg
                c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            }
            if (header) {
                paint.color = lineColor
                c.drawRect(left.toFloat(), bottom - 1.5f * density, right.toFloat(), bottom.toFloat(), paint)
            }
        }
    }

    /** শিরোনামের নিচে সরু রেখা। */
    private class RuleUnderSpan(private val color: Int, private val density: Float) : LineBackgroundSpan {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun drawBackground(
            c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            if (!lineIsLast(text, this, end)) return
            paint.color = color
            c.drawRect(left.toFloat(), bottom - 1f * density, right.toFloat(), bottom.toFloat(), paint)
        }
    }

    /** আড়াআড়ি রেখা (---)। */
    private class RuleSpan(private val color: Int, private val density: Float) : LineBackgroundSpan {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun drawBackground(
            c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            paint.color = color
            val y = (top + bottom) / 2f
            c.drawRect(left.toFloat(), y - 0.75f * density, right.toFloat(), y + 0.75f * density, paint)
        }
    }
}
