package com.hemel.lenspilot.keyboard

import android.content.Context
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService
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

/**
 * v120 — অনুবাদ-মোডের গ্লাস কার্ড।
 *
 * কীবোর্ডের "文A" আইকন চাপলে চ্যাট-অ্যাপের ওপর (কীবোর্ড বাদে) পুরো জায়গা জুড়ে একটা ঘষা-কাচের (frosted) কার্ড বসে:
 *  - ওপরে শিরোনাম (চাপলে আমার-মেসেজের ভাষা বদলায) আর ✕ (বন্ধ)।
 *  - মাঝখানে চ্যাটের মেসেজগুলো (আসা + আমার পাঠানো) ইউজারের ভাষায় অনুবাদ হয়ে ঠিক মূল মেসেজের জায়গাতেই বাবলে বসে।
 *    বাবলগুলো অস্বচ্ছ, আর একটা আরেকটার ওপর চড়ে না — তাই নিচের আসল লেখা দেখা যায় না, হিজিবিজিও হয় না।
 *  - নিচে মেইন চ্যাটের মতো আরামদায়ক গ্লাস-কার্ড লেখার ঘর। পাঠালে লেখা অনুবাদ হয়ে আসল চ্যাট-অ্যাপের বক্সে বসে
 *    ও সেন্ড হয়; চ্যাটে সেটা দেখা যায়।
 *  - নতুন মেসেজ এলে (Accessibility দিয়ে স্ক্রিনের লেখা পড়ে) নিজে থেকেই অনুবাদ করে কার্ডে দেখায়।
 * এটা Lenspilot Accessibility সার্ভিসের overlay উইন্ডো — সার্ভিস চালু না থাকলে [show] false দেয়।
 */
object TranslateGlassOverlay {
    @Volatile
    var isShowing: Boolean = false
        private set

    /** দেখা/বন্ধ হলে কীবোর্ডকে জানায় (আইকনের রং বদলাতে)। */
    var listener: (() -> Unit)? = null

    private var impl: Impl? = null

    val LANGS = listOf("বাংলা", "English", "हिन्दी", "اردو", "العربية")

    fun show(ctx: Context): Boolean {
        val svc = LenspilotAccessibilityService.instance ?: return false
        if (impl != null) return true
        val i = Impl(svc)
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

    /** Accessibility সার্ভিস অন্য অ্যাপের স্ক্রিন বদলের ইভেন্ট পেলে এটা ডাকে। */
    fun onScreenChanged() {
        impl?.scheduleScan()
    }

    // ------------------------------------------------------------------

    private class Bubble(val y: Int, val left: Int, val right: Int, val mine: Boolean, val text: String, val pending: Boolean)

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

    private class Impl(val svc: LenspilotAccessibilityService) {
        private val ctx: Context = svc
        private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()

        private lateinit var root: RootFrame
        private lateinit var title: TextView
        private lateinit var layer: BubbleLayer
        private lateinit var hint: TextView
        private lateinit var statusView: TextView
        private lateinit var edit: EditText
        private lateinit var closeBtn: TextView
        private lateinit var sendBtnV: TextView
        private var tgOverlay: KeyboardResizeOverlay? = null
        private var tgSel: String? = null
        private var tgStart = floatArrayOf(1f, 1f, 1f)
        private var tgStartPx = intArrayOf(0, 0)
        private val tgIds = listOf("tg_close", "tg_send", "tg_title")

        private var kbTop = Int.MAX_VALUE
        private var attached = false
        private var translateJob: Job? = null
        private var sendJob: Job? = null

        /** মূল-লেখা → আমার-ভাষার অনুবাদ */
        private val cache = LinkedHashMap<String, String>()
        /** আমি যা পাঠিয়েছি: অনুবাদ-করা লেখা → আমার আসল লেখা (চ্যাটে ফিরে এলে API ছাড়াই আসলটা দেখাই) */
        private val sent = LinkedHashMap<String, String>()
        private var current: List<LenspilotAccessibilityService.ChatLine> = emptyList()

        private val scanRun = Runnable { scan() }

        private fun statusBarHeight(): Int {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
        }

        private fun navBarHeight(): Int {
            val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        }

        fun attach(): Boolean {
            root = RootFrame(ctx) { TranslateGlassOverlay.hide() }.apply {
                orientation = LinearLayout.VERTICAL
                // ঘষা-কাচ: প্রায়-অস্বচ্ছ হালকা নীলচে-সাদা, যাতে নিচের চ্যাটের লেখা ভেসে না ওঠে
                setBackgroundColor(0xFCF1F5FB.toInt())
                setPadding(0, statusBarHeight(), 0, navBarHeight())
                isClickable = true     // নিচের অ্যাপে টাচ যেন না যায়
            }

            // ---- ওপরের বার: শিরোনাম + ✕ ----
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

            // ---- নিচে: মেইন চ্যাটের মতো গ্লাস-কার্ড লেখার ঘর ----
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
            edit = EditText(ctx).apply {
                hint = ctx.getString(R.string.tg_hint)
                setHintTextColor(0xFF94A3B8.toInt())
                setTextColor(0xFF0F172A.toInt())
                textSize = 16f
                background = null
                minHeight = dp(48)
                maxLines = 5
                setPadding(0, dp(8), dp(8), dp(8))
                imeOptions = EditorInfo.IME_ACTION_SEND
                setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
                }
            }
            val sendBtn = TextView(ctx).apply {
                text = "➤"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2563EB.toInt()) }
                setOnClickListener { send() }
                contentDescription = "Send"
            }
            sendBtnV = sendBtn
            compose.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            compose.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            root.addView(compose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // NOT_FOCUSABLE নেই — লেখার ঘরে কীবোর্ড আসতে হবে। কীবোর্ড এলে উইন্ডো ছোট হয় (ADJUST_RESIZE)।
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = 28
                }
            }
            try {
                wm.addView(root, lp)
            } catch (e: Exception) {
                Log.w("TranslateGlass", "addView failed", e)
                return false
            }
            attached = true
            tgApply()
            edit.requestFocus()
            handler.postDelayed({
                runCatching {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 250)
            scheduleScan(200)
            return true
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
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
                runCatching {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(edit.windowToken, 0)
                }
                runCatching { wm.removeView(root) }
                attached = false
            }
        }

        fun onKeyboardTop(y: Int) {
            val changed = kbTop != y
            kbTop = y
            if (attached) handler.post { applyBottomPad() }
            // কীবোর্ড ওঠা/নামায় চ্যাট-অ্যাপের মেসেজ সরে যায় — নতুন জায়গা থেকে আবার পড়ি
            if (changed && attached) scheduleScan(350)
        }

        /** কীবোর্ড উইন্ডো ছোট না করলেও লেখার ঘর যেন কীবোর্ডের নিচে না ঢাকে। */
        private fun applyBottomPad() {
            if (!attached) return
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val rootBottom = loc[1] + root.height
            val wanted = if (kbTop == Int.MAX_VALUE) navBarHeight() else maxOf(0, rootBottom - kbTop)
            if (root.paddingBottom != wanted) root.setPadding(0, root.paddingTop, 0, wanted)
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
            statusView.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
            statusView.text = t.orEmpty()
        }

        // ---------------- আসা/পাঠানো মেসেজ পড়া ও অনুবাদ ----------------

        fun scheduleScan(delay: Long = 700) {
            if (!attached) return
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

        /** লেখাটা কি ইতিমধ্যে ইউজারের ভাষায়? (এখন শুধু বাংলা চেনা যায়; বাকিটা সার্ভার "unchanged" ফেরত দেয়) */
        private fun alreadyMyLang(t: String): Boolean {
            if (Prefs.translateMyLang(ctx) != "বাংলা") return false
            val letters = t.count { it.isLetter() }
            if (letters == 0) return true
            val bn = t.count { it in 'ঀ'..'৿' }
            return bn.toFloat() / letters > 0.6f
        }

        private fun scan() {
            if (!attached) return
            val lines = try { svc.captureChatMessages() } catch (e: Exception) { Log.w("TranslateGlass", "scan failed", e); return }
            current = lines
            val need = ArrayList<String>()
            for (l in lines) {
                val t = norm(l.text)
                if (cache.containsKey(t) || lookupSent(t) != null) continue
                if (alreadyMyLang(t)) { cache[t] = t; continue }
                if (t !in need) need.add(t)
            }
            render()
            if (need.isNotEmpty() && translateJob?.isActive != true) {
                val batch = need.take(20)
                val my = Prefs.translateMyLang(ctx)
                translateJob = scope.launch {
                    val out = TranslateApi.translate(ctx, batch, my)
                    if (out != null) {
                        batch.forEachIndexed { i, src -> cache[src] = out.getOrNull(i)?.ifBlank { src } ?: src }
                        while (cache.size > 400) cache.remove(cache.keys.first())
                    } else {
                        // ব্যর্থ — পরের স্ক্যানে আবার চেষ্টা; এখন ফের লুপ না করতে শুধু স্ট্যাটাস
                        setStatus(ctx.getString(R.string.keyboard_translate_error))
                        handler.postDelayed({ setStatus(null) }, 2500)
                    }
                    render()
                    if (out != null && need.size > batch.size) scheduleScan(100)
                }
            }
        }

        private fun render() {
            if (!attached) return
            val loc = IntArray(2)
            layer.getLocationOnScreen(loc)
            val items = current.map { l ->
                val n = norm(l.text)
                val shown = lookupSent(n) ?: cache[n]
                Bubble(l.bounds.top - loc[1], l.bounds.left - loc[0], l.bounds.right - loc[0], l.mine, shown ?: "…", shown == null)
            }
            layer.setItems(items)
            hint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        // ---------------- আমার লেখা → অনুবাদ → চ্যাট-অ্যাপে পাঠানো ----------------

        private fun send() {
            val text = edit.text.toString().trim()
            if (text.isEmpty() || sendJob?.isActive == true) return
            edit.setText("")
            setStatus(ctx.getString(R.string.tg_translating))
            val out = Prefs.translateTargetLang(ctx)
            sendJob = scope.launch {
                val tr = TranslateApi.translate(ctx, listOf(text), out)?.firstOrNull()?.trim()
                if (tr.isNullOrBlank()) {
                    setStatus(null)
                    edit.setText(text)         // হারিয়ে যেতে দিই না
                    edit.setSelection(text.length)
                    Toast.makeText(ctx, R.string.tg_send_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                sent[norm(tr)] = text
                while (sent.size > 100) sent.remove(sent.keys.first())
                svc.sendTextToChatApp(tr) { code ->
                    setStatus(null)
                    when (code) {
                        1 -> Toast.makeText(ctx, R.string.tg_sent_no_button, Toast.LENGTH_LONG).show()
                        2 -> Toast.makeText(ctx, R.string.tg_no_field, Toast.LENGTH_LONG).show()
                    }
                    scheduleScan(900)
                }
            }
        }
    }
}
