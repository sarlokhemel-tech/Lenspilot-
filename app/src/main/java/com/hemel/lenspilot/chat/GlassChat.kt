package com.hemel.lenspilot.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hemel.lenspilot.R

/**
 * v40: গ্লাস-চ্যাটের কার্ড-ভিউ — অটোমেশন ব্রাউজারের লাইভ চ্যাট ([com.hemel.lenspilot.browser.LiveChatDialog])
 * ও গাইডলাইন-মোডের টেম্পোরারি চ্যাট ([GuideChatOverlay]) দুটোই এখান থেকে কার্ড বানায়, তাই দুই জায়গায় হুবহু এক চেহারা।
 *
 *  • AI-র বার্তা  = iPhone নোটিফিকেশনের মতো ফ্রস্টেড কার্ড (বাঁয়ে অ্যাপ-আইকন, উপরে বোল্ড নাম + ডানে "এখন", নিচে বার্তা)।
 *  • ইউজারের বার্তা = হালকা কালো গ্লাস, ডানে।
 *  • সব জায়গায় সাদা বোল্ড লেখা + নরম ছায়া (যেকোনো ব্যাকগ্রাউন্ডে পড়া যায়)।
 *  • চ্যাটের পেছনটা একদম স্বচ্ছ — শুধু কার্ডগুলো কাচের মতো ভাসে।
 */
object GlassChat {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** সাদা বোল্ড লেখা + গাঢ় ছায়া। */
    fun whiteBold(tv: TextView, sizeSp: Float) {
        tv.setTextColor(Color.WHITE)
        tv.textSize = sizeSp
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setShadowLayer(4f, 0f, 1.5f, 0xB3000000.toInt())
    }

    private fun bn(n: Long): String {
        val digits = "০১২৩৪৫৬৭৮৯"
        return n.toString().map { if (it in '0'..'9') digits[it - '0'] else it }.joinToString("")
    }

    /** iPhone-এর "now / 2m ago"-এর বাংলা। */
    fun timeLabel(ts: Long): String {
        val sec = ((System.currentTimeMillis() - ts) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "Now"
            sec < 3600 -> "${sec / 60}m ago"
            sec < 86400 -> "${sec / 3600}h ago"
            else -> "${sec / 86400}d ago"
        }
    }

    /** "এখন" → "২ মি আগে" নিজে থেকে বদলানোর জন্য। */
    class TimeTicker {
        private val handler = Handler(Looper.getMainLooper())
        private val items = ArrayList<Pair<TextView, Long>>()
        private var running = false
        private val tick = object : Runnable {
            override fun run() {
                items.forEach { (tv, ts) -> tv.text = timeLabel(ts) }
                if (running) handler.postDelayed(this, 20_000)
            }
        }
        fun add(tv: TextView, ts: Long) { items.add(tv to ts) }
        fun clear() { items.clear() }
        fun start() { if (!running) { running = true; handler.postDelayed(tick, 20_000) } }
        fun stop() { running = false; handler.removeCallbacks(tick) }
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    /** AI-র বার্তা — iPhone নোটিফিকেশন-কার্ড। [ticker] দিলে "এখন" সময় নিজে বদলায়। */
    fun aiCard(ctx: Context, text: CharSequence, ts: Long, ticker: TimeTicker?, imageBase64: String? = null): View {
        var bodyRef: TextView? = null
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setBackgroundResource(R.drawable.bg_live_glass_card)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 13))
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }
        card.addView(icon, lp(dp(ctx, 38), dp(ctx, 38)).apply { marginEnd = dp(ctx, 11) })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(ctx).apply { this.text = "Lenspilot"; whiteBold(this, 15f) }
        val time = TextView(ctx).apply {
            this.text = timeLabel(ts)
            whiteBold(this, 12f)
            alpha = 0.78f
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(time, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
        col.addView(head)
        ticker?.add(time, ts)

        if (imageBase64 != null) decode(imageBase64)?.let { bmp ->
            val iv = ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            col.addView(iv, lp(dp(ctx, 180), dp(ctx, 130)).apply { topMargin = dp(ctx, 6) })
        }
        if (text.isNotBlank()) {
            val body = TextView(ctx).apply {
                this.text = text
                whiteBold(this, 15f)
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
                setTextIsSelectable(true)
            }
            col.addView(body, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 2) })
            bodyRef = body
        }
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // iPhone-এর মতো কার্ড পুরো প্রস্থ জুড়ে
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        row.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = bodyRef   // v52: লাইভ-স্ট্রিমিং আপডেটের জন্য বডি TextView
        return row.withMargin(ctx).enter(ctx)
    }

    /** ইউজারের বার্তা — হালকা কালো গ্লাস, ডানে। */
    fun userCard(ctx: Context, text: String, imageBase64: String?): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_live_glass_card_user)
            setPadding(dp(ctx, 16), dp(ctx, 11), dp(ctx, 16), dp(ctx, 12))
        }
        if (imageBase64 != null) decode(imageBase64)?.let { bmp ->
            val iv = ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(iv, lp(dp(ctx, 180), dp(ctx, 130)).apply { bottomMargin = dp(ctx, 6) })
        }
        if (text.isNotBlank()) {
            val tv = TextView(ctx).apply {
                this.text = text
                whiteBold(this, 15.5f)
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
                setTextIsSelectable(true)
                maxWidth = ctx.resources.displayMetrics.widthPixels * 80 / 100
            }
            card.addView(tv)
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        row.addView(card, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row.withMargin(ctx).enter(ctx)
    }

    /** "লিখছে…" — AI ভাবছে। রিটার্ন: (ভিউ, TextView) — ডাকা-পক্ষ নিজে অ্যানিমেট করে। */
    fun typingCard(ctx: Context): Pair<View, TextView> {
        val tv = TextView(ctx).apply {
            text = "Typing"
            whiteBold(this, 14f)
            setBackgroundResource(R.drawable.bg_live_glass_card)
            setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 18), dp(ctx, 10))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        row.addView(tv, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row.withMargin(ctx) to tv
    }

    private fun LinearLayout.withMargin(ctx: Context): LinearLayout {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 6) }
        return this
    }

    /** নরম প্রবেশ: নিচ থেকে একটু উঠে আসে + ফেড-ইন। */
    private fun View.enter(ctx: Context): View {
        alpha = 0f
        translationY = dp(ctx, 10).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(260).start()
        return this
    }

    private fun decode(base64: String): Bitmap? = try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }
}
