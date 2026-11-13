package com.hemel.lenspilot.chat

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.R
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.audio.RemoteTts

/**
 * v87: গ্লাস কার্ড = অ্যাপের মেইন চ্যাট UI-এর হুবহু গ্লাস রূপ।
 *
 * যেখানে যেখানে গ্লাস কার্ড আছে (ভাসমান AI Agent বাবল, গাইডলাইন-মোডের বাবল চ্যাট, অটোমেশন ব্রাউজারের লাইভ চ্যাট)
 * সবাই এখান থেকে একই জিনিস নেয়:
 *
 *  • [topBar]  — মেইন চ্যাটের টপ বারের মতো: বাঁয়ে সাইডবার (history) বাটন, তার পাশে (শুধু যেখানে স্ক্রিন-দেখা আছে)
 *                👁 স্ক্রিন-দেখা অন/অফ বাটন, একদম ডানে আগের টেম্পোরারি-চ্যাট আইকনের জায়গায় ✕।
 *  • [Sidebar] — মেইন চ্যাটের সাইড ড্রয়ারের মতো গ্লাস history প্যানেল (নতুন চ্যাট + আগের কথোপকথন)।
 *  • [aiMessage] / [userMessage] — মেইন চ্যাটের মেসেজ-আইটেমের মতো: বাবল + নিচে কপি, 🔊 শোনা (TTS), ↻ রিপিট (রি-জেনারেট/আবার পাঠানো)।
 *
 * সিস্টেম (Accessibility / Vision, মডেল, মোড, ভয়েস ইত্যাদি) আগের মতোই — এখানে শুধু চেহারা।
 */
object GlassMain {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ top bar

    class TopBar(val root: View, val sidebarBtn: ImageButton, val screenBtn: ImageButton?, val closeBtn: ImageButton)

    private fun glassIconBtn(ctx: Context, iconRes: Int, desc: String, active: Boolean = false, padDp: Int = 9): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            background = ctx.getDrawable(if (active) R.drawable.bg_live_glass_circle_active else R.drawable.bg_live_glass_circle)
            setPadding(dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
        }

    /**
     * মেইন চ্যাটের টপ বারের গ্লাস রূপ। [showScreenToggle] = false হলে (অটোমেশন ব্রাউজার) 👁 বাটন থাকে না।
     * [middle] = সাইডবার/👁 আর ✕-এর মাঝে বসানোর বাড়তি বাটন (যেমন লাইভ চ্যাটের "উত্তর পড়ে শোনাও" স্পিকার)।
     */
    fun topBar(
        ctx: Context,
        showScreenToggle: Boolean,
        screenOn: Boolean,
        onSidebar: () -> Unit,
        onScreenToggle: () -> Unit,
        onClose: () -> Unit,
        middle: View? = null
    ): TopBar {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_live_glass_input)
            setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        }
        val side = glassIconBtn(ctx, R.drawable.ic_history, "Chat history")
        side.setOnClickListener { onSidebar() }
        row.addView(side, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))

        var eye: ImageButton? = null
        if (showScreenToggle) {
            val b = glassIconBtn(ctx, R.drawable.ic_eye, "Screen viewing")
            b.setOnClickListener { onScreenToggle() }
            row.addView(b, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginStart = dp(ctx, 8) })
            eye = b
            setScreenState(b, screenOn)
        }
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (middle != null) {
            row.addView(middle, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginEnd = dp(ctx, 8) })
        }
        val close = glassIconBtn(ctx, R.drawable.ic_close_white, "Close", padDp = 10)
        close.setOnClickListener { onClose() }
        row.addView(close, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
        return TopBar(row, side, eye, close)
    }

    /** 👁 বাটনের অন/অফ চেহারা — অন = উজ্জ্বল গ্লাস + খোলা চোখ, অফ = সাধারণ গ্লাস + কাটা চোখ। */
    fun setScreenState(btn: ImageButton, on: Boolean) {
        val ctx = btn.context
        btn.setImageResource(if (on) R.drawable.ic_eye else R.drawable.ic_eye_off)
        btn.setColorFilter(Color.WHITE)
        btn.background = ctx.getDrawable(if (on) R.drawable.bg_live_glass_circle_active else R.drawable.bg_live_glass_circle)
        btn.alpha = if (on) 1f else 0.8f
        btn.contentDescription = if (on) "Screen viewing is on. Tap to turn off" else "Screen viewing is off. Tap to turn on"
    }

    // ------------------------------------------------------------------ messages

    private fun whiteBold(tv: TextView, sizeSp: Float) {
        tv.setTextColor(Color.WHITE)
        tv.textSize = sizeSp
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setShadowLayer(4f, 0f, 1.5f, 0xB3000000.toInt())
    }

    private fun decode(base64: String): Bitmap? = try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }

    private fun actionIcon(ctx: Context, iconRes: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            alpha = 0.85f
            setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
            contentDescription = desc
            val tv = android.util.TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }

    private fun actionRow(ctx: Context, textOf: () -> String, canSpeak: Boolean, onRetry: (() -> Unit)?): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(ctx, 30)
        row.addView(actionIcon(ctx, R.drawable.ic_copy, "Copy") { copy(ctx, textOf()) }, LinearLayout.LayoutParams(size, size))
        if (canSpeak) row.addView(actionIcon(ctx, R.drawable.ic_speaker, "Read aloud") { speak(ctx, textOf()) }, LinearLayout.LayoutParams(size, size))
        if (onRetry != null) row.addView(actionIcon(ctx, R.drawable.ic_retry, "Repeat") { onRetry() }, LinearLayout.LayoutParams(size, size))
        return row
    }

    fun copy(ctx: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Lenspilot", text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private var speakingText: String? = null

    /** মেইন চ্যাটের মতোই: আগে সার্ভারের Edge TTS, না হলে ফোনের নিজস্ব TTS। একই মেসেজে আবার চাপলে থামে। */
    fun speak(ctx: Context, raw: String) {
        val clean = raw.replace(Regex("[*_`#>]+"), " ").replace(Regex("\\s+"), " ").trim().take(2500)
        if (clean.isBlank()) return
        if (speakingText == clean) {
            LocalTts.stop(); RemoteTts.stop(); speakingText = null
            return
        }
        speakingText = clean
        LocalTts.stop(); RemoteTts.stop()
        RemoteTts.speak(
            ctx.applicationContext, ctx.getString(R.string.space_base_url), clean,
            onDone = { speakingText = null },
            onError = {
                LocalTts.speak(ctx, clean, onDone = { speakingText = null }, onError = {
                    speakingText = null
                    Toast.makeText(ctx, "Text-to-speech is not available on this device.", Toast.LENGTH_LONG).show()
                })
            }
        )
    }

    fun stopSpeaking() {
        speakingText = null
        LocalTts.stop(); RemoteTts.stop()
    }

    private fun LinearLayout.withMargin(ctx: Context): LinearLayout {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 6) }
        return this
    }

    private fun View.enter(ctx: Context): View {
        alpha = 0f
        translationY = dp(ctx, 10).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(260).start()
        return this
    }

    /**
     * AI-র বার্তা — মেইন চ্যাটের AI বাবলের গ্লাস রূপ (বাঁয়ে, ডানে একটু ফাঁক) + নিচে কপি / শোনা / রিপিট।
     * লাইভ-স্ট্রিমিং আপডেটের জন্য রিটার্ন-করা ভিউয়ের `tag` = বডি [TextView] (আগের [GlassChat.aiCard]-এর মতোই)।
     * [onRetry] null হলে ↻ বাটন থাকে না।
     */
    fun aiMessage(
        ctx: Context,
        text: CharSequence,
        ts: Long,
        ticker: GlassChat.TimeTicker?,
        imageBase64: String? = null,
        onRetry: (() -> Unit)? = null,
        showActions: Boolean = true
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_live_glass_bubble_ai)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 11))
        }
        if (imageBase64 != null) decode(imageBase64)?.let { bmp ->
            val iv = ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(iv, LinearLayout.LayoutParams(dp(ctx, 180), dp(ctx, 130)).apply { bottomMargin = dp(ctx, 6) })
        }
        val body = TextView(ctx).apply {
            this.text = text
            whiteBold(this, 15f)
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setOnLongClickListener { copy(ctx, this.text.toString()); true }
        }
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        row.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(ctx, 40) })
        // ts/ticker: মেইন চ্যাটে সময় দেখানো নেই — পুরনো কলারদের সামঞ্জস্যের জন্য প্যারামিটার রাখা, ব্যবহার হয় না।
        if (showActions) row.addView(actionRow(ctx, { body.text.toString() }, true, onRetry),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 4) })
        row.tag = body
        return row.withMargin(ctx).enter(ctx)
    }

    /** ইউজারের বার্তা — মেইন চ্যাটের ইউজার বাবলের গ্লাস রূপ (ডানে) + নিচে কপি / রিপিট (আবার পাঠানো)। */
    fun userMessage(ctx: Context, text: String, imageBase64: String?, onRetry: (() -> Unit)? = null, showActions: Boolean = true): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (imageBase64 != null) decode(imageBase64)?.let { bmp ->
            val iv = ImageView(ctx).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_live_glass_card_user)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(ctx, 18).toFloat()) }
                }
                clipToOutline = true
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(ctx, 160), dp(ctx, 160)).apply { bottomMargin = dp(ctx, 4); marginStart = dp(ctx, 40) })
        }
        if (text.isNotBlank()) {
            val tv = TextView(ctx).apply {
                this.text = text
                whiteBold(this, 15f)
                setBackgroundResource(R.drawable.bg_live_glass_card_user)
                setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 11))
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
                setOnLongClickListener { copy(ctx, this.text.toString()); true }
            }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 40) })
            if (showActions) row.addView(actionRow(ctx, { text }, false, onRetry),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row.withMargin(ctx).enter(ctx)
    }

    /**
     * v90: উত্তরের নিচে সোর্স-চিপ সারি (গ্লাস রূপ) — আলোচনা মোডে সার্চ হলে। [urls] থাকলে চিপে চাপলে আসল পেজ খোলে।
     * অনেক হলে আড়াআড়ি স্ক্রল। [labels] ফাঁকা হলে null।
     */
    fun sourceChips(ctx: Context, labels: List<String>, urls: List<String>?): View? {
        if (labels.isEmpty()) return null
        val scroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        labels.forEachIndexed { i, lab ->
            val url = urls?.getOrNull(i)?.takeIf { it.startsWith("http") }
            val chip = TextView(ctx).apply {
                text = if (url != null) "↗ $lab" else lab
                whiteBold(this, 12f)
                setBackgroundResource(R.drawable.bg_live_glass_chip)
                setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 6))
                maxLines = 1
                if (url != null) setOnClickListener {
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(ctx, 6) })
        }
        scroll.addView(row)
        return scroll
    }

    /** "Typing…" — মেইন চ্যাটের মতো ছোট গ্লাস বাবল। রিটার্ন: (ভিউ, TextView) — ডাকা-পক্ষ অ্যানিমেট করে। */
    fun typing(ctx: Context): Pair<View, TextView> {
        val tv = TextView(ctx).apply {
            text = "Typing"
            whiteBold(this, 14f)
            setBackgroundResource(R.drawable.bg_live_glass_bubble_ai)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 11))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row.withMargin(ctx) to tv
    }

    /** "Typing..." তিন-বিন্দু অ্যানিমেশন; ডাকা-পক্ষ ValueAnimator-টা ধরে রেখে বন্ধ করে। */
    fun animateTyping(tv: TextView): ValueAnimator =
        ValueAnimator.ofInt(0, 4).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tv.text = "Typing" + ".".repeat(it.animatedValue as Int % 4) }
            start()
        }

    // ------------------------------------------------------------------ sidebar

    /**
     * মেইন চ্যাটের সাইড ড্রয়ারের গ্লাস রূপ। [scope] = [HistoryStore.SCOPE_CHAT] / [HistoryStore.SCOPE_BROWSER]।
     * [host] অবশ্যই FrameLayout (পুরো উইন্ডো জুড়ে) — বাঁ দিক থেকে স্লাইড করে আসে, বাইরে ছুঁলে বন্ধ।
     * [onPick] = একটা পুরনো কথোপকথন বাছা, [onNew] = নতুন চ্যাট।
     */
    class Row(val label: String, val selected: Boolean, val onClick: () -> Unit)
    class Section(val title: String, val rows: List<Row>, val note: String? = null)

    class Sidebar(
        private val ctx: Context,
        private val scope: String,
        private val onPick: (HistoryStore.Session) -> Unit,
        private val onNew: () -> Unit,
        /** সাইডবারে বাড়তি সেটিং-সেকশন (যেমন "স্ক্রিন কীভাবে দেখবে") — স্ক্রিনের ওপরে নয়, এখান থেকেই বদলায়। */
        private val sections: () -> List<Section> = { emptyList() }
    ) {
        private var view: View? = null
        private var host: ViewGroup? = null

        val isOpen: Boolean get() = view != null

        fun toggle(h: ViewGroup) { if (view != null) close() else open(h) }

        fun open(h: ViewGroup) {
            if (view != null) return
            val panelW = minOf(dp(ctx, 320), ctx.resources.displayMetrics.widthPixels * 82 / 100)

            val scrim = FrameLayout(ctx).apply {
                setBackgroundColor(0x40000000)
                isClickable = true
                setOnClickListener { close() }
            }
            val panel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_live_glass_input)
                isClickable = true
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(ctx).apply { text = "History"; whiteBold(this, 18f) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 6) })
            val newBtn = glassIconBtn(ctx, R.drawable.ic_new_chat, "New chat", padDp = 9)
            newBtn.setOnClickListener { close(); onNew() }
            head.addView(newBtn, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
            panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val extra = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sections().forEach { sec ->
                extra.addView(TextView(ctx).apply { text = sec.title; whiteBold(this, 12f); alpha = 0.8f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(ctx, 10); marginStart = dp(ctx, 6); bottomMargin = dp(ctx, 4) })
                sec.note?.let { n ->
                    extra.addView(TextView(ctx).apply {
                        text = n
                        whiteBold(this, 12f)
                        setBackgroundResource(R.drawable.bg_live_glass_chip)
                        setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 9))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(ctx, 6) })
                }
                sec.rows.forEach { r ->
                    extra.addView(TextView(ctx).apply {
                        text = (if (r.selected) "✓  " else "     ") + r.label
                        whiteBold(this, 14f)
                        setBackgroundResource(if (r.selected) R.drawable.bg_live_glass_chip_active else R.drawable.bg_live_glass_chip)
                        setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 10))
                        setOnClickListener { r.onClick(); close() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 5) })
                }
            }
            val sessions = runCatching { HistoryStore.listSessions(ctx, scope) }.getOrDefault(emptyList())
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 8), 0, 0) }
            list.addView(extra)
            if (sections().isNotEmpty()) list.addView(TextView(ctx).apply { text = "Chats"; whiteBold(this, 12f); alpha = 0.8f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(ctx, 12); marginStart = dp(ctx, 6); bottomMargin = dp(ctx, 4) })
            if (sessions.isEmpty()) {
                list.addView(TextView(ctx).apply { text = "No saved chats yet"; whiteBold(this, 14f); alpha = 0.85f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(ctx, 12); marginStart = dp(ctx, 6) })
            }
            sessions.forEach { s ->
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_live_glass_chip)
                    setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
                    setOnClickListener { close(); onPick(s) }
                }
                item.addView(TextView(ctx).apply {
                    text = s.title.ifBlank { "Chat" }
                    whiteBold(this, 14f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                item.addView(TextView(ctx).apply {
                    text = GlassChat.timeLabel(s.updatedAt)
                    whiteBold(this, 11f)
                    alpha = 0.75f
                })
                list.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(ctx, 6) })
            }
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
            panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            scrim.addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
                .apply { setMargins(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8)) })
            h.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            host = h
            view = scrim
            panel.translationX = -panelW.toFloat()
            scrim.alpha = 0f
            panel.animate().translationX(0f).setDuration(220).start()
            scrim.animate().alpha(1f).setDuration(220).start()
        }

        fun close() {
            val v = view ?: return
            view = null
            val h = host
            host = null
            v.animate().alpha(0f).setDuration(160).withEndAction { runCatching { h?.removeView(v) } }.start()
        }

        /** হোস্ট উইন্ডো বন্ধ হলে সরাসরি সরিয়ে ফেলা (অ্যানিমেশন ছাড়া)। */
        fun dismissNow() {
            val v = view ?: return
            runCatching { host?.removeView(v) }
            view = null; host = null
        }
    }

    /**
     * এখন কোন মাধ্যমে স্ক্রিন দেখা হচ্ছে + দরকারি জিনিসগুলো সত্যিই চালু আছে কিনা (Accessibility সার্ভিস, স্ক্রিন-ক্যাপচার)।
     * Accessibility মোডে সার্ভিস বন্ধ থাকলে বাস্তবে স্ক্রিনশটে ফলব্যাক হয় — সেটাও এখানে বলা হয়।
     */
    fun screenStatusNote(ctx: Context, mode: String, screenOn: Boolean?): String {
        val a11y = com.hemel.lenspilot.accessibility.LenspilotAccessibilityService.isEnabled(ctx)
        val capture = com.hemel.lenspilot.vision.ScreenCaptureService.instance?.isReady == true
        val name = when (mode) {
            com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY -> "Accessibility (reads screen elements)"
            com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_VLM -> "VLM (screenshot → vision model)"
            com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_YOLO -> "YOLO (on-device icons + OCR)"
            com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_OCR -> "OCR (on-device text only)"
            com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_HYBRID -> "Hybrid (YOLO + OCR, more elements)"
            else -> "Vision (screenshot → AI)"
        }
        val head = if (screenOn == false) "Screen viewing is OFF — tap 👁 to turn it on.\nSelected method: $name"
                   else "Now viewing via: $name"
        val fallback = when {
            screenOn == false -> ""
            mode == com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY && !a11y ->
                "\n⚠ Accessibility is off, so a screenshot is used instead."
            mode != com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY && !capture ->
                "\n⚠ Screen capture is not allowed yet — Android will ask on your next question."
            else -> ""
        }
        return head + fallback +
            "\nAccessibility service: " + (if (a11y) "ON ✓" else "OFF ✗") +
            "  •  Screen capture: " + (if (capture) "READY ✓" else "NOT READY ✗")
    }

    /** "স্ক্রিন কীভাবে দেখবে" — Settings-এর একই সিস্টেম (Prefs.screenGuideMode), এখন সাইডবার থেকে বদলানো যায়। */
    fun screenMethodSection(ctx: Context, screenOn: Boolean? = null, onChanged: () -> Unit = {}): Section {
        val cur = com.hemel.lenspilot.Prefs.screenGuideMode(ctx)
        fun r(label: String, key: String) = Row(label, cur == key) {
            com.hemel.lenspilot.Prefs.setScreenGuideMode(ctx, key)
            Toast.makeText(ctx, "Screen reading: $label", Toast.LENGTH_SHORT).show()
            onChanged()
        }
        return Section("Screen reading method", note = screenStatusNote(ctx, cur, screenOn), rows = listOf(
            r("Vision (screenshot)", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_VISION),
            r("Accessibility (nodes)", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY),
            r("VLM", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_VLM),
            r("YOLO", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_YOLO),
            r("OCR", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_OCR),
            r("Hybrid", com.hemel.lenspilot.Prefs.SCREEN_GUIDE_MODE_HYBRID)
        ))
    }
}
