package com.hemel.lenspilot.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.R

/**
 * v95: উত্তরের ভেতরের "ব্লক" আঁকা — ```কোড``` কার্ড (ভাষার নাম + কপি বাটন + লম্বা হলে ভাঁজ/খোলা)
 * আর :::tip/warn/note/key কলআউট বক্স। বাকি লেখা আগের মতোই (proseRender দিয়ে) আঁকা হয়।
 * স্ট্রিমিং-এ ফেন্স বন্ধ না হলেও (closed=false) কার্ড আঁকে — কপি বাটন তখন বন্ধ থাকে।
 */
object RichBlocks {
    sealed class Seg {
        data class Prose(val text: String) : Seg()
        data class Code(val lang: String, val text: String, val closed: Boolean) : Seg()
        data class Callout(val kind: String, val text: String) : Seg()
    }

    private val CALLOUT_OPEN = Regex("^:::\\s*(tip|warn|warning|note|key)\\s*$", RegexOption.IGNORE_CASE)
    private const val FOLD_OVER = 16      // এর বেশি লাইন হলে ভাঁজ
    private const val FOLD_SHOW = 12

    fun hasBlocks(raw: String): Boolean = raw.contains("```") || raw.contains("\n:::") || raw.startsWith(":::")

    fun parse(raw: String): List<Seg> {
        val out = ArrayList<Seg>()
        val prose = StringBuilder()
        val buf = StringBuilder()
        var mode = 0            // 0 লেখা, 1 কোড, 2 কলআউট
        var lang = ""
        var kind = ""
        fun flushProse() {
            val t = prose.toString().trim('\n')
            if (t.isNotBlank()) out.add(Seg.Prose(t))
            prose.setLength(0)
        }
        for (line in raw.split("\n")) {
            val tr = line.trim()
            when (mode) {
                0 -> when {
                    tr.startsWith("```") -> { flushProse(); mode = 1; lang = tr.removePrefix("```").trim().take(20); buf.setLength(0) }
                    CALLOUT_OPEN.matches(tr) -> {
                        flushProse(); mode = 2; buf.setLength(0)
                        kind = CALLOUT_OPEN.find(tr)!!.groupValues[1].lowercase().let { if (it == "warning") "warn" else it }
                    }
                    else -> prose.append(line).append("\n")
                }
                1 -> if (tr.startsWith("```")) { out.add(Seg.Code(lang, buf.toString().trimEnd('\n'), true)); mode = 0 }
                     else buf.append(line).append("\n")
                else -> if (tr == ":::") { out.add(Seg.Callout(kind, buf.toString().trim('\n'))); mode = 0 }
                        else buf.append(line).append("\n")
            }
        }
        when (mode) {
            0 -> flushProse()
            1 -> out.add(Seg.Code(lang, buf.toString().trimEnd('\n'), false))
            else -> out.add(Seg.Callout(kind, buf.toString().trim('\n')))
        }
        return out
    }

    private fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Lenspilot", text))
        Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
    }

    /** base = বাবলের মূল TextView (রং/ফন্ট/সাইজ নেওয়ার জন্য)। proseRender = লেখা-অংশ আঁকার ফাংশন (বোল্ড ইত্যাদি)। */
    fun build(container: LinearLayout, segs: List<Seg>, base: TextView, proseRender: (String) -> CharSequence) {
        container.removeAllViews()
        val ctx = container.context
        fun proseView(text: String, color: Int = base.currentTextColor): TextView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, base.textSize)
            setTextColor(color)
            typeface = base.typeface
            setLineSpacing(base.lineSpacingExtra, base.lineSpacingMultiplier)
            this.text = proseRender(text)
            setPadding(dp(container, 4), dp(container, 2), dp(container, 4), dp(container, 2))
        }
        for (seg in segs) when (seg) {
            is Seg.Prose -> container.addView(proseView(seg.text), lp(container, 4))
            is Seg.Callout -> container.addView(calloutView(container, seg) { t, c -> proseView(t, c) }, lp(container, 8))
            is Seg.Code -> container.addView(codeView(container, seg), lp(container, 8))
        }
    }

    private fun lp(v: View, topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v, topDp) }

    private fun calloutView(v: View, seg: Seg.Callout, proseView: (String, Int) -> TextView): View {
        val ctx = v.context
        // (পটভূমি, দাগ/শিরোনামের রং, শিরোনাম)
        val (bg, accent, title) = when (seg.kind) {
            "tip" -> Triple("#ECFDF5", "#059669", "টিপ")
            "warn" -> Triple("#FFFBEB", "#B45309", "সতর্কতা")
            "key" -> Triple("#F5F3FF", "#6D28D9", "মূল কথা")
            else -> Triple("#EFF6FF", "#1D4ED8", "নোট")
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg)); cornerRadius = dp(v, 12).toFloat()
                setStroke(dp(v, 1), androidx.core.graphics.ColorUtils.setAlphaComponent(Color.parseColor(accent), 110))
            }
            setPadding(dp(v, 10), dp(v, 8), dp(v, 10), dp(v, 8))
            addView(TextView(ctx).apply {
                text = title; setTextColor(Color.parseColor(accent)); setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(v, 4), 0, dp(v, 4), dp(v, 2))
            })
            addView(proseView(seg.text, Color.parseColor("#1F2937")))
        }
    }

    private fun codeView(v: View, seg: Seg.Code): View {
        val ctx = v.context
        val lines = seg.text.split("\n")
        val foldable = seg.closed && lines.size > FOLD_OVER
        var expanded = false

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#1E1E2E")); cornerRadius = dp(v, 12).toFloat() }
        }
        // শিরোনাম-সারি: ভাষা + কপি
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(v, 12), dp(v, 6), dp(v, 6), dp(v, 6))
        }
        header.addView(TextView(ctx).apply {
            text = seg.lang.ifBlank { "কোড" }.lowercase(); setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val copyBtn = TextView(ctx).apply {
            text = "কপি"; setTextColor(Color.parseColor("#E2E8F0")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(v, 12), dp(v, 6), dp(v, 12), dp(v, 6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(v, 8).toFloat() }
            alpha = if (seg.closed) 1f else 0.4f          // স্ট্রিমিং-এ লেখা চলছে — শেষ হলে চাপা যাবে
            isClickable = seg.closed
            setOnClickListener {
                if (!seg.closed) return@setOnClickListener
                copy(ctx, seg.text)
                text = "কপি হয়েছে ✓"
                Handler(Looper.getMainLooper()).postDelayed({ text = "কপি" }, 1600)
            }
        }
        header.addView(copyBtn)
        card.addView(header)

        val body = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setLineSpacing(0f, 1.1f)
            setPadding(dp(v, 12), dp(v, 2), dp(v, 12), dp(v, 10))
            setHorizontallyScrolling(true)
            setOnLongClickListener { if (seg.closed) { copy(ctx, seg.text); true } else false }
        }
        fun render() {
            body.text = if (foldable && !expanded) lines.take(FOLD_SHOW).joinToString("\n") else seg.text
        }
        render()
        card.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (foldable) {
            card.addView(TextView(ctx).apply {
                fun label() { text = if (expanded) "▲ ভাঁজ করুন" else "▼ আরও ${lines.size - FOLD_SHOW} লাইন দেখুন" }
                label(); setTextColor(Color.parseColor("#93C5FD")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER; setPadding(0, dp(v, 6), 0, dp(v, 10))
                setOnClickListener { expanded = !expanded; render(); label() }
            })
        }
        return card
    }
}
