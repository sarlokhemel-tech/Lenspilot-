package com.hemel.lenspilot.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.hemel.lenspilot.R

/**
 * v40: গাইডলাইন-মোডের টেম্পোরারি গ্লাস চ্যাট — কন্ট্রোল-বারের মেসেজ আইকনে খোলে (আগে ছিল ছোট ইনপুট-বক্স)।
 * অটোমেশন ব্রাউজারের লাইভ চ্যাটের মতো: ×, 🖼 গ্যালারির ছবি, লেখার ঘর, AI-র উত্তর iPhone-নোটিফিকেশন কার্ডে।
 * দুই সার্ভিসই (Accessibility ও Vision) এটা ব্যবহার করে। v45: লেখার সময় উইন্ডো পুরো স্ক্রিন (ইনপুট কিবোর্ডের ঠিক ওপরে);
 * পাঠানোর পর কন্টেন্ট-সমান উঁচু ও ফোকাসহীন, তাই বাকি স্ক্রিন ছোঁয়া যায়। স্ক্রিনশটের সময় সার্ভিস [rootView] লুকিয়ে রাখে — AI চ্যাটের কার্ড দেখে না।
 *
 * [onSend] true ফেরালে বার্তা নেওয়া হয়েছে (AI-র উত্তরের অপেক্ষা), false = এখন কোনো কাজ চলছে না।
 * [pickImage] গ্যালারি খুলে ছবি (base64 JPEG, বাতিল হলে null) ফেরত দেয়।
 */
class GuideChatOverlay(
    private val ctx: Context,
    private val windowManager: () -> WindowManager?,
    private val onSend: (text: String, imageBase64: String?) -> Boolean,
    private val pickImage: ((String?) -> Unit) -> Unit,
    private val onUnreadChanged: (Int) -> Unit = {},
    private val onPicking: (Boolean) -> Unit = {}
) {
    private class Msg(val fromUser: Boolean, val text: String, val image: String?, val ts: Long = System.currentTimeMillis())

    private companion object {
        const val MAX_MSGS = 30
        const val BUSY_TIMEOUT_MS = 45_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val msgs = ArrayList<Msg>()
    private val ticker = GlassChat.TimeTicker()

    private var root: View? = null
    private var attached = false
    private var list: LinearLayout? = null
    private var scroll: MaxHeightScrollView? = null
    private var panel: View? = null
    /** true = লেখার মোড: উইন্ডো পুরো স্ক্রিন + ফোকাস + কিবোর্ড। false = পাঠানোর পর: ছোট, ফোকাসহীন (নিচের অ্যাপ ছোঁয়া যায়)। */
    private var compose = true
    private var imeHeight = 0
    private var curPad = 0
    private var input: EditText? = null
    private var previewRow: View? = null
    private var previewImage: ImageView? = null
    private var pendingImage: String? = null
    private var busy = false
    private var typingView: View? = null
    private var typingAnim: ValueAnimator? = null
    private val busyTimeout = Runnable { setBusy(false) }

    /** চ্যাট বন্ধ থাকা অবস্থায় AI-র যত প্রশ্ন/বার্তা এসেছে (চ্যাট-আইকনের ব্যাজ)। চ্যাট খুললে ০। */
    var unread = 0
        private set

    private fun setUnread(n: Int) { if (unread != n) { unread = n; onUnreadChanged(n) } }

    /** স্ক্রিনশটের সময় লুকানোর জন্য; চ্যাট বন্ধ থাকলে null। */
    val rootView: View? get() = root

    val isShowing: Boolean get() = root != null

    fun toggle() { if (root != null) hide() else show() }

    fun show() {
        if (root != null) return
        val wm = windowManager() ?: return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_guide_chat, null)
        bind(v)
        if (!attach(wm, v)) return
        root = v
        attached = true
        setUnread(0)
        renderAll()
        ticker.start()
        requestKeyboard()
    }

    fun hide() {
        val v = root ?: return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        if (attached) runCatching { windowManager()?.removeView(v) }
        attached = false
        root = null
        list = null; scroll = null; panel = null; input = null; previewRow = null; previewImage = null
        imeHeight = 0; curPad = 0
        pendingImage = null
        ticker.stop(); ticker.clear()
        stopTyping()
        handler.removeCallbacks(busyTimeout)
    }

    /** কাজ শেষ/নতুন কাজ — কথোপকথন মুছে ফেলা (চ্যাটটা "টেম্পোরারি")। */
    fun reset() {
        hide()
        msgs.clear()
        busy = false
        setUnread(0)
    }

    /** AI-র কথা (গাইডলাইনের say, প্রশ্ন, ত্রুটি) — চ্যাট বন্ধ থাকলেও জমে থাকে, খুললে দেখা যায়। */
    fun postAi(text: String, asks: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        setBusy(false)
        if (msgs.lastOrNull()?.let { !it.fromUser && it.text == t } == true) return
        addMsg(Msg(false, t, null))
        if (asks && root == null) setUnread(unread + 1)   // বন্ধ চ্যাটে AI প্রশ্ন করলে আইকনে ১, ২…
    }

    // ------------------------------------------------------------------

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        // v45: লেখার সময় পুরো স্ক্রিন (আগে WRAP_CONTENT ছিল — তাই ছোট দেখাত, আর কিবোর্ড অর্ধেক ইনপুট ঢেকে দিত)
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // ফোকাসযোগ্য (কিবোর্ডের জন্য)। LAYOUT_IN_SCREEN নেই — স্ট্যাটাস/নেভিগেশন বারের ভেতরে থাকে।
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
        // ADJUST_RESIZE: কিবোর্ড উঠলে উইন্ডো ছোট হয়, ইনপুট কিবোর্ডের ঠিক ওপরে থাকে। (না হলে fixIme() নিজে প্যাডিং দেয়)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private var focusable = true

    private fun attach(wm: WindowManager, v: View): Boolean {
        focusable = true
        compose = true
        applyPanelMode(v)
        return runCatching { wm.addView(v, params()) }.isSuccess
    }

    /**
     * লেখার মোড ([compose]=true): পুরো স্ক্রিন, ফোকাসসহ, ADJUST_RESIZE, হালকা অন্ধকার পর্দা।
     * পাঠানোর পর ([compose]=false): কন্টেন্ট-সমান উঁচু, NOT_FOCUSABLE — নিচের অ্যাপ আবার ফোকাস পায়, তাই Lenspilot কিবোর্ড/অ্যাপের ঘরে লেখা
     * ও গাইডের পরের ধাপ আগের মতোই চলে। লেখার ঘরে আবার ছুঁলে লেখার মোড ফেরে (bind-এর touch-listener)।
     */
    private fun applyWindow(composeOn: Boolean, focusOn: Boolean) {
        val v = root ?: return
        val wm = windowManager() ?: return
        if (!attached || (compose == composeOn && focusable == focusOn)) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.height = if (composeOn) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = if (focusOn) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.softInputMode = (if (composeOn) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN) or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        compose = composeOn
        focusable = focusOn
        applyPanelMode(v)
        runCatching { wm.updateViewLayout(v, lp) }
        if (!composeOn) setPad(0)
    }

    /** লেখার মোডে বার্তার তালিকা বাকি জায়গা নেয়; ছোট মোডে কন্টেন্টের মাপে (সর্বোচ্চ ৪৫%)। */
    private fun applyPanelMode(v: View) {
        val p = panel ?: return
        val sc = scroll ?: return
        (p.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = if (compose) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
            p.layoutParams = it
        }
        (sc.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = if (compose) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            it.weight = if (compose) 1f else 0f
            sc.layoutParams = it
        }
        sc.maxHeightPx = if (compose) 0 else ctx.resources.displayMetrics.heightPixels * 45 / 100
        // v55: পেছনে কোনো কালচে পর্দা নেই — লেখার সময়ও নিচের স্ক্রিন পুরোপুরি পরিষ্কার দেখা যায় (ফাঁকা জায়গায় ছুঁলেও আগের মতো কিবোর্ড নামে)
        v.setBackgroundColor(0)
    }

    private fun screenHeight(): Int {
        val wm = windowManager() ?: return ctx.resources.displayMetrics.heightPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    @Suppress("DEPRECATION")
    private fun readIme(ins: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ins.isVisible(WindowInsets.Type.ime())) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            val b = ins.systemWindowInsetBottom
            if (b > ins.stableInsetBottom) b else 0
        }

    private fun setPad(px: Int) {
        val v = root ?: return
        if (px == curPad) return
        curPad = px
        v.setPadding(0, 0, 0, px)
    }

    /**
     * ADJUST_RESIZE কিছু ফোনে ওভারলে-উইন্ডোকে ছোট করে না — তখন কিবোর্ড ইনপুটের অর্ধেক ঢেকে দেয়। এখানে সরাসরি মেপে দেখি
     * উইন্ডোর তলা কিবোর্ডের মাথার নিচে নেমেছে কিনা; নামলে ততটুকু নিচে প্যাডিং দিই। উইন্ডো নিজেই ছোট হলে প্যাডিং ০ (দুবার সরে না)।
     */
    private fun fixIme() {
        val v = root ?: return
        if (!compose || imeHeight <= 0) { setPad(0); return }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val windowBottom = loc[1] + v.height
        val kbTop = screenHeight() - imeHeight
        setPad((windowBottom - kbTop).coerceAtLeast(0))
    }

    private fun bind(v: View) {
        list = v.findViewById(R.id.guideChatList)
        panel = v.findViewById(R.id.guideChatPanel)
        scroll = v.findViewById(R.id.guideChatScroll)
        input = v.findViewById(R.id.guideChatInput)
        previewRow = v.findViewById(R.id.guideChatPreviewRow)
        previewImage = v.findViewById(R.id.guideChatPreview)

        v.findViewById<ImageButton>(R.id.guideChatClose).setOnClickListener { hide() }
        v.findViewById<ImageButton>(R.id.guideChatSend).setOnClickListener { submit() }
        v.findViewById<ImageButton>(R.id.guideChatPreviewRemove).setOnClickListener { setPendingImage(null) }
        v.findViewById<ImageButton>(R.id.guideChatAttach).setOnClickListener { attachImage() }
        input?.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN && (!focusable || !compose)) {
                applyWindow(true, true)
                input?.post { requestKeyboard() }
            }
            false
        }
        // লেখার মোডে ওপরের ফাঁকা পর্দায় ছুঁলে কিবোর্ড নামে ও চ্যাট ছোট হয় — নিচের অ্যাপ আবার দেখা/ছোঁয়া যায়
        v.setOnClickListener {
            if (compose) {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
                applyWindow(false, false)
            }
        }
        v.setOnApplyWindowInsetsListener { view, ins ->
            imeHeight = readIme(ins)
            view.post { fixIme() }
            ins
        }
        v.viewTreeObserver.addOnGlobalLayoutListener { fixIme() }
        input?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    /** গ্যালারি পিকারের ওপর চ্যাট-উইন্ডো ঢাকা না দিতে — পিক করার সময় উইন্ডো সরিয়ে, ফিরলে আবার বসাই। */
    private fun attachImage() {
        val v = root ?: return
        val wm = windowManager() ?: return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        runCatching { wm.removeView(v) }
        attached = false
        onPicking(true)
        pickImage { base64 ->
            Handler(Looper.getMainLooper()).post {
                onPicking(false)
                if (root !== v) return@post          // এর মধ্যে বন্ধ হয়ে গেছে
                val w = windowManager()
                if (w != null && !attached) attached = attach(w, v)
                if (!attached) { root = null; return@post }
                if (base64 != null) setPendingImage(base64)
                requestKeyboard()
            }
        }
    }

    private fun setPendingImage(base64: String?) {
        pendingImage = base64
        val row = previewRow ?: return
        if (base64 == null) { row.visibility = View.GONE; return }
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            previewImage?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            row.visibility = View.VISIBLE
        } catch (e: Exception) { pendingImage = null }
    }

    private fun submit() {
        val et = input ?: return
        val t = et.text?.toString()?.trim().orEmpty()
        val img = pendingImage
        if (t.isEmpty() && img == null) return
        if (busy) {
            Toast.makeText(ctx, "Please wait. The reply is on its way…", Toast.LENGTH_SHORT).show()
            return
        }
        et.setText("")
        setPendingImage(null)
        addMsg(Msg(true, t, img))
        setBusy(true)
        // কিবোর্ড নামিয়ে ফোকাস অ্যাপকে ফিরিয়ে দিই — গাইড এখন স্ক্রিন দেখে উত্তর দেবে
        root?.let { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(it.windowToken, 0) }
        applyWindow(false, false)
        if (!onSend(t, img)) {
            postAi("No task is running right now. Start a task from the app first.")
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        handler.removeCallbacks(busyTimeout)
        if (b) handler.postDelayed(busyTimeout, BUSY_TIMEOUT_MS)
        if (root != null) { removeTyping(); if (b) addTyping(); scrollToEnd() }
    }

    private fun addMsg(m: Msg) {
        msgs.add(m)
        while (msgs.size > MAX_MSGS) msgs.removeAt(0)
        if (root != null) renderAll()
    }

    private fun renderAll() {
        val l = list ?: return
        removeTyping()
        l.removeAllViews()
        ticker.clear()
        msgs.forEach { m ->
            l.addView(if (m.fromUser) GlassChat.userCard(ctx, m.text, m.image) else GlassChat.aiCard(ctx, m.text, m.ts, ticker))
        }
        if (busy) addTyping()
        scrollToEnd()
    }

    private fun scrollToEnd() { scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) } }

    private fun addTyping() {
        val l = list ?: return
        val (row, tv) = GlassChat.typingCard(ctx)
        typingView = row
        l.addView(row)
        typingAnim = ValueAnimator.ofInt(0, 4).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tv.text = "Typing" + ".".repeat(it.animatedValue as Int % 4) }
            start()
        }
    }

    private fun removeTyping() {
        stopTyping()
        typingView?.let { list?.removeView(it) }
        typingView = null
    }

    private fun stopTyping() { typingAnim?.cancel(); typingAnim = null }

    private fun requestKeyboard() {
        val et = input ?: return
        fun show() {
            et.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
        show()
        et.post { show() }
        et.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) { show(); et.viewTreeObserver.removeOnWindowFocusChangeListener(this) }
            }
        })
    }
}
