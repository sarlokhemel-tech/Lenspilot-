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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import com.hemel.lenspilot.ComposerOptions
import com.hemel.lenspilot.MainActivity
import com.hemel.lenspilot.ModePickerSheet
import com.hemel.lenspilot.PendingMediaPick
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.browser.AutoPromptChat
import com.hemel.lenspilot.guide.GuideAutoPromptLog

/**
 * v40: গাইডলাইন-মোডের টেম্পোরারি গ্লাস চ্যাট — কন্ট্রোল-বারের মেসেজ আইকনে খোলে (আগে ছিল ছোট ইনপুট-বক্স)।
 * অটোমেশন ব্রাউজারের লাইভ চ্যাটের মতো: ×, 🖼 গ্যালারির ছবি, লেখার ঘর, AI-র উত্তর iPhone-নোটিফিকেশন কার্ডে।
 * দুই সার্ভিসই (Accessibility ও Vision) এটা ব্যবহার করে। v45: লেখার সময় উইন্ডো পুরো স্ক্রিন (ইনপুট কিবোর্ডের ঠিক ওপরে);
 * পাঠানোর পর কন্টেন্ট-সমান উঁচু ও ফোকাসহীন, তাই বাকি স্ক্রিন ছোঁয়া যায়। স্ক্রিনশটের সময় সার্ভিস [rootView] লুকিয়ে রাখে — AI চ্যাটের কার্ড দেখে না।
 *
 * [onSend] true ফেরালে বার্তা নেওয়া হয়েছে (AI-র উত্তরের অপেক্ষা), false = এখন কোনো কাজ চলছে না। তৃতীয় আর্গুমেন্ট [withScreen] =
 * ওপরের ডানের 👁 বাটন চালু কিনা — চালু হলে AI এই বার্তার সাথে এখনকার স্ক্রিন (নোড/স্ক্রিনশট) নিজে নেয়, বন্ধ হলে নেয় না।
 * [pickMedia] "image" / "video" / "file" বাছাই চালায়; ফল ([PendingMediaPick.Picked], বাতিলে null) ফেরত দেয়।
 *
 * v81: দেখতে মেইন চ্যাটের হুবহু (একই বাবল, কম্পোজার, গোল বাটন, মডেল/মোড/"+" সাদা শিট)।
 */
class GuideChatOverlay(
    private val ctx: Context,
    private val windowManager: () -> WindowManager?,
    private val onSend: (text: String, imageBase64: String?, withScreen: Boolean) -> Boolean,
    private val pickMedia: (kind: String, cb: (PendingMediaPick.Picked?) -> Unit) -> Unit,
    private val onUnreadChanged: (Int) -> Unit = {},
    private val onPicking: (Boolean) -> Unit = {},
    /** v156: the guide loop's background record of its automatic prompts (null = none) — shown by the "Auto prompt" chip. */
    private val autoLog: () -> GuideAutoPromptLog? = { null }
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
    // v79: মেইন চ্যাটের মতো মডেল/মোড চিপ + তালিকা
    private var modelChip: TextView? = null
    private var modeChip: TextView? = null
    private var pickBox: FrameLayout? = null
    private var pickKind: String? = null
    private var pendingImage: String? = null
    // v81: ভিডিও/ফাইলের নোট (AI-কে যায়) ও প্রিভিউ-লেখা; 👁 স্ক্রিন-দেখা বাটন
    private var pendingContext: String? = null
    private var pendingLabel: String? = null
    private var previewLabel: TextView? = null
    private var screenBtn: ImageButton? = null
    // v156: "Auto prompt" view — the automatic prompts the guide sends in the background, shown like the browser's Auto prompt chat
    private var autoChip: TextView? = null
    private var autoView = false
    private var autoChat: AutoPromptChat? = null
    private var sidebar: GlassMain.Sidebar? = null
    /** v87: সাইডবার থেকে বাছা পুরনো কথোপকথন (শুধু দেখার জন্য)। null = চলতি চ্যাট। */
    private var viewing: List<Msg>? = null
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
        modelChip = null; modeChip = null; pickBox = null; pickKind = null
        previewLabel = null; screenBtn = null
        autoLog()?.detach(); autoChat = null; autoChip = null; autoView = false
        sidebar?.dismissNow(); sidebar = null; viewing = null
        imeHeight = 0; curPad = 0
        pendingImage = null; pendingContext = null; pendingLabel = null
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
        modelChip = v.findViewById(R.id.guideChatModelChip)
        modeChip = v.findViewById(R.id.guideChatModeChip)
        pickBox = v.findViewById(R.id.guideChatPick)
        previewLabel = v.findViewById(R.id.guideChatPreviewLabel)
        // v87: মেইন চ্যাটের টপ বারের গ্লাস রূপ — সাইডবার | 👁 | ········ | ✕
        sidebar = GlassMain.Sidebar(ctx, HistoryStore.SCOPE_CHAT, onPick = { viewSession(it) }, onNew = { newChat() },
            sections = { listOf(GlassMain.screenMethodSection(ctx, screenOn = Prefs.guideChatScreenOn(ctx))) })
        val top = GlassMain.topBar(
            ctx, showScreenToggle = true, screenOn = Prefs.guideChatScreenOn(ctx),
            onSidebar = {
                // সাইডবার পুরো উচ্চতা চায় — তাই পূর্ণ-স্ক্রিন মোডে নিয়ে খুলি
                applyWindow(true, true)
                (v as? ViewGroup)?.let { host -> host.post { sidebar?.toggle(host) } }
            },
            onScreenToggle = { toggleScreen() },
            onClose = { hide() }
        )
        screenBtn = top.screenBtn
        v.findViewById<FrameLayout>(R.id.guideChatTopHolder).addView(top.root)
        modelChip?.setOnClickListener { togglePicker("model") }
        modeChip?.setOnClickListener { togglePicker("mode") }
        autoChip = v.findViewById(R.id.guideChatAutoChip)
        autoChip?.setOnClickListener { toggleAuto() }
        refreshAutoChip()
        refreshChips()
        refreshScreenButton()

        v.findViewById<ImageButton>(R.id.guideChatSend).setOnClickListener { submit() }
        v.findViewById<ImageButton>(R.id.guideChatPreviewRemove).setOnClickListener { setPending(null) }
        v.findViewById<ImageButton>(R.id.guideChatAttach).setOnClickListener { togglePicker("attach") }
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

    // ------------------------------------------------------------------
    // v79: মডেল / মোড বাছাই — মেইন চ্যাটের একই তালিকা (ComposerOptions), গ্লাস স্টাইলে, উইন্ডোর ভেতরেই
    // (ওভারলে উইন্ডোতে Dialog খোলা যায় না, তাই কম্পোজারের ওপরে তালিকা-প্যানেল ওঠে)।
    // ------------------------------------------------------------------

    private fun refreshChips() {
        modelChip?.text = ComposerOptions.systemLabel(Prefs.selectedSystem(ctx)) + " ▾"
        val mk = Prefs.currentModeKey(ctx)
        modeChip?.text = ComposerOptions.modeLabel(mk) + " ▾"
        modeChip?.setBackgroundResource(if (mk == "chat") R.drawable.bg_live_glass_chip else R.drawable.bg_live_glass_chip_active)
    }

    // ------------------------------------------------------------------
    // v81: ওপরের ডান কোনার 👁 — স্ক্রিন দেখা অন/অফ। চালু = AI প্রতিটা বার্তার সাথে এখনকার স্ক্রিন (নোড বা স্ক্রিনশট) নিজে দেখে।
    // ------------------------------------------------------------------

    private fun refreshScreenButton() {
        val on = Prefs.guideChatScreenOn(ctx)
        screenBtn?.let { GlassMain.setScreenState(it, on) }
    }

    private fun toggleScreen() {
        val on = !Prefs.guideChatScreenOn(ctx)
        Prefs.setGuideChatScreenOn(ctx, on)
        refreshScreenButton()
        Toast.makeText(
            ctx,
            if (on) "Screen viewing on — with your next question the AI will look at your screen." else "Screen viewing off — the AI will not see your screen.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun closePicker() {
        pickBox?.apply { removeAllViews(); visibility = View.GONE }
        pickKind = null
    }

    /**
     * "model" / "mode" / "attach" — মেইন চ্যাটের একই সাদা শিট ([ModePickerSheet.buildSheet]), একই তালিকা ([ComposerOptions]),
     * শুধু ওভারলে উইন্ডোতে Dialog খোলা যায় না বলে কম্পোজারের ওপরে ভাসে। একই চিপে/বাটনে আবার চাপলে বন্ধ।
     */
    private fun togglePicker(kind: String) {
        val box = pickBox ?: return
        if (pickKind == kind) { closePicker(); return }
        val curMode = Prefs.currentModeKey(ctx)
        val (title, items, selected) = when (kind) {
            "model" -> Triple("Model", ComposerOptions.systems, Prefs.selectedSystem(ctx))
            "mode" -> Triple("Mode", ComposerOptions.modes(curMode), curMode)
            else -> Triple("Attach", ComposerOptions.attachItems, null)
        }
        box.removeAllViews()
        pickKind = kind
        val sheet = ModePickerSheet.buildSheet(ctx, title, items, selected, floating = true) { key -> onPicked(kind, key, selected) }
        val sc = MaxHeightScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            maxHeightPx = ctx.resources.displayMetrics.heightPixels * 45 / 100
            addView(sheet)
        }
        box.addView(sc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        box.visibility = View.VISIBLE
        scrollToEnd()
    }

    private fun onPicked(kind: String, key: String, previous: String?) {
        closePicker()
        if (kind == "attach") { startPick(key); return }
        if (key == previous) return
        if (kind == "model") {
            Prefs.setSelectedSystem(ctx, key)
            refreshChips()
            Toast.makeText(ctx, "Model: " + ComposerOptions.systemLabel(key) + " — used from the next task", Toast.LENGTH_SHORT).show()
        } else {
            // মোড বদলাতে মেইন অ্যাপ লাগে — বাছাই রেখে অ্যাপ সামনে আনি, MainActivity.onResume মোড বদলায়
            Prefs.setPendingMode(ctx, key)
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            hide()
        }
    }

    /**
     * "+" → ছবি / ভিডিও / ফাইল। পিকারের ওপর চ্যাট-উইন্ডো ঢাকা না দিতে — বাছার সময় উইন্ডো সরিয়ে, ফিরলে আবার বসাই।
     * ভিডিও = ফোনেই কয়েকটা ফ্রেম তুলে একটা কনট্যাক্ট-শিট ছবি (+ AI-র জন্য নোট); ফাইল = PDF/Word/Excel/PowerPoint/টেক্সট/কোড।
     */
    private fun startPick(kind: String) {
        val v = root ?: return
        val wm = windowManager() ?: return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        runCatching { wm.removeView(v) }
        attached = false
        onPicking(true)
        pickMedia(kind) { picked ->
            Handler(Looper.getMainLooper()).post {
                onPicking(false)
                if (root !== v) return@post          // এর মধ্যে বন্ধ হয়ে গেছে
                val w = windowManager()
                if (w != null && !attached) attached = attach(w, v)
                if (!attached) { root = null; return@post }
                if (picked != null) setPending(picked)
                requestKeyboard()
            }
        }
    }

    private fun setPending(p: PendingMediaPick.Picked?) {
        pendingImage = p?.imageBase64
        pendingContext = p?.context
        pendingLabel = p?.label
        val row = previewRow ?: return
        if (p == null) {
            row.visibility = View.GONE
            previewLabel?.visibility = View.GONE
            return
        }
        val img = p.imageBase64
        if (img != null) {
            try {
                val bytes = Base64.decode(img, Base64.NO_WRAP)
                previewImage?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (e: Exception) { pendingImage = null; previewImage?.setImageDrawable(null) }
        } else {
            // টেক্সট-ফাইল: ছবি নেই — ধূসর ঘর + পাশে নাম (✕ বাটন থাকে)
            previewImage?.setImageDrawable(null)
        }
        previewLabel?.apply {
            if (p.context != null) { text = p.label; visibility = View.VISIBLE } else visibility = View.GONE
        }
        row.visibility = View.VISIBLE
    }

    private fun submit() {
        val et = input ?: return
        val t = et.text?.toString()?.trim().orEmpty()
        val img = pendingImage
        val note = pendingContext
        val label = pendingLabel
        if (t.isEmpty() && img == null && note == null) return
        if (busy) {
            Toast.makeText(ctx, "Please wait. The reply is on its way…", Toast.LENGTH_SHORT).show()
            return
        }
        et.setText("")
        setPending(null)
        viewing = null
        if (autoView) { autoView = false; autoLog()?.detach(); autoChat = null; refreshAutoChip(); renderAll() }   // v156
        // কার্ডে: ভিডিও/ফাইলের নাম + ইউজারের লেখা। AI-কে: নোট (ভিডিও/ফাইলের বিবরণ বা টেক্সট) + ইউজারের লেখা
        val shown = if (note != null && !label.isNullOrBlank()) (label + if (t.isNotEmpty()) "\n$t" else "") else t
        val toAi = when {
            note == null -> t
            t.isNotEmpty() -> note + "\n\n" + t
            else -> note + "\n\n(The user sent this with no text. Look at it and work out what is needed.)"
        }
        addMsg(Msg(true, shown, img))
        setBusy(true)
        // কিবোর্ড নামিয়ে ফোকাস অ্যাপকে ফিরিয়ে দিই — গাইড এখন (👁 চালু থাকলে স্ক্রিন দেখে) উত্তর দেবে
        root?.let { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(it.windowToken, 0) }
        applyWindow(false, false)
        if (!onSend(toAi, img, Prefs.guideChatScreenOn(ctx))) {
            postAi("No task is running right now. Start a task from the app first.")
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        handler.removeCallbacks(busyTimeout)
        if (b) handler.postDelayed(busyTimeout, BUSY_TIMEOUT_MS)
        if (root != null && !autoView) { removeTyping(); if (b) addTyping(); scrollToEnd() }
    }

    private fun addMsg(m: Msg) {
        msgs.add(m)
        while (msgs.size > MAX_MSGS) msgs.removeAt(0)
        if (root != null && !autoView) renderAll()   // v156: the Auto prompt view is not rebuilt for every chat message
    }

    /** v156: switch the list between the normal guide chat and the Auto prompt view. */
    private fun toggleAuto() {
        autoView = !autoView
        if (!autoView) { autoLog()?.detach(); autoChat = null }
        refreshAutoChip()
        renderAll()
    }

    private fun refreshAutoChip() {
        autoChip?.text = if (autoView) "💬 Chat" else "⚙ Auto prompt"
    }

    /** v156: the automatic prompts, replayed from the background record (the guide records them whether or not this window is open). */
    private fun renderAuto() {
        val l = list ?: return
        val sc = scroll ?: return
        removeTyping()
        l.removeAllViews()
        ticker.clear()
        autoLog()?.detach()
        val chat = AutoPromptChat(ctx, l, sc, inlineDetails = true)
        autoChat = chat
        val log = autoLog()
        log?.attach(chat)          // attach() clears the list first, so anything else is added after it
        if (log == null || log.isEmpty) {
            l.addView(TextView(ctx).apply {
                text = "No automatic prompts yet. Each time the guide asks the AI about the screen, it shows up here."
                setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
                textSize = 13f
                setPadding(32, 32, 32, 32)
            })
        }
        scrollToEnd()
    }

    private fun renderAll() {
        if (autoView) { renderAuto(); return }
        val l = list ?: return
        removeTyping()
        l.removeAllViews()
        ticker.clear()
        val shown = viewing ?: msgs
        shown.forEachIndexed { i, m ->
            l.addView(
                if (m.fromUser) GlassChat.mainUserCard(ctx, m.text, m.image, onRetry = { retry(m.text) })
                else GlassChat.mainAiCard(ctx, m.text)   // গাইডের ধাপ/প্রশ্ন চলমান কাজের অংশ — ↻ রিপিট নেই, কপি ও শোনা আছে
            )
        }
        if (busy && viewing == null) addTyping()
        scrollToEnd()
    }

    /** ↻ রিপিট — প্রশ্নটা আবার পাঠায় (পুরনো কথোপকথন দেখার সময় চলতি চ্যাটে ফিরে)। */
    private fun retry(text: String) {
        if (text.isBlank()) return
        viewing = null
        input?.setText(text)
        submit()
    }

    /** সাইডবার থেকে পুরনো কথোপকথন দেখা। */
    private fun viewSession(sess: HistoryStore.Session) {
        viewing = sess.messages.filter { it.text.isNotBlank() }.map { Msg(it.role == "user", it.text, null) }
        renderAll()
    }

    /** সাইডবারের "নতুন চ্যাট" — চলতি কথোপকথন মুছে ফাঁকা শুরু। */
    private fun newChat() {
        viewing = null
        msgs.clear()
        renderAll()
    }

    private fun scrollToEnd() { scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) } }

    private fun addTyping() {
        val l = list ?: return
        val (row, tv) = GlassChat.mainTypingCard(ctx)
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
