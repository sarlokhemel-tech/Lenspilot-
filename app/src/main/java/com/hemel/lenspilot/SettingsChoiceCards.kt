package com.hemel.lenspilot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v58: Settings-এর বাছাই-কার্ড। প্রতিটা অপশন = বাঁদিকে ইমোজি-ছবি, মাঝে নাম + ব্যাজ + বিস্তারিত বিবরণ,
 * ডানে ✓ (সিলেক্টেড হলে)। সিলেক্টেড কার্ডের বর্ডার/ব্যাকগ্রাউন্ড নীলচে হয়ে আলাদা বোঝা যায়।
 */
object SettingsChoiceCards {

    class Option(
        val id: String,
        val emoji: String,
        val title: String,
        val badge: String?,
        val desc: String
    )

    private const val ACCENT = 0xFF2563EB.toInt()
    private const val ACCENT_TINT = 0xFFEFF6FF.toInt()
    private const val BORDER = 0xFFE2E8F0.toInt()
    private const val TITLE = 0xFF0F172A.toInt()
    private const val BODY = 0xFF475569.toInt()

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun cardBg(ctx: Context, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 16).toFloat()
        setColor(if (selected) ACCENT_TINT else Color.WHITE)
        setStroke(dp(ctx, if (selected) 2 else 1), if (selected) ACCENT else BORDER)
    }

    /** [container]-এ কার্ডগুলো বানায়; কার্ডে চাপলে [onSelect] ডাকে ও হাইলাইট বদলায়। */
    fun build(
        ctx: Context,
        container: LinearLayout,
        options: List<Option>,
        selectedId: String,
        onSelect: (String) -> Unit
    ) {
        var current = selectedId
        val cards = LinkedHashMap<String, Triple<View, TextView, GradientDrawable>>()

        fun refresh() {
            cards.forEach { (id, t) ->
                val sel = id == current
                t.first.background = cardBg(ctx, sel)
                t.second.visibility = if (sel) View.VISIBLE else View.INVISIBLE
                (t.third).apply {
                    setColor(if (sel) ACCENT else 0xFFF1F5F9.toInt())
                }
            }
        }

        container.removeAllViews()
        options.forEach { o ->
            val iconBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF1F5F9.toInt()) }
            val emoji = TextView(ctx).apply {
                text = o.emoji; textSize = 24f; gravity = Gravity.CENTER; background = iconBg
            }
            val check = TextView(ctx).apply {
                text = "✓"; textSize = 18f; setTextColor(ACCENT); gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                visibility = View.INVISIBLE
            }
            val title = TextView(ctx).apply {
                text = o.title; textSize = 15f; setTextColor(TITLE); setTypeface(typeface, Typeface.BOLD)
            }
            val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            text.addView(titleRow)
            if (!o.badge.isNullOrBlank()) {
                val badge = TextView(ctx).apply {
                    this.text = o.badge; textSize = 10.5f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
                    background = GradientDrawable().apply { cornerRadius = dp(ctx, 8).toFloat(); setColor(0xFFDBEAFE.toInt()) }
                }
                text.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(ctx, 3) })
            }
            text.addView(TextView(ctx).apply {
                this.text = o.desc; textSize = 12.5f; setTextColor(BODY); setLineSpacing(dp(ctx, 2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 5) })

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (current != o.id) { current = o.id; refresh(); onSelect(o.id) }
                }
            }
            row.addView(emoji, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 12); marginEnd = dp(ctx, 6) })
            row.addView(check, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))

            cards[o.id] = Triple(row, check, iconBg)
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 8) })
        }
        refresh()
    }
}
