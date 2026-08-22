package com.hemel.lenspilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import com.hemel.lenspilot.R
import com.hemel.lenspilot.audio.TtsPlayer
import com.hemel.lenspilot.audio.VoiceRecorder
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
 * TIER 1 of the 4-tier screen-reading pipeline: the Android Accessibility
 * Tree. Also owns the WORKFLOW RUN LOOP and everything that must keep
 * working while the user has LEFT the Lenspilot app (e.g. they're inside
 * Facebook) — which is why TTS, mic recording, and the floating control UI
 * all live here rather than in MainActivity: an accessibility service
 * stays alive independent of any Activity's visibility, MainActivity does
 * not.
 *
 * Guidance is no longer auto-triggered on every screen change (that was
 * both slow-feeling — a network round trip fired on every keyboard blink —
 * and the source of the "keeps highlighting the profile picture" bug, since
 * content-changed events fire far more often than real navigation). Instead:
 *   - the FIRST step of a workflow is analyzed immediately after Home,
 *   - every step after that is analyzed only when the user taps the small
 *     floating trigger bubble ([showTriggerBubble]) — explicit, on-demand,
 *     fast to reason about, and never analyzes a stale screen.
 *
 * The user must manually enable this service once, in Android Settings ->
 * Accessibility -> Lenspilot (a deliberate OS protection apps cannot
 * bypass). [isEnabled] can be used to prompt the user if it's still off,
 * and [openAccessibilitySettings] jumps them straight there.
 */
class LenspilotAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var highlightView: HighlightOverlayView? = null
    private var controlBarView: View? = null
    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(applicationContext) }

    // ---- workflow-run state -------------------------------------------
    private var workflowActive = false
    private var workflowStepGoals: List<String> = emptyList()
    private var workflowIndex = 0
    private var lastGuidanceText: String = ""
    private var onWorkflowUpdate: ((guidance: String, stepIndex: Int, stepTotal: Int, done: Boolean) -> Unit)? = null
    private var onWorkflowError: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "LenspilotA11y"

        @Volatile
        var instance: LenspilotAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedComponent = "${context.packageName}/${LenspilotAccessibilityService::class.java.name}"
            return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "Lenspilot accessibility service connected.")
    }

    override fun onDestroy() {
        stopWorkflow()
        instance = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted.")
    }

    // No auto-analysis on accessibility events anymore — see class kdoc.
    // Still required to override (abstract in the base class).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ------------------------------------------------------------------
    // TIER 1: walk the accessibility tree of the currently active window
    // ------------------------------------------------------------------

    fun captureScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<ScreenElement>()
        var counter = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val isMeaningful = label.isNotEmpty() && bounds.width() > 0 && bounds.height() > 0

            if (isMeaningful) {
                val type = when {
                    node.isEditable -> "input"
                    node.className?.contains("Button", ignoreCase = true) == true -> "button"
                    node.isClickable -> "icon"
                    else -> "text"
                }
                results.add(
                    ScreenElement(
                        id = "el_${counter++}",
                        type = type,
                        label = if (label.length > 80) label.take(80) else label,
                        bbox = Rect(bounds),
                        clickable = node.isClickable
                    )
                )
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)

        // Prioritize elements most likely to matter: clickable ones first
        // (these are what can actually be highlighted/tapped), then cap
        // the total. A long, noisy list of plain text elements was very
        // likely a real contributor to "chaotic" guidance — fewer, more
        // relevant candidates means less for the model to get confused by.
        val clickable = results.filter { it.clickable }
        val nonClickable = results.filter { !it.clickable }
        return (clickable + nonClickable).take(90)
    }

    // ------------------------------------------------------------------
    // Send Tier 1 elements to the Space, then draw whatever highlights
    // come back — with bbox/size taken from OUR OWN captured element
    // bounds (keyed by element_id), never from whatever pixel numbers
    // Gemini echoed. This is what actually guarantees the highlight lines
    // up with the real icon/button, pixel for pixel, regardless of any
    // rounding the model does when copying coordinates.
    // ------------------------------------------------------------------

    fun analyzeAndHighlight(
        userGoal: String? = null,
        onResult: (guidanceText: String, step: Int, total: Int, done: Boolean) -> Unit = { _, _, _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        // Immediate feedback — shown the instant this is called, BEFORE the
        // network round-trip even starts. This is what actually fixes the
        // "no idea if anything is happening, feels broken" complaint: a
        // silent 2-4s wait now has visible on-screen proof something is
        // in progress the whole time.
        showThinking()

        val elements = captureScreenElements()
        val elementsById = elements.associateBy { it.id }
        // TODO(next phase): if elements.isEmpty(), fall through to Tier 2
        // (ML Kit OCR) and Tier 3 (OmniParser) before giving up to Tier 4
        // (raw screenshot straight to Gemini via image_base64).

        val metrics = resources.displayMetrics
        val body = JSONObject().apply {
            put("elements", elements.toAnalyzeScreenJson())
            put("screen_source", "accessibility_tree")
            put("screen_width", metrics.widthPixels)
            put("screen_height", metrics.heightPixels)
            if (userGoal != null) put("user_goal", userGoal)
        }

        val baseUrl = getString(R.string.space_base_url)

        scope.launch {
            var sawDone = false
            val result = ApiClient.streamAuthed(
                this@LenspilotAccessibilityService, baseUrl, "/api/analyze-screen", body.toString()
            ) { evt ->
                when (evt.optString("type")) {
                    "done" -> {
                        sawDone = true
                        val resultObj = evt.optJSONObject("result") ?: JSONObject()
                        mainHandler.post { applyAnalyzeScreenResult(resultObj, elementsById, onResult) }
                    }
                    "error" -> {
                        sawDone = true
                        val msg = evt.optString("error", "analyze-screen error")
                        mainHandler.post { clearHighlights(); onError(msg) }
                    }
                }
            }
            if (!sawDone) {
                result.onFailure { mainHandler.post { clearHighlights(); onError(it.message ?: "analyze-screen request failed") } }
            }
        }
    }

    private fun applyAnalyzeScreenResult(
        resultObj: JSONObject,
        elementsById: Map<String, ScreenElement>,
        onResult: (guidanceText: String, step: Int, total: Int, done: Boolean) -> Unit
    ) {
        val highlightsArr = resultObj.optJSONArray("highlights")
        val highlights = mutableListOf<Highlight>()
        if (highlightsArr != null) {
            for (i in 0 until highlightsArr.length()) {
                val h = highlightsArr.getJSONObject(i)
                val elementId = h.optString("element_id", "")
                // Authoritative bbox: our own captured element bounds for
                // this id, if we have it. Only fall back to whatever
                // Gemini echoed when the id doesn't match anything (should
                // be rare, and better than nothing).
                val matched = elementsById[elementId]
                val bbox = if (matched != null) {
                    Rect(matched.bbox)
                } else {
                    val bboxArr = h.optJSONArray("bbox")
                    if (bboxArr != null && bboxArr.length() == 4) {
                        val x = bboxArr.optDouble(0, 0.0).toInt()
                        val y = bboxArr.optDouble(1, 0.0).toInt()
                        val w = bboxArr.optDouble(2, 0.0).toInt()
                        val hgt = bboxArr.optDouble(3, 0.0).toInt()
                        Rect(x, y, x + w, y + hgt)
                    } else Rect(0, 0, 0, 0)
                }
                val colorInt = try {
                    android.graphics.Color.parseColor(h.optString("color", "#2563EB"))
                } catch (e: IllegalArgumentException) {
                    android.graphics.Color.parseColor("#2563EB")
                }
                highlights.add(
                    Highlight(
                        elementId = elementId,
                        bbox = bbox,
                        color = colorInt,
                        actionHint = h.optString("action_hint", "tap"),
                        label = h.optString("label", "")
                    )
                )
            }
        }

        val errorDetected = resultObj.optBoolean("error_detected", false)
        val errorSolution = if (resultObj.isNull("error_solution")) null else resultObj.optString("error_solution", null)
        val guidance = if (errorDetected && !errorSolution.isNullOrBlank()) errorSolution else resultObj.optString("guidance_text", "")
        lastGuidanceText = guidance

        // Highlight box + the caption that explains it land together, in
        // one render call — per spec: "গ্লাস কার্ডের উপর লেখা উঠতেছে" AND
        // the highlight, shown as one unit, not the box alone.
        showHighlightsWithCaption(highlights, guidance)

        val step = resultObj.optInt("workflow_step", 1)
        val total = resultObj.optInt("workflow_total", 1)
        onResult(guidance, step, total, step >= total)
    }

    // ------------------------------------------------------------------
    // Workflow run loop — walks an explicit, model-planned step list.
    // Step advancement is driven by the caller (MainActivity) calling
    // [advanceToNextStep] once narration finishes; re-analysis of a step
    // is driven by the user tapping the trigger bubble, NOT automatically.
    // ------------------------------------------------------------------

    fun startWorkflow(
        stepGoals: List<String>,
        onUpdate: (guidance: String, stepIndex: Int, stepTotal: Int, done: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (stepGoals.isEmpty()) {
            onError("Empty workflow")
            return
        }
        workflowActive = true
        workflowStepGoals = stepGoals
        workflowIndex = 0
        onWorkflowUpdate = onUpdate
        onWorkflowError = onError
        showControlBar()
        showTriggerBubble()
        triggerWorkflowStep()
    }

    fun stopWorkflow() {
        workflowActive = false
        workflowStepGoals = emptyList()
        workflowIndex = 0
        onWorkflowUpdate = null
        onWorkflowError = null
        if (voiceRecorder.isRecording) voiceRecorder.stop()
        TtsPlayer.stop()
        clearHighlights()
        hideControlBar()
        hideTriggerBubble()
    }

    val isWorkflowActive: Boolean get() = workflowActive

    /** Re-analyzes the CURRENT step's goal on whatever is on screen right
     * now, speaks the guidance, then advances the step pointer once
     * narration finishes — one bubble tap = one guided step. Fully
     * self-contained (TTS included) so it keeps working whether or not
     * MainActivity is still alive/foregrounded. [onWorkflowUpdate] is only
     * used to keep the in-app chat card's status text in sync — it never
     * drives TTS or step advancement itself anymore. */
    fun triggerWorkflowStep(extraContext: String? = null) {
        if (!workflowActive || workflowIndex >= workflowStepGoals.size) return
        val baseGoal = workflowStepGoals[workflowIndex]
        val goal = if (extraContext.isNullOrBlank()) baseGoal else "$baseGoal — ইউজার বলেছে: $extraContext"
        val total = workflowStepGoals.size
        val stepAtCallTime = workflowIndex
        analyzeAndHighlight(
            userGoal = goal,
            onResult = { guidance, _, _, _ ->
                onWorkflowUpdate?.invoke(guidance, stepAtCallTime, total, false)
                if (guidance.isNotBlank()) {
                    TtsPlayer.speak(
                        applicationContext, getString(R.string.space_base_url), guidance, scope,
                        onDone = {
                            clearHighlights()
                            if (workflowActive && workflowIndex == stepAtCallTime) advanceToNextStep()
                        },
                        onError = {
                            // Guidance is already shown/highlighted even if
                            // speech fails — still advance so the user
                            // isn't stuck waiting on audio that never comes.
                            if (workflowActive && workflowIndex == stepAtCallTime) advanceToNextStep()
                        }
                    )
                } else if (workflowActive && workflowIndex == stepAtCallTime) {
                    advanceToNextStep()
                }
            },
            onError = { msg -> onWorkflowError?.invoke(msg) }
        )
    }

    /** Advances the local step pointer to the next planned step (or marks
     * the workflow done). Does NOT re-trigger analysis automatically —
     * the newly-current step is analyzed the next time the user taps the
     * trigger bubble, keeping every network call user-initiated. */
    fun advanceToNextStep() {
        workflowIndex++
        if (workflowIndex >= workflowStepGoals.size) {
            val total = workflowStepGoals.size
            onWorkflowUpdate?.invoke("", total, total, true)
            workflowActive = false
            hideControlBar()
            hideTriggerBubble()
        }
    }

    // ------------------------------------------------------------------
    // Pass-through highlight overlay (TYPE_ACCESSIBILITY_OVERLAY — only an
    // active accessibility service is allowed to create this window type).
    // ------------------------------------------------------------------

    fun ensureOverlayView(): HighlightOverlayView? {
        val wm = windowManager ?: return null
        if (highlightView == null) {
            val view = HighlightOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // NOT_TOUCHABLE = taps fall straight through to the real
                // app underneath.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            view.fitsSystemWindows = false
            wm.addView(view, params)
            highlightView = view
        }
        return highlightView
    }

    /** Shown the instant a check starts, before the network reply lands. */
    fun showThinking() {
        ensureOverlayView()?.render(emptyList(), null, isThinking = true)
    }

    /** The box and the caption that explains it, together, in one frame. */
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
    // Floating glass control bar (speak / mic / stop) — TOUCHABLE, shown
    // top-center while a workflow is active, so the 3 control icons stay
    // reachable even though the Lenspilot app itself isn't in the
    // foreground (the user is inside whatever app they're being guided
    // through). Self-contained: every button here talks straight to
    // TtsPlayer/VoiceRecorder/ApiClient, no MainActivity involved.
    // ------------------------------------------------------------------

    private fun showControlBar() {
        val wm = windowManager ?: return
        if (controlBarView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control_bar, null)

        view.findViewById<ImageButton>(R.id.overlaySpeakButton).setOnClickListener {
            if (lastGuidanceText.isNotBlank()) {
                TtsPlayer.speak(applicationContext, getString(R.string.space_base_url), lastGuidanceText, scope)
            }
        }
        view.findViewById<ImageButton>(R.id.overlayMicButton).setOnClickListener {
            toggleOverlayRecording()
        }
        view.findViewById<ImageButton>(R.id.overlayStopButton).setOnClickListener {
            stopWorkflow()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * resources.displayMetrics.density).toInt()
        }
        wm.addView(view, params)
        controlBarView = view
    }

    private fun hideControlBar() {
        controlBarView?.let {
            runCatching { windowManager?.removeView(it) }
            controlBarView = null
        }
    }

    private fun toggleOverlayRecording() {
        if (voiceRecorder.isRecording) {
            val file = voiceRecorder.stop() ?: return
            scope.launch {
                val baseUrl = getString(R.string.space_base_url)
                val result = ApiClient.uploadAudioAuthed(applicationContext, baseUrl, "/api/transcribe", file)
                result.fold(
                    onSuccess = { json ->
                        val text = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
                        if (text.isNotBlank()) triggerWorkflowStep(extraContext = text)
                    },
                    onFailure = { onWorkflowError?.invoke(it.message ?: "voice command failed") }
                )
                file.delete()
            }
        } else {
            voiceRecorder.start()
        }
    }

    // ------------------------------------------------------------------
    // Small floating trigger bubble — draggable, tap = re-analyze the
    // current step's goal on the current screen. This is the ONLY thing
    // that fires an /api/analyze-screen call after the step's first
    // automatic check, replacing the old "re-analyze on every screen
    // change" loop (which felt slow, waiting on network calls the user
    // never asked for, and could re-highlight a stale/wrong element while
    // the keyboard was still opening etc.).
    // ------------------------------------------------------------------

    private fun showTriggerBubble() {
        val wm = windowManager ?: return
        if (bubbleView != null) return
        val view = ImageButton(this).apply {
            setImageResource(R.drawable.ic_lenspilot_logo)
            background = androidx.core.content.ContextCompat.getDrawable(this@LenspilotAccessibilityService, R.drawable.bg_glass_bubble)
            setPadding(8, 8, 8, 8)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tap for guidance"
        }
        val sizePx = (44 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
        wm.addView(view, params)
        bubbleView = view
    }

    private fun hideTriggerBubble() {
        bubbleView?.let {
            runCatching { windowManager?.removeView(it) }
            bubbleView = null
        }
    }
}
