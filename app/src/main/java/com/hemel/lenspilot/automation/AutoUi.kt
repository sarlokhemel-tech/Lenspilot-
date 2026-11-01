package com.hemel.lenspilot.automation

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat

/**
 * অটোমেশন মোডের (v37: প্রিমিয়াম হালকা থিম — লোগোর নীল/সাদা; আগের কালো-সবুজ "হ্যাকার" লুক বাদ) ছোট UI-সহায়ক।
 * XML ছাড়া কোডে। পুরো অটোমেশন মোডের (হাব, এজেন্ট পেজ, কন্ট্রোল স্ক্রিন) রং এক জায়গা থেকে আসে।
 */
internal object AutoUi {
    const val BG = 0xFFF4F6FB.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val ACCENT = 0xFF1E6BFF.toInt()
    const val ACCENT_2 = 0xFF00B4FF.toInt()
    const val INK = 0xFF0B1B4A.toInt()
    const val TEXT = 0xFF0F172A.toInt()
    const val MUTED = 0xFF64748B.toInt()
    const val STROKE = 0xFFE3E8F2.toInt()
    const val CHIP_BG = 0xFFEAF1FF.toInt()
    const val DANGER = 0xFFE5484D.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** স্ট্যাটাস বার BG-র সাথে মিশে যায়, আইকন গাঢ়। */
    fun chrome(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = BG
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = BG
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    fun rounded(ctx: Context, fill: Int, radiusDp: Int = 14, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    /** নীল → আকাশি গ্রেডিয়েন্ট (বাটন, হিরো, থাম্বনেইলের ফলব্যাক)। */
    fun gradient(
        ctx: Context, radiusDp: Int = 14,
        from: Int = ACCENT, to: Int = ACCENT_2,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR
    ): GradientDrawable =
        GradientDrawable(orientation, intArrayOf(from, to)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(ctx: Context, label: String, filled: Boolean = true, color: Int = ACCENT, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            setTextColor(if (filled) Color.WHITE else color)
            background = when {
                !filled -> rounded(ctx, Color.TRANSPARENT, 14, color)
                color == ACCENT -> gradient(ctx, 14)
                else -> rounded(ctx, color, 14)
            }
            minHeight = dp(ctx, 46)
            minimumHeight = dp(ctx, 46)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    /** সাদা, গোল-কোণা, হালকা ছায়ার কার্ড। */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, 20, STROKE)
        elevation = dp(ctx, 2).toFloat()
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 12) }
    }

    /** ছোট গোল-কোণা ট্যাগ (ক্যাটাগরি, মেশিন, ট্রান্সপোর্ট)। */
    fun chip(ctx: Context, s: String, fill: Int = CHIP_BG, color: Int = ACCENT): TextView =
        TextView(ctx).apply {
            text = s
            textSize = 11.5f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(ctx, fill, 20)
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        }

    /** শুধু ওপরের দুই কোণ গোল করে ছাঁটে — কার্ডের ওপরের কভার-ছবির জন্য। */
    fun roundTop(view: View, radiusDp: Int) {
        val r = dp(view.context, radiusDp)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height + r, r.toFloat())
            }
        }
        view.clipToOutline = true
    }

    fun gap(ctx: Context, dpv: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dpv))
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
}
