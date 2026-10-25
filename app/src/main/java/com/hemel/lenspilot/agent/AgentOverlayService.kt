package com.hemel.lenspilot.agent

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.ImagePickTrampolineActivity
import com.hemel.lenspilot.PendingImagePick
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService
import com.hemel.lenspilot.chat.GlassChat
import com.hemel.lenspilot.chat.GuideCardsView
import com.hemel.lenspilot.chat.MaxHeightScrollView
import com.hemel.lenspilot.learning.BoardMarkdown
import com.hemel.lenspilot.learning.LearningModeActivity
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.vision.ScreenCaptureService
import com.hemel.lenspilot.vision.ScreenshotProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v52: "Get an AI agent" — যেকোনো অ্যাপের ওপরে ভাসা AI এজেন্ট (Accessibility ছাড়াও চলে; শুধু Overlay পারমিশন)।
 *
 *  • বাবল = Messenger-এর মতো টেনে সরানো যায়, কিনারায় সেঁটে যায়; টেনে নিচের ✕-এ ফেললে বন্ধ। ট্যাপে গ্লাস কার্ড।
 *  • গ্লাস কার্ড = চ্যাট (ছবি আপলোড, ভয়েস কমান্ড), মোড (Chat / Discussion / Multi-agent / Learn), মডেল (system) বাছাই,
 *    আর "Screen" সুইচ — চালু থাকলে প্রতিটা প্রশ্নের আগে AI স্ক্রিন দেখে (Accessibility ট্রি, না থাকলে ভিশন/স্ক্রিন-ক্যাপচার)।
 *  • কার্ড বন্ধ (✕/Back) করলেই বাবল হয়ে যায়। কুইক-সেটিংস টাইল চাপলে কার্ড নিজে খোলে (QuickScanTrampolineActivity)।
 *  • উত্তর সার্ভারের /api/workflow/plan থেকে (অ্যাপের চ্যাটের মতোই), BoardMarkdown দিয়ে সাজানো।
 */
class AgentOverlayService : Service() {

    companion object {
        const val ACTION_BUBBLE = "com.hemel.lenspilot.agent.BUBBLE"
        const val ACTION_PANEL = "com.hemel.lenspilot.agent.PANEL"
        const val ACTION_STOP = "com.hemel.lenspilot.agent.STOP"
        private const val CHANNEL_ID = "agent_overlay"
        private const val NOTIFICATION_ID = 4400
        private const val PREFS = "agent_overlay"

        @Volatile var instance: AgentOverlayService? = null
            private set

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context, showPanel: Boolean) {
            val i = Intent(ctx, AgentOverlayService::class.java).setAction(if (showPanel) ACTION_PANEL else ACTION_BUBBLE)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun overlayPermissionIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private enum class Mode(val label: String) { CHAT("Chat"), DISCUSSION("Discussion"), MULTI("Multi-agent"), LEARN("Learn") }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null

    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var deleteTarget: TextView? = null

    private var panel: FrameLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var list: LinearLayout? = null
    private var scroll: MaxHeightScrollView? = null
    private var input: EditText? = null
    private var optionsRow: LinearLayout? = null
    private var modelRow: LinearLayout? = null
    private var previewRow: LinearLayout? = null
    private var previewImg: ImageView? = null
    private var micBtn: ImageButton? = null
    private var emptyHint: TextView? = null
    private val ticker = GlassChat.TimeTicker()

    private var mode = Mode.CHAT
    private var screenAware = false
    private var guideOn = false          // v53: Guide সুইচ — চালু থাকলে প্রতিটা প্রশ্ন গাইডলাইন মোডের মতো চলে (হাইলাইট + intent/deep link), Run বাটন ছাড়াই
    private var pendingImage: String? = null
    private var busy = false
    private var job: Job? = null
    private val history = ArrayList<Pair<String, String>>()   // (role, text)

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var imeH = 0          // v55: কিবোর্ডের উচ্চতা (ইনসেট থেকে)
    private var curPad = 0        // v55: কিবোর্ডের নিচে না পড়তে প্যানেলের নিচে দেওয়া প্যাডিং

    private fun dp(v: Int) = GlassChat.dp(this, v)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = runCatching { Mode.valueOf(sp.getString("mode", "CHAT") ?: "CHAT") }.getOrDefault(Mode.CHAT)
        screenAware = sp.getBoolean("screen", false)
        guideOn = sp.getBoolean("guide", false)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDraw(this)) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PANEL -> showPanel()
            else -> if (panel == null) showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LenspilotAccessibilityService.guideEndedListener = null
        job?.cancel()
        scope.cancel()
        stopVoice()
        removePanelView()
        removeBubbleView()
        removeDeleteTarget()
        instance = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Lenspilot AI agent", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AgentOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenspilot AI agent")
            .setContentText("Tap to open the card")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi(ACTION_PANEL, 1))
            .addAction(Notification.Action.Builder(null, "Close agent", pi(ACTION_STOP, 2)).build())
            .setOngoing(true)
            .build()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        if (bubble != null) return
        val w = wm ?: return
        removePanelView()
        val size = dp(58)
        val iv = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(6).toFloat()
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW() - size - dp(2)
            y = screenH() / 3
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        iv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; showDeleteTarget() }
                    if (dragging) {
                        lp.x = (startX + dx).toInt().coerceIn(0, screenW() - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, screenH() - size)
                        runCatching { w.updateViewLayout(iv, lp) }
                        highlightDelete(overDelete(lp, size))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        if (e.action == MotionEvent.ACTION_UP) showPanel()
                    } else {
                        val over = overDelete(lp, size)
                        removeDeleteTarget()
                        if (over) stopSelf() else snapToEdge(iv, lp, size)
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { w.addView(iv, lp) }.onFailure { stopSelf(); return }
        bubble = iv; bubbleLp = lp
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams, size: Int) {
        val target = if (lp.x + size / 2 < screenW() / 2) dp(2) else screenW() - size - dp(2)
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener {
                lp.x = it.animatedValue as Int
                runCatching { wm?.updateViewLayout(v, lp) }
            }
            start()
        }
    }

    private fun showDeleteTarget() {
        if (deleteTarget != null) return
        val w = wm ?: return
        val size = dp(66)
        val tv = TextView(this).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 24f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1F2937.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        runCatching { w.addView(tv, lp) }
        deleteTarget = tv
    }

    private fun overDelete(lp: WindowManager.LayoutParams, size: Int): Boolean {
        val cx = lp.x + size / 2f; val cy = lp.y + size / 2f
        val tx = screenW() / 2f; val ty = screenH() - dp(48) - dp(33).toFloat()
        return hypot(cx - tx, cy - ty) < dp(70)
    }

    private fun highlightDelete(on: Boolean) {
        deleteTarget?.apply {
            val s = if (on) 1.25f else 1f
            scaleX = s; scaleY = s
            (background as? GradientDrawable)?.setColor(if (on) 0xFFDC2626.toInt() else 0xCC1F2937.toInt())
        }
    }

    private fun removeDeleteTarget() { deleteTarget?.let { runCatching { wm?.removeView(it) } }; deleteTarget = null }
    private fun removeBubbleView() { bubble?.let { runCatching { wm?.removeView(it) } }; bubble = null; bubbleLp = null }

    // ------------------------------------------------------------------ glass card (panel)

    fun showPanel() {
        if (panel != null) return
        val w = wm ?: return
        removeBubbleView()
        val root = buildPanel()
        // v56: পূর্ণ-স্ক্রিন চ্যাট — উইন্ডো MATCH_PARENT × MATCH_PARENT, পেছনে গাঢ় নেভি গ্রেডিয়েন্ট (আগে শুধু কন্টেন্টের সমান
        // উঁচু, স্বচ্ছ, নিচে বসা কার্ড ছিল)। ADJUST_RESIZE → কিবোর্ড উঠলে বার্তার তালিকা ছোট হয়, লেখার বক্স কিবোর্ডের ঠিক ওপরে থাকে।
        // স্ক্রিন পড়ার সময় readScreen() পুরো প্যানেল alpha=0 + NOT_TOUCHABLE করে — তাই নিচের অ্যাপের স্ক্রিন ঠিকই পড়া যায়।
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        runCatching { w.addView(root, lp) }.onFailure { showBubble(); return }
        panel = root; panelLp = lp
        ticker.start()
        root.setOnApplyWindowInsetsListener { v, insets ->
            imeH = readIme(insets)
            v.post { fixIme() }
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { fixIme() }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun readIme(ins: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ins.isVisible(WindowInsets.Type.ime())) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            val b = ins.systemWindowInsetBottom
            if (b > ins.stableInsetBottom) b else 0
        }

    private fun fullScreenH(): Int {
        val m = wm ?: return screenH()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) m.currentWindowMetrics.bounds.height()
        else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") m.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    private fun setPad(px: Int) {
        val v = panel ?: return
        if (px == curPad) return
        curPad = px
        v.setPadding(0, 0, 0, px)
    }

    /**
     * v55: ADJUST_RESIZE কিছু ফোনে ওভারলে-উইন্ডোকে ছোট করে না — তখন কিবোর্ড বক্সের অর্ধেক/পুরোটা ঢেকে দিত। এখানে সরাসরি মেপে দেখি
     * উইন্ডোর তলা কিবোর্ডের মাথার নিচে নেমেছে কিনা; নামলে ততটুকু প্যাডিং দিই। উইন্ডো নিজেই ছোট হলে প্যাডিং ০ (দুবার সরে না)।
     * কিবোর্ড খোলা থাকলে বার্তার তালিকা ছোট রাখি, যাতে লেখার বক্স আর বাটন সবসময় দেখা যায়।
     */
    private fun fixIme() {
        val v = panel ?: return
        // v56: বার্তার তালিকা এখন বাকি পুরো জায়গা নেয় (weight=1) — কিবোর্ড উঠলে নিজে থেকেই ছোট হয়, আলাদা সর্বোচ্চ উচ্চতা লাগে না
        if (imeH <= 0) { setPad(0); return }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val windowBottom = loc[1] + v.height
        val kbTop = fullScreenH() - imeH
        setPad((windowBottom - kbTop).coerceAtLeast(0))
    }

    /** কার্ড বন্ধ → বাবল। */
    private fun collapseToBubble() {
        stopVoice()
        hideKeyboard()
        removePanelView()
        showBubble()
    }

    private fun removePanelView() {
        ticker.stop()
        panel?.let { runCatching { wm?.removeView(it) } }
        panel = null; panelLp = null; list = null; scroll = null; input = null
        optionsRow = null; modelRow = null; previewRow = null; previewImg = null; micBtn = null; emptyHint = null
        imeH = 0; curPad = 0
    }

    private fun hideKeyboard() {
        input?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    /** v55: আনসিলেক্টেড চিপ = গাঢ় কাচ + হালকা বর্ডার (স্বচ্ছ পেছনেও সাদা/কালো যেকোনো স্ক্রিনে স্পষ্ট); সিলেক্টেড = সাদা। */
    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (selected) { setColor(0xF2FFFFFF.toInt()); setStroke(dp(1), 0xFFFFFFFF.toInt()) }
        else { setColor(0xB3111827.toInt()); setStroke(dp(1), 0x59FFFFFF) }
    }

    private fun chip(label: String, selected: Boolean, iconRes: Int? = null, onClick: () -> Unit): View {
        val fg = if (selected) 0xFF111827.toInt() else Color.WHITE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chipBg(selected)
            setPadding(dp(12), dp(6), dp(12), dp(7))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) }
        }
        if (iconRes != null) {
            row.addView(ImageView(this).apply { setImageResource(iconRes); setColorFilter(fg) },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
        })
        return row
    }

    private fun roundBtn(iconRes: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(iconRes)
        background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_circle)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    private fun buildPanel(): FrameLayout {
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { collapseToBubble(); return true }
                return super.dispatchKeyEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean = true
        }
        // v55: পেছন একদম স্বচ্ছ — কোনো গাঢ় রঙ/ব্লার নেই। কার্ড, হেডার, চিপ, লেখার বক্স নিজেরাই কাচের মতো ভাসে।
        root.setBackgroundColor(Color.TRANSPARENT)   // v57: পূর্ণ-স্ক্রিন কিন্তু একদম স্বচ্ছ — নিচের স্ক্রিন/হাইলাইট কাচের ভেতর দিয়ে দেখা যায়
        root.isFocusableInTouchMode = true

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- header (কাচের পিল)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_input)
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round); scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        header.addView(TextView(this).apply { text = "AI Agent"; GlassChat.whiteBold(this, 17f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(roundBtn(R.drawable.ic_close_white, "Minimize to bubble") { collapseToBubble() },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(10), dp(8), dp(10), dp(2)) })

        // ---- options (screen / guide / mode / model)
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(8), dp(6)) }
        hs.addView(opts)
        col.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        optionsRow = opts

        val models = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(dp(10), 0, dp(8), dp(6))
        }
        col.addView(models)
        modelRow = models

        // ---- messages (কন্টেন্টের মাপে; বেশি হলে স্ক্রল — fixIme() সর্বোচ্চ উচ্চতা ঠিক করে)
        val sv = MaxHeightScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        sv.addView(ll)
        // v56: বাকি পুরো উচ্চতা নেয় (weight=1) — হেডার/অপশন ওপরে, লেখার বক্স একদম নিচে
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list = ll; scroll = sv
        val hint = TextView(this).apply {
            GlassChat.whiteBold(this, 14f)
            setBackgroundResource(R.drawable.bg_live_glass_card)
            setPadding(dp(18), dp(14), dp(18), dp(15))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        ll.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        emptyHint = hint; updateHint()
        history.forEach { (role, text) -> addCard(role == "user", text) }

        // ---- image preview (কাচের পিল)
        val pv = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_input)
            setPadding(dp(10), dp(6), dp(8), dp(6))
        }
        val pi = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        pv.addView(pi, LinearLayout.LayoutParams(dp(46), dp(46)))
        pv.addView(TextView(this).apply { text = "Image attached"; GlassChat.whiteBold(this, 13f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        pv.addView(roundBtn(R.drawable.ic_close_white, "Remove image") { pendingImage = null; pv.visibility = View.GONE },
            LinearLayout.LayoutParams(dp(32), dp(32)))
        col.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(10), dp(2), dp(10), dp(4)) })
        previewRow = pv; previewImg = pi

        // ---- v55: লেখার বক্স = অ্যাপের মেইন চ্যাট-বক্সের হুবহু ডিজাইন (ওপরে লেখার জায়গা, নিচে বাটন-সারি)।
        // শুধু মোড-বাছাইয়ের আইকনগুলো নিচে নেই — মোড/মডেল/Screen/Guide ওপরের চিপ-সারিতে।
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_input)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val inputHint = getString(R.string.chat_input_hint)
        val et = EditText(this).apply {
            this.hint = inputHint   // বাইরের local `hint` (TextView) আড়াল না করতে this.
            setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 6
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        composer.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(6), dp(4), dp(6), 0) })
        input = et

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        fun mainBtn(iconRes: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(iconRes)
            background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_circle)
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        btnRow.addView(mainBtn(R.drawable.ic_add, "Attach image") { pickImage() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val mic = mainBtn(R.drawable.ic_mic, "Voice command") { toggleVoice() }
        btnRow.addView(mic, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(10) })
        micBtn = mic
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = ContextCompat.getDrawable(this@AgentOverlayService, R.drawable.bg_live_glass_circle_active)
            setColorFilter(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Send"
            setOnClickListener { send() }
        }
        btnRow.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        composer.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        col.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })

        refreshOptions()
        return root
    }

    private fun systemLabel(s: String) = when (s) {
        "super_lite" -> "Super Lite"; "ela_1st" -> "ELA 1st"; "ela_4n" -> "ELA 4N"; else -> "Super 1.2"
    }

    private fun refreshOptions() {
        val row = optionsRow ?: return
        row.removeAllViews()
        // v55: "Screen" (স্ক্রিন দেখবে কি দেখবে না) সুইচ এখন সবার প্রথমে — আগে সবার শেষে ছিল। Guide চালু থাকলেও এটা থাকে।
        row.addView(chip(if (screenAware) "Screen: On" else "Screen: Off", screenAware, R.drawable.ic_agent_eye) {
            screenAware = !screenAware
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("screen", screenAware).apply()
            if (screenAware && !LenspilotAccessibilityService.isEnabled(this) && ScreenCaptureService.instance == null) {
                Toast.makeText(this, "Turn on Lenspilot Accessibility so I can read the screen", Toast.LENGTH_LONG).show()
            }
            updateHint()
            refreshOptions()
        })
        // Guide সুইচ — চালু হলে নিচের মোড/মডেল বাছাই লাগে না, সব প্রশ্ন গাইডলাইন মোডে যায়
        row.addView(chip(if (guideOn) "Guide: On" else "Guide: Off", guideOn, R.drawable.ic_agent_guide) {
            guideOn = !guideOn
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("guide", guideOn).apply()
            if (guideOn && !LenspilotAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, "Guide mode needs Lenspilot Accessibility — turn it on in Settings", Toast.LENGTH_LONG).show()
            }
            updateHint()
            refreshOptions()
        })
        // v56: মডেল বাছাই সব মোডে (Guide চালু থাকলেও) থাকে
        if (!guideOn) {
            Mode.values().forEach { m ->
                row.addView(chip(m.label, mode == m) {
                    mode = m
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mode", m.name).apply()
                    refreshOptions()
                })
            }
        }
        val cur = Prefs.selectedSystem(this)
        row.addView(chip("Model: ${systemLabel(cur)} ▾", false) {
            modelRow?.visibility = if (modelRow?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        if (!guideOn && LenspilotAccessibilityService.instance != null) {
            row.addView(chip("Scan", false, R.drawable.ic_agent_scan) {
                collapseToBubble()
                handler.postDelayed({ LenspilotAccessibilityService.instance?.quickScanCurrentScreen() }, 450)
            })
        }
        // মডেল সারি
        val mr = modelRow ?: return
        mr.removeAllViews()
        listOf("super_1_2", "super_lite", "ela_1st", "ela_4n").forEach { sys ->
            mr.addView(chip(systemLabel(sys), cur == sys) {
                Prefs.setSelectedSystem(this, sys)
                mr.visibility = View.GONE
                refreshOptions()
            })
        }
    }

    // ------------------------------------------------------------------ messages

    private fun addCard(fromUser: Boolean, text: String, image: String? = null): View? {
        val ll = list ?: return null
        emptyHint?.let { ll.removeView(it); emptyHint = null }
        val v = if (fromUser) GlassChat.userCard(this, text, image)
        else GlassChat.aiCard(this, renderAi(text), System.currentTimeMillis(), ticker)
        ll.addView(v)
        scrollDown()
        return v
    }

    private fun updateHint() {
        emptyHint?.text = when {
            guideOn -> "Guide mode is on.\nAsk anything — I’ll show the answer right on your screen with highlights, open the exact page for you, and search the web when I need fresh facts. No Run button needed."
            screenAware -> "Screen is on.\nAsk anything — I’ll read what’s in front of you first, then answer."
            else -> "Ask anything.\nTurn on “Screen” and I’ll read what’s in front of you before answering."
        }
    }

    /** Guide মোডে পাঠানো: Accessibility-র গাইড ইঞ্জিনই চলে (Run চাপার দরকার নেই, হোমেও যায় না — এখন যে স্ক্রিনে আছো সেখান থেকেই)। */
    private fun sendToGuide(text: String, img: String?) {
        val svc = LenspilotAccessibilityService.instance
        if (svc == null) {
            addCard(false, "Guide mode needs **Lenspilot Accessibility**. Turn it on in Settings → Accessibility, then ask again.")
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val goal = text.ifBlank { "(sent an image with no text. Look at the image and work out what is needed)" }
        LenspilotAccessibilityService.guideEndedListener = { handler.post { onGuideEnded() } }
        stopVoice(); hideKeyboard()
        removePanelView(); removeBubbleView()      // গাইডের নিজের কন্ট্রোল-বার/ট্রিগার-বাবল আসবে; দুটো বাবল একসাথে নয়
        if (svc.isWorkflowActive) {
            svc.triggerWorkflowStep(extraContext = goal, extraImageBase64 = img)   // চলমান গাইডে নতুন প্রশ্ন
            return
        }
        handler.postDelayed({
            svc.startWorkflow(
                goal = goal, system = Prefs.selectedSystem(this), extraUserInfo = emptyList(),
                onUpdate = { _, _ -> },
                onError = { msg -> handler.post { Toast.makeText(this, msg.take(120), Toast.LENGTH_LONG).show() } },
                extraImageBase64 = img
            )
        }, 350)
    }

    /** গাইড শেষ/থামলে এজেন্ট-বাবল ফিরে আসে। */
    private fun onGuideEnded() {
        LenspilotAccessibilityService.guideEndedListener = null
        if (panel == null && bubble == null && instance != null) showBubble()
    }

    private fun renderAi(text: String): CharSequence {
        val d = resources.displayMetrics
        val tv = TextView(this).apply { textSize = 15f }
        return BoardMarkdown.render(text, tv.paint, d.widthPixels - dp(110), d.density, light = false)
    }

    private fun scrollDown() { scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) } }

    // ------------------------------------------------------------------ image & voice

    private fun pickImage() {
        val p = panel
        p?.visibility = View.INVISIBLE
        PendingImagePick.awaitNext { b64 ->
            handler.post {
                panel?.visibility = View.VISIBLE
                if (b64 != null) {
                    pendingImage = b64
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        previewImg?.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                    previewRow?.visibility = View.VISIBLE
                }
            }
        }
        startActivity(Intent(this, ImagePickTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleVoice() {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow the microphone for Lenspilot first (open the app once)", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition isn’t available on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onResults(results: Bundle) {
                setListening(false)
                val t = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) input?.let { it.setText((it.text.toString() + " " + t).trim()); it.setSelection(it.text.length) }
            }
            override fun onError(error: Int) { setListening(false) }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        })
    }

    private fun setListening(on: Boolean) {
        listening = on
        micBtn?.background = ContextCompat.getDrawable(this, if (on) R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon)
        if (on) micBtn?.setColorFilter(Color.WHITE) else micBtn?.clearColorFilter()
    }

    private fun stopVoice() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    // ------------------------------------------------------------------ send

    private fun send() {
        if (busy) return
        val et = input ?: return
        val text = et.text.toString().trim()
        val img = pendingImage
        if (text.isEmpty() && img == null) return
        et.setText("")
        pendingImage = null
        previewRow?.visibility = View.GONE
        hideKeyboard()
        val shown = text.ifBlank { "(image)" }
        addCard(true, text, img)
        history.add("user" to shown)

        if (guideOn) { sendToGuide(text, img); return }

        if (mode == Mode.LEARN) {
            val imagePath = img?.let { b64 ->
                runCatching {
                    val f = java.io.File(cacheDir, "learning_pending_image.jpg")
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); f.absolutePath
                }.getOrNull()
            }
            startActivity(Intent(this, LearningModeActivity::class.java).apply {
                putExtra(LearningModeActivity.EXTRA_TOPIC, text.ifBlank { LearningModeActivity.DEFAULT_IMAGE_PROMPT })
                if (imagePath != null) putExtra(LearningModeActivity.EXTRA_IMAGE_PATH, imagePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            collapseToBubble()
            return
        }

        busy = true
        job = scope.launch {
            val typing = GlassChat.typingCard(this@AgentOverlayService)
            list?.addView(typing.first); scrollDown()
            var message = text.ifBlank { "Look at the attached image and tell me about it." }
            var image = img

            // ---- স্ক্রিন দেখা (Screen সুইচ চালু থাকলে)
            if (screenAware) {
                typing.second.text = "Looking at your screen…"
                val seen = readScreen(img == null)
                if (seen.text.isNotBlank()) message = message + "\n\n[Current screen — read from the phone just now]\n" + seen.text
                if (image == null && seen.image != null) image = seen.image
                if (seen.text.isBlank() && seen.image == null) {
                    list?.addView(GlassChat.aiCard(this@AgentOverlayService,
                        "I couldn’t read the screen — turn on Lenspilot Accessibility (or screen capture), then try again.", System.currentTimeMillis(), ticker))
                }
            }
            typing.second.text = "Typing"

            val body = JSONObject().apply {
                put("message", message)
                put("history", JSONArray().also { arr ->
                    history.dropLast(1).takeLast(6).forEach { (r, t) ->
                        arr.put(JSONObject().apply { put("role", if (r == "user") "user" else "ai"); put("text", t) })
                    }
                })
                if (image != null) put("image_base64", image)
                when (mode) {
                    Mode.DISCUSSION -> { put("discussion_mode", true); put("system", Prefs.selectedSystem(this@AgentOverlayService)) }
                    Mode.MULTI -> {
                        put("system", "multi_agent")
                        put("agent_count", Prefs.multiAgentCount(this@AgentOverlayService))
                    }
                    else -> put("system", Prefs.selectedSystem(this@AgentOverlayService))
                }
                Prefs.userInfoJsonArrayOrNull(this@AgentOverlayService)?.let { put("user_info", it) }
            }.toString()

            val reply = StringBuilder()
            var liveBody: TextView? = null
            var liveRow: View? = null
            var finalText: String? = null
            var finalGuide: JSONObject? = null     // v55: আলোচনা মোডের স্ক্রিন-গাইড কার্ড (steps / choice / info)
            var errorText: String? = null
            val baseUrl = getString(R.string.space_base_url)

            fun showLive(t: String) {
                if (liveRow == null) {
                    list?.removeView(typing.first)
                    liveRow = GlassChat.aiCard(this@AgentOverlayService, "…", System.currentTimeMillis(), ticker)
                    liveBody = liveRow?.tag as? TextView
                    list?.addView(liveRow)
                }
                liveBody?.text = renderAi(t)
                scrollDown()
            }

            val result = ApiClient.streamAuthed(this@AgentOverlayService, baseUrl, "/api/workflow/plan", body) { evt ->
                when (evt.optString("type")) {
                    "status" -> {
                        val st = evt.optString("text", "")
                        if (st.isNotBlank() && liveRow == null) handler.post { typing.second.text = st.take(60) }
                    }
                    "reply_delta" -> {
                        reply.append(evt.optString("text", ""))
                        val cur = reply.toString()
                        handler.post { showLive(cur) }
                    }
                    "done" -> {
                        val ro = evt.optJSONObject("result") ?: JSONObject()
                        var t = ro.optString("reply_text", "")
                        if (ro.optBoolean("is_workflow", false) && ro.optJSONObject("workflow") != null) {
                            t = (t + "\n\n> Note: this is a phone task — open Lenspilot to run it step by step.").trim()
                        }
                        val srcArr = ro.optJSONArray("sources")
                        if (srcArr != null && srcArr.length() > 0 && t.isNotBlank()) {
                            val names = (0 until srcArr.length()).map { srcArr.optString(it) }.filter { it.isNotBlank() }
                            if (names.isNotEmpty()) t = t + "\n\n> Sources: " + names.joinToString(" · ")
                        }
                        finalText = t
                        if (ro.optBoolean("discussion", false)) finalGuide = ro.optJSONObject("guide")
                    }
                    "error" -> errorText = evt.optString("error", "error")
                }
            }
            withContext(Dispatchers.Main) {
                val ft = finalText?.takeIf { it.isNotBlank() } ?: reply.toString().takeIf { it.isNotBlank() }
                val gd = finalGuide?.takeIf { (it.optJSONArray("cards")?.length() ?: 0) > 0 }
                when {
                    ft != null || gd != null -> {
                        if (ft != null) {
                            if (liveRow == null) { list?.removeView(typing.first); showLive(ft) } else liveBody?.text = renderAi(ft)
                        } else list?.removeView(typing.first)
                        var memo = ft.orEmpty()
                        if (gd != null) {
                            addGuideCards(gd, image)
                            memo = (memo + " " + GuideCardsView.summarize(gd.toString())).trim()
                        }
                        history.add("ai" to memo)
                    }
                    else -> {
                        list?.removeView(typing.first)
                        val msg = errorText ?: result.exceptionOrNull()?.message ?: "No answer came back"
                        addCard(false, "Error: ${msg.take(180)}")
                    }
                }
                busy = false
                scrollDown()
            }
        }
    }

    // ------------------------------------------------------------------ v55: আলোচনা মোডের গাইড-কার্ড

    /** সার্ভারের result.guide কে অ্যাপের চ্যাটের মতোই কার্ড হিসেবে দেখায় (ধাপ, তথ্য-কার্ড, প্রশ্ন+অপশন)। অপশন চাপলে সেটা পরের মেসেজ হয়ে যায়। */
    private fun addGuideCards(guide: JSONObject, userImage: String?) {
        val ll = list ?: return
        runCatching {
            if (guideUsesUserImage(guide)) {
                val thumb = userImage?.let { makeThumb(it) }
                if (thumb != null) {
                    val images = guide.optJSONObject("images") ?: JSONObject().also { guide.put("images", it) }
                    images.put("user", JSONObject().apply {
                        put("b64", thumb.first); put("w", thumb.second.first); put("h", thumb.second.second)
                    })
                } else {
                    stripUserMarks(guide)   // ছবি নেই — ভুল জায়গায় মার্ক না দেখিয়ে শুধু লেখা
                }
            }
        }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun draw() {
            GuideCardsView.render(holder, guide) { cardIndex, option ->
                val card = guide.optJSONArray("cards")?.optJSONObject(cardIndex)
                if (card != null && !card.has("selected") && !busy) {
                    card.put("selected", option)
                    draw()
                    input?.setText(option)
                    send()
                }
            }
        }
        draw()
        ll.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        scrollDown()
    }

    private fun guideUsesUserImage(guide: JSONObject): Boolean {
        val cards = guide.optJSONArray("cards") ?: return false
        for (ci in 0 until cards.length()) {
            val steps = cards.optJSONObject(ci)?.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                if (steps.optJSONObject(i)?.optString("image_id") == "user") return true
            }
        }
        return false
    }

    private fun stripUserMarks(guide: JSONObject) {
        val cards = guide.optJSONArray("cards") ?: return
        for (ci in 0 until cards.length()) {
            val steps = cards.optJSONObject(ci)?.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val st = steps.optJSONObject(i) ?: continue
                if (st.optString("image_id") == "user") { st.put("image_id", JSONObject.NULL); st.put("mark", JSONObject.NULL) }
            }
        }
    }

    /** স্ক্রিনশট → ছোট JPEG base64 (সর্বোচ্চ ~৬৪০px) + (প্রস্থ, উচ্চতা)। মার্কের box স্বাভাবিকীকৃত (০–১০০০), তাই ছোট করলেও জায়গা ঠিক থাকে। */
    private fun makeThumb(b64: String): Pair<String, Pair<Int, Int>>? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f)
            android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        else bmp
        val bos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, bos)
        Pair(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), Pair(out.width, out.height))
    }.getOrNull()

    private class Seen(val text: String, val image: String?)

    /** স্ক্রিন পড়া: প্যানেল সরিয়ে/নন-ফোকাস করে নিচের অ্যাপটাকে সক্রিয় হতে দিই, তারপর Accessibility ট্রি; না থাকলে ভিশন। */
    private suspend fun readScreen(wantImage: Boolean): Seen {
        val p = panel
        val lp = panelLp
        val w = wm
        p?.alpha = 0f
        if (p != null && lp != null && w != null) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { w.updateViewLayout(p, lp) }
        }
        bubble?.alpha = 0f
        delay(450)
        var text = ""
        var image: String? = null
        try {
            val svc = LenspilotAccessibilityService.instance
            if (svc != null) {
                val els = withContext(Dispatchers.Default) { runCatching { svc.captureScreenElements() }.getOrDefault(emptyList()) }
                text = els.filter { it.label.isNotBlank() || it.desc.isNotBlank() }.take(90).joinToString("\n") { e ->
                    val d = if (e.desc.isNotBlank() && e.desc != e.label) " (${e.desc})" else ""
                    "- ${e.type}: ${e.label}$d" + if (e.clickable) " [tappable]" else ""
                }
            }
            if (wantImage && ScreenCaptureService.instance != null) {
                val bmp = withContext(Dispatchers.Default) { runCatching { ScreenCaptureService.instance?.captureFrame() }.getOrNull() }
                if (bmp != null) {
                    image = withContext(Dispatchers.Default) {
                        runCatching { ScreenshotProcessor.process(applicationContext, bmp, false, emptyList()).base64 }.getOrNull()
                    }
                    runCatching { bmp.recycle() }
                }
            }
        } finally {
            if (p != null && lp != null && w != null) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { w.updateViewLayout(p, lp) }
            }
            p?.alpha = 1f
            bubble?.alpha = 1f
        }
        return Seen(text, image)
    }
}
