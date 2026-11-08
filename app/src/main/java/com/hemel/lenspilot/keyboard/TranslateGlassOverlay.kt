package com.hemel.lenspilot.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.ScreenCaptureGrant
import com.hemel.lenspilot.ScreenCaptureTrampolineActivity
import com.hemel.lenspilot.vision.ScreenCaptureService
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** অনুবাদ API-র ছোট হেল্পার — একসাথে অনেক মেসেজ, একই ক্রমে ফেরত। ব্যর্থ হলে null। */
object TranslateApi {
    suspend fun translate(ctx: Context, items: List<String>, target: String): List<String>? {
        if (items.isEmpty()) return emptyList()
        val body = JSONObject().put("items", JSONArray(items)).put("target_lang", target).toString()
        var result: List<String>? = null
        val r = ApiClient.streamAuthed(ctx, ctx.getString(R.string.space_base_url), "/api/translate", body) { ev ->
            if (ev.optString("type") == "done") {
                val a = ev.optJSONArray("translations")
                result = if (a != null) (0 until a.length()).map { a.optString(it) } else listOf(ev.optString("translated_text"))
            }
        }
        return if (r.isSuccess) result else null
    }
}

/** v125: স্ক্রিনশট থেকে পাওয়া একটা মেসেজ — [box] = [ymin, xmin, ymax, xmax], ০–১০০০ স্কেলে। */
class VisionLine(val text: String, val translation: String, val box: IntArray, val mine: Boolean)

object TranslateScreenApi {
    suspend fun translateScreen(ctx: Context, imageB64: String, target: String): List<VisionLine>? {
        val body = JSONObject().put("image_base64", imageB64).put("target_lang", target).toString()
        var result: List<VisionLine>? = null
        val r = ApiClient.streamAuthed(ctx, ctx.getString(R.string.space_base_url), "/api/translate_screen", body) { ev ->
            if (ev.optString("type") == "done") {
                val a = ev.optJSONArray("lines")
                val out = ArrayList<VisionLine>()
                if (a != null) for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val b = o.optJSONArray("box") ?: continue
                    if (b.length() != 4) continue
                    out.add(VisionLine(
                        o.optString("text"), o.optString("translation"),
                        intArrayOf(b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3)), o.optBoolean("mine")
                    ))
                }
                result = out
            }
        }
        return if (r.isSuccess) result else null
    }
}

/** স্ক্রিনে পাওয়া একটা মেসেজ: [text], স্ক্রিনে তার [bounds], আর [mine] = ডানদিকের (আমার পাঠানো) কিনা। */
class ChatLine(val text: String, val bounds: Rect, val mine: Boolean)

private fun overlayType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

/**
 * v125 — অনুবাদ-মোডের গ্লাস কার্ড (Accessibility ছাড়া)।
 *
 *  - স্ক্রিন পড়া: MediaProjection স্ক্রিন-ক্যাপচার (ScreenCaptureService) → ছবি সার্ভারে OCR/Vision-এ পাঠিয়ে মেসেজ-লেখা + অনুবাদ + জায়গা।
 *  - কার্ড: "Draw over other apps" (TYPE_APPLICATION_OVERLAY) উইন্ডো — কীবোর্ডের ওপরের পুরো জায়গা জুড়ে ঘষা-কাচ;
 *    মূল মেসেজের ঠিক ওপরে অস্বচ্ছ বাবল বসে (নিচেরটা ঢাকা পড়ে)।
 *  - আমার লেখা: কার্ডটা NOT_FOCUSABLE, তাই কীবোর্ড চ্যাট-অ্যাপের আসল বক্সেই লেখে; কার্ডের নিচের ঘর শুধু সেই লেখার আয়না।
 *    ➤ বা Enter চাপলে কীবোর্ড নিজেই (InputConnection দিয়ে) লেখা অনুবাদ করে আসল বক্সে বসায় (আর অ্যাপ সেন্ড-অ্যাকশন দিলে পাঠায়)।
 */
object TranslateGlassOverlay {
    @Volatile
    var isShowing: Boolean = false
        private set

    /** দেখা/বন্ধ হলে কীবোর্ডকে জানায় (আইকনের রং বদলাতে)। */
    var listener: (() -> Unit)? = null

    /** কার্ডের ➤ চাপলে কীবোর্ড এটা চালায় (অনুবাদ করে আসল বক্সে বসানো)। */
    var onSend: (() -> Unit)? = null

    private var impl: Impl? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val LANGS = listOf("বাংলা", "English", "हिन्दी", "اردو", "العربية")

    const val NEED_OVERLAY = 1
    const val NEED_CAPTURE = 2

    /** 0 = চালু হয়েছে, [NEED_OVERLAY] = "অন্য অ্যাপের ওপর দেখানো" অনুমতি দিতে সেটিং খুলেছে, [NEED_CAPTURE] = স্ক্রিন-ক্যাপচার অনুমতির ডায়ালগ এসেছে (দিলে নিজে চালু হবে)। */
    fun show(ctx: Context): Int {
        if (impl != null) return 0
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return NEED_OVERLAY
        }
        val cap = ScreenCaptureService.instance
        if (cap == null || !cap.isReady) {
            requestCapture(app)
            return NEED_CAPTURE
        }
        return if (attachNow(app)) 0 else NEED_OVERLAY
    }

    private fun requestCapture(app: Context) {
        ScreenCaptureGrant.awaitNext { granted ->
            if (!granted) return@awaitNext
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    val c = ScreenCaptureService.instance
                    if (c != null && c.isReady) { attachNow(app); return }
                    if (++tries < 30) mainHandler.postDelayed(this, 150)
                }
            }
            mainHandler.post(poll)
        }
        runCatching {
            app.startActivity(Intent(app, ScreenCaptureTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { ScreenCaptureGrant.deliver(false) }
    }

    private fun attachNow(app: Context): Boolean {
        if (impl != null) return true
        val i = Impl(app)
        if (!i.attach()) return false
        impl = i
        isShowing = true
        listener?.invoke()
        return true
    }

    fun hide() {
        impl?.detach()
        impl = null
        isShowing = false
        listener?.invoke()
    }

    fun onKeyboardTop(y: Int) {
        impl?.onKeyboardTop(y)
    }

    /** কীবোর্ড আসল বক্সে যা লিখেছে তার আয়না (কার্ডের নিচের ঘরে দেখায়)। */
    fun setDraft(text: String) {
        impl?.setDraft(text)
    }

    /** অনুবাদ চলছে — স্ট্যাটাস দেখায় (null = মুছে দাও)। */
    fun setBusy(text: String?) {
        impl?.setBusyText(text)
    }

    /** আমি এই অনুবাদ-করা লেখা [translated] পাঠিয়েছি, আসলটা [original] — চ্যাটে ফিরে এলে আসলটাই দেখাব। */
    fun rememberSent(translated: String, original: String) {
        impl?.rememberSent(translated, original)
    }

    /** কার্ড কিছুক্ষণ স্বচ্ছ ও টাচ-ছাড়া — যাতে নিচের চ্যাট-অ্যাপের সেন্ড বাটন চাপা যায়। */
    fun passThrough(ms: Long) {
        impl?.passThrough(ms)
    }

    // ------------------------------------------------------------------

    private class Bubble(val y: Int, val left: Int, val right: Int, val bottom: Int, val mine: Boolean, val text: String, val pending: Boolean)

    /** বাবলগুলো মূল মেসেজের উচ্চতায় বসায়; ওপর-নিচ চড়ে গেলে ঠেলে নামায়; শেষটা নিচের সীমা ছাড়ালে সবাইকে ওপরে তোলে। */
    private class BubbleLayer(context: Context) : ViewGroup(context) {
        private var items: List<Bubble> = emptyList()
        private val d = resources.displayMetrics.density

        init { clipChildren = true }

        fun setItems(list: List<Bubble>) {
            items = list
            removeAllViews()
            for (b in list) addView(makeBubble(b))
            requestLayout()
        }

        private fun makeBubble(b: Bubble): View = TextView(context).apply {
            text = b.text
            textSize = 14.5f
            setLineSpacing(1.5f * d, 1f)
            setTextColor(if (b.pending) 0xFF94A3B8.toInt() else 0xFF0F172A.toInt())
            setPadding((13 * d).toInt(), (7 * d).toInt(), (13 * d).toInt(), (7 * d).toInt())
            // v126: অন্তত মূল মেসেজের উচ্চতা — অনুবাদ ছোট হলেও নিচের মূল লেখা বেরিয়ে থাকে না
            minHeight = (b.bottom - b.y).coerceAtLeast(0)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(if (b.mine) 0xFFDCEBFF.toInt() else 0xFFFFFFFF.toInt())
                setStroke((1 * d).toInt(), if (b.mine) 0xFFBFD7FF.toInt() else 0xFFE2E8F0.toInt())
            }
            elevation = 2 * d
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val side = (10 * d).toInt()
            for (i in 0 until childCount) {
                // মূল মেসেজের চওড়ার কাছাকাছি (অন্তত ৫৫%), দরকারে ৮৬% পর্যন্ত — যাতে অনুবাদ লম্বা হলেও বাবল ছোট ও পরিষ্কার থাকে
                val bb = items.getOrNull(i)
                val orig = if (bb != null) bb.right - bb.left else 0
                val maxW = ((w * 0.86f).toInt()).coerceAtMost(w - 2 * side)
                val want = maxOf(orig, (w * 0.55f).toInt()).coerceAtMost(maxW)
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(want, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
                )
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val n = childCount
            val gap = (6 * d).toInt()
            val side = (10 * d).toInt()
            val topPad = (4 * d).toInt()
            val limit = h - (6 * d).toInt()
            val hs = IntArray(n) { getChildAt(it).measuredHeight }
            val ys = IntArray(n) { items.getOrNull(it)?.y ?: 0 }
            // ১) সামনে থেকে: ওপরেরটার নিচে ঠেলে নামাই — কখনো চড়ে বসে না
            var prevBottom = topPad - gap
            for (i in 0 until n) {
                ys[i] = maxOf(ys[i], prevBottom + gap)
                prevBottom = ys[i] + hs[i]
            }
            // ২) পেছন থেকে: নিচের সীমা ছাড়ালে শুধু যাদের জায়গা লাগে তারাই ওপরে ওঠে (বাকিরা মূল জায়গাতেই থাকে)
            var nextTop = limit + gap
            for (i in n - 1 downTo 0) {
                if (ys[i] + hs[i] > nextTop - gap) ys[i] = nextTop - gap - hs[i]
                nextTop = ys[i]
            }
            for (i in 0 until n) {
                val c = getChildAt(i)
                val bb = items.getOrNull(i)
                val bw = c.measuredWidth
                // মূল মেসেজ যেদিকে ছিল সেদিকেই: আমার = মূল ডান কিনারায়, অন্যের = মূল বাঁ কিনারায়
                var x = if (bb != null && bb.mine) bb.right - bw else (bb?.left ?: side)
                x = x.coerceIn(side, maxOf(side, w - side - bw))
                c.layout(x, ys[i], x + bw, ys[i] + hs[i])
            }
        }
    }

    private class RootFrame(context: Context, val onBack: () -> Unit) : LinearLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private class Impl(val ctx: Context) {
        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()

        private lateinit var root: RootFrame
        private lateinit var title: TextView
        private lateinit var layer: BubbleLayer
        private lateinit var hint: TextView
        private lateinit var statusView: TextView
        private lateinit var draftView: TextView
        private lateinit var closeBtn: TextView
        private lateinit var sendBtnV: TextView
        private var tgOverlay: KeyboardResizeOverlay? = null
        private var tgSel: String? = null
        private var tgStart = floatArrayOf(1f, 1f, 1f)
        private var tgStartPx = intArrayOf(0, 0)
        private val tgIds = listOf("tg_close", "tg_send", "tg_title")

        private var kbTop = Int.MAX_VALUE
        private var attached = false
        private var scanning = false
        private var passing = false
        private var lastSmall: IntArray? = null
        private var lastChangedAt = 0L

        /** মূল-লেখা → আমার-ভাষার অনুবাদ */
        private val cache = LinkedHashMap<String, String>()
        /** আমি যা পাঠিয়েছি: অনুবাদ-করা লেখা → আমার আসল লেখা */
        private val sent = LinkedHashMap<String, String>()
        private var current: List<ChatLine> = emptyList()

        private val scanRun = Runnable { scan(false) }

        private fun statusBarHeight(): Int {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
        }

        private fun navBarHeight(): Int {
            val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        }

        private fun baseAlpha() = if (passing) 0f else 1f

        fun attach(): Boolean {
            root = RootFrame(ctx) { TranslateGlassOverlay.hide() }.apply {
                orientation = LinearLayout.VERTICAL
                // ঘষা-কাচ: প্রায়-অস্বচ্ছ হালকা নীলচে-সাদা, যাতে নিচের চ্যাটের লেখা ভেসে না ওঠে
                setBackgroundColor(0xFCF1F5FB.toInt())
                setPadding(0, statusBarHeight(), 0, navBarHeight())
                isClickable = true     // নিচের অ্যাপে টাচ যেন না যায়
            }

            // ---- ওপরের বার: শিরোনাম + ↻ + ✕ ----
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title = TextView(ctx).apply {
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
                setPadding(dp(18), 0, dp(8), 0)
                maxLines = 1
                setOnClickListener { cycleOutLang() }
                // v124: লং-প্রেস = এই কার্ডের বাটন/লেখা টেনে ছোট-বড় (Gboard-এর মতো দাগ)
                setOnLongClickListener { startTgEdit(); true }
            }
            val refresh = TextView(ctx).apply {
                text = "↻"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF334155.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE8EDF5.toInt()) }
                setOnClickListener { scan(true) }
                contentDescription = "Refresh"
            }
            val close = TextView(ctx).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF334155.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE8EDF5.toInt()) }
                setOnClickListener { TranslateGlassOverlay.hide() }
                contentDescription = "Close"
            }
            closeBtn = close
            top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(refresh, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
            top.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            refreshTitle()

            // ---- মাঝখান: মেসেজ-বাবল ----
            layer = BubbleLayer(ctx)
            hint = TextView(ctx).apply {
                text = ctx.getString(R.string.tg_empty_chat)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF94A3B8.toInt())
                setPadding(dp(32), 0, dp(32), 0)
            }
            val mid = FrameLayout(ctx)
            mid.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            mid.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            // ---- স্ট্যাটাস ----
            statusView = TextView(ctx).apply {
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(20), dp(2), dp(20), dp(2))
                visibility = View.GONE
            }
            root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- নিচে: মেইন চ্যাটের মতো গ্লাস-কার্ড লেখার ঘর (কীবোর্ডের লেখার আয়না) ----
            val compose = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1), 0xFFE2E8F0.toInt())
                }
                elevation = dp(3).toFloat()
                setPadding(dp(18), dp(6), dp(8), dp(6))
            }
            draftView = TextView(ctx).apply {
                hint = ctx.getString(R.string.tg_hint)
                setHintTextColor(0xFF94A3B8.toInt())
                setTextColor(0xFF0F172A.toInt())
                textSize = 16f
                minHeight = dp(48)
                maxLines = 5
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), dp(8), dp(8))
            }
            val sendBtn = TextView(ctx).apply {
                text = "➤"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2563EB.toInt()) }
                setOnClickListener { TranslateGlassOverlay.onSend?.invoke() }
                contentDescription = "Send"
            }
            sendBtnV = sendBtn
            compose.addView(draftView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            compose.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            root.addView(compose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })

            // NOT_FOCUSABLE: কীবোর্ড আসল চ্যাট-অ্যাপের বক্সের সঙ্গেই জুড়ে থাকে। উইন্ডোর উচ্চতা কীবোর্ডের মাথা পর্যন্ত — কীবোর্ড ঢাকে না।
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(root, lp)
            } catch (e: Exception) {
                Log.w("TranslateGlass", "addView failed", e)
                return false
            }
            attached = true
            tgApply()
            applyWindowSize()
            scheduleScan(500)
            return true
        }

        /** উইন্ডো কীবোর্ডের মাথা পর্যন্ত (কীবোর্ড দেখা না গেলে পুরো স্ক্রিন)। */
        private fun applyWindowSize() {
            if (!attached) return
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
            val kb = kbTop != Int.MAX_VALUE
            lp.height = if (kb) kbTop.coerceAtLeast(dp(220)) else WindowManager.LayoutParams.MATCH_PARENT
            root.setPadding(0, statusBarHeight(), 0, if (kb) 0 else navBarHeight())
            runCatching { wm.updateViewLayout(root, lp) }
        }

        // ------------------------------------------------------------------
        // v124: অনুবাদ কার্ডের ✕ / ➤ / শিরোনামের লেখা — Gboard-এর মতো হ্যান্ডেল টেনে ছোট-বড়
        // ------------------------------------------------------------------

        private fun tgView(id: String): View? = when (id) {
            "tg_close" -> closeBtn
            "tg_send" -> sendBtnV
            "tg_title" -> title
            else -> null
        }

        private fun tgRect(v: View?): Rect? {
            if (v == null || v.width <= 0 || v.height <= 0 || !v.isShown) return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }

        private fun tgApply() {
            if (!::closeBtn.isInitialized || !::sendBtnV.isInitialized || !::title.isInitialized) return
            val kc = KeyCustomizer.get(ctx, "tg_close")
            closeBtn.layoutParams = (closeBtn.layoutParams as LinearLayout.LayoutParams).apply {
                width = (dp(38) * kc.w).toInt().coerceAtLeast(dp(16)); height = (dp(38) * kc.h).toInt().coerceAtLeast(dp(16))
            }
            closeBtn.textSize = 18f * kc.t
            val ks = KeyCustomizer.get(ctx, "tg_send")
            sendBtnV.layoutParams = (sendBtnV.layoutParams as LinearLayout.LayoutParams).apply {
                width = (dp(44) * ks.w).toInt().coerceAtLeast(dp(16)); height = (dp(44) * ks.h).toInt().coerceAtLeast(dp(16))
            }
            sendBtnV.textSize = 18f * ks.t
            title.textSize = 15f * KeyCustomizer.get(ctx, "tg_title").t
        }

        private val tgHost = object : KeyboardResizeOverlay.Host {
            override fun selectedBounds(): Rect? = tgSel?.let { tgRect(tgView(it)) }
            override fun frameBounds(): Rect? = null
            override fun buttonBegin() {
                val id = tgSel ?: return
                val v = tgView(id) ?: return
                val k = KeyCustomizer.get(ctx, id)
                tgStart = floatArrayOf(k.w, k.h, k.t)
                tgStartPx = intArrayOf(v.width, v.height)
            }
            override fun buttonApply(kind: Int, dx: Float, dy: Float) {
                val id = tgSel ?: return
                val k = KeyCustomizer.get(ctx, id)
                when (kind) {
                    0 -> if (id != "tg_title" && tgStartPx[0] > 0) k.w = (tgStart[0] * (tgStartPx[0] + dx) / tgStartPx[0]).coerceIn(0.4f, 3f)
                    1 -> if (id != "tg_title" && tgStartPx[1] > 0) k.h = (tgStart[1] * (tgStartPx[1] + dy) / tgStartPx[1]).coerceIn(0.4f, 3f)
                    2 -> k.t = (tgStart[2] * Math.exp((-dy / (90f * d)).toDouble()).toFloat()).coerceIn(0.5f, 3f)
                }
                tgApply()
            }
            override fun buttonEnd() { KeyCustomizer.save(ctx); tgOverlay?.refresh() }
            override fun frameBegin() {}
            override fun frameApply(kind: Int, dx: Float, dy: Float) {}
            override fun frameEnd() {}
            override fun onReset() {
                tgIds.forEach { val k = KeyCustomizer.get(ctx, it); k.w = 1f; k.h = 1f; k.t = 1f }
                KeyCustomizer.save(ctx)
                tgApply()
                tgOverlay?.refresh()
            }
            override fun onDone() { stopTgEdit() }
            override fun pick(rawX: Float, rawY: Float): Boolean {
                for (id in tgIds) {
                    val r = tgRect(tgView(id)) ?: continue
                    if (r.contains(rawX.toInt(), rawY.toInt())) { tgSel = id; return true }
                }
                return false
            }
        }

        private fun startTgEdit() {
            if (!attached || tgOverlay != null) return
            val o = KeyboardResizeOverlay(ctx, tgHost)
            o.hideMove()
            o.setBackgroundColor(0x33000000)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(o, lp)
                tgOverlay = o
                tgSel = null
                o.post { o.refresh() }
            } catch (e: Exception) {
                Log.w("TranslateGlass", "resize overlay failed", e)
            }
        }

        private fun stopTgEdit() {
            tgOverlay?.let { runCatching { wm.removeView(it) } }
            tgOverlay = null
            tgSel = null
        }

        fun detach() {
            stopTgEdit()
            handler.removeCallbacksAndMessages(null)
            scope.coroutineContext[Job]?.cancel()
            if (attached) {
                runCatching { wm.removeView(root) }
                attached = false
            }
        }

        fun onKeyboardTop(y: Int) {
            val changed = kbTop != y
            kbTop = y
            if (attached && changed) {
                handler.post { applyWindowSize() }
                // কীবোর্ড ওঠা/নামায় চ্যাট সরে যায় — আবার পড়ি
                scheduleScan(600, force = true)
            }
        }

        fun setDraft(text: String) {
            if (!attached) return
            draftView.text = text
        }

        fun setBusyText(t: String?) = setStatus(t)

        fun rememberSent(translated: String, original: String) {
            sent[norm(translated)] = original
            while (sent.size > 100) sent.remove(sent.keys.first())
        }

        fun passThrough(ms: Long) {
            if (!attached) return
            passing = true
            root.alpha = 0f
            (root.layoutParams as? WindowManager.LayoutParams)?.let {
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(root, it) }
            }
            handler.postDelayed({
                if (!attached) return@postDelayed
                passing = false
                root.alpha = 1f
                (root.layoutParams as? WindowManager.LayoutParams)?.let {
                    it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { wm.updateViewLayout(root, it) }
                }
                scheduleScan(1200, force = true)
            }, ms)
        }

        private fun refreshTitle() {
            title.text = "🌐 ${ctx.getString(R.string.tg_title)} · ${Prefs.translateMyLang(ctx)} ⇄ ${Prefs.translateTargetLang(ctx)}"
        }

        private fun cycleOutLang() {
            val idx = LANGS.indexOf(Prefs.translateTargetLang(ctx))
            Prefs.setTranslateTargetLang(ctx, LANGS[(idx + 1).mod(LANGS.size)])
            refreshTitle()
        }

        private fun setStatus(t: String?) {
            if (!attached) return
            statusView.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
            statusView.text = t.orEmpty()
        }

        // ---------------- স্ক্রিনশট + OCR/Vision দিয়ে আসা/পাঠানো মেসেজ পড়া ও অনুবাদ ----------------

        private var forceNext = false

        fun scheduleScan(delay: Long = 5000, force: Boolean = false) {
            if (!attached) return
            if (force) forceNext = true
            handler.removeCallbacks(scanRun)
            handler.postDelayed(scanRun, delay)
        }

        private fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")

        private fun lookupSent(t: String): String? {
            val n = norm(t)
            sent[n]?.let { return it }
            for ((k, v) in sent) if (k.length >= 4 && n.startsWith(k)) return v
            return null
        }

        /** [manual] = ↻ চাপা; নইলে আগের ছবির সঙ্গে প্রায় একই হলে সার্ভারে পাঠাই না (টোকেন বাঁচে)। */
        private fun scan(manual: Boolean) {
            if (!attached) return
            if (scanning || passing) { scheduleScan(1500); return }
            val cap = ScreenCaptureService.instance
            if (cap == null || !cap.isReady) {
                setStatus(ctx.getString(R.string.tg_capture_lost))
                scheduleScan(4000)
                return
            }
            val force = manual || forceNext
            forceNext = false
            scanning = true
            // স্ক্রিনশটে আমাদের নিজের কার্ড যেন না আসে — এক মুহূর্ত স্বচ্ছ
            root.alpha = 0f
            handler.postDelayed({
                if (!attached) { scanning = false; return@postDelayed }
                scope.launch {
                    var bmp: android.graphics.Bitmap? = null
                    try {
                        bmp = try { cap.captureFreshFrame() } catch (e: Exception) { null }
                        if (attached) root.alpha = baseAlpha()
                        if (bmp == null || !attached) return@launch
                        processFrame(bmp, force)
                    } finally {
                        if (attached) root.alpha = baseAlpha()
                        runCatching { bmp?.recycle() }
                        scanning = false
                        setStatus(null)
                        scheduleScan(5000)
                    }
                }
            }, 220)
        }

        private suspend fun processFrame(bmp: android.graphics.Bitmap, force: Boolean) {
            val w = bmp.width
            val h = bmp.height
            val top = (statusBarHeight() + dp(56)).coerceIn(0, h / 3)
            val bottom = (if (kbTop != Int.MAX_VALUE) minOf(kbTop, h) else (h * 0.9f).toInt()).coerceIn(top + 1, h)
            if (bottom - top < dp(120)) return

            val prep = kotlinx.coroutines.withContext(Dispatchers.Default) {
                try {
                    val crop = android.graphics.Bitmap.createBitmap(bmp, 0, top, w, bottom - top)
                    // বদলেছে কিনা দেখার ছোট ফিঙ্গারপ্রিন্ট (৩২×৪৮)
                    val tiny = android.graphics.Bitmap.createScaledBitmap(crop, 32, 48, true)
                    val px = IntArray(32 * 48)
                    tiny.getPixels(px, 0, 32, 0, 0, 32, 48)
                    tiny.recycle()
                    val prev = lastSmall
                    var diff = 0L
                    if (prev != null && prev.size == px.size) {
                        for (i in px.indices) {
                            val a = px[i]; val b = prev[i]
                            diff += Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                                Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
                                Math.abs((a and 255) - (b and 255))
                        }
                        diff /= px.size
                    } else diff = 999
                    val unchanged = diff < 6
                    if (unchanged && !force) {
                        crop.recycle()
                        return@withContext null
                    }
                    lastSmall = px
                    val maxSide = 1280f
                    val k = minOf(1f, maxSide / maxOf(crop.width, crop.height))
                    val small = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(crop, (crop.width * k).toInt(), (crop.height * k).toInt(), true) else crop
                    val bos = java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bos)
                    if (small !== crop) small.recycle()
                    crop.recycle()
                    android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) { null }
            } ?: return
            if (!attached) return

            setStatus(ctx.getString(R.string.tg_reading_screen))
            val res = TranslateScreenApi.translateScreen(ctx, prep, Prefs.translateMyLang(ctx))
            if (res == null) {
                lastSmall = null            // পরের স্ক্যানে আবার চেষ্টা
                setStatus(ctx.getString(R.string.keyboard_translate_error))
                handler.postDelayed({ setStatus(null) }, 2500)
                return
            }
            val ch = (bottom - top).toFloat()
            val out = ArrayList<ChatLine>()
            for (v in res) {
                val src = norm(v.text)
                if (src.isEmpty()) continue
                cache[src] = v.translation.ifBlank { src }
                val r = Rect(
                    (v.box[1] / 1000f * w).toInt(), top + (v.box[0] / 1000f * ch).toInt(),
                    (v.box[3] / 1000f * w).toInt(), top + (v.box[2] / 1000f * ch).toInt()
                )
                out.add(ChatLine(v.text, r, v.mine))
            }
            while (cache.size > 400) cache.remove(cache.keys.first())
            current = out.sortedBy { it.bounds.top }
            render()
        }

        private fun render() {
            if (!attached) return
            val loc = IntArray(2)
            layer.getLocationOnScreen(loc)
            val items = current.map { l ->
                val n = norm(l.text)
                val shown = lookupSent(n) ?: cache[n]
                val pad = dp(6)
                Bubble(l.bounds.top - loc[1] - pad, l.bounds.left - loc[0] - pad, l.bounds.right - loc[0] + pad,
                    l.bounds.bottom - loc[1] + pad, l.mine, shown ?: "…", shown == null)
            }
            layer.setItems(items)
            hint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
