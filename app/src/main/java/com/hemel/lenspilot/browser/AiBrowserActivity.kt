package com.hemel.lenspilot.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.workflow.ContextWindowStore
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

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
        private const val MAX_AUTO_STEPS = 60          // soft cap — pauses for a check-in, never a dead stop (see pauseForUserAnswer)
        private const val STEP_SETTLE_DELAY_MS = 900L  // let a page/DOM update settle
        private const val PAGE_LOAD_SETTLE_DELAY_MS = 1100L
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var aiSwitch: Switch
    private lateinit var closeButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var goalRow: View
    private lateinit var goalInput: EditText
    private lateinit var goalGoButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStepRunnable: Runnable? = null

    private var currentGoal: String? = null
    private var stepCount = 0
    private var waitingForPageFinished = false

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
        aiSwitch = findViewById(R.id.browserAiSwitch)
        closeButton = findViewById(R.id.browserCloseButton)
        progressBar = findViewById(R.id.browserProgressBar)
        goalRow = findViewById(R.id.browserGoalRow)
        goalInput = findViewById(R.id.browserGoalInput)
        goalGoButton = findViewById(R.id.browserGoalGoButton)

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
                    scheduleNextStep(PAGE_LOAD_SETTLE_DELAY_MS)
                }
            }
        }

        closeButton.setOnClickListener { finish() }

        aiSwitch.setOnCheckedChangeListener(aiSwitchListener)

        goalGoButton.setOnClickListener { submitGoalFromInput() }
        goalInput.setOnEditorActionListener { _, _, _ -> submitGoalFromInput(); true }

        val goalFromIntent = intent.getStringExtra(EXTRA_GOAL)
        if (!goalFromIntent.isNullOrBlank()) {
            beginNewGoal(goalFromIntent)
            aiSwitch.isChecked = true
            webView.loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(goalFromIntent, "UTF-8"))
            waitingForPageFinished = true
            statusText.text = "খুঁজে দেখছি: $goalFromIntent"
        } else {
            webView.loadUrl("https://www.google.com")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // Goal row (shown only when idle & AI is on)
    // ------------------------------------------------------------------

    private fun showGoalRow() {
        awaitingUserAnswer = false
        goalInput.hint = "কী করতে চাও লেখো… (যেমন: Arijit Singh এর Tum Hi Ho চালু করো)"
        goalRow.visibility = View.VISIBLE
        statusText.text = "কী করতে চাও লেখো"
    }

    private fun hideGoalRow() {
        goalRow.visibility = View.GONE
    }

    /** Starts a brand new task: fresh session id, fresh on-device context
     * window, no carried-over browsing-RAG note (a new goal may not match
     * the same one — the next request just searches again, cheaply). */
    private fun beginNewGoal(goal: String) {
        currentGoal = goal
        stepCount = 0
        awaitingUserAnswer = false
        sessionId = UUID.randomUUID().toString().replace("-", "")
        browsingRagNoteId = null
        goalChangedPending = true
        ContextWindowStore.startSession(this, sessionId, goal)
    }

    private fun submitGoalFromInput() {
        val text = goalInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        goalInput.setText("")
        hideGoalRow()

        if (awaitingUserAnswer && !currentGoal.isNullOrBlank()) {
            // Mid-task reply: this is an answer to a question, or a new
            // instruction layered onto the SAME task — keep the session,
            // the on-device context window, and the browsing-RAG match
            // exactly as they are, just feed the text in as the next
            // step's context and resume stepping. Never restarts the task.
            awaitingUserAnswer = false
            ContextWindowStore.remember(this, sessionId, "ইউজার বলেছে: $text")
            statusText.text = "ঠিক আছে, চালিয়ে যাচ্ছি…"
            if (!aiSwitch.isChecked) {
                aiSwitch.setOnCheckedChangeListener(null)
                aiSwitch.isChecked = true
                aiSwitch.setOnCheckedChangeListener(aiSwitchListener)
            }
            scheduleNextStep(STEP_SETTLE_DELAY_MS)
            return
        }

        beginNewGoal(text)
        statusText.text = "খুঁজে দেখছি: $text"
        waitingForPageFinished = true
        webView.loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8"))
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
        if (stepCount >= MAX_AUTO_STEPS) {
            pauseForUserAnswer("অনেক ধাপ হয়ে গেছে ($MAX_AUTO_STEPS) — কাজ ঠিক পথে আছে কিনা একবার দেখে নাও। উপরে কিছু লিখলে (এমনকি শুধু \"চালিয়ে যাও\") আমি একই কাজ চালিয়ে যাব।")
            return
        }
        stepCount++

        webView.evaluateJavascript(PRUNER_JS) { rawResult ->
            val elementsJson = unwrapJsString(rawResult)
            val elements = PrunedElement.listFromJsonArray(elementsJson)
            val currentUrl = webView.url.orEmpty()
            webView.evaluateJavascript("(function(){return document.title;})();") { titleRaw ->
                val pageTitle = unwrapJsString(titleRaw)
                requestNextAction(currentGoal!!, currentUrl, pageTitle, elements)
            }
        }
    }

    private fun requestNextAction(goal: String, currentUrl: String, pageTitle: String, elements: List<PrunedElement>) {
        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            // Only the last few compact lines from the on-device context
            // window go over the network — the rest of what's been done
            // this task stays local (see ContextWindowStore).
            val recentHistory = ContextWindowStore.recentForPrompt(this@AiBrowserActivity, sessionId)
            val body = BrowserActionRequest.build(
                goal = goal,
                stepNumber = stepCount,
                history = recentHistory,
                currentUrl = currentUrl,
                pageTitle = pageTitle,
                elements = elements,
                browsingRagNoteId = browsingRagNoteId,
                goalChanged = goalChangedPending
            )
            goalChangedPending = false
            val result = ApiClient.callAuthed(this@AiBrowserActivity, baseUrl, "/api/browser-action", body)
            result.onSuccess { bodyStr ->
                val parsed = try { BrowserActionResult.fromJson(JSONObject(bodyStr)) } catch (e: Exception) { null }
                if (parsed == null) {
                    stopAutoLoop("উত্তর বোঝা যায়নি — নিজে চালিয়ে নাও।")
                    return@onSuccess
                }
                if (parsed.ragMatchId != null) browsingRagNoteId = parsed.ragMatchId
                applyAction(parsed)
            }.onFailure { e ->
                stopAutoLoop("নেটওয়ার্ক সমস্যা — নিজে চালিয়ে নাও।")
                Toast.makeText(this@AiBrowserActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyAction(result: BrowserActionResult) {
        if (result.messageToUser.isNotBlank()) statusText.text = result.messageToUser
        ContextWindowStore.remember(this, sessionId, "[${result.action}] ${result.messageToUser}")

        if (result.taskComplete || result.action == "task_complete") {
            stopAutoLoop(result.messageToUser.ifBlank { "কাজ শেষ — নিজে দেখে নাও।" })
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
                    stopAutoLoop("অনিরাপদ লিংক — থামছি।")
                    return
                }
                waitingForPageFinished = true
                webView.loadUrl(safeUrl)
            }
            "search" -> {
                val q = result.query.orEmpty()
                waitingForPageFinished = true
                webView.loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
            }
            "go_back" -> {
                waitingForPageFinished = true
                if (webView.canGoBack()) webView.goBack() else scheduleNextStep(STEP_SETTLE_DELAY_MS)
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
            else -> stopAutoLoop("অজানা পদক্ষেপ — নিজে চালিয়ে নাও।")
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
              el.value = $escaped;
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              if ($submit) {
                var form = el.form;
                if (form) { form.requestSubmit ? form.requestSubmit() : form.submit(); }
                else {
                  el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}));
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
}
