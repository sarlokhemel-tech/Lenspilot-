package com.hemel.lenspilot.automation

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** অটোমেশন মোডের (গাঢ় নীল-সবুজ, চ্যাটবট থেকে সম্পূর্ণ আলাদা) ছোট UI-সহায়ক — XML ছাড়া কোডে। */
internal object AutoUi {
    const val BG = 0xFF0B0F14.toInt()
    const val SURFACE = 0xFF161D26.toInt()
    const val ACCENT = 0xFF22D3EE.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val DANGER = 0xFFEF4444.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Int = 14, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun button(ctx: Context, label: String, filled: Boolean = true, color: Int = ACCENT, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (filled) Color.BLACK else color)
            background = if (filled) rounded(ctx, color, 12) else rounded(ctx, Color.TRANSPARENT, 12, color)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, 16, 0xFF243041.toInt())
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 10) }
    }

    fun gap(ctx: Context, dpv: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dpv))
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
}
