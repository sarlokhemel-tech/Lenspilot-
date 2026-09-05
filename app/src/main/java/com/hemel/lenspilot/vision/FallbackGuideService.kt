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
import android.widget.ImageButton
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.ScreenElement
import com.hemel.lenspilot.accessibility.toAnalyzeScreenJson
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.overlay.Highlight
import com.hemel.lenspilot.overlay.HighlightOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlaySpeechRecognizer: SpeechRecognizer? = null
    private var overlayListening = false
    private var isLiveCallActive = false

    private var workflowActive = false
    private var workflowGoal: String = ""
    private var workflowOutline: List<String> = emptyList()
    private var lastGuidanceText: String = ""
    private var onWorkflowUpdate: ((guidance: String, done: Boolean) -> Unit)? = null
    private var onWorkflowError: ((String) -> Unit)? = null
    private var checkInFlight = false

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
            .setContentText("Accessibility ছাড়া ফলব্যাক মোডে চলছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    // ------------------------------------------------------------------
    // Send Tier 2/3 elements to the Space — identical contract/handling
    // to LenspilotAccessibilityService.analyzeAndHighlight.
    // ------------------------------------------------------------------

    fun analyzeAndHighlight(
        userGoal: String? = null,
        onResult: (guidanceText: String, taskComplete: Boolean) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        retriesLeft: Int = 3
    ) {
        if (checkInFlight) return
        checkInFlight = true
        showThinking()

        scope.launch {
            val elements = visionManager.captureScreenElements()

            if (elements.isEmpty() && retriesLeft > 0) {
                checkInFlight = false
                mainHandler.postDelayed({ analyzeAndHighlight(userGoal, onResult, onError, retriesLeft - 1) }, 350)
                return@launch
            }
            if (elements.isEmpty()) {
                checkInFlight = false
                clearHighlights()
                onError("এই স্ক্রিনে কিছু পড়া যাচ্ছে না — একটু অপেক্ষা করে আবার চেষ্টা করুন।")
                return@launch
            }
            val elementsById = elements.associateBy { it.id }

            val metrics = resources.displayMetrics
            val body = JSONObject().apply {
                put("elements", elements.toAnalyzeScreenJson())
                put("screen_source", "vision_fallback")
                put("screen_width", metrics.widthPixels)
                put("screen_height", metrics.heightPixels)
                if (userGoal != null) put("user_goal", userGoal)
            }

            val baseUrl = getString(R.string.space_base_url)
            val guidanceSoFar = StringBuilder()
            var sawDone = false
            val result = ApiClient.streamAuthed(
                this@FallbackGuideService, baseUrl, "/api/analyze-screen", body.toString()
            ) { evt ->
                when (evt.optString("type")) {
                    "guidance_delta" -> {
                        guidanceSoFar.append(evt.optString("text", ""))
                        val current = guidanceSoFar.toString()
                        mainHandler.post { ensureOverlayView()?.render(emptyList(), current, isThinking = false) }
                    }
                    "done" -> {
                        sawDone = true
                        checkInFlight = false
                        val resultObj = evt.optJSONObject("result") ?: JSONObject()
                        mainHandler.post { applyAnalyzeScreenResult(resultObj, elementsById, onResult) }
                    }
                    "error" -> {
                        sawDone = true
                        checkInFlight = false
                        val msg = evt.optString("error", "analyze-screen error")
                        mainHandler.post { clearHighlights(); onError(msg) }
                    }
                }
            }
            if (!sawDone) {
                checkInFlight = false
                result.onFailure { mainHandler.post { clearHighlights(); onError(it.message ?: "analyze-screen request failed") } }
            }
        }
    }

    private fun applyAnalyzeScreenResult(
        resultObj: JSONObject,
        elementsById: Map<String, ScreenElement>,
        onResult: (guidanceText: String, taskComplete: Boolean) -> Unit
    ) {
        val highlightsArr = resultObj.optJSONArray("highlights")
        val highlights = mutableListOf<Highlight>()
        if (highlightsArr != null) {
            for (i in 0 until highlightsArr.length()) {
                val h = highlightsArr.getJSONObject(i)
                val elementId = h.optString("element_id", "")
                val matched = elementsById[elementId]
                val bbox = if (matched != null) {
                    android.graphics.Rect(matched.bbox)
                } else {
                    val bboxArr = h.optJSONArray("bbox")
                    if (bboxArr != null && bboxArr.length() == 4) {
                        val x = bboxArr.optDouble(0, 0.0).toInt()
                        val y = bboxArr.optDouble(1, 0.0).toInt()
                        val w = bboxArr.optDouble(2, 0.0).toInt()
                        val hgt = bboxArr.optDouble(3, 0.0).toInt()
                        android.graphics.Rect(x, y, x + w, y + hgt)
                    } else android.graphics.Rect(0, 0, 0, 0)
                }
                val colorInt = try {
                    android.graphics.Color.parseColor(h.optString("color", "#2563EB"))
                } catch (e: IllegalArgumentException) {
                    android.graphics.Color.parseColor("#2563EB")
                }
                highlights.add(
                    Highlight(
                        elementId = elementId, bbox = bbox, color = colorInt,
                        actionHint = h.optString("action_hint", "tap"), label = h.optString("label", "")
                    )
                )
            }
        }

        val errorDetected = resultObj.optBoolean("error_detected", false)
        val errorSolution = if (resultObj.isNull("error_solution")) null else resultObj.optString("error_solution", null)
        val guidance = if (errorDetected && !errorSolution.isNullOrBlank()) errorSolution else resultObj.optString("guidance_text", "")
        lastGuidanceText = guidance

        showHighlightsWithCaption(highlights, guidance)

        val actionType = resultObj.optString("action_type", "highlight")
        val intentTarget = resultObj.optJSONObject("intent_target")
        if (actionType != "highlight" && intentTarget != null) {
            fireIntentAction(actionType, intentTarget)
        }

        if (guidance.isNotBlank() && (Prefs.autoSpeak(this) || isLiveCallActive)) {
            if (isLiveCallActive) {
                speakGuidance(guidance, onDone = {
                    mainHandler.post { if (isLiveCallActive) startOverlayLiveListening() }
                })
            } else {
                speakGuidance(guidance)
            }
        }

        val taskComplete = resultObj.optBoolean("task_complete", false)
        onResult(guidance, taskComplete)
    }

    private fun fireIntentAction(actionType: String, target: JSONObject) {
        try {
            when (actionType) {
                "open_app" -> {
                    val pkg = target.optString("package", "")
                    if (pkg.isBlank()) return
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
                "open_settings" -> {
                    val action = target.optString("settings_action", "")
                    if (action.isBlank() || !action.startsWith("android.settings.")) return
                    startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "open_app_settings" -> {
                    val pkg = target.optString("package", "")
                    if (pkg.isBlank()) return
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", pkg, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                "open_url" -> {
                    val url = target.optString("url", "")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Intent action failed: $actionType", e)
        }
    }

    // ------------------------------------------------------------------
    // Workflow run loop — identical semantics to the Accessibility path:
    // ends only on task_complete=true or manual Stop.
    // ------------------------------------------------------------------

    fun startWorkflow(
        goal: String,
        planOutline: List<String>,
        onUpdate: (guidance: String, done: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (goal.isBlank()) {
            onError("Empty workflow")
            return
        }
        workflowActive = true
        workflowGoal = goal
        workflowOutline = planOutline
        lastGuidanceText = ""
        onWorkflowUpdate = onUpdate
        onWorkflowError = onError
        checkInFlight = false
        showControlBar()
        showTriggerBubble()
        triggerWorkflowStep()
    }

    fun stopWorkflow() {
        workflowActive = false
        workflowGoal = ""
        workflowOutline = emptyList()
        onWorkflowUpdate = null
        onWorkflowError = null
        checkInFlight = false
        if (overlayListening) stopOverlayVoiceCommand(cancelled = true)
        LocalTts.stop()
        clearHighlights()
        hideControlBar()
        hideTriggerBubble()
    }

    /** Pure on-device TTS — no cloud round-trip — for max speed. */
    private fun speakGuidance(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        LocalTts.speak(applicationContext, text, onDone = onDone)
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

    fun triggerWorkflowStep(extraContext: String? = null) {
        if (!workflowActive) return
        val parts = mutableListOf("মূল লক্ষ্য: $workflowGoal")
        if (workflowOutline.isNotEmpty()) {
            parts.add("সম্ভাব্য ধাপগুলো (শুধু ধারণা দেওয়ার জন্য): " + workflowOutline.joinToString(" | "))
        }
        if (lastGuidanceText.isNotBlank()) {
            parts.add("তুমি ঠিক আগেই বলেছিলে: \"$lastGuidanceText\" — এখন নতুন স্ক্রিন দেখে সামঞ্জস্যপূর্ণ, পরের ধাপের কথা বলো।")
        }
        if (!extraContext.isNullOrBlank()) {
            parts.add("ইউজার এইমাত্র বলেছে: $extraContext")
        }
        analyzeAndHighlight(
            userGoal = parts.joinToString("\n"),
            onResult = { guidance, taskComplete ->
                onWorkflowUpdate?.invoke(guidance, taskComplete)
                if (taskComplete && workflowActive) {
                    workflowActive = false
                    hideControlBar()
                    hideTriggerBubble()
                }
            },
            onError = { msg -> onWorkflowError?.invoke(msg) }
        )
    }

    /** Same as [LenspilotAccessibilityService.answerWorkflowQuestion] —
     * ✓/× shortcut for a yes/no question the AI just asked, folded into
     * the current workflow's context instead of starting a new one. */
    private fun answerWorkflowQuestion(yes: Boolean) {
        if (!workflowActive || checkInFlight) return
        triggerWorkflowStep(extraContext = if (yes) "হ্যাঁ" else "না")
    }

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
                if (lastGuidanceText.isBlank()) return@setOnClickListener
                if (speakButtonLoading) return@setOnClickListener // already working — the spin already shows that
                speakButtonLoading = true
                startSpeakButtonSpin(speakBtn)
                LocalTts.speak(
                    applicationContext,
                    lastGuidanceText,
                    onStart = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                    onDone = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } },
                    onError = { mainHandler.post { stopSpeakButtonSpin(speakBtn) } }
                )
            }
        }
        view.findViewById<ImageButton>(R.id.overlayYesButton).setOnClickListener { answerWorkflowQuestion(yes = true) }
        view.findViewById<ImageButton>(R.id.overlayMicButton).setOnClickListener { toggleOverlayRecording() }
        view.findViewById<ImageButton>(R.id.overlayStopButton).setOnClickListener { stopWorkflow() }
        view.findViewById<ImageButton>(R.id.overlayNoButton).setOnClickListener { answerWorkflowQuestion(yes = false) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Clear the status bar itself so the bar isn't drawn partly
            // behind/underneath it (was invisible on many devices at a
            // fixed 6dp).
            y = statusBarHeightPx() + (8 * resources.displayMetrics.density).toInt()
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        controlBarView = view
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun hideControlBar() {
        if (isLiveCallActive) toggleLiveCall()
        speakButtonLoading = false // view is going away — no dangling spin state
        controlBarView?.let {
            runCatching { windowManager?.removeView(it) }
            controlBarView = null
        }
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
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) triggerWorkflowStep()
                    true
                }
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
