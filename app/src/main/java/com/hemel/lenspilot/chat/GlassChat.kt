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

    // ------------------------------------------------------------------
    // v87: সব গ্লাস কার্ড এখন মেইন চ্যাটের মেসেজ-ডিজাইনের গ্লাস রূপ — আসল কাজ [GlassMain]-এ। নিচের ফাংশনগুলো আগের নাম/সিগনেচার
    // রেখেছে (পুরনো কলাররা না ভেঙে), শুধু ভেতরে GlassMain ডাকে। [onRetry] দিলে কপি/শোনা-র পাশে ↻ রিপিট বাটন আসে।
    // ------------------------------------------------------------------

    /** AI-র বার্তা (গ্লাস)। রিটার্ন-ভিউয়ের `tag` = বডি TextView (লাইভ-স্ট্রিমিং আপডেটের জন্য)। */
    fun aiCard(ctx: Context, text: CharSequence, ts: Long, ticker: TimeTicker?, imageBase64: String? = null, onRetry: (() -> Unit)? = null, showActions: Boolean = true): View =
        GlassMain.aiMessage(ctx, text, ts, ticker, imageBase64, onRetry, showActions)

    /** ইউজারের বার্তা (গ্লাস), ডানে। */
    fun userCard(ctx: Context, text: String, imageBase64: String?, onRetry: (() -> Unit)? = null, showActions: Boolean = true): View =
        GlassMain.userMessage(ctx, text, imageBase64, onRetry, showActions)

    /** "Typing…" — রিটার্ন: (ভিউ, TextView) — ডাকা-পক্ষ নিজে অ্যানিমেট করে। */
    fun typingCard(ctx: Context): Pair<View, TextView> = GlassMain.typing(ctx)

    // বাবল-চ্যাট ([GuideChatOverlay]) — আগের নামগুলো একই গ্লাস কার্ডে
    fun mainAiCard(ctx: Context, text: CharSequence, onRetry: (() -> Unit)? = null, showActions: Boolean = true): View =
        GlassMain.aiMessage(ctx, text, System.currentTimeMillis(), null, null, onRetry, showActions)

    fun mainUserCard(ctx: Context, text: String, imageBase64: String?, onRetry: (() -> Unit)? = null): View =
        GlassMain.userMessage(ctx, text, imageBase64, onRetry)

    fun mainTypingCard(ctx: Context): Pair<View, TextView> = GlassMain.typing(ctx)

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
