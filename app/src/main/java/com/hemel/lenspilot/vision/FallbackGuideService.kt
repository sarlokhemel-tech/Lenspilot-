package com.hemel.lenspilot.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.view.inputmethod.InputMethodManager
import com.hemel.lenspilot.ImagePickTrampolineActivity
import com.hemel.lenspilot.PendingImagePick
import com.hemel.lenspilot.chat.GuideChatOverlay
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.ScreenElement
import com.hemel.lenspilot.accessibility.toAnalyzeScreenJson
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.audio.RemoteTts
import com.hemel.lenspilot.guide.GuideHost
import com.hemel.lenspilot.guide.GuideLoopV3
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService
import com.hemel.lenspilot.accessibility.ScreenReader
import com.hemel.lenspilot.userinfo.UserInfoEntry
import android.graphics.Rect
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.overlay.Highlight
import com.hemel.lenspilot.overlay.HighlightOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.hemel.lenspilot.workflow.WorkflowContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * TIER 2/3 counterpart of LenspilotAccessibilityService — used ONLY when
 * the user has NOT enabled Accessibility. Deliberately a near-mirror of
 * that class (same workflow loop, same overlay rendering, same intent
 * firing, same TTS/mic behavior) so guidance feels identical either way;
 * the only real differences are:
 *   1. Screen elements come from [VisionFallbackManager] (ML Kit OCR on a
 *      MediaProjection screenshot) instead of the accessibility tree.
 *   2. The overlay windows are TYPE_APPLICATION_OVERLAY (needs the
 *      "draw over other apps" permission) instead of
 *      TYPE_ACCESSIBILITY_OVERLAY (which only an active accessibility
 *      service is allowed to create).
 *   3. There's no accessibility-event stream to auto-detect a genuine
 *      screen change, so every step after the first needs the floating
 *      trigger bubble tapped manually — no real loss in practice, since
 *      that bubble already existed as the fast path either way.
 *
 * Kept as its own file rather than refactored to share code with
 * LenspilotAccessibilityService, specifically so the (working, tested)
 * Accessibility path can never be put at risk by a change made here.
 */
class FallbackGuideService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var visionManager: VisionFallbackManager
    private var highlightView: HighlightOverlayView? = null
    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    /** v40: গাইড-চ্যাট খোলা থাকলে তার রুট ভিউ (স্ক্রিনশটের সময় লুকানো হয়)। */
    private val messageInputView: View? get() = guideChat.rootView
    private var controlBarUserMoved = false
    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlaySpeechRecognizer: SpeechRecognizer? = null
    private var overlayListening = false
    private var isLiveCallActive = false

    // Guide v3 — সব সিদ্ধান্ত/স্মৃতি GuideLoopV3-এ (Accessibility সার্ভিসের সাথে একই ইঞ্জিন)। এখানে শুধু Vision মোডের
    // "হাত-পা": MediaProjection স্ক্রিনশট, ওভারলে, ভয়েস। Accessibility চালু থাকলে (ঐচ্ছিক) tree/ইভেন্টও কাজে লাগে।
    private var onWorkflowUpdate: ((guidance: String, done: Boolean) -> Unit)? = null
    private var onWorkflowError: ((String) -> Unit)? = null

    private val guideHost = object : GuideHost {
        override val context: Context get() = this@FallbackGuideService
        override val scope: CoroutineScope get() = this@FallbackGuideService.scope
        override val hasScreenEvents: Boolean get() = LenspilotAccessibilityService.instance != null
        override val imageFirst: Boolean get() = true
        override fun readTree(): ScreenReader.Snapshot? = try {
            LenspilotAccessibilityService.instance?.captureSnapshot()
        } catch (e: Exception) { null }
        override fun fingerprintNow(): Int = try {
            LenspilotAccessibilityService.instance?.captureSnapshot()?.fingerprint ?: 0
        } catch (e: Exception) { 0 }
        override fun captureShot(boost: Boolean, masks: List<Rect>, cb: (ScreenshotProcessor.Result?) -> Unit) {
            this@FallbackGuideService.scope.launch { cb(captureVisionShot(boost, masks)) }
        }
        override fun sensitiveRects(): List<Rect> = try {
            LenspilotAccessibilityService.instance?.captureSnapshot()?.sensitive ?: emptyList()
        } catch (e: Exception) { emptyList() }
        override fun showThinking() = this@FallbackGuideService.showThinking()
        override fun showCard(highlights: List<Highlight>, caption: String) = showHighlightsWithCaption(highlights, caption)
        override fun clearHighlights() = this@FallbackGuideService.clearHighlights()
        override fun deliverSay(say: String, asks: Boolean) {
            if (say.isBlank()) return
            guideChat.postAi(say, asks)
            if (Prefs.autoSpeak(this@FallbackGuideService) || isLiveCallActive) {
                if (isLiveCallActive) {
                    speakGuidance(say, onDone = { mainHandler.post { if (isLiveCallActive) startOverlayLiveListening() } })
                } else {
                    speakGuidance(say)
                }
            }
        }
        override fun onUpdate(say: String, done: Boolean) { onWorkflowUpdate?.invoke(say, done) }
        override fun onError(msg: String) { guideChat.postAi(msg.take(160)); onWorkflowError?.invoke(msg) }
        override fun finishUi() {
            this@FallbackGuideService.clearHighlights()
            hideControlBar()
            hideTriggerBubble()
        }
    }
    private val guideLoop by lazy { GuideLoopV3(guideHost) }
    private val workflowActive: Boolean get() = guideLoop.active

    companion object {
        private const val TAG = "FallbackGuideService"
        private const val CHANNEL_ID = "fallback_guide"
        private const val NOTIFICATION_ID = 4300

        @Volatile
        var instance: FallbackGuideService? = null
            private set

        fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        visionManager = VisionFallbackManager(applicationContext)
        com.hemel.lenspilot.audio.LocalTts.warmUp(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Fallback guide service started (no Accessibility mode).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWorkflow()
        visionManager.release()
        overlaySpeechRecognizer?.destroy()
        overlaySpeechRecognizer = null
        instance = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Lenspilot গাইডলাইন", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenspilot গাইড করছে")
            .setContentText("ভিসন মোডে চলছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    /** MediaProjection ফ্রেম (ScreenCaptureService) → ছোট JPEG base64; সার্ভিস না থাকলে null। */

    /** MediaProjection ফ্রেম → [ScreenshotProcessor] (JPG/সাদাকালো/ছোট)। নিজের ওভারলে এক মুহূর্ত লুকিয়ে নেয়,
     * যাতে AI নিজের আঁকা বক্স স্ক্রিনের অংশ না ভাবে। */
    /** নিজের কন্ট্রোল-বার/বাবল স্ক্রিনশটে কালো করার জায়গা — লুকিয়ে ঝলক না দিয়ে মাস্ক (বার "নাই হয়ে আবার আসা" বাগ ঠেকাতে)। */
    private fun ownOverlayMasks(): List<Rect> = listOfNotNull(controlBarView, bubbleView).mapNotNull { v ->
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) null else {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }

    private suspend fun captureVisionShot(boost: Boolean = false, masks: List<Rect> = emptyList()): ScreenshotProcessor.Result? {
        val allMasks = masks + ownOverlayMasks()
        val hidden = mutableListOf<Pair<View, Int>>()
        for (v in listOfNotNull(highlightView, messageInputView)) {   // কন্ট্রোল-বার/বাবল লুকাই না — মাস্ক করি
            hidden.add(v to v.visibility)
            v.visibility = View.INVISIBLE
        }
        fun restore() { hidden.forEach { (v, vis) -> v.visibility = vis } }
        try {
            kotlinx.coroutines.delay(90)
            // শুধু ফ্রেম নেওয়ার মুহূর্তটুকু লুকানো; ছবি প্রসেসিং (ভারী) শুরুর আগেই ওভারলে ফেরত
            val bmp = kotlinx.coroutines.withContext(Dispatchers.Default) { ScreenCaptureService.instance?.captureFrame() }
            restore()
            if (bmp == null) return null
            return kotlinx.coroutines.withContext(Dispatchers.Default) {
                try {
                    ScreenshotProcessor.process(applicationContext, bmp, boost, allMasks)
                } catch (e: Exception) { null } finally {
                    runCatching { bmp.recycle() }
                }
            }
        } catch (e: Exception) {
            restore()
            return null
        }
    }


    fun startWorkflow(
        goal: String,
        system: String = "super_1_2",
        extraUserInfo: List<UserInfoEntry> = emptyList(),
        onUpdate: (guidance: String, done: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (goal.isBlank()) {
            onError("Empty workflow")
            return
        }
        onWorkflowUpdate = onUpdate
        onWorkflowError = onError
        // Accessibility চালু থাকলে স্ক্রিন বদলালে অটো-কল; না থাকলে ট্রিগার বাবল (এবং intent খোলার পর লুপের নিজের আবার-দেখা)
        LenspilotAccessibilityService.screenChangeListener = { if (guideLoop.active) guideLoop.onScreenChanged() }
        showControlBar()
        showTriggerBubble()
        guideLoop.start(goal, system, extraUserInfo)
    }

    fun stopWorkflow() {
        guideLoop.stop()
        LenspilotAccessibilityService.screenChangeListener = null
        onWorkflowUpdate = null
        onWorkflowError = null
        if (overlayListening) stopOverlayVoiceCommand(cancelled = true)
        LocalTts.stop()
        RemoteTts.stop()
        clearHighlights()
        hideControlBar()
        hideTriggerBubble()
    }

    /** Mirrors LenspilotAccessibilityService.speakGuidance — see there for
     * the full explanation. Tries RemoteTts (Edge/Gemini via /api/tts)
     * first, falls back to on-device LocalTts on any error. */
    private fun speakGuidance(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        val baseUrl = getString(R.string.space_base_url)
        RemoteTts.speak(
            applicationContext, baseUrl, text,
            onDone = onDone,
            onError = { LocalTts.speak(applicationContext, text, onDone = onDone) }
        )
    }

    // See LenspilotAccessibilityService's identical block for the full
    // rationale — same 38dp icon, same rotate-in-place loading feedback.
    private var speakButtonLoading = false

    private fun startSpeakButtonSpin(view: View) {
        view.animate().cancel()
        view.rotation = 0f
        spinOnce(view)
    }

    private fun spinOnce(view: View) {
        view.animate()
            .rotationBy(360f)
            .setDuration(700)
            .setInterpolator(LinearInterpolator())
            .withEndAction { if (speakButtonLoading) spinOnce(view) }
            .start()
    }

    private fun stopSpeakButtonSpin(view: View) {
        speakButtonLoading = false
        view.animate().cancel()
        view.rotation = 0f
    }

    val isWorkflowActive: Boolean get() = workflowActive

    /** ট্রিগার বাবল / মাইক / মেসেজ-বক্স — ইউজার নিজে এখনকার স্ক্রিন দেখিয়ে "এরপর কী?" জিজ্ঞেস করছে। */
    fun triggerWorkflowStep(extraContext: String? = null, extraImageBase64: String? = null) {
        if (!guideLoop.active) return
        guideLoop.trigger(userSaid = extraContext, extraImage = extraImageBase64, manual = true)
    }

    // ------------------------------------------------------------------
    // v40: গাইড-মোডের টেম্পোরারি গ্লাস চ্যাট (আগে ছিল ছোট ইনপুট-বক্স) — কন্ট্রোল-বারের মেসেজ আইকনে খোলে/বন্ধ হয়।
    // ইউজারের লেখা/ছবি আগের মতোই triggerWorkflowStep-এর পথে চলমান কাজে ঢোকে; AI-র উত্তর (say/প্রশ্ন/ত্রুটি)
    // deliverSay/onError থেকে চ্যাটে iPhone-কার্ড হয়ে আসে। ছবি আসে ফোনের গ্যালারি থেকে। দেখো GuideChatOverlay।
    // ------------------------------------------------------------------

    private val guideChat: GuideChatOverlay by lazy {
        GuideChatOverlay(
            ctx = this,
            windowManager = { windowManager },
            onSend = { text, image ->
                if (!workflowActive) false else {
                    triggerWorkflowStep(
                        extraContext = text.ifBlank { "(ছবি পাঠিয়েছে, লেখেনি — ছবিটা দেখে বুঝে নাও)" },
                        extraImageBase64 = image
                    )
                    true
                }
            },
            onUnreadChanged = { n -> updateChatBadge(n) },
            onPicking = { picking -> guideLoop.suspended = picking },
            pickImage = { cb ->
                PendingImagePick.awaitNext { base64 -> cb(base64) }
                startActivity(
                    Intent(this, ImagePickTrampolineActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    }

    private fun toggleMessageInput() = guideChat.toggle()

    /** কন্ট্রোল-বারের মেসেজ আইকনের উপরে না-পড়া AI বার্তার সংখ্যা (০ হলে লুকানো)। */
    private fun updateChatBadge(n: Int = guideChat.unread) {
        val tv = controlBarView?.findViewById<android.widget.TextView>(R.id.overlayMessageBadge) ?: return
        tv.text = if (n > 9) "9+" else n.toString()
        tv.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun hideMessageInput() = guideChat.hide()

    // ------------------------------------------------------------------
    // Overlay (TYPE_APPLICATION_OVERLAY — needs SYSTEM_ALERT_WINDOW,
    // checked by MainActivity before this service is ever started).
    // ------------------------------------------------------------------

    fun ensureOverlayView(): HighlightOverlayView? {
        val wm = windowManager ?: return null
        if (highlightView == null) {
            val view = HighlightOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching { wm.addView(view, params) }.onFailure { return null }
            highlightView = view
        }
        return highlightView
    }

    fun showThinking() {
        ensureOverlayView()?.render(emptyList(), null, isThinking = true)
    }

    fun showHighlightsWithCaption(highlights: List<Highlight>, guidance: String) {
        ensureOverlayView()?.render(highlights, guidance.ifBlank { null }, isThinking = false)
        repositionControlBarIfBlocked(highlights)
    }

    fun clearHighlights() {
        highlightView?.let {
            runCatching { windowManager?.removeView(it) }
            highlightView = null
        }
    }

    // ------------------------------------------------------------------
    // Floating glass control bar (speak / mic / stop)
    // ------------------------------------------------------------------

    private fun showControlBar() {
        val wm = windowManager ?: return
        if (controlBarView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control_bar, null)

        view.findViewById<ImageButton>(R.id.overlaySpeakButton).let { speakBtn ->
            speakBtn.setOnClickListener {
                if (guideLoop.lastSay.isBlank()) return@setOnClickListener
                if (speakButtonLoading) return@setOnClickListener // already working — the spin already shows that
                speakButtonLoading = true
                startSpeakButtonSpin(speakBtn)
                val baseUrl = getString(R.string.space_base_url)
                RemoteTts.speak(
                    applicationContext,
                    baseUrl,
                    guideLoop.lastSay,
                    onStart = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                    onDone = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                    onError = {
                        LocalTts.speak(
                            applicationContext,
                            guideLoop.lastSay,
                            onStart = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                            onDone = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                            onError = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } }
                        )
                    }
                )
            }
        }
        view.findViewById<ImageButton>(R.id.overlayMicButton).setOnClickListener { toggleOverlayRecording() }
        view.findViewById<ImageButton>(R.id.overlayStopButton).setOnClickListener { stopWorkflow() }
        view.findViewById<ImageButton>(R.id.overlayMessageButton).setOnClickListener { toggleMessageInput() }

        // TOP|START (not CENTER_HORIZONTAL) so x/y both mean exact pixel
        // offsets — needed for dragging to behave predictably. Started
        // centered anyway via the post{} below, once the pill's real
        // width is known after layout.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            // Clear the status bar itself so the bar isn't drawn partly
            // behind/underneath it (was invisible on many devices at a
            // fixed 6dp).
            y = statusBarHeightPx() + (8 * resources.displayMetrics.density).toInt()
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        controlBarView = view
        updateChatBadge()
        controlBarParams = params
        controlBarUserMoved = false

        // Center it horizontally once its real (wrap_content) width is
        // known — can't compute this before the first layout pass.
        view.post {
            if (controlBarView !== view) return@post
            params.x = (resources.displayMetrics.widthPixels - view.width) / 2
            runCatching { wm.updateViewLayout(view, params) }
        }

        // Drag by the grip handle only — the other five icons keep their
        // own tap behavior untouched (see the layout's comment).
        val handle = view.findViewById<View>(R.id.overlayDragHandle)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // A real drag means "the user placed this on purpose" —
                    // auto-reposition (see repositionControlBarIfBlocked)
                    // backs off and leaves it exactly where they put it.
                    if (moved) controlBarUserMoved = true
                    true
                }
                else -> false
            }
        }
    }

    /** Called every time a new highlight/caption frame is shown — if the
     * screen underneath needs something near the top (a search bar, an
     * icon row) that the control bar would otherwise sit on top of, nudge
     * the bar down just below it. Never overrides a manual drag — once
     * the user has moved the bar themselves, this leaves it alone. */
    private fun repositionControlBarIfBlocked(highlights: List<Highlight>) {
        if (controlBarUserMoved) return
        val wm = windowManager ?: return
        val view = controlBarView ?: return
        val params = controlBarParams ?: return
        val density = resources.displayMetrics.density
        val defaultY = statusBarHeightPx() + (8 * density).toInt()
        val barHeightPx = if (view.height > 0) view.height else (56 * density).toInt()
        val marginPx = (10 * density).toInt()
        // Cap how far it'll auto-slide down, so it can never end up
        // covering the middle of the screen just because a lot of
        // elements were detected near the top.
        val maxY = (resources.displayMetrics.heightPixels * 0.35).toInt()

        val blockingBottom = highlights
            .filter { it.bbox.top < defaultY + barHeightPx && it.bbox.bottom > defaultY }
            .maxOfOrNull { it.bbox.bottom }

        val desiredY = if (blockingBottom != null) {
            (blockingBottom + marginPx).coerceAtMost(maxY)
        } else {
            defaultY
        }
        if (params.y != desiredY) {
            params.y = desiredY
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun hideControlBar() {
        if (isLiveCallActive) toggleLiveCall()
        guideChat.reset()   // v40: কাজ শেষ — টেম্পোরারি চ্যাট মুছে যায়
        speakButtonLoading = false // view is going away — no dangling spin state
        controlBarView?.let {
            runCatching { windowManager?.removeView(it) }
            controlBarView = null
        }
        controlBarParams = null
        controlBarUserMoved = false
    }

    /** Same Live Call loop as LenspilotAccessibilityService — see that
     * class's kdoc for the full explanation. Duplicated here rather than
     * shared because this service and the accessibility one don't share
     * a common base class; both stay self-contained on purpose. */
    private fun toggleLiveCall() {
        // Call icon removed from the control bar per user request — this
        // stays only as dead-code safety (isLiveCallActive can never
        // become true now, so this path never actually runs).
        isLiveCallActive = !isLiveCallActive
        if (isLiveCallActive) {
            if (overlayListening) stopOverlayVoiceCommand(cancelled = true)
            startOverlayLiveListening()
        } else {
            stopOverlayVoiceCommand(cancelled = true)
            LocalTts.stop()
            RemoteTts.stop()
        }
    }

    private fun startOverlayLiveListening() {
        if (!isLiveCallActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onWorkflowError?.invoke("এই ডিভাইসে ভয়েস রিকগনিশন পাওয়া যাচ্ছে না")
            isLiveCallActive = false
            return
        }
        val recognizer = overlaySpeechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also { overlaySpeechRecognizer = it }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onResults(results: Bundle) {
                overlayListening = false
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!isLiveCallActive) return
                if (!text.isNullOrBlank()) {
                    triggerWorkflowStep(extraContext = text)
                } else {
                    mainHandler.postDelayed({ if (isLiveCallActive) startOverlayLiveListening() }, 500)
                }
            }
            override fun onError(error: Int) {
                overlayListening = false
                if (isLiveCallActive) {
                    mainHandler.postDelayed({ if (isLiveCallActive) startOverlayLiveListening() }, 800)
                }
            }
        })

        overlayListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun toggleOverlayRecording() {
        if (overlayListening) {
            stopOverlayVoiceCommand(cancelled = false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onWorkflowError?.invoke("এই ডিভাইসে ভয়েস রিকগনিশন পাওয়া যাচ্ছে না")
            return
        }
        val recognizer = overlaySpeechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also { overlaySpeechRecognizer = it }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onResults(results: Bundle) {
                overlayListening = false
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) triggerWorkflowStep(extraContext = text)
            }
            override fun onError(error: Int) { overlayListening = false }
        })

        overlayListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun stopOverlayVoiceCommand(cancelled: Boolean) {
        if (cancelled) overlaySpeechRecognizer?.cancel() else overlaySpeechRecognizer?.stopListening()
        overlayListening = false
    }

    // ------------------------------------------------------------------
    // Small floating trigger bubble — draggable, tap = re-check now.
    // The ONLY way steps after the first advance in fallback mode (no
    // accessibility events available to auto-detect a screen change).
    // ------------------------------------------------------------------

    private fun showTriggerBubble() {
        val wm = windowManager ?: return
        if (bubbleView != null) return
        val view = ImageButton(this).apply {
            setImageResource(R.drawable.ic_lenspilot_logo)
            background = androidx.core.content.ContextCompat.getDrawable(this@FallbackGuideService, R.drawable.bg_glass_bubble)
            setPadding(8, 8, 8, 8)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tap for guidance"
        }
        val sizePx = (44 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - (16 * resources.displayMetrics.density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.6).toInt()
        }

        // ট্রিগার বাটন ঠিক: আগে ড্র্যাগের সীমা ছিল ৮ *পিক্সেল* (≈৩dp) — আঙুলের স্বাভাবিক কাঁপুনিতেই
        // "ড্র্যাগ" ধরা পড়ে ট্যাপ বাতিল হতো। এখন ~১০dp, আর সীমা পেরোনোর আগে বাবল নড়েও না।
        val slop = 10f * resources.displayMetrics.density
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performClick(); triggerWorkflowStep() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        bubbleView = view
    }

    private fun hideTriggerBubble() {
        bubbleView?.let {
            runCatching { windowManager?.removeView(it) }
            bubbleView = null
        }
    }
}
