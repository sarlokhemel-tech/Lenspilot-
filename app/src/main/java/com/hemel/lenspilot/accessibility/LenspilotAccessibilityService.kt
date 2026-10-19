package com.hemel.lenspilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.Rect
import android.util.Base64
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
import com.hemel.lenspilot.ImagePickTrampolineActivity
import com.hemel.lenspilot.PendingImagePick
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.vision.ScreenshotProcessor
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.audio.RemoteTts
import com.hemel.lenspilot.guide.GuideHost
import com.hemel.lenspilot.guide.GuideLoopV3
import com.hemel.lenspilot.userinfo.UserInfoEntry
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.overlay.Highlight
import com.hemel.lenspilot.overlay.HighlightOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // ---- Guide v3 -----------------------------------------------------
    // ধাপ/প্ল্যান/workflow-অবস্থা নেই — সব সিদ্ধান্ত ও স্মৃতি GuideLoopV3-এ (ছোট ইতিহাস + note)।
    // এই সার্ভিস শুধু "হাত-পা": স্ক্রিন পড়া, স্ক্রিনশট, হাইলাইট ওভারলে, ভয়েস, স্ক্রিন-বদলের ইভেন্ট।
    private var onWorkflowUpdate: ((guidance: String, done: Boolean) -> Unit)? = null
    private var onWorkflowError: ((String) -> Unit)? = null
    private var lastAutoTriggerAt = 0L
    private var lastNoticeText: String = ""
    private var lastNoticeAt = 0L
    private val screenReader by lazy { ScreenReader(this) }

    private val guideHost = object : GuideHost {
        override val context: Context get() = this@LenspilotAccessibilityService
        override val scope: CoroutineScope get() = this@LenspilotAccessibilityService.scope
        override val hasScreenEvents: Boolean get() = true
        override val imageFirst: Boolean get() = Prefs.isVisionPrimary(this@LenspilotAccessibilityService)
        override fun readTree(): ScreenReader.Snapshot? = try { captureSnapshot() } catch (e: Exception) { null }
        override fun fingerprintNow(): Int = screenFingerprint()
        override fun captureShot(boost: Boolean, masks: List<Rect>, cb: (ScreenshotProcessor.Result?) -> Unit) =
            captureScreenshotResult(boost = boost, masks = masks, cb = cb)
        override fun sensitiveRects(): List<Rect> = try { captureSnapshot().sensitive } catch (e: Exception) { emptyList() }
        override fun showThinking() = this@LenspilotAccessibilityService.showThinking()
        override fun showCard(highlights: List<Highlight>, caption: String) = showHighlightsWithCaption(highlights, caption)
        override fun clearHighlights() = this@LenspilotAccessibilityService.clearHighlights()
        override fun deliverSay(say: String) {
            if (say.isBlank()) return
            // Settings -> "সবসময় ভয়েসে গাইডলাইন"; লাইভ-কলে সবসময় পড়ে, শেষে মাইক আবার খোলে
            if (Prefs.autoSpeak(this@LenspilotAccessibilityService) || isLiveCallActive) {
                if (isLiveCallActive) {
                    speakGuidance(say, onDone = { mainHandler.post { if (isLiveCallActive) startOverlayLiveListening() } })
                } else {
                    speakGuidance(say)
                }
            }
        }
        override fun onUpdate(say: String, done: Boolean) { onWorkflowUpdate?.invoke(say, done) }
        override fun onError(msg: String) { onWorkflowError?.invoke(msg) }
        override fun finishUi() {
            this@LenspilotAccessibilityService.clearHighlights()
            hideControlBar()
            hideTriggerBubble()
        }
    }
    private val guideLoop by lazy { GuideLoopV3(guideHost) }
    private val workflowActive: Boolean get() = guideLoop.active

    // স্ক্রিন বদলালে অটো-কল (১৫০ms ডিবাউন্স + থিতু-হওয়া অপেক্ষা); ছাপ না বদলালে লুপ নিজেই নতুন কল করে না।
    private val screenChangeRunnable = Runnable {
        if (!workflowActive && screenChangeListener == null) return@Runnable
        waitForScreenSettledThen {
            lastAutoTriggerAt = android.os.SystemClock.elapsedRealtime()
            if (workflowActive) guideLoop.onScreenChanged() else screenChangeListener?.invoke()
        }
    }

    private fun screenFingerprint(): Int = try {
        screenReader.read().fingerprint
    } catch (e: Exception) { 0 }

    // স্ক্রিনের element-ছাপ পরপর দুবার এক হলেই (বা সর্বোচ্চ ~১.৪ সে পরে) এগোও। এটা সিদ্ধান্ত নয়, শুধু অপেক্ষার কৌশল।
    private fun waitForScreenSettledThen(action: () -> Unit) {
        var last = screenFingerprint()
        var stableTicks = 0
        var elapsed = 0
        val tick = object : Runnable {
            override fun run() {
                if (!workflowActive && screenChangeListener == null) return
                val now = screenFingerprint()
                if (now == last && now != 0) stableTicks++ else { stableTicks = 0; last = now }
                elapsed += 120
                if (stableTicks >= 1 || elapsed >= 1400) action() else mainHandler.postDelayed(this, 120)
            }
        }
        mainHandler.postDelayed(tick, 120)
    }

    companion object {
        private const val TAG = "LenspilotA11y"
        private const val CHANNEL_ID = "lenspilot_a11y_session"
        private const val NOTIFICATION_ID = 4300
        /** Vision সার্ভিস (FallbackGuideService) Accessibility ইভেন্ট থেকে স্ক্রিন-বদলের অটো-কল পেতে এটা বসায়। */
        @Volatile
        var screenChangeListener: (() -> Unit)? = null

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
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return // our own overlay updates

        // টোস্ট / স্ন্যাকবার-জাতীয় ক্ষণস্থায়ী নোটিশ — নোড-ট্রিতে থাকে না, তাই ইভেন্ট থেকেই ধরা।
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.className?.toString()?.contains("Toast") == true) {
            val t = event.text?.joinToString(" ")?.trim().orEmpty()
            if (t.isNotEmpty()) { lastNoticeText = t; lastNoticeAt = android.os.SystemClock.elapsedRealtime() }
            return
        }
        if (!workflowActive && screenChangeListener == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (pkg == packageName) return
        // স্ট্যাটাসবারের ঘড়ি/ব্যাটারি ইত্যাদির content-change শুধু শব্দ
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg == "com.android.systemui") return

        val isContentOnly = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val now = android.os.SystemClock.elapsedRealtime()
        // শুধু content বদলের জন্য: ধীরে (থিতু হলে) এবং ২.৫ সে-র বেশি ঘন ঘন নয় (ভিডিও/অ্যানিমেশনে বন্যা ঠেকাতে)।
        if (isContentOnly && now - lastAutoTriggerAt < 2500L) return
        mainHandler.removeCallbacks(screenChangeRunnable)
        mainHandler.postDelayed(screenChangeRunnable, if (isContentOnly) 700L else 150L)
    }

    // ------------------------------------------------------------------
    // TIER 1: walk the accessibility tree of the currently active window
    // ------------------------------------------------------------------

    /** Guidance reader: clickable + popup/notice text, compact (see ScreenReader). */
    fun captureSnapshot(): ScreenReader.Snapshot {
        val fresh = android.os.SystemClock.elapsedRealtime() - lastNoticeAt < 8000L
        return screenReader.read(if (fresh) lastNoticeText else null)
    }

    fun captureScreenElements(): List<ScreenElement> = try { captureSnapshot().elements } catch (e: Exception) {
        Log.w(TAG, "captureScreenElements failed", e); emptyList()
    }

    /** Old full walk — every text node. Only for translate / quick-scan, which need ALL on-screen text. */
    fun captureAllTextElements(): List<ScreenElement> {
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

    /**
     * Vision-primary-র স্ক্রিনশট: নিজের ওভারলে (হাইলাইট, ট্রিগার বাবল, কন্ট্রোল বার) এক মুহূর্ত লুকিয়ে —
     * নইলে AI নিজের আঁকা বক্সকেই স্ক্রিনের অংশ ভাবত — তারপর ছবি নিয়ে [ScreenshotProcessor]-এ JPG/সাদাকালো/
     * ছোট করে। Android প্রতি ~১ সেকেন্ডে একটার বেশি takeScreenshot দেয় না; তাই "খুব তাড়াতাড়ি" হলে একবার
     * থেমে আবার চেষ্টা করে। না পেলে cb(null) → কলার tree-পথে নামে।
     */
    /** নিজের কন্ট্রোল-বার/বাবল স্ক্রিনশটে কালো করার জায়গা — লুকিয়ে ঝলক না দিয়ে মাস্ক (বার "নাই হয়ে আবার আসা" বাগ ঠেকাতে)। */
    private fun ownOverlayMasks(): List<Rect> = listOfNotNull(controlBarView, bubbleView).mapNotNull { v ->
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) null else {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }

    private fun captureScreenshotResult(
        retryOnRateLimit: Boolean = true,
        boost: Boolean = false,
        masks: List<Rect> = emptyList(),
        cb: (ScreenshotProcessor.Result?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        val hidden = mutableListOf<Pair<View, Int>>()
        val allMasks = masks + ownOverlayMasks()
        for (v in listOfNotNull(highlightView, messageInputView)) {   // কন্ট্রোল-বার/বাবল লুকাই না — মাস্ক করি
            hidden.add(v to v.visibility)
            v.visibility = View.INVISIBLE
        }
        fun restore() { hidden.forEach { (v, vis) -> v.visibility = vis } }
        mainHandler.postDelayed({
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            restore()
                            val soft = try {
                                val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) { null } finally {
                                runCatching { result.hardwareBuffer.close() }
                            }
                            if (soft == null) { cb(null); return }
                            scope.launch(Dispatchers.Default) {
                                val r = runCatching { ScreenshotProcessor.process(applicationContext, soft, boost, allMasks) }.getOrNull()
                                runCatching { soft.recycle() }
                                mainHandler.post { cb(r) }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            restore()
                            if (retryOnRateLimit && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                mainHandler.postDelayed({ captureScreenshotResult(false, boost, masks, cb) }, 1100)
                            } else cb(null)
                        }
                    }
                )
            } catch (e: Exception) {
                restore()
                cb(null)
            }
        }, 90)
    }


    // ------------------------------------------------------------------
    // Guide v3 লুপ — লক্ষ্য + ছোট ইতিহাস + এখনকার স্ক্রিন; AI প্রতিবার ঠিক করে এখন কী করতে হবে (GuideLoopV3)।
    // থামে শুধু AI "done" বললে অথবা ইউজার Stop চাপলে।
    // ------------------------------------------------------------------

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
        showControlBar()
        showTriggerBubble()
        guideLoop.start(goal, system, extraUserInfo)
    }

    // ---- OTP কপি-গাইড (ব্রাউজার মোড ↔ গাইডলাইন) ----------------------------------
    private var otpCopyMode = false
    private var otpClipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    /** ব্রাউজার-এজেন্টের OTP ধাপে: গাইড-লুপ চালু + কন্ট্রোল বারে "কপি হয়েছে"। */
    fun startOtpCopyGuide(goal: String, system: String) {
        otpCopyMode = true
        com.hemel.lenspilot.browser.OtpBridge.assistActive = true
        if (controlBarView != null) hideControlBar()     // নতুন করে বানাই — বাটনসহ
        startWorkflow(goal, system, emptyList(), onUpdate = { _, _ -> }, onError = { msg ->
            android.widget.Toast.makeText(this, "গাইড চালানো গেল না: $msg — কোড কপি করে Lenspilot-এ ফিরে এসো", android.widget.Toast.LENGTH_LONG).show()
        })
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val l = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                // কপি হলে বাটন উজ্জ্বল — "এবার এটা চাপো"
                mainHandler.post { controlBarView?.findViewById<View>(R.id.overlayCopiedButton)?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(250)?.start() }
            }
            cm.addPrimaryClipChangedListener(l); otpClipListener = l
        } catch (e: Exception) { }
    }

    fun stopOtpCopyGuide() {
        if (!otpCopyMode) return
        endOtpMode()
        stopWorkflow()
    }

    private fun endOtpMode() {
        otpCopyMode = false
        com.hemel.lenspilot.browser.OtpBridge.assistActive = false
        try {
            otpClipListener?.let { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).removePrimaryClipChangedListener(it) }
        } catch (e: Exception) { }
        otpClipListener = null
    }

    /** "কপি হয়েছে" চাপা: ফ্ল্যাগ রেখে গাইড বন্ধ, একই ব্রাউজার-অ্যাক্টিভিটি (সেশন সহ) সামনে আনা। */
    private fun onOtpCopiedTapped() {
        com.hemel.lenspilot.browser.OtpBridge.copiedFlag = true
        stopOtpCopyGuide()
        val i = Intent(this, com.hemel.lenspilot.browser.AiBrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(com.hemel.lenspilot.browser.AiBrowserActivity.EXTRA_OTP_COPIED, true)
        try { startActivity(i) } catch (e: Exception) {
            try {
                android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE).send()
            } catch (e2: Exception) {
                android.widget.Toast.makeText(this, "Lenspilot অ্যাপ খুলে ফিরে এসো — কোড নিজেই বসবে", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stopWorkflow() {
        if (otpCopyMode) endOtpMode()
        guideLoop.stop()
        onWorkflowUpdate = null
        onWorkflowError = null
        mainHandler.removeCallbacks(screenChangeRunnable)
        if (overlayListening) stopOverlayVoiceCommand(cancelled = true)
        LocalTts.stop()
        RemoteTts.stop()
        clearHighlights()
        hideControlBar()
        hideTriggerBubble()
    }

    /**
     * Guidance narration. Tries [RemoteTts] first (Edge TTS by default,
     * admin-switchable to Gemini TTS — see /api/tts + call_learning_tts()
     * in app.py) for a consistent voice across every device, since the
     * on-device engine's quality/reliability depends entirely on whatever
     * voice pack happens to be installed on THAT phone (see LocalTts's own
     * file doc for the history of chasing that). Falls back to on-device
     * [LocalTts] the moment RemoteTts reports any error (no network,
     * upstream failure, playback issue) so a network hiccup never leaves
     * guidance completely silent — LocalTts's own internal retry (busy-
     * engine reset + one fresh attempt) is still the last line of defense
     * after that.
     */
    private fun speakGuidance(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        val baseUrl = getString(R.string.space_base_url)
        RemoteTts.speak(
            applicationContext, baseUrl, text,
            onDone = onDone,
            onError = { LocalTts.speak(applicationContext, text, onDone = onDone) }
        )
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

    val isWorkflowActive: Boolean get() = guideLoop.active

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
            onUpdate = { guidance, _ ->
                if (guidance.isNotBlank()) speakGuidance(guidance)
            },
            onError = { msg ->
                speakGuidance("স্ক্যান করা যায়নি: $msg")
            }
        )
    }

    /**
     * Quick Settings tile's OTHER mode — used INSTEAD of
     * quickScanCurrentScreen() when Prefs.translateEnabled is on (see
     * QuickScanTrampolineActivity, which branches between the two).
     * Deliberately bypasses the whole workflow/analyze-screen guidance
     * loop: this just reads every text label currently on screen (via
     * the same accessibility-tree walk captureScreenElements() already
     * uses, so no extra permission and no MediaProjection screenshot
     * needed — node.text/contentDescription IS the real on-screen text,
     * far more reliable for Bengali than on-device OCR, see TextOcr's
     * kdoc), sends it to /api/translate as plain text, and speaks +
     * toasts the result. One-shot — no highlights, no workflow state.
     */
    fun translateCurrentScreen() {
        val labels = captureAllTextElements()
            .map { it.label }
            .filter { it.isNotBlank() }
            .distinct()
        val screenText = labels.joinToString("\n").take(4000)
        if (screenText.isBlank()) {
            speakGuidance("স্ক্রিনে অনুবাদ করার মতো কোনো লেখা পাইনি।")
            return
        }
        val targetLang = Prefs.translateTargetLang(applicationContext)
        val baseUrl = getString(R.string.space_base_url)
        val body = JSONObject().apply {
            put("text", screenText)
            put("target_lang", targetLang)
        }.toString()

        scope.launch {
            var translated: String? = null
            val result = ApiClient.streamAuthed(applicationContext, baseUrl, "/api/translate", body) { event ->
                when (event.optString("type")) {
                    "done" -> translated = event.optString("translated_text")
                    "error" -> Log.w(TAG, "translate error: ${event.optString("error")}")
                }
            }
            val text = translated?.trim()
            if (result.isFailure || text.isNullOrBlank()) {
                speakGuidance("স্ক্রিনের লেখা অনুবাদ করা যায়নি।")
                return@launch
            }
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            speakGuidance(text)
        }
    }

    /**
     * ট্রিগার বাবল / মাইক / মেসেজ-বক্স — ইউজার নিজে এখনকার স্ক্রিন দেখিয়ে "এরপর কী?" জিজ্ঞেস করছে।
     * [extraContext] = ইউজার এইমাত্র যা বলেছে/লিখেছে (লুপের ইতিহাসেও থেকে যায়), [extraImageBase64] = তার সংযুক্ত ছবি।
     */
    fun triggerWorkflowStep(extraContext: String? = null, extraImageBase64: String? = null) {
        if (!guideLoop.active) return
        guideLoop.trigger(userSaid = extraContext, extraImage = extraImageBase64, manual = true)
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
        val previewRow = view.findViewById<View>(R.id.overlayMsgImagePreviewRow)
        val previewImage = view.findViewById<ImageView>(R.id.overlayMsgImagePreview)
        var pendingImageBase64: String? = null

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // ROOT-CAUSE FIX for "কিবোর্ড চালু হয় না": this used to be
            // TYPE_ACCESSIBILITY_OVERLAY, same as the pass-through
            // highlight overlay. That type is meant for
            // draw-only/pass-through accessibility visuals — on most
            // OEM builds WindowManagerService simply never routes IME
            // focus to it, no matter what flags/softInputMode are set,
            // so requestFocus()+showSoftInput() below silently did
            // nothing. FallbackGuideService's identical card already
            // used TYPE_APPLICATION_OVERLAY and never had this problem
            // (see that file) — SYSTEM_ALERT_WINDOW is already required
            // and checked in MainActivity before any workflow can start,
            // so it's safe to use here too, just for this one focusable
            // input card (the control bar / highlight overlay stay on
            // TYPE_ACCESSIBILITY_OVERLAY, unaffected).
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            // ADJUST_RESIZE does nothing useful for a WRAP_CONTENT floating
            // window (nothing to resize), and on some OEMs it's exactly
            // what suppressed the IME from ever being requested. PAN +
            // forcing the IME visible the moment this window gets focus
            // is the reliable combination for a small floating input card.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        messageInputView = view

        fun send() {
            val text = input.text?.toString()?.trim().orEmpty()
            val image = pendingImageBase64
            if (text.isEmpty() && image == null) return
            input.setText("")
            hideMessageInput()
            // Same path a spoken/✓ answer used to take — folds into
            // whichever workflow is currently running.
            if (workflowActive) {
                triggerWorkflowStep(
                    extraContext = text.ifBlank { "(ছবি পাঠিয়েছে, লেখেনি — ছবিটা দেখে বুঝে নাও)" },
                    extraImageBase64 = image
                )
            }
        }

        // × — closes the card outright, with nothing sent, whether or not
        // the user has typed/attached anything. This is the fix for "খুলে
        // গেলে বন্ধ করার উপায় নেই যদি লিখতে না চাই".
        view.findViewById<ImageButton>(R.id.overlayMsgCloseButton).setOnClickListener { hideMessageInput() }

        // 📎 — hands off to the invisible trampoline Activity (a Service
        // can't launch a picker or get its result directly, see
        // PendingImagePick) to pick a photo from the gallery, then shows a
        // small thumbnail chip above the input so the user can see/remove
        // it before sending.
        view.findViewById<ImageButton>(R.id.overlayMsgAttachButton).setOnClickListener {
            PendingImagePick.awaitNext { base64 ->
                mainHandler.post {
                    if (messageInputView !== view) return@post  // card closed meanwhile
                    if (base64 == null) return@post
                    pendingImageBase64 = base64
                    try {
                        val bytes = Base64.decode(base64, Base64.NO_WRAP)
                        previewImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        previewRow.visibility = View.VISIBLE
                    } catch (e: Exception) { /* ignore a bad/undecodable pick */ }
                }
            }
            startActivity(
                Intent(this, ImagePickTrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        view.findViewById<ImageButton>(R.id.overlayMsgImageRemoveButton).setOnClickListener {
            pendingImageBase64 = null
            previewRow.visibility = View.GONE
        }

        view.findViewById<ImageButton>(R.id.overlayMsgSendButton).setOnClickListener { send() }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
        requestKeyboardFor(input)
    }

    /** Belt-and-braces keyboard trigger for a just-added focusable
     * overlay window: input.post{} alone races the window actually
     * receiving focus from WindowManagerService, so on slower devices
     * showSoftInput() could fire before the window is focused and get
     * silently dropped. Also listening for the window-focus-gained
     * callback and re-requesting there catches that case without
     * duplicating the call once focus is already in (SHOW_IMPLICIT is a
     * no-op if the keyboard is already up). */
    private fun requestKeyboardFor(input: EditText) {
        fun show() {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        show()
        input.post { show() }
        input.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) {
                    show()
                    input.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                }
            }
        })
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
        view.findViewById<ImageButton>(R.id.overlayMicButton).setOnClickListener {
            toggleOverlayRecording()
        }
        view.findViewById<View>(R.id.overlayCopiedButton).let { cb ->
            cb.visibility = if (otpCopyMode) View.VISIBLE else View.GONE
            cb.setOnClickListener { onOtpCopiedTapped() }
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

    /** ELA 4N only — see overlay_control_bar.xml's overlaySupervisorContainer
     * comment. Called from analyzeAndHighlight's "status" SSE handling
     * (only when that step's `system == "ela_4n"`) with the same
     * stage/text app.py's `_supervise` loop already sends for chat
     * (stage: "supervising"/"searching"/"fixing", or null to hide it
     * again once the step is done — see the "done"/"error" cases below).
     * Every other system never calls this, so the container just stays
     * the "gone" the layout starts it as. */
    private fun setSupervisorState(stage: String?, text: String?) {
        val bar = controlBarView ?: return
        val container = bar.findViewById<View>(R.id.overlaySupervisorContainer) ?: return
        val icon = bar.findViewById<ImageView>(R.id.overlaySupervisorIcon) ?: return
        val bubble = bar.findViewById<TextView>(R.id.overlaySupervisorBubble) ?: return
        when (stage) {
            null -> {
                // Run finished (or hasn't started) — no supervisor chatter
                // to show right now.
                container.visibility = View.GONE
            }
            "supervising", "searching" -> {
                // Just watching / double-checking — plain robot, no bubble,
                // so this doesn't compete for attention on every single step.
                container.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_supervisor_robot)
                icon.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_circle_icon)
                icon.clearColorFilter()
                bubble.visibility = View.GONE
            }
            "fixing" -> {
                // A real correction is happening — the one moment this is
                // worth actually saying something, so the small bubble
                // shows what it's doing right next to the (now amber)
                // robot.
                container.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_supervisor_robot)
                icon.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_circle_icon_amber)
                icon.clearColorFilter()
                bubble.text = text ?: "নতুন নির্দেশনা লিখছি…"
                bubble.visibility = View.VISIBLE
            }
            "fixed" -> {
                // Brief success flash, then back to plain-watching after a
                // beat — see the postDelayed below. Deliberately short so
                // this never turns into a lingering "done" badge.
                container.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_check)
                icon.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_circle_icon_success)
                icon.setColorFilter(android.graphics.Color.WHITE)
                bubble.visibility = View.GONE
                mainHandler.postDelayed({
                    if (controlBarView === bar) setSupervisorState("supervising", null)
                }, 1200)
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
            RemoteTts.stop()
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
