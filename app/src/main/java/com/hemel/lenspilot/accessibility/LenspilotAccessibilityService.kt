package com.hemel.lenspilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
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
import android.widget.Toast
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.view.inputmethod.InputMethodManager
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.overlay.Highlight
import com.hemel.lenspilot.overlay.HighlightOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.hemel.lenspilot.workflow.ContextWindowStore
import com.hemel.lenspilot.workflow.WorkflowContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
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
    private var messageInputView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var controlBarUserMoved = false
    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlaySpeechRecognizer: SpeechRecognizer? = null
    private var overlayListening = false
    private var isLiveCallActive = false

    // ---- workflow-run state -------------------------------------------
    private var workflowActive = false
    private var workflowGoal: String = ""
    private var workflowOutline: List<String> = emptyList()
    private var lastGuidanceText: String = ""
    // Same on-device "RAM" context window the AI browser uses (see
    // ContextWindowStore.kt) — one sessionId per goal, so this loop keeps
    // its own compact log of what it's already done without resending
    // the full history on every step. This REPLACES the old single-line
    // lastGuidanceText-only memory with the unified, capped, on-device
    // store (still just a few short lines into the prompt — token cost
    // does not go up, it's the same shape just backed by the shared
    // store and now visible in Settings -> "Context Window").
    private var workflowSessionId: String = UUID.randomUUID().toString().replace("-", "")
    private var onWorkflowUpdate: ((guidance: String, done: Boolean) -> Unit)? = null
    private var onWorkflowError: ((String) -> Unit)? = null
    private var checkInFlight = false
    private var lastSeenWindowKey: String? = null
    private val screenChangeRunnable = Runnable {
        if (workflowActive && !checkInFlight) triggerWorkflowStep()
    }

    companion object {
        private const val TAG = "LenspilotA11y"
        private const val CHANNEL_ID = "lenspilot_a11y_session"
        private const val NOTIFICATION_ID = 4300

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
        com.hemel.lenspilot.audio.LocalTts.warmUp(applicationContext)
        // Bugreport root cause (2026-09-09): ActivityManager was killing the
        // whole process at adj 901 "cch-act" (cached activity) under low
        // memory — Huawei's iAwareF low-mem killer — even while Settings
        // still showed Accessibility as ON. A plain AccessibilityService
        // with no running Activity sits at cached priority as soon as the
        // user leaves Lenspilot, which is exactly when this service needs
        // to keep working. Holding a foreground-service notification here
        // keeps this process at foreground-service importance instead of
        // cached, which is what the "instance == null but isEnabled() ==
        // true" stuck-dialog case (showAccessibilityNotRespondingDialog)
        // was actually a symptom of.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Foreground start can fail on some OEM ROMs if the user has
            // revoked POST_NOTIFICATIONS or blocked background start;
            // the service still runs, just without the OOM protection.
            Log.w(TAG, "startForeground failed, continuing without it", e)
        }
        Log.i(TAG, "Lenspilot accessibility service connected.")
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Guidance session", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenspilot চালু আছে")
            .setContentText("ব্যাকগ্রাউন্ডে গাইডলাইন দেওয়ার জন্য সচল")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopWorkflow()
        overlaySpeechRecognizer?.destroy()
        overlaySpeechRecognizer = null
        instance = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted.")
    }

    /**
     * Auto-re-checks the current step ONLY on a genuine screen/app change
     * (a real new window taking over — TYPE_WINDOW_STATE_CHANGED), never on
     * in-place noise like the keyboard opening, a cursor blinking, or list
     * content updating — those fire TYPE_WINDOW_CONTENT_CHANGED, which is
     * deliberately ignored. This is what makes the trigger bubble optional
     * rather than mandatory: "স্ক্রিন পরিবর্তন হলে তবেই... এক স্ক্রিনেই কাজ
     * হলে দরকার নেই" — if the user is still on the same screen, nothing
     * auto-fires (the model is instead asked to narrate ahead in
     * ANALYZE_SCREEN_INSTRUCTIONS); the bubble stays available for a manual
     * re-check any time.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!workflowActive || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val key = "${event.packageName}/${event.className}"
        if (key == lastSeenWindowKey) return
        lastSeenWindowKey = key
        mainHandler.removeCallbacks(screenChangeRunnable)
        mainHandler.postDelayed(screenChangeRunnable, 500)
    }

    // ------------------------------------------------------------------
    // TIER 1: walk the accessibility tree of the currently active window
    // ------------------------------------------------------------------

    fun captureScreenElements(): List<ScreenElement> {
        // DIAGNOSTIC HARDENING: AccessibilityNodeInfo objects can go stale
        // mid-walk (the window behind them changed/recycled while we were
        // still reading it) and throw IllegalStateException on ANY method
        // call — getBoundsInScreen(), .text, .isEditable, all of it. This
        // walk previously had zero exception handling: an uncaught
        // exception here doesn't show an "app has stopped" dialog (this is
        // a background bound service, not a foreground Activity) — it just
        // silently kills the whole accessibility service PROCESS. Settings
        // still shows the toggle as ON (nothing tells Android to flip it
        // off), but LenspilotAccessibilityService.instance is gone and
        // nothing responds — exactly "on but doesn't work" with no visible
        // error anywhere. This was very likely the actual bug behind that
        // report. Every node access below is now wrapped so a single bad
        // node gets skipped instead of taking the whole service down, and
        // a Toast surfaces the failure on-screen (no adb/root needed to
        // see it — this device has neither available).
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "rootInActiveWindow threw", e)
            Toast.makeText(applicationContext, "LP ডায়াগনস্টিক: rootInActiveWindow crash — ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            null
        } ?: return emptyList()
        val results = mutableListOf<ScreenElement>()
        var counter = 0
        var skippedStaleNodes = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
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

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    walk(try { node.getChild(i) } catch (e: Exception) { null })
                }
            } catch (e: Exception) {
                // A single stale/recycled node — skip it, keep walking the
                // rest of the tree instead of crashing the whole service.
                skippedStaleNodes++
                Log.w(TAG, "Skipped a stale accessibility node during walk", e)
            }
        }

        walk(root)
        if (skippedStaleNodes > 0) {
            Log.w(TAG, "captureScreenElements: skipped $skippedStaleNodes stale node(s) this pass")
        }

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
        contextHistory: List<String> = emptyList(),
        onResult: (guidanceText: String, taskComplete: Boolean) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        retriesLeft: Int = 3
    ) {
        if (checkInFlight) return  // a check (bubble tap / auto-trigger / mic) is already running
        checkInFlight = true
        // Immediate feedback — shown the instant this is called, BEFORE the
        // network round-trip even starts. This is what actually fixes the
        // "no idea if anything is happening, feels broken" complaint: a
        // silent 2-4s wait now has visible on-screen proof something is
        // in progress the whole time.
        showThinking()

        val elements = captureScreenElements()

        // A screen that just opened (e.g. right after an Intent-based
        // open_app_settings jump, or mid-transition-animation) can briefly
        // have an empty/not-yet-populated accessibility tree. Firing the
        // request anyway used to send elements:[] with no image_base64,
        // which the Space correctly rejects with "HTTP 400: elements or
        // image_base64 is required" — that raw error is exactly what was
        // flashing intermittently. Instead: wait a beat for the new
        // screen to finish settling and try capturing again, rather than
        // firing a request that's guaranteed to fail.
        if (elements.isEmpty() && retriesLeft > 0) {
            checkInFlight = false
            mainHandler.postDelayed(
                { analyzeAndHighlight(userGoal, contextHistory, onResult, onError, retriesLeft - 1) },
                350
            )
            return
        }
        if (elements.isEmpty()) {
            // Retries exhausted — genuinely nothing readable on this
            // screen (e.g. a fullscreen video/game with no accessibility
            // nodes at all). Fail quietly and clearly instead of showing
            // a raw server error the user can't act on.
            checkInFlight = false
            clearHighlights()
            onError("এই স্ক্রিনে কিছু পড়া যাচ্ছে না — একটু অপেক্ষা করে আবার চেষ্টা করুন।")
            return
        }
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
            if (contextHistory.isNotEmpty()) {
                put("context_history", org.json.JSONArray(contextHistory))
            }
        }

        val baseUrl = getString(R.string.space_base_url)
        val guidanceSoFar = StringBuilder()

        scope.launch {
            var sawDone = false
            val result = ApiClient.streamAuthed(
                this@LenspilotAccessibilityService, baseUrl, "/api/analyze-screen", body.toString()
            ) { evt ->
                when (evt.optString("type")) {
                    "guidance_delta" -> {
                        // True incremental text as Gemini actually generates
                        // it (the server no longer forces JSON-mode output,
                        // which used to buffer the whole response before
                        // sending anything) — append and render immediately,
                        // word by word, exactly as it streams in.
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
                        mainHandler.post {
                            clearHighlights()
                            if (msg.contains("TOKEN_LIMIT")) {
                                Toast.makeText(
                                    applicationContext,
                                    "টোকেন শেষ — অ্যাপ খুলে বিজ্ঞাপন দেখে টোকেন নাও",
                                    Toast.LENGTH_LONG
                                ).show()
                                onError("টোকেন শেষ হয়ে গেছে")
                            } else {
                                onError(msg)
                            }
                        }
                    }
                }
            }
            if (!sawDone) {
                checkInFlight = false
                result.onFailure {
                    mainHandler.post {
                        clearHighlights()
                        val message = it.message ?: "analyze-screen request failed"
                        if (message.contains("TOKEN_LIMIT")) {
                            Toast.makeText(
                                applicationContext,
                                "টোকেন শেষ — অ্যাপ খুলে বিজ্ঞাপন দেখে টোকেন নাও",
                                Toast.LENGTH_LONG
                            ).show()
                            onError("টোকেন শেষ হয়ে গেছে")
                        } else {
                            onError(message)
                        }
                    }
                }
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
        // Compact log line into the on-device "RAM" context window — same
        // idea as the browser loop's ContextWindowStore.remember() call.
        // Kept short and generic on purpose: "what happened", not the
        // full guidance sentence, so a long task's log stays cheap to
        // resend as context_history on the next step.
        if (guidance.isNotBlank()) {
            ContextWindowStore.remember(this, workflowSessionId, guidance.take(160))
        }

        // Highlight box + the caption that explains it land together, in
        // one render call — per spec: "গ্লাস কার্ডের উপর লেখা উঠতেছে" AND
        // the highlight, shown as one unit, not the box alone.
        showHighlightsWithCaption(highlights, guidance)

        // NEW: direct app/settings/link opening via Intent — when the
        // model decides the next step needs a different app or a specific
        // settings screen (and it isn't already open), it says so instead
        // of trying to highlight a home-screen icon. Highlights are empty
        // in this case (server contract), so nothing above conflicts with
        // firing the intent right after the caption renders.
        val actionType = resultObj.optString("action_type", "highlight")
        val intentTarget = resultObj.optJSONObject("intent_target")
        if (actionType != "highlight" && intentTarget != null) {
            fireIntentAction(actionType, intentTarget)
        }

        // Settings -> "সবসময় ভয়েসে গাইডলাইন" — speak automatically without
        // needing the speaker button, if the user turned that on. During
        // an active Live Call, guidance is ALWAYS spoken regardless of
        // that setting (that's the whole point of a live call), and once
        // it finishes speaking, the mic auto-reopens for the user's next
        // spoken line — a hands-free back-and-forth, Gemini-Live style.
        if (guidance.isNotBlank() && (Prefs.autoSpeak(this) || isLiveCallActive)) {
            if (isLiveCallActive) {
                speakGuidance(guidance, onDone = {
                    mainHandler.post { if (isLiveCallActive) startOverlayLiveListening() }
                })
            } else {
                speakGuidance(guidance)
            }
        }

        // task_complete — set by the model itself, based on what it
        // actually sees on screen RIGHT NOW — is the ONLY thing that ends
        // a workflow. This used to be faked from a local step-counter
        // (workflow_step >= workflow_total against a fixed pre-planned
        // list), which meant the whole guidance session silently shut
        // itself off after N re-checks regardless of whether the real
        // task was done — that was the "stops after a few seconds" bug.
        val taskComplete = resultObj.optBoolean("task_complete", false)

        // new_goal — the model refined the goal this step (same idea as
        // the browser loop's edit_goal). Update our own goal, the
        // context-window's stored goal line, and give the same brief
        // "editing goal" indicator the browser shows, so chat mode gets
        // this too (per the spec: "এই সিমিত টোকেন এর মাধ্যমে নিজের লক্ষ্যে
        // অটল থাকবে... তখন লেখা উঠবে editing goal").
        val newGoal = if (resultObj.isNull("new_goal")) null else resultObj.optString("new_goal", null)
        if (!newGoal.isNullOrBlank() && workflowActive) {
            workflowGoal = newGoal
            WorkflowContext.start(newGoal)
            ContextWindowStore.updateGoal(this, workflowSessionId, newGoal)
            Toast.makeText(this, "🎯 লক্ষ্য ঠিক করছি...", Toast.LENGTH_SHORT).show()
        }

        if (taskComplete) {
            // "কাজ শেষ হলে অটো ডিলিট" — mirrors the manual-Stop path in
            // stopWorkflow(), just triggered by the model's own
            // task_complete=true instead of the user tapping Stop.
            ContextWindowStore.clearSession(this, workflowSessionId)
        }

        onResult(guidance, taskComplete)
    }

    /** Fires the Space-approved Intent for action_type "open_app" /
     * "open_settings" / "open_url" — see ANALYZE_SCREEN_INSTRUCTIONS on
     * the backend for what decides this. All three target shapes are
     * already whitelisted server-side (package-name regex, a fixed
     * android.settings.* action set, http(s)-only URLs), so this is a
     * second, cheap client-side check rather than the only line of
     * defense. Silently does nothing on failure — worst case, the next
     * manual trigger just falls back to normal highlight guidance on
     * whatever screen is actually showing. */
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
                    // A specific app's own App Info page (permissions,
                    // notifications, storage, battery) — e.g. "X-এর
                    // নোটিফিকেশন আসছে না" jumps straight here instead of
                    // walking through Settings -> Apps -> X step by step.
                    val pkg = target.optString("package", "")
                    if (pkg.isBlank()) return
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", pkg, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                "open_app_notification_settings" -> {
                    // One level MORE direct than "open_app_settings": lands
                    // straight on that app's own notification-channel page,
                    // skipping App Info entirely — e.g. "WhatsApp-এর
                    // নোটিফিকেশন বন্ধ করো" should never stop at App Info.
                    val pkg = target.optString("package", "")
                    if (pkg.isBlank()) return
                    val appInfo = try { packageManager.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }
                    val intent = Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                        putExtra("android.provider.extra.APP_PACKAGE", pkg)
                        if (appInfo != null) putExtra("app_uid", appInfo.uid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        // Older API levels without this action — fall back
                        // to App Info rather than doing nothing.
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", pkg, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                "open_url" -> {
                    val url = target.optString("url", "")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Intent action failed: $actionType", e)
        }
    }

    // ------------------------------------------------------------------
    // Workflow run loop — re-analyzes the current screen against the
    // overall goal every time it's triggered (bubble tap, genuine screen
    // change, or mic clarification). Ends only on task_complete=true or
    // manual Stop — never on "ran out of pre-planned steps".
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
        lastSeenWindowKey = null
        checkInFlight = false
        WorkflowContext.start(goal)
        workflowSessionId = UUID.randomUUID().toString().replace("-", "")
        ContextWindowStore.startSession(this, workflowSessionId, goal)
        showControlBar()
        showTriggerBubble()
        triggerWorkflowStep()
    }

    fun stopWorkflow() {
        workflowActive = false
        workflowGoal = ""
        WorkflowContext.stop()
        workflowOutline = emptyList()
        onWorkflowUpdate = null
        onWorkflowError = null
        checkInFlight = false
        mainHandler.removeCallbacks(screenChangeRunnable)
        if (overlayListening) stopOverlayVoiceCommand(cancelled = true)
        LocalTts.stop()
        clearHighlights()
        hideControlBar()
        hideTriggerBubble()
        // "কাজ শেষ হলে অটো ডিলিট" — task ended (manual Stop here; the
        // task_complete=true path clears it in applyAnalyzeScreenResult
        // instead, right before this same stopWorkflow teardown runs).
        ContextWindowStore.clearSession(this, workflowSessionId)
    }

    /**
     * Guidance narration. Pure on-device TTS only — no cloud round-trip —
     * for maximum speed. LocalTts's own internal retry (busy-engine reset
     * + one fresh attempt) is the only recovery path now; if it still
     * fails, guidance stays silent for that one utterance rather than
     * waiting on a network call.
     */
    private fun speakGuidance(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        LocalTts.speak(applicationContext, text, onDone = onDone)
    }

    // ------------------------------------------------------------------
    // Control-bar speak-button loading spin. On-device TTS is fast most
    // of the time but still has a real, variable delay (cold engine init,
    // an OEM background-throttled engine process waking back up) — a
    // continuous rotation of the SAME 38dp icon (no size/shape change,
    // per spec) starts the instant the button is tapped and stops the
    // instant LocalTts's onStart fires (audio actually begins) or the
    // request finishes/fails, so the wait always shows visible progress
    // instead of looking like the tap did nothing. Tapping again while it
    // is already spinning is intentionally a no-op — the still-spinning
    // icon IS the "yes, this is working" feedback — rather than queuing
    // a second overlapping utterance.
    // ------------------------------------------------------------------
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

    /**
     * Entry point for the Quick Settings tile ("phone-এর উপরের বারে
     * শর্টকাট" — pull down the shade, tap the Lenspilot tile). Runs
     * entirely inside this always-alive service, no MainActivity/UI
     * needed: reads whatever screen is CURRENTLY in the foreground (via
     * the same accessibility-tree analysis the normal workflow loop
     * uses) and asks the model to look for a problem on it. If it finds
     * one, guidance + highlights start right away (same as any other
     * running workflow — Stop always available on the control bar). If
     * it doesn't see anything clearly wrong, the model just asks the
     * user what they want, spoken aloud, and the loop stays open so the
     * next thing they say (via the control bar's mic) continues it.
     */
    fun quickScanCurrentScreen() {
        val diagnosticGoal =
            "ইউজার এইমাত্র কুইক-সেটিংস শর্টকাট থেকে সরাসরি স্ক্রিন স্ক্যান করতে বলেছে, " +
                "কোনো লেখা টাইপ করেনি। বর্তমান স্ক্রিনে সুস্পষ্ট কোনো সমস্যা বা সমাধানযোগ্য " +
                "বিষয় দেখতে পেলে সেটার সমাধানের গাইডেন্স দাও (হাইলাইট করে দেখাও কী চাপতে হবে)। " +
                "স্ক্রিনে সমস্যা স্পষ্ট না হলে, কোনো কিছু অনুমান করে বানিয়ে বোলো না — বরং ইউজারকে " +
                "জিজ্ঞেস করো ঠিক কী সমস্যা বা কী করতে চাও।"
        startWorkflow(
            goal = diagnosticGoal,
            planOutline = emptyList(),
            onUpdate = { guidance, _ ->
                if (guidance.isNotBlank()) speakGuidance(guidance)
            },
            onError = { msg ->
                speakGuidance("স্ক্যান করা যায়নি: $msg")
            }
        )
    }

    /**
     * Re-analyzes the CURRENT screen against the overall goal (NOT a fixed
     * step index) and shows the result. The pre-planned outline from
     * /api/workflow/plan is sent along only as loose CONTEXT — a rough
     * itinerary the model can reference, never a countdown that ends the
     * session on its own. What's already happened this session comes
     * from the on-device ContextWindowStore (a few compact recent lines,
     * not the full log) so consecutive checks stay consistent instead of
     * contradicting themselves call to call.
     *
     * The ONLY thing that ends a workflow is the model reporting
     * task_complete=true for what it currently sees on screen, or the
     * user tapping Stop — never "ran out of pre-planned steps". Speech is
     * never triggered automatically here — narration only happens when
     * the user taps the speaker button, unless Settings' "always speak
     * guidance" is on (see MainActivity/Prefs.autoSpeak).
     */
    fun triggerWorkflowStep(extraContext: String? = null) {
        if (!workflowActive) return
        // The user just answered something (control-bar voice mic or the
        // ✓/× shortcut) — bump WorkflowContext so Lenspilot Keyboard,
        // if it's sitting in a "waiting for you to answer" state for an
        // auto-type field, knows to retry now that context has changed.
        if (!extraContext.isNullOrBlank()) {
            WorkflowContext.noteUserAnswered()
            // BUGFIX: this used to be injected into the goal text for
            // THIS one request only — if the model didn't fold it into
            // new_goal right away, the correction vanished on the very
            // next automatic re-check ("বললাম একটা, করলো আরেকটা"). Now it
            // also lands in the on-device context window, so it stays
            // visible in recentContext across every following step until
            // the session ends, exactly like the AI's own guidance lines.
            ContextWindowStore.remember(this, workflowSessionId, "ইউজার বলেছে: $extraContext")
        }
        val parts = mutableListOf("মূল লক্ষ্য: $workflowGoal")
        if (workflowOutline.isNotEmpty()) {
            parts.add("সম্ভাব্য ধাপগুলো (শুধু ধারণা দেওয়ার জন্য, কড়াকড়ি স্ক্রিপ্ট না — স্ক্রিনে যা সত্যিই দেখছো সেটাই আসল): " +
                workflowOutline.joinToString(" | "))
        }
        if (!extraContext.isNullOrBlank()) {
            parts.add("ইউজার এইমাত্র বলেছে: $extraContext")
        }
        val goal = parts.joinToString("\n")
        // What's already happened this session comes from the on-device
        // "RAM" context window now (ContextWindowStore), not folded into
        // the goal text as a single line — same compact/cheap shape as
        // before ("তুমি ঠিক আগেই বলেছিলে..."), just backed by the shared
        // store so it also works for a long multi-step task (e.g. "reply
        // to my 1000 Facebook comments") and is visible in Settings.
        val recentContext = ContextWindowStore.recentForPrompt(this, workflowSessionId)
        analyzeAndHighlight(
            userGoal = goal,
            contextHistory = recentContext,
            onResult = { guidance, taskComplete ->
                onWorkflowUpdate?.invoke(guidance, taskComplete)
                if (taskComplete && workflowActive) {
                    workflowActive = false
                    WorkflowContext.stop()
                    // Same full visual teardown as the manual Stop button.
                    // clearHighlights() was missing here — that's the
                    // "control icon goes away but the last caption stays
                    // stuck on screen" bug. (Not calling LocalTts.stop()
                    // here on purpose: if autoSpeak is on, the completion
                    // guidance — e.g. "you're already logged in" — has
                    // already started playing by this point, and cutting
                    // it off mid-sentence would just trade one bug for
                    // another; it finishes on its own with nothing new
                    // queued after, since workflowActive is now false.)
                    clearHighlights()
                    hideControlBar()
                    hideTriggerBubble()
                }
            },
            onError = { msg -> onWorkflowError?.invoke(msg) }
        )
    }

    // ------------------------------------------------------------------
    // Message input glass card — replaces the old ✓/× shortcut. Instead
    // of only being able to answer yes/no, the user can type ANY reply to
    // an AI question, or hand it a brand-new instruction mid-workflow,
    // right from the control bar. Goes through the exact same
    // [triggerWorkflowStep] extraContext path the old ✓/× buttons and the
    // mic already use, so it folds straight into the CURRENT workflow —
    // it never stops the running task or starts a new one on its own.
    // ------------------------------------------------------------------

    private fun toggleMessageInput() {
        if (messageInputView != null) hideMessageInput() else showMessageInput()
    }

    private fun showMessageInput() {
        val wm = windowManager ?: return
        val barView = controlBarView ?: return
        val barParams = controlBarParams ?: return
        if (messageInputView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_message_input, null)
        val input = view.findViewById<EditText>(R.id.overlayMsgInput)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Deliberately NOT FLAG_NOT_FOCUSABLE — unlike the control bar,
            // this card needs to actually take keyboard focus so the user
            // can type into it.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = barParams.x
            y = barParams.y + (barView.height.takeIf { it > 0 } ?: (56 * resources.displayMetrics.density).toInt()) +
                (8 * resources.displayMetrics.density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        messageInputView = view

        fun send() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            input.setText("")
            hideMessageInput()
            // Same path a spoken/✓ answer used to take — folds into
            // whichever workflow is currently running.
            if (workflowActive && !checkInFlight) triggerWorkflowStep(extraContext = text)
        }

        view.findViewById<ImageButton>(R.id.overlayMsgSendButton).setOnClickListener { send() }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideMessageInput() {
        messageInputView?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
            runCatching { windowManager?.removeView(it) }
            messageInputView = null
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
            // ROOT-CAUSE FIX (same crash class as captureScreenElements'
            // "DIAGNOSTIC HARDENING" above): this addView was completely
            // unguarded. ensureOverlayView() runs on EVERY single workflow
            // step (via showThinking(), the very first thing analyzeAndHighlight
            // does) so any WindowManager rejection here — a stale/duplicate
            // TYPE_ACCESSIBILITY_OVERLAY token, an OEM restricting overlay
            // windows for background-bound services, the window manager not
            // yet ready microseconds after onServiceConnected — took down the
            // WHOLE service process on the very first screen check. Settings
            // still shows Accessibility as ON, instance still looks fine
            // right up until this call, and then everything silently stops:
            // exactly "permission on but not working" with zero visible
            // cause. Now: catch it, surface it, and leave highlightView null
            // so the NEXT trigger (bubble tap / auto re-check) retries
            // instead of the service being gone for good.
            val added = runCatching { wm.addView(view, params) }
            if (added.isFailure) {
                Log.w(TAG, "ensureOverlayView: wm.addView failed", added.exceptionOrNull())
                Toast.makeText(
                    applicationContext,
                    "LP ডায়াগনস্টিক: ওভারলে দেখানো যায়নি — ${added.exceptionOrNull()?.javaClass?.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
                return null
            }
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
        repositionControlBarIfBlocked(highlights)
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
    // LocalTts/VoiceRecorder/ApiClient, no MainActivity involved.
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
        view.findViewById<ImageButton>(R.id.overlayMicButton).setOnClickListener {
            toggleOverlayRecording()
        }
        view.findViewById<ImageButton>(R.id.overlayStopButton).setOnClickListener {
            stopWorkflow()
        }
        view.findViewById<ImageButton>(R.id.overlayMessageButton).setOnClickListener {
            toggleMessageInput()
        }

        // TOP|START (not CENTER_HORIZONTAL) so x/y both mean exact pixel
        // offsets — needed for dragging to behave predictably. Started
        // centered anyway via the post{} below, once the pill's real
        // width is known after layout.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            // Clear the status bar itself (not just a fixed 6dp, which sat
            // right underneath the status-bar icons and got clipped/hidden
            // on many devices) plus a little breathing room.
            y = statusBarHeightPx() + (8 * resources.displayMetrics.density).toInt()
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        controlBarView = view
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

    /** Real status-bar height for this device, falling back to a sane
     * default (24dp) if the system resource isn't found for some reason —
     * needed so the control bar sits fully BELOW the status bar instead of
     * being partly drawn underneath/behind it (invisible in practice). */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun hideControlBar() {
        if (isLiveCallActive) toggleLiveCall()
        hideMessageInput()
        speakButtonLoading = false // view is going away — no dangling spin state
        controlBarView?.let {
            runCatching { windowManager?.removeView(it) }
            controlBarView = null
        }
        controlBarParams = null
        controlBarUserMoved = false
    }

    /**
     * Live Call — Gemini-Live-style hands-free loop, layered on top of the
     * exact same one-shot voice mechanism the mic button already uses:
     * listen once -> [triggerWorkflowStep] with the recognized text as
     * extraContext -> guidance comes back and is ALWAYS spoken (see the
     * autoSpeak/isLiveCallActive check in applyAnalyzeScreenResult) ->
     * the instant that speech finishes, listening restarts automatically.
     * Tapping the button again (or Stop) breaks the loop.
     */
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

    /** Same live SpeechRecognizer approach as [toggleOverlayRecording],
     * kept as its own method (rather than reusing that one directly)
     * because its result path needs to keep the live-call loop going —
     * relisten after each reply — instead of stopping after one exchange
     * the way the plain mic button does. */
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
                    // Nothing caught this round — try listening again
                    // shortly rather than silently ending the call.
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

    /** Same live speech-to-text as the voice-first ball (android.speech.
     * SpeechRecognizer — text appears as you talk, not a record-full-clip-
     * then-upload round trip) so the control-bar mic behaves consistently
     * everywhere in the app and responds as fast as the OS voice-typing
     * shortcut does. */
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

            override fun onError(error: Int) {
                overlayListening = false
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT just means nothing
                // was caught — not worth surfacing, the user can just tap
                // the mic again.
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

    private fun stopOverlayVoiceCommand(cancelled: Boolean) {
        if (cancelled) overlaySpeechRecognizer?.cancel() else overlaySpeechRecognizer?.stopListening()
        overlayListening = false
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
        // Same unguarded-addView crash class fixed in ensureOverlayView()
        // above — this bubble is added right at the start of every
        // startWorkflow() call, so a rejected addView here used to kill
        // the whole service before the first screen was ever even read.
        val added = runCatching { wm.addView(view, params) }
        if (added.isFailure) {
            Log.w(TAG, "showTriggerBubble: wm.addView failed", added.exceptionOrNull())
            Toast.makeText(
                applicationContext,
                "LP ডায়াগনস্টিক: বাবল দেখানো যায়নি — ${added.exceptionOrNull()?.javaClass?.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        bubbleView = view
    }

    private fun hideTriggerBubble() {
        bubbleView?.let {
            runCatching { windowManager?.removeView(it) }
            bubbleView = null
        }
    }
}
