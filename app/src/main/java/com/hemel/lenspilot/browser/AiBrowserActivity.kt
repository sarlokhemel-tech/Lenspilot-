package com.hemel.lenspilot.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.workflow.ContextWindowStore
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.max

/**
 * The small in-app browser described in the feature request:
 *
 *  - Lives entirely inside Lenspilot (its own WebView) — because it's the
 *    app's OWN page, JS injection can read/act on the DOM directly, so no
 *    Accessibility permission is needed here at all (unlike the main
 *    on-screen guide, which highlights for the HUMAN to tap).
 *  - Top control bar has just one real control: an AI on/off switch. AI ON
 *    = this activity runs the pruned-DOM decide-act loop below on its own.
 *    AI OFF = it's a completely normal WebView the user taps themselves.
 *  - Only ever opened when the "browser mode" toggle next to the chat input
 *    was selected for that run — the ordinary on-screen highlight workflow
 *    is completely unchanged otherwise; this is purely a different
 *    execution target for the exact same goal/steps.
 */
class AiBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_SYSTEM = "extra_system"    // chat-box system that launched this run ("ela_4n", ...)
        private const val CAPTCHA_POLL_MS = 2500L  // how often we re-check whether the human solved the captcha
        private const val CAPTCHA_IGNORE_STEPS = 3 // after a manual "চালিয়ে যাও" at a captcha pause, skip the check this many steps
        private const val MAX_AUTO_STEPS = 60          // soft cap — pauses for a check-in, never a dead stop (see pauseForUserAnswer)
        private const val MAX_AUTO_STEPS_SUPERVISED = 200  // same soft cap, but for supervised ELA 4N runs (big multi-item jobs)
        // Trimmed down from 900/1100ms — these were fixed pauses added on
        // top of the click/type/load itself "just in case", independent of
        // whether the page had actually already settled. Halving them
        // removes dead waiting time once the button is already on screen,
        // while still giving a fast DOM update a moment to land before the
        // next PRUNER_JS scan runs.
        private const val STEP_SETTLE_DELAY_MS = 450L  // let a page/DOM update settle
        private const val PAGE_LOAD_SETTLE_DELAY_MS = 400L
        // BUGFIX ("মাঝে মাঝে থেমে যায়"): onPageFinished never fires for some
        // pages (blocked/looping redirects, ad interstitials, SPA route
        // changes that never trigger a real navigation event) — previously
        // waitingForPageFinished just stayed true forever and the whole loop
        // silently died with no error, no timeout, nothing. This watchdog
        // force-advances the loop if the real page-finished callback doesn't
        // show up in time, so a bad page pauses/continues instead of hanging.
        // BUGFIX ("পেজে গেলেই যথেষ্ট, ফুল লোডের জন্য বসে থেকো না"): heavy
        // SPA sites (Facebook, Instagram, etc.) keep firing background
        // network activity for ads/trackers/chat widgets long after the
        // page is actually usable, so onPageFinished either arrives very
        // late or — on some client-side navigations — never fires again at
        // all. Waiting the old 6s here meant every single step on those
        // sites paid nearly the full watchdog delay, which is what read as
        // "waits like before / এলোমেলো". Cut to 2.5s: long enough for a
        // normal server-rendered page, short enough that a stuck/SPA page
        // just falls through to the next step instead of stalling the loop.
        private const val PAGE_LOAD_WATCHDOG_MS = 2500L
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var systemBadge: TextView
    private lateinit var aiSwitch: Switch
    private lateinit var closeButton: ImageButton
    private lateinit var newMessageButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var goalRow: View
    private lateinit var goalInput: EditText
    private lateinit var goalGoButton: ImageButton
    private lateinit var goalCloseButton: ImageButton
    private lateinit var goalAttachButton: ImageButton
    private lateinit var goalImagePreviewRow: View
    private lateinit var goalImagePreview: ImageView
    private lateinit var goalImageRemoveButton: ImageButton

    // Set while the goal row has an attached photo waiting to be sent —
    // cleared the instant it's actually included in a request (see
    // submitGoalFromInput / requestNextAction).
    private var pendingGoalImageBase64: String? = null

    // gallery picker for "AI-কে ছবিও পাঠানো যাবে" — AiBrowserActivity is
    // already an Activity, so (unlike the overlay services) it can just
    // register for this result directly, no trampoline needed.
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val base64 = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return@use null
                val scaled = downscaleBitmap(original, 1024)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) { null }
        if (base64 == null) {
            Toast.makeText(this, "ছবিটা পড়া গেল না", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingGoalImageBase64 = base64
        goalImagePreview.setImageBitmap(BitmapFactory.decodeByteArray(
            Base64.decode(base64, Base64.NO_WRAP), 0, Base64.decode(base64, Base64.NO_WRAP).size
        ))
        goalImagePreviewRow.visibility = View.VISIBLE
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStepRunnable: Runnable? = null

    private var currentGoal: String? = null
    private var stepCount = 0

    // Chat-box system that started this run — "ela_4n" makes the server put a
    // second, independent supervisor AI over every step (hallucination check,
    // auto-corrective prompts, live search when needed).
    private var browserSystem: String = "super_1_2"

    // ---- Captcha hand-over -------------------------------------------------
    // Captchas are for humans. The moment one is detected (on-device probe
    // before every step, or the server's own check) the loop STOPS and the
    // person is asked to solve it; the AI never touches it. Once the person has
    // solved it the run continues by itself from the same place.
    private var captchaWaiting = false
    private var captchaClearStreak = 0
    private var captchaFromProbe = false
    private var captchaPauseUrl = ""
    private var captchaPauseTitle = ""
    private var captchaIgnoreSteps = 0

    private val captchaPollRunnable = object : Runnable {
        override fun run() {
            if (!captchaWaiting) return
            webView.evaluateJavascript(CAPTCHA_PROBE_JS) { raw ->
                if (!captchaWaiting) return@evaluateJavascript
                val stillThere = raw?.trim() == "true"
                // Probe-detected: solved once the probe stops seeing it. Server-detected
                // (URL/title based, the probe saw nothing): the page must also have moved on,
                // otherwise we'd resume straight into the same pause again.
                val pageChanged = webView.url.orEmpty() != captchaPauseUrl ||
                    webView.title.orEmpty() != captchaPauseTitle
                val cleared = if (captchaFromProbe) !stillThere else (!stillThere && pageChanged)
                captchaClearStreak = if (cleared) captchaClearStreak + 1 else 0
                if (captchaClearStreak >= 2) resumeAfterCaptcha() else handler.postDelayed(this, CAPTCHA_POLL_MS)
            }
        }
    }

    private fun pauseForCaptcha(message: String, fromProbe: Boolean) {
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        pendingStepRunnable = null
        pageLoadWatchdog?.let { handler.removeCallbacks(it) }
        pageLoadWatchdog = null
        waitingForPageFinished = false
        // awaitingUserAnswer = true so typing anything (e.g. "চালিয়ে যাও") also resumes —
        // the goal row itself stays hidden so the captcha isn't covered.
        awaitingUserAnswer = true
        manualNudgeMode = false
        captchaWaiting = true
        captchaFromProbe = fromProbe
        captchaClearStreak = 0
        captchaPauseUrl = webView.url.orEmpty()
        captchaPauseTitle = webView.title.orEmpty()
        statusText.text = message
        goalInput.hint = "ক্যাপচা সমাধান করলে নিজেই চলবে — না চললে \"চালিয়ে যাও\" লেখো"
        handler.removeCallbacks(captchaPollRunnable)
        handler.postDelayed(captchaPollRunnable, CAPTCHA_POLL_MS)
    }

    private fun resumeAfterCaptcha() {
        captchaWaiting = false
        handler.removeCallbacks(captchaPollRunnable)
        awaitingUserAnswer = false
        ContextWindowStore.remember(this, sessionId, "ইউজার ক্যাপচা সমাধান করেছে, কাজ চলছে")
        statusText.text = "✅ ক্যাপচা শেষ — চালিয়ে যাচ্ছি…"
        scheduleNextStep(STEP_SETTLE_DELAY_MS)
    }
    private var waitingForPageFinished = false
    private var pageLoadWatchdog: Runnable? = null

    /** Call this instead of setting waitingForPageFinished = true directly.
     * Arms a watchdog so the loop can't get stuck forever if this
     * particular page never calls onPageFinished back. */
    private fun beginPageLoadWait() {
        waitingForPageFinished = true
        pageLoadWatchdog?.let { handler.removeCallbacks(it) }
        val watchdog = Runnable {
            if (waitingForPageFinished) {
                waitingForPageFinished = false
                pageLoadWatchdog = null
                scheduleNextStep(STEP_SETTLE_DELAY_MS)
            }
        }
        pageLoadWatchdog = watchdog
        handler.postDelayed(watchdog, PAGE_LOAD_WATCHDOG_MS)
    }

    // True while the loop is paused waiting for the human to answer a
    // question or give new mid-task instructions (see pauseForUserAnswer).
    // While true, the goal row's Go button feeds the typed text back into
    // the SAME session/context instead of starting a brand new task.
    private var awaitingUserAnswer = false

    // One sessionId per goal — the key ContextWindowStore uses to scope its
    // on-device "RAM-style" log (see workflow/ContextWindowStore.kt): what's
    // already been done stays local and capped, only the last few compact
    // lines of it ever go into a request (see requestNextAction).
    private var sessionId: String = UUID.randomUUID().toString().replace("-", "")

    // The browsing-vault RAG note (if any) matched for the CURRENT goal —
    // once the server reports a match we keep sending its id back so it can
    // be fetched straight from cache instead of paying for another
    // embedding search on every following step. Reset whenever the goal
    // changes (new goal, or an edit_goal mid-task).
    private var browsingRagNoteId: String? = null
    private var goalChangedPending = true

    // Set right before a request that should include a photo (either the
    // user answered/nudged with one attached, or attached one while
    // starting a brand-new goal) — read once by requestNextAction/build
    // and cleared immediately after, so it's never accidentally resent on
    // a later, unrelated step.
    private var pendingStepImageBase64: String? = null

    private val aiSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) {
            if (currentGoal.isNullOrBlank()) showGoalRow() else { hideGoalRow(); startAutoLoop() }
        } else {
            stopAutoLoop("ম্যানুয়াল মোড — নিজে ব্রাউজ করুন", clearMemory = false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_browser)

        webView = findViewById(R.id.aiWebView)
        statusText = findViewById(R.id.browserStatusText)
        systemBadge = findViewById(R.id.browserSystemBadge)
        aiSwitch = findViewById(R.id.browserAiSwitch)
        closeButton = findViewById(R.id.browserCloseButton)
        newMessageButton = findViewById(R.id.browserNewMessageButton)
        progressBar = findViewById(R.id.browserProgressBar)
        goalRow = findViewById(R.id.browserGoalRow)
        goalInput = findViewById(R.id.browserGoalInput)
        goalGoButton = findViewById(R.id.browserGoalGoButton)
        goalCloseButton = findViewById(R.id.browserGoalCloseButton)
        goalAttachButton = findViewById(R.id.browserGoalAttachButton)
        goalImagePreviewRow = findViewById(R.id.browserGoalImagePreviewRow)
        goalImagePreview = findViewById(R.id.browserGoalImagePreview)
        goalImageRemoveButton = findViewById(R.id.browserGoalImageRemoveButton)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                if (waitingForPageFinished) {
                    waitingForPageFinished = false
                    pageLoadWatchdog?.let { handler.removeCallbacks(it) }
                    pageLoadWatchdog = null
                    scheduleNextStep(PAGE_LOAD_SETTLE_DELAY_MS)
                }
            }
        }

        closeButton.setOnClickListener { finish() }
        newMessageButton.setOnClickListener { openManualMessageRow() }
        goalCloseButton.setOnClickListener { hideGoalRow() }
        goalAttachButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        goalImageRemoveButton.setOnClickListener {
            pendingGoalImageBase64 = null
            goalImagePreviewRow.visibility = View.GONE
        }

        aiSwitch.setOnCheckedChangeListener(aiSwitchListener)

        goalGoButton.setOnClickListener { submitGoalFromInput() }
        goalInput.setOnEditorActionListener { _, _, _ -> submitGoalFromInput(); true }

        browserSystem = intent.getStringExtra(EXTRA_SYSTEM)?.ifBlank { null } ?: "super_1_2"
        updateSystemBadge()
        val goalFromIntent = intent.getStringExtra(EXTRA_GOAL)
        if (!goalFromIntent.isNullOrBlank()) {
            beginNewGoal(goalFromIntent)
            // BUGFIX ("এলোমেলো, সবসময় গুগলে সার্চ করে, কোন পেজে আছে বোঝে না"):
            // this used to ALWAYS force-navigate to a literal Google search of
            // the raw goal text (even when the goal was e.g. "ফেসবুকে পোস্ট
            // দাও: ...") AND set aiSwitch.isChecked = true in the same breath.
            // Checking the switch fires aiSwitchListener synchronously, which
            // — since currentGoal was already set by beginNewGoal above — calls
            // startAutoLoop() right then, scheduling a step ~450ms later. That
            // step often fired BEFORE the just-triggered Google-search page had
            // actually finished loading, so the model's first decision was made
            // against a half-loaded/irrelevant page — exactly the "confused
            // about the current page" symptom. Setting the switch silently
            // (listener detached) and never forcing a search means the model's
            // OWN first decision — made from the real, settled starting state —
            // decides whether to navigate straight to a known site (per
            // BROWSER_AUTOMATION_INSTRUCTIONS' facebook.com/youtube.com/etc.
            // rule) or to actually search, instead of a hardcoded guess always
            // winning that choice first.
            aiSwitch.setOnCheckedChangeListener(null)
            aiSwitch.isChecked = true
            aiSwitch.setOnCheckedChangeListener(aiSwitchListener)
            statusText.text = "লক্ষ্য বুঝে পরবর্তী পদক্ষেপ ঠিক করছি…"
            startAutoLoop()
        } else {
            webView.loadUrl("https://www.google.com")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        pageLoadWatchdog?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(captchaPollRunnable)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // ELA 4N system badge — persistent (not a one-off status line) sign
    // that this browser session is being driven by ELA 4N with its
    // independent supervisor AI checking every step (see app.py's
    // _ela4n_supervise_browser / SUPERVISOR_ENABLED, and the
    // "supervisor"/"supervisor_note" fields already read in
    // BrowserModels.kt). Was declared in the layout already but never
    // actually shown — this is the missing wiring for "বোঝা যাবে যে
    // এখানে ELA 4N মডেল চলতেছে... তদারকির সিস্টেম থাকবে".
    // ------------------------------------------------------------------
    private fun updateSystemBadge() {
        systemBadge.visibility = if (browserSystem == "ela_4n") View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Goal row (idle/paused ones, or opened anytime via the new-message
    // button — see openManualMessageRow)
    // ------------------------------------------------------------------

    // True only when the goal row was opened by browserNewMessageButton
    // WHILE automation was actively running (not idle, not already
    // paused) — see openManualMessageRow/submitGoalFromInput.
    private var manualNudgeMode = false

    private fun showGoalRow() {
        manualNudgeMode = false
        awaitingUserAnswer = false
        goalInput.hint = "কী করতে চাও লেখো… (যেমন: Arijit Singh এর Tum Hi Ho চালু করো)"
        goalRow.visibility = View.VISIBLE
        statusText.text = "কী করতে চাও লেখো"
    }

    private fun hideGoalRow() {
        goalRow.visibility = View.GONE
        pendingGoalImageBase64 = null
        goalImagePreviewRow.visibility = View.GONE
    }

    /** Opens the goal row as a free-standing "tell the AI something new"
     * box, usable at ANY time — including mid-automation, while the loop
     * is actively stepping on its own. This is the fix for "অটোমেশন
     * চলাকালীন নতুন কমান্ড দেওয়ার অপসন নেই": submitting here never
     * restarts or stops the current task (see submitGoalFromInput's
     * manualNudgeMode branch) — the text/image is just folded into the
     * on-device context window for whichever step is already scheduled
     * next. Tapping the same button again while it's open just closes it
     * (same as the × button), so it also acts as its own toggle. */
    private fun openManualMessageRow() {
        if (goalRow.visibility == View.VISIBLE) {
            hideGoalRow()
            return
        }
        manualNudgeMode = aiSwitch.isChecked && !currentGoal.isNullOrBlank() && !awaitingUserAnswer
        goalInput.hint = if (manualNudgeMode)
            "চলতি কাজে নতুন কিছু বলো… (থামাবে না, পরের ধাপে যোগ হবে)"
        else
            "কী করতে চাও লেখো…"
        goalRow.visibility = View.VISIBLE
        goalInput.requestFocus()
    }

    /** Starts a brand new task: fresh session id, fresh on-device context
     * window, no carried-over browsing-RAG note (a new goal may not match
     * the same one — the next request just searches again, cheaply). */
    private fun beginNewGoal(goal: String) {
        currentGoal = goal
        stepCount = 0
        awaitingUserAnswer = false
        manualNudgeMode = false
        captchaWaiting = false
        captchaIgnoreSteps = 0
        handler.removeCallbacks(captchaPollRunnable)
        sessionId = UUID.randomUUID().toString().replace("-", "")
        browsingRagNoteId = null
        goalChangedPending = true
        ContextWindowStore.startSession(this, sessionId, goal)
    }

    private fun submitGoalFromInput() {
        val text = goalInput.text?.toString()?.trim().orEmpty()
        val image = pendingGoalImageBase64
        if (text.isEmpty() && image == null) return
        goalInput.setText("")
        pendingGoalImageBase64 = null
        goalImagePreviewRow.visibility = View.GONE
        hideGoalRow()

        if (awaitingUserAnswer && !currentGoal.isNullOrBlank()) {
            // Mid-task reply: this is an answer to a question, or a new
            // instruction layered onto the SAME task — keep the session,
            // the on-device context window, and the browsing-RAG match
            // exactly as they are, just feed the text in as the next
            // step's context and resume stepping. Never restarts the task.
            awaitingUserAnswer = false
            if (captchaWaiting) {
                // Person said "go on" themselves — trust them, and skip the captcha
                // check for a few steps so a false alarm can't re-trap the task.
                captchaWaiting = false
                handler.removeCallbacks(captchaPollRunnable)
                captchaIgnoreSteps = CAPTCHA_IGNORE_STEPS
            }
            pendingStepImageBase64 = image
            ContextWindowStore.remember(this, sessionId, "ইউজার বলেছে: ${text.ifBlank { "(একটা ছবি পাঠিয়েছে)" }}")
            statusText.text = "ঠিক আছে, চালিয়ে যাচ্ছি…"
            if (!aiSwitch.isChecked) {
                aiSwitch.setOnCheckedChangeListener(null)
                aiSwitch.isChecked = true
                aiSwitch.setOnCheckedChangeListener(aiSwitchListener)
            }
            scheduleNextStep(STEP_SETTLE_DELAY_MS)
            return
        }

        if (manualNudgeMode && aiSwitch.isChecked && !currentGoal.isNullOrBlank()) {
            // Mid-run nudge (see openManualMessageRow): the loop is
            // already stepping on its own — deliberately touch NOTHING
            // about the running task (session, step count, the already-
            // scheduled pendingStepRunnable) beyond dropping this note
            // into the on-device context window, so the very next step
            // that was already about to happen just picks it up, exactly
            // like any other bit of recent history.
            manualNudgeMode = false
            pendingStepImageBase64 = image
            ContextWindowStore.remember(this, sessionId, "ইউজার নতুন করে বলেছে: ${text.ifBlank { "(একটা ছবি পাঠিয়েছে)" }}")
            statusText.text = if (text.isNotBlank()) "ঠিক আছে: $text" else "ছবি যোগ হলো, পরের ধাপে দেখবে"
            return
        }

        manualNudgeMode = false
        pendingStepImageBase64 = image
        beginNewGoal(text.ifBlank { "পাঠানো ছবিটা দেখে সাহায্য করো" })
        // Same fix as onCreate's goalFromIntent branch above — let the model's
        // own first decision pick navigate-vs-search from the real current
        // page instead of force-loading a literal Google search of the goal.
        statusText.text = "লক্ষ্য বুঝে পরবর্তী পদক্ষেপ ঠিক করছি…"
        startAutoLoop()
    }

    /** Pauses the loop for a human check-in WITHOUT losing the task: the
     * session, on-device context window, and step count all survive, and
     * the AI switch is left on. This replaces the old behavior where
     * ask_user (or hitting the step cap) fully stopped the loop and forced
     * the user to restart from scratch — now the goal row just opens in
     * "answer" mode and [submitGoalFromInput] resumes the SAME task the
     * instant a reply comes in. */
    private fun pauseForUserAnswer(message: String) {
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        pendingStepRunnable = null
        awaitingUserAnswer = true
        manualNudgeMode = false
        statusText.text = message
        goalInput.hint = "উত্তর লেখো বা নতুন নির্দেশনা দাও…"
        goalRow.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------
    // Auto loop
    // ------------------------------------------------------------------

    private fun startAutoLoop() {
        if (currentGoal.isNullOrBlank()) return
        scheduleNextStep(STEP_SETTLE_DELAY_MS)
    }

    /** [clearMemory] auto-deletes this task's on-device context window —
     * true for every real "the task is over" case (complete, needs the
     * user, too many steps, network/parse error); false only for the
     * manual AI-off toggle, since the user might flip it back on and
     * continue the SAME task a moment later ("কাজ শেষ হলে অটো ডিলিট"). */
    private fun stopAutoLoop(reasonMessage: String?, clearMemory: Boolean = true) {
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        pendingStepRunnable = null
        pageLoadWatchdog?.let { handler.removeCallbacks(it) }
        pageLoadWatchdog = null
        waitingForPageFinished = false
        captchaWaiting = false
        handler.removeCallbacks(captchaPollRunnable)
        if (reasonMessage != null) statusText.text = reasonMessage
        if (clearMemory) ContextWindowStore.clearSession(this, sessionId)
        // Flip the switch off without re-entering this same listener.
        if (aiSwitch.isChecked) {
            aiSwitch.setOnCheckedChangeListener(null)
            aiSwitch.isChecked = false
            aiSwitch.setOnCheckedChangeListener(aiSwitchListener)
        }
    }

    private fun scheduleNextStep(delayMs: Long) {
        if (!aiSwitch.isChecked) return
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { runNextAiStep() }
        pendingStepRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun runNextAiStep() {
        if (!aiSwitch.isChecked || currentGoal.isNullOrBlank()) return
        // ELA 4N runs are supervised step-by-step by a second AI, so long jobs (many
        // forms/applications in a row) get a much higher check-in threshold.
        val stepLimit = if (browserSystem == "ela_4n") MAX_AUTO_STEPS_SUPERVISED else MAX_AUTO_STEPS
        if (stepCount >= stepLimit) {
            pauseForUserAnswer("অনেক ধাপ হয়ে গেছে ($stepLimit) — কাজ ঠিক পথে আছে কিনা একবার দেখে নাও। উপরে কিছু লিখলে (এমনকি শুধু \"চালিয়ে যাও\") আমি একই কাজ চালিয়ে যাব।")
            return
        }
        stepCount++

        // Captcha check FIRST, on-device, before anything is sent anywhere: if a
        // human-verification challenge is showing, stop and hand over to the person.
        val ignoreCaptchaThisStep = captchaIgnoreSteps > 0
        if (ignoreCaptchaThisStep) captchaIgnoreSteps--
        webView.evaluateJavascript(CAPTCHA_PROBE_JS) { probeRaw ->
            if (!aiSwitch.isChecked || currentGoal.isNullOrBlank()) return@evaluateJavascript
            val captchaOnPage = probeRaw?.trim() == "true"
            if (captchaOnPage && !ignoreCaptchaThisStep) {
                stepCount--   // this step didn't happen — don't burn the step budget
                ContextWindowStore.remember(this, sessionId, "[ক্যাপচা] মানুষ সমাধান করছে")
                pauseForCaptcha(CAPTCHA_MESSAGE, fromProbe = true)
                return@evaluateJavascript
            }
            webView.evaluateJavascript(PRUNER_JS) { rawResult ->
                val elementsJson = unwrapJsString(rawResult)
                val elements = PrunedElement.listFromJsonArray(elementsJson)
                val currentUrl = webView.url.orEmpty()
                webView.evaluateJavascript("(function(){return document.title;})();") { titleRaw ->
                    val pageTitle = unwrapJsString(titleRaw)
                    requestNextAction(currentGoal!!, currentUrl, pageTitle, elements, ignoreCaptchaThisStep)
                }
            }
        }
    }

    private fun requestNextAction(
        goal: String, currentUrl: String, pageTitle: String, elements: List<PrunedElement>,
        captchaIgnore: Boolean = false
    ) {
        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            // Only the last few compact lines from the on-device context
            // window go over the network — the rest of what's been done
            // this task stays local (see ContextWindowStore).
            val recentHistory = ContextWindowStore.recentForPrompt(this@AiBrowserActivity, sessionId)
            // Only ever sent on the ONE step right after the user attached
            // it (via an answer, a mid-run nudge, or a brand-new goal) —
            // cleared immediately so a later, unrelated step never resends
            // a stale photo.
            val imageForThisStep = pendingStepImageBase64
            pendingStepImageBase64 = null
            val body = BrowserActionRequest.build(
                goal = goal,
                stepNumber = stepCount,
                history = recentHistory,
                currentUrl = currentUrl,
                pageTitle = pageTitle,
                elements = elements,
                browsingRagNoteId = browsingRagNoteId,
                goalChanged = goalChangedPending,
                imageBase64 = imageForThisStep,
                context = this@AiBrowserActivity,
                system = browserSystem,
                captchaIgnore = captchaIgnore
            )
            goalChangedPending = false
            val result = ApiClient.callAuthed(this@AiBrowserActivity, baseUrl, "/api/browser-action", body)
            result.onSuccess { bodyStr ->
                val parsed = try { BrowserActionResult.fromJson(JSONObject(bodyStr)) } catch (e: Exception) { null }
                if (parsed == null) {
                    // BUGFIX ("নতুন কমান্ড গুগলে সার্চ হয়ে যায়"): this used to
                    // call stopAutoLoop(), which flips the AI switch off and
                    // clears the on-device context but leaves currentGoal
                    // set. The NEXT thing the user typed then fell through
                    // submitGoalFromInput()'s "neither answering nor
                    // nudging" branch and got treated as a brand-new task —
                    // i.e. Google-searched verbatim instead of resuming the
                    // real goal. pauseForUserAnswer keeps the session/goal/
                    // context alive and marks awaitingUserAnswer=true, so
                    // whatever the user types next correctly resumes the
                    // SAME task instead of starting a fresh one.
                    pauseForUserAnswer("উত্তর বুঝতে পারিনি — উপরে কিছু লিখলে (এমনকি শুধু \"চালিয়ে যাও\") একই কাজ আবার চালাবো।")
                    return@onSuccess
                }
                if (parsed.ragMatchId != null) browsingRagNoteId = parsed.ragMatchId
                applyAction(parsed)
            }.onFailure { e ->
                // Same fix as above — a transient network hiccup shouldn't
                // throw away the task. Pause and resume on the same goal
                // instead of forcing a restart-as-new-search.
                pauseForUserAnswer("নেটওয়ার্ক সমস্যা হয়েছে — ঠিক হয়ে গেলে উপরে \"চালিয়ে যাও\" লিখো, একই কাজ চালিয়ে যাবো।")
                Toast.makeText(this@AiBrowserActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyAction(result: BrowserActionResult) {
        if (result.messageToUser.isNotBlank()) {
            statusText.text = if (!result.supervisorNote.isNullOrBlank())
                "${result.supervisorNote}\n${result.messageToUser}" else result.messageToUser
        } else if (!result.supervisorNote.isNullOrBlank()) {
            statusText.text = result.supervisorNote
        }
        ContextWindowStore.remember(this, sessionId, "[${result.action}] ${result.messageToUser}")

        // Server-side captcha detection (URL/title/element text — things the on-device
        // probe can miss). Same hand-over as the probe: stop, ask the person, resume alone.
        if (result.captcha) {
            pauseForCaptcha(result.messageToUser.ifBlank { CAPTCHA_MESSAGE }, fromProbe = false)
            return
        }

        if (result.taskComplete || result.action == "task_complete") {
            stopAutoLoop(result.messageToUser.ifBlank { "কাজ শেষ — নিজে দেখে নাও।" })
            // Task genuinely finished — this is the one case where the NEXT
            // thing the user types really should start a brand-new task
            // (submitGoalFromInput's fallback branch), so clear currentGoal
            // here rather than leaving the old goal lying around.
            currentGoal = null
            return
        }
        if (result.action == "ask_user") {
            pauseForUserAnswer(result.messageToUser.ifBlank { "এখানে একটু সাহায্য লাগবে — উপরে উত্তর লেখো।" })
            return
        }

        when (result.action) {
            "edit_goal" -> {
                val newGoal = result.newGoal
                if (newGoal.isNullOrBlank()) {
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                } else {
                    currentGoal = newGoal
                    ContextWindowStore.updateGoal(this, sessionId, newGoal)
                    // The old browsing-RAG match may not fit the refined
                    // goal anymore — drop it and let the very next request
                    // search again (goalChangedPending), without resetting
                    // the on-device log this task has already built up.
                    browsingRagNoteId = null
                    goalChangedPending = true
                    statusText.text = if (result.messageToUser.isNotBlank()) result.messageToUser
                        else "🎯 লক্ষ্য ঠিক করছি: $newGoal"
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                }
            }
            "open_native_app" -> handleOpenNativeApp(result)
            "navigate" -> {
                val safeUrl = sanitizeUrl(result.url)
                if (safeUrl == null) {
                    // Same fix — pause & keep the goal alive instead of
                    // stopping outright, so the user's next message resumes
                    // this task rather than becoming a new Google search.
                    pauseForUserAnswer("অনিরাপদ লিংক পাওয়া গেছে — উপরে বলো এরপর কী করব।")
                    return
                }
                beginPageLoadWait()
                webView.loadUrl(safeUrl)
            }
            "search" -> {
                val q = result.query.orEmpty()
                beginPageLoadWait()
                webView.loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
            }
            "go_back" -> {
                if (webView.canGoBack()) { beginPageLoadWait(); webView.goBack() } else scheduleNextStep(STEP_SETTLE_DELAY_MS)
            }
            "click" -> {
                val id = result.elementId
                if (id == null) {
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                } else {
                    webView.evaluateJavascript(clickJs(id)) { scheduleNextStep(STEP_SETTLE_DELAY_MS) }
                }
            }
            "type" -> {
                val id = result.elementId
                val text = result.textToType.orEmpty()
                if (id == null) {
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                } else {
                    webView.evaluateJavascript(typeJs(id, text, result.submitAfterType)) {
                        scheduleNextStep(STEP_SETTLE_DELAY_MS)
                    }
                }
            }
            "scroll" -> {
                webView.evaluateJavascript("(function(){window.scrollBy(0,600);})();") {
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                }
            }
            "wait" -> scheduleNextStep(STEP_SETTLE_DELAY_MS * 2)
            else -> pauseForUserAnswer("বুঝতে পারিনি — উপরে বলো এরপর কী করব।")
        }
    }

    // ------------------------------------------------------------------
    // Native-app deep linking ("ফেসবুক খুলে দাও" style goals)
    // ------------------------------------------------------------------

    /** The model only ever sends a short whitelisted key (see
     * NativeAppTargets in BrowserModels.kt) — never a package name or URI
     * — so this method decides the only two things the client actually
     * controls: is the app installed, and if so, launch it (deep link
     * first, plain launcher intent as a fallback); if not, stop and ask
     * the user what they'd like to do, exactly as the spec describes
     * ("আগে থেকে খোলা/ইনস্টল থাকলে খুলবে, না থাকলে জিজ্ঞেস করবে"). */
    private fun handleOpenNativeApp(result: BrowserActionResult) {
        val target = NativeAppTargets.forKey(result.appTarget)
        if (target == null) {
            stopAutoLoop("কোন অ্যাপ খুলব বুঝতে পারিনি — নিজে করো।")
            return
        }
        // BUGFIX ("ব্রাউজিং মোড চালু আছে তবুও বাইরের অ্যাপে চলে যায়"): this
        // used to fire — and silently launch the app if installed — on
        // ANY step, including deep into an already-running browsing
        // task. A late, unprompted jump straight out of the browser is
        // far more disruptive than a wrong highlight/click, so it's only
        // ever auto-launched when it's genuinely the first decision for
        // the (possibly just-edited) goal — steps 1-2, or right after an
        // edit_goal pivot. Any later step gets treated as a pause instead
        // of an instant exit, same spirit as "ask_user".
        val isEarlyDecision = stepCount <= 2 || goalChangedPending
        if (!isEarlyDecision) {
            // clearMemory=false: this is a pause, not a finished/abandoned
            // task — the on-device context window stays intact so
            // flipping the AI switch back on continues the same session
            // instead of starting fresh.
            stopAutoLoop(
                "${result.appTarget?.replaceFirstChar { it.uppercase() } ?: "একটা"} অ্যাপ খোলা লাগতে পারে — " +
                    "ব্রাউজারেই থাকব নাকি অ্যাপে যাব? আবার AI চালু করলে আগের জায়গা থেকেই চলবে।",
                clearMemory = false
            )
            return
        }
        if (!isPackageInstalled(target.packageName)) {
            stopAutoLoop(
                "${result.appTarget?.replaceFirstChar { it.uppercase() }} অ্যাপ ইনস্টল করা নেই — " +
                    "Play Store থেকে ইনস্টল করবে, নাকি ব্রাউজারেই চালিয়ে যাব?"
            )
            return
        }
        val launched = try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target.deepLink)).apply {
                setPackage(target.packageName)
            }
            startActivity(deepLinkIntent)
            true
        } catch (e: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(target.packageName)
                if (launchIntent != null) { startActivity(launchIntent); true } else false
            } catch (e2: Exception) {
                false
            }
        }
        if (launched) {
            stopAutoLoop(result.messageToUser.ifBlank { "অ্যাপটা খুলে দিলাম।" })
        } else {
            stopAutoLoop("অ্যাপটা খুলতে পারলাম না — নিজে চেষ্টা করো।")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ------------------------------------------------------------------
    // JS helpers
    // ------------------------------------------------------------------

    private fun clickJs(elementId: Int): String = """
        (function(){
          var el = document.querySelector('[data-lp-id="$elementId"]');
          if (el) { el.scrollIntoView({block:'center'}); el.click(); }
        })();
    """.trimIndent()

    private fun typeJs(elementId: Int, text: String, submit: Boolean): String {
        val escaped = JSONObject.quote(text)
        return """
            (function(){
              var el = document.querySelector('[data-lp-id="$elementId"]');
              if (!el) return;
              el.focus();
              if (el.isContentEditable) {
                // BUGFIX: a contenteditable div (Facebook/Messenger/Twitter/
                // LinkedIn-style post & comment boxes — see lp_dom_pruner.js)
                // has no .value property, so the plain assignment below is a
                // silent no-op on these — nothing gets typed even though the
                // click/focus succeeds, and the loop just quietly fails and
                // tries something else next step. execCommand('insertText')
                // is what actually inserts real, framework-visible text into
                // these editors (React/Draft.js-based composers listen for
                // the same input events a real keystroke would fire).
                var range = document.createRange();
                range.selectNodeContents(el);
                var sel = window.getSelection();
                sel.removeAllRanges();
                sel.addRange(range);
                document.execCommand('insertText', false, $escaped);
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              } else {
                el.value = $escaped;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }
              if ($submit) {
                var form = el.form;
                if (form) { form.requestSubmit ? form.requestSubmit() : form.submit(); }
                else {
                  el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, keyCode:13, which:13}));
                }
              }
            })();
        """.trimIndent()
    }

    /** evaluateJavascript's callback returns the value as a JSON-encoded
     * string (e.g. a returned JS string comes back wrapped in escaped
     * quotes) — this undoes that one layer of wrapping. */
    private fun unwrapJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try { JSONObject("""{"v":$raw}""").getString("v") } catch (e: Exception) {
            raw.trim('"')
        }
    }

    /** Only http/https is ever allowed — no javascript:, data:, file:,
     * intent: or content: schemes, so a bad model response can never be
     * turned into script injection or a local-file/app-launch escape. */
    private fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            if (uri.scheme == "http" || uri.scheme == "https") url else null
        } catch (e: Exception) {
            null
        }
    }

    private val PRUNER_JS: String by lazy {
        assets.open("lp_dom_pruner.js").bufferedReader().use { it.readText() }
    }

    private val CAPTCHA_PROBE_JS: String by lazy {
        assets.open("lp_captcha_probe.js").bufferedReader().use { it.readText() }
    }

    private val CAPTCHA_MESSAGE =
        "🔒 ক্যাপচা ভেরিফিকেশন এসেছে — এটা মানুষকেই করতে হয়, আমি ছুঁইনি। তুমি নিজে সমাধান করো; হয়ে গেলে আমি নিজেই আবার চালিয়ে যাব।"
}
