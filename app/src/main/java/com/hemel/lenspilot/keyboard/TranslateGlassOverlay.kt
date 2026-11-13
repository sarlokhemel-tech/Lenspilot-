package com.hemel.lenspilot.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
import android.view.MotionEvent
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

    /** v127: কার্ডের নিচের লেখার ঘরে চাপলে কীবোর্ড আবার তোলার জন্য। */
    var onShowKeyboard: (() -> Unit)? = null

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

    /** v127: কীবোর্ডের মাথার সর্বশেষ জানা y — কার্ড তৈরির সময়ই এটা দিয়ে কীবোর্ড ঢাকা এড়ানো হয়। */
    @Volatile
    var lastKeyboardTop: Int = Int.MAX_VALUE
        private set

    fun onKeyboardTop(y: Int) {
        lastKeyboardTop = y
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

    private class Bubble(val y: Int, val left: Int, val right: Int, val bottom: Int, val mine: Boolean, val text: String, val pending: Boolean, val orig: String = "")

    /** বাবলগুলো মূল মেসেজের উচ্চতায় বসায়; ওপর-নিচ চড়ে গেলে ঠেলে নামায়; শেষটা নিচের সীমা ছাড়ালে সবাইকে ওপরে তোলে। */
    private class BubbleLayer(context: Context) : ViewGroup(context) {
        private var items: List<Bubble> = emptyList()
        private val d = resources.displayMetrics.density
        /** নিচের লেখার ঘরের (কম্পোজার) জায়গা — বাবল এর নিচে নামে না */
        var reserveBottom = 0

        init { clipChildren = true }

        fun setItems(list: List<Bubble>) {
            items = list
            removeAllViews()
            for (b in list) addView(makeBubble(b))
            requestLayout()
        }

        private fun makeBubble(b: Bubble): View = TextView(context).apply {
            text = b.text
            textSize = 15.5f
            setLineSpacing(1.5f * d, 1f)
            setTextColor(if (b.pending) 0xFF64748B.toInt() else 0xFF0B1220.toInt())
            setPadding((13 * d).toInt(), (7 * d).toInt(), (13 * d).toInt(), (7 * d).toInt())
            // v126: অন্তত মূল মেসেজের উচ্চতা — অনুবাদ ছোট হলেও নিচের মূল লেখা বেরিয়ে থাকে না
            minHeight = (b.bottom - b.y).coerceAtLeast(0)
            gravity = Gravity.CENTER_VERTICAL
            // v127: হাইলাইট — শনাক্ত-লেখার ঠিক ওপরে রঙিন বাক্স (অন্যের = হালকা হলুদ, আমার = হালকা নীল), ওপরে অনুবাদ
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(if (b.pending) 0xEEF1F5F9.toInt() else if (b.mine) 0xF2D3E6FF.toInt() else 0xF2FFF1B8.toInt())
                setStroke((2 * d).toInt(), if (b.mine) 0xFF5B9BFF.toInt() else 0xFFF2B705.toInt())
            }
            elevation = 3 * d
            // বাবলে চাপলে মূল লেখা ↔ অনুবাদ বদলায়
            if (!b.pending && b.orig.isNotEmpty()) {
                val v = this
                setOnClickListener { v.text = if (v.text.toString() == b.text) b.orig else b.text }
            }
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
            val limit = h - reserveBottom - (6 * d).toInt()
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

    /** v127: "অঞ্চল বেছে অনুবাদ" — কাচের ওপর টেনে একটা আয়তক্ষেত্র আঁকলে সেই অংশ আলাদা করে পড়ে অনুবাদ করে। */
    private class SelectView(
        context: Context,
        private val hintText: String,
        private val onSelected: (Rect) -> Unit,
        private val onCancel: () -> Unit
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val fill = Paint().apply { color = 0x332563EB; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2563EB.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
            pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt(); textSize = 16f * d; textAlign = Paint.Align.CENTER
        }
        private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
        private var sx = 0f
        private var sy = 0f
        private var cx = 0f
        private var cy = 0f
        private var dragging = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; cx = sx; cy = sy; dragging = true; invalidate() }
                MotionEvent.ACTION_MOVE -> { cx = e.rawX; cy = e.rawY; invalidate() }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val r = Rect(minOf(sx, cx).toInt(), minOf(sy, cy).toInt(), maxOf(sx, cx).toInt(), maxOf(sy, cy).toInt())
                    invalidate()
                    if (r.width() > 24 * d && r.height() > 16 * d) onSelected(r) else onCancel()
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); onCancel() }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            if (dragging) {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val l = minOf(sx, cx) - loc[0]; val r = maxOf(sx, cx) - loc[0]
                val t = minOf(sy, cy) - loc[1]; val b = maxOf(sy, cy) - loc[1]
                c.drawRect(l, t, r, b, fill)
                c.drawRect(l, t, r, b, line)
            } else {
                val w = hintPaint.measureText(hintText) + 32 * d
                val cxm = width / 2f
                val cym = height * 0.4f
                c.drawRoundRect(cxm - w / 2, cym - 26 * d, cxm + w / 2, cym + 16 * d, 22 * d, 22 * d, hintBg)
                c.drawText(hintText, cxm, cym + 4 * d, hintPaint)
            }
        }
    }

    private class Impl(val ctx: Context) {
        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()

        // v127: তিনটা আলাদা উইন্ডো — মাঝেরটা (কাচ) স্পর্শ-ছাড় (নিচের অ্যাপ/কীবোর্ড আগের মতোই চালানো যায়), ওপর ও নিচের বার স্পর্শযোগ্য
        private lateinit var glassRoot: FrameLayout
        private lateinit var topRoot: LinearLayout
        private lateinit var bottomRoot: LinearLayout
        private lateinit var title: TextView
        private lateinit var layer: BubbleLayer
        private lateinit var hint: TextView
        private lateinit var statusView: TextView
        private lateinit var draftView: TextView
        private lateinit var closeBtn: TextView
        private lateinit var sendBtnV: TextView
        private lateinit var selectBtn: TextView
        private var selectView: SelectView? = null
        private var selecting = false
        /** প্রতিটা হাইলাইট-বাবল নিজের ছোট উইন্ডোতে — বাবলের বাইরে স্পর্শ সরাসরি নিচের অ্যাপে যায় */
        private val bubbleWins = ArrayList<View>()
        private var tgOverlay: KeyboardResizeOverlay? = null
        private var tgSel: String? = null
        private var tgStart = floatArrayOf(1f, 1f, 1f)
        private var tgStartPx = intArrayOf(0, 0)
        private val tgIds = listOf("tg_close", "tg_send", "tg_title")

        private var kbTop = TranslateGlassOverlay.lastKeyboardTop
        private var attached = false
        private var scanning = false
        private var passing = false
        private var lastSmall: IntArray? = null
        private var prevScanSmall: IntArray? = null

        /** মূল-লেখা → আমার-ভাষার অনুবাদ */
        private val cache = LinkedHashMap<String, String>()
        /** আমি যা পাঠিয়েছি: অনুবাদ-করা লেখা → আমার আসল লেখা */
        private val sent = LinkedHashMap<String, String>()
        private var current: List<ChatLine> = emptyList()
        /** হাত দিয়ে অঞ্চল বেছে পাওয়া লাইন — স্ক্রিন বদলালে মুছে যায় */
        private val manual = ArrayList<ChatLine>()

        private val scanRun = Runnable { scan(false) }

        private fun statusBarHeight(): Int {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
        }

        private fun navBarHeight(): Int {
            val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        }

        private fun realScreenH(): Int =
            if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
            else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                dm.heightPixels
            }

        private fun baseAlpha() = if (passing) 0f else 1f

        private fun setAllAlpha(a: Float) {
            if (!attached) return
            glassRoot.alpha = a
            topRoot.alpha = a
            bottomRoot.alpha = a
            for (b in bubbleWins) b.alpha = a
        }

        private fun setTouchable(v: View, touchable: Boolean) {
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(v, lp) }
        }

        private fun roundBtn(label: String, sizeSp: Float, cd: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = label
            textSize = sizeSp
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE8EDF5.toInt()) }
            contentDescription = cd
            setOnClickListener { onClick() }
        }

        private fun windowParams(h: Int, touchable: Boolean, blur: Boolean): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (blur && Build.VERSION.SDK_INT >= 31) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, h, overlayType(), flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (blur && Build.VERSION.SDK_INT >= 31) blurBehindRadius = dp(18)
            }
        }

        fun attach(): Boolean {
            // ---------------- ১) কাচ: প্রায়-স্বচ্ছ, স্পর্শ-ছাড় ----------------
            glassRoot = FrameLayout(ctx).apply {
                // সত্যিকারের কাচ — হালকা নীলচে আভা; নিচের পাতা পরিষ্কার দেখা যায়
                setBackgroundColor(0x40EAF1FB.toInt())
            }
            layer = BubbleLayer(ctx)
            hint = TextView(ctx).apply {
                text = ctx.getString(R.string.tg_empty_chat)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF334155.toInt())
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xDDFFFFFF.toInt()) }
            }
                        glassRoot.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = statusBarHeight() + dp(64); marginStart = dp(24); marginEnd = dp(24) })

            // ---------------- ২) ওপরের বার: শিরোনাম + 🔍 + ↻ + ✕ ----------------
            topRoot = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
                    setColor(0xD9F1F5FB.toInt())
                }
                elevation = dp(3).toFloat()
            }
            title = TextView(ctx).apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
                setPadding(dp(18), 0, dp(8), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { cycleOutLang() }
                // v124: লং-প্রেস = এই কার্ডের বাটন/লেখা টেনে ছোট-বড় (Gboard-এর মতো দাগ)
                setOnLongClickListener { startTgEdit(); true }
            }
            selectBtn = roundBtn("🔍", 18f, ctx.getString(R.string.tg_select_cd)) { toggleSelect() }
            val refresh = roundBtn("↻", 20f, "Refresh") { scan(true) }
            closeBtn = roundBtn("✕", 20f, "Close") { TranslateGlassOverlay.hide() }
            topRoot.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRoot.addView(selectBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
            topRoot.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
            topRoot.addView(closeBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            refreshTitle()

            // ---------------- ৩) নিচের বার: স্ট্যাটাস + গ্লাস-কার্ড লেখার ঘর (কীবোর্ডের লেখার আয়না) ----------------
            bottomRoot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            statusView = TextView(ctx).apply {
                textSize = 13f
                setTextColor(0xFF334155.toInt())
                setPadding(dp(14), dp(4), dp(14), dp(4))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xDDFFFFFF.toInt()) }
                visibility = View.GONE
            }
            bottomRoot.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(4) })
            val compose = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(0xE8FFFFFF.toInt())
                    setStroke(dp(1), 0xFFCBD5E1.toInt())
                }
                elevation = dp(4).toFloat()
                setPadding(dp(18), dp(6), dp(8), dp(6))
            }
            draftView = TextView(ctx).apply {
                hint = ctx.getString(R.string.tg_hint)
                setHintTextColor(0xFF64748B.toInt())
                setTextColor(0xFF0F172A.toInt())
                textSize = 17f
                minHeight = dp(48)
                maxLines = 5
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), dp(8), dp(8))
            }
            sendBtnV = TextView(ctx).apply {
                text = "➤"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2563EB.toInt()) }
                setOnClickListener { TranslateGlassOverlay.onSend?.invoke() }
                contentDescription = "Send"
            }
            compose.addView(draftView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            compose.addView(sendBtnV, LinearLayout.LayoutParams(dp(48), dp(48)))
            // ঘরে চাপলে কীবোর্ড তোলে; ওপরে টেনে ছাড়লে (swipe up) অনুবাদ করে পাঠায়
            var downY = 0f
            compose.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downY = e.rawY
                    MotionEvent.ACTION_UP -> {
                        val dy = downY - e.rawY
                        if (dy > dp(36)) TranslateGlassOverlay.onSend?.invoke()
                        else if (Math.abs(dy) < dp(12)) TranslateGlassOverlay.onShowKeyboard?.invoke()
                    }
                }
                true
            }
            bottomRoot.addView(compose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(10), dp(2), dp(10), dp(8)) })
            bottomRoot.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
                if (b - t != ob - ot) {
                    layer.reserveBottom = bottomRoot.height
                    layer.requestLayout()
                }
            }

            val kb = kbTop != Int.MAX_VALUE
            // alpha ≤ 0.8: Android 12+ এর নিয়ম — নইলে এই উইন্ডো পার হয়ে নিচের অ্যাপে স্পর্শ যায় না
            val lpGlass = windowParams(if (kb) kbTop.coerceAtLeast(dp(220)) else WindowManager.LayoutParams.MATCH_PARENT, false, false).apply { alpha = 0.8f }
            val lpTop = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, true, true).apply { y = statusBarHeight() }
            val lpBottom = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, true, true).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                y = if (kb) (realScreenH() - kbTop).coerceAtLeast(0) else navBarHeight()
            }
            val added = ArrayList<View>()
            try {
                wm.addView(glassRoot, lpGlass); added.add(glassRoot)
                wm.addView(topRoot, lpTop); added.add(topRoot)
                wm.addView(bottomRoot, lpBottom); added.add(bottomRoot)
            } catch (e: Exception) {
                Log.w("TranslateGlass", "addView failed", e)
                added.forEach { runCatching { wm.removeView(it) } }
                return false
            }
            attached = true
            tgApply()
            scheduleScan(500, force = true)
            return true
        }

        /** কাচ কীবোর্ডের মাথা পর্যন্ত (কীবোর্ড দেখা না গেলে পুরো স্ক্রিন); নিচের বার ঠিক কীবোর্ডের ওপরে। */
        private fun applyWindowSize() {
            if (!attached) return
            val kb = kbTop != Int.MAX_VALUE
            (glassRoot.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.height = if (kb) kbTop.coerceAtLeast(dp(220)) else WindowManager.LayoutParams.MATCH_PARENT
                runCatching { wm.updateViewLayout(glassRoot, lp) }
            }
            (bottomRoot.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.y = if (kb) (realScreenH() - kbTop).coerceAtLeast(0) else navBarHeight()
                runCatching { wm.updateViewLayout(bottomRoot, lp) }
            }
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
                width = (dp(42) * kc.w).toInt().coerceAtLeast(dp(16)); height = (dp(42) * kc.h).toInt().coerceAtLeast(dp(16))
            }
            closeBtn.textSize = 20f * kc.t
            val ks = KeyCustomizer.get(ctx, "tg_send")
            sendBtnV.layoutParams = (sendBtnV.layoutParams as LinearLayout.LayoutParams).apply {
                width = (dp(48) * ks.w).toInt().coerceAtLeast(dp(16)); height = (dp(48) * ks.h).toInt().coerceAtLeast(dp(16))
            }
            sendBtnV.textSize = 20f * ks.t
            title.textSize = 16f * KeyCustomizer.get(ctx, "tg_title").t
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

        // ------------------------------------------------------------------
        // v127: অঞ্চল বেছে অনুবাদ (🔍) — স্বয়ংক্রিয় হাইলাইট কিছু মিস করলে টেনে দেখিয়ে দেওয়া
        // ------------------------------------------------------------------

        private fun toggleSelect() { if (selecting) stopSelect() else startSelect() }

        private fun startSelect() {
            if (!attached || selecting) return
            selecting = true
            val sv = SelectView(ctx, ctx.getString(R.string.tg_select_hint), { r -> stopSelect(); scanRegion(r) }, { stopSelect() })
            glassRoot.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            selectView = sv
            setTouchable(glassRoot, true)
            (selectBtn.background as? GradientDrawable)?.setColor(0xFF2563EB.toInt())
            selectBtn.setTextColor(0xFFFFFFFF.toInt())
        }

        private fun stopSelect() {
            if (!selecting) return
            selecting = false
            selectView?.let { glassRoot.removeView(it) }
            selectView = null
            if (attached) {
                setTouchable(glassRoot, false)
                (selectBtn.background as? GradientDrawable)?.setColor(0xFFE8EDF5.toInt())
                selectBtn.setTextColor(0xFF1E293B.toInt())
            }
        }

        fun detach() {
            stopSelect()
            stopTgEdit()
            handler.removeCallbacksAndMessages(null)
            scope.coroutineContext[Job]?.cancel()
            clearBubbleWindows()
            if (attached) {
                runCatching { wm.removeView(glassRoot) }
                runCatching { wm.removeView(topRoot) }
                runCatching { wm.removeView(bottomRoot) }
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
            setAllAlpha(0f)
            setTouchable(topRoot, false)
            setTouchable(bottomRoot, false)
            for (b in bubbleWins) setTouchable(b, false)
            handler.postDelayed({
                if (!attached) return@postDelayed
                passing = false
                setAllAlpha(1f)
                setTouchable(topRoot, true)
                setTouchable(bottomRoot, true)
                for (b in bubbleWins) setTouchable(b, true)
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

        // ---------------- স্ক্রিনশট + OCR/Vision দিয়ে লেখা পড়া ও অনুবাদ ----------------

        private var forceNext = false

        fun scheduleScan(delay: Long = 2500, force: Boolean = false) {
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

        private fun fpDiff(a: IntArray, b: IntArray): Long {
            var diff = 0L
            for (i in a.indices) {
                val x = a[i]; val y = b[i]
                diff += Math.abs(((x shr 16) and 255) - ((y shr 16) and 255)) +
                    Math.abs(((x shr 8) and 255) - ((y shr 8) and 255)) +
                    Math.abs((x and 255) - (y and 255))
            }
            return diff / a.size
        }

        /** [userTap] = ↻ চাপা; নইলে আগের ছবির সঙ্গে প্রায় একই হলে সার্ভারে পাঠাই না (টোকেন বাঁচে)। */
        private fun scan(userTap: Boolean) {
            if (!attached) return
            if (scanning || passing || selecting) { scheduleScan(1500); return }
            val cap = ScreenCaptureService.instance
            if (cap == null || !cap.isReady) {
                setStatus(ctx.getString(R.string.tg_capture_lost))
                scheduleScan(4000)
                return
            }
            val force = userTap || forceNext
            forceNext = false
            scanning = true
            // স্ক্রিনশটে আমাদের নিজের কার্ড যেন না আসে — এক মুহূর্ত স্বচ্ছ
            setAllAlpha(0f)
            handler.postDelayed({
                if (!attached) { scanning = false; return@postDelayed }
                scope.launch {
                    var bmp: android.graphics.Bitmap? = null
                    try {
                        bmp = try { cap.captureFreshFrame() } catch (e: Exception) { null }
                        if (attached) setAllAlpha(baseAlpha())
                        if (bmp == null || !attached) return@launch
                        processFrame(bmp, force)
                    } finally {
                        if (attached) setAllAlpha(baseAlpha())
                        runCatching { bmp?.recycle() }
                        scanning = false
                        setStatus(null)
                        scheduleScan(2500)
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

            var moving = false
            var changed = false
            val prep = kotlinx.coroutines.withContext(Dispatchers.Default) {
                try {
                    val crop = android.graphics.Bitmap.createBitmap(bmp, 0, top, w, bottom - top)
                    // বদলেছে কিনা দেখার ছোট ফিঙ্গারপ্রিন্ট (৩২×৪৮)
                    val tiny = android.graphics.Bitmap.createScaledBitmap(crop, 32, 48, true)
                    val px = IntArray(32 * 48)
                    tiny.getPixels(px, 0, 32, 0, 0, 32, 48)
                    tiny.recycle()
                    val prevScan = prevScanSmall
                    prevScanSmall = px
                    val sentFp = lastSmall
                    val diffSent = if (sentFp != null && sentFp.size == px.size) fpDiff(px, sentFp) else 999L
                    val diffPrev = if (prevScan != null && prevScan.size == px.size) fpDiff(px, prevScan) else 0L
                    changed = diffSent >= 6
                    if (!changed && !force) {
                        crop.recycle()
                        return@withContext null
                    }
                    // স্ক্রিন এখনো নড়ছে (স্ক্রল চলছে) — থামা পর্যন্ত অপেক্ষা
                    if (diffPrev >= 6 && !force) {
                        moving = true
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
            }
            if (moving) {
                // পুরনো বাবল ভুল জায়গায় থাকবে — সরিয়ে দিই, থামলে নতুন করে বসবে
                current = emptyList(); manual.clear(); render()
                return
            }
            if (prep == null) return
            if (!attached) return
            if (changed) manual.clear()

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

        /** 🔍 দিয়ে বাছা অঞ্চল: ঠিক ওই অংশটুকুর ছবি পাঠিয়ে পড়া/অনুবাদ করে হাইলাইট বসায়। */
        private fun scanRegion(r: Rect) {
            if (!attached) return
            if (scanning) { handler.postDelayed({ scanRegion(r) }, 600); return }
            val cap = ScreenCaptureService.instance
            if (cap == null || !cap.isReady) {
                setStatus(ctx.getString(R.string.tg_capture_lost))
                return
            }
            scanning = true
            setAllAlpha(0f)
            handler.postDelayed({
                if (!attached) { scanning = false; return@postDelayed }
                scope.launch {
                    var bmp: android.graphics.Bitmap? = null
                    try {
                        bmp = try { cap.captureFreshFrame() } catch (e: Exception) { null }
                        if (attached) setAllAlpha(baseAlpha())
                        if (bmp == null || !attached) return@launch
                        processRegion(bmp, r)
                    } finally {
                        if (attached) setAllAlpha(baseAlpha())
                        runCatching { bmp?.recycle() }
                        scanning = false
                        setStatus(null)
                        scheduleScan(2500)
                    }
                }
            }, 220)
        }

        private suspend fun processRegion(bmp: android.graphics.Bitmap, r: Rect) {
            val rr = Rect(r)
            if (!rr.intersect(0, 0, bmp.width, bmp.height)) return
            if (rr.width() < dp(24) || rr.height() < dp(16)) return
            val b64 = kotlinx.coroutines.withContext(Dispatchers.Default) {
                try {
                    val crop = android.graphics.Bitmap.createBitmap(bmp, rr.left, rr.top, rr.width(), rr.height())
                    val k = minOf(1f, 1280f / maxOf(crop.width, crop.height))
                    val small = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(crop, (crop.width * k).toInt(), (crop.height * k).toInt(), true) else crop
                    val bos = java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                    if (small !== crop) small.recycle()
                    crop.recycle()
                    android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) { null }
            } ?: return
            if (!attached) return
            setStatus(ctx.getString(R.string.tg_reading_screen))
            val res = TranslateScreenApi.translateScreen(ctx, b64, Prefs.translateMyLang(ctx))
            if (res == null) {
                setStatus(ctx.getString(R.string.keyboard_translate_error))
                handler.postDelayed({ setStatus(null) }, 2500)
                return
            }
            if (res.isEmpty()) {
                setStatus(ctx.getString(R.string.tg_no_text_found))
                handler.postDelayed({ setStatus(null) }, 2500)
                return
            }
            val cw = rr.width().toFloat()
            val ch = rr.height().toFloat()
            val out = ArrayList<ChatLine>()
            for (v in res) {
                val src = norm(v.text)
                if (src.isEmpty()) continue
                cache[src] = v.translation.ifBlank { src }
                val box = Rect(
                    rr.left + (v.box[1] / 1000f * cw).toInt(), rr.top + (v.box[0] / 1000f * ch).toInt(),
                    rr.left + (v.box[3] / 1000f * cw).toInt(), rr.top + (v.box[2] / 1000f * ch).toInt()
                )
                out.add(ChatLine(v.text, box, v.mine))
            }
            manual.removeAll { Rect.intersects(it.bounds, rr) }
            manual.addAll(out)
            render()
        }

        private fun clearBubbleWindows() {
            for (v in bubbleWins) runCatching { wm.removeView(v) }
            bubbleWins.clear()
        }

        private fun render() {
            if (!attached) return
            val loc = IntArray(2)
            glassRoot.getLocationOnScreen(loc)
            val all = (current.filter { c -> manual.none { m -> Rect.intersects(m.bounds, c.bounds) } } + manual)
                .sortedBy { it.bounds.top }.take(30)
            val pad = dp(6)
            val items = all.map { l ->
                val n = norm(l.text)
                val shown = lookupSent(n) ?: cache[n]
                Bubble(l.bounds.top - loc[1] - pad, l.bounds.left - loc[0] - pad, l.bounds.right - loc[0] + pad,
                    l.bounds.bottom - loc[1] + pad, l.mine, shown ?: "…", shown == null, l.text)
            }
            clearBubbleWindows()
            hint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) return
            // বাবলগুলো অফ-স্ক্রিনে মেপে/সাজিয়ে নিই (চড়াচড়ি এড়াতে), তারপর প্রতিটাকে নিজের উইন্ডোতে বসাই
            val gw = glassRoot.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
            val gh = glassRoot.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
            layer.reserveBottom = if (::bottomRoot.isInitialized) bottomRoot.height else 0
            layer.setItems(items)
            layer.measure(View.MeasureSpec.makeMeasureSpec(gw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(gh, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, gw, gh)
            val kids = ArrayList<View>()
            for (i in 0 until layer.childCount) kids.add(layer.getChildAt(i))
            val rects = kids.map { Rect(it.left, it.top, it.right, it.bottom) }
            layer.removeAllViews()
            kids.forEachIndexed { i, v ->
                val r = rects[i]
                val lp = WindowManager.LayoutParams(
                    r.width(), r.height(), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START; x = r.left + loc[0]; y = r.top + loc[1] }
                try {
                    wm.addView(v, lp)
                    bubbleWins.add(v)
                    v.alpha = baseAlpha()
                } catch (e: Exception) {
                    Log.w("TranslateGlass", "bubble addView failed", e)
                }
            }
        }
    }
}
