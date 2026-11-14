package com.hemel.lenspilot.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.appcompat.app.AlertDialog
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.text.Editable
import android.text.TextWatcher
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.GalleryPick
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.browser.ToolsBrowserSession
import com.hemel.lenspilot.workflow.ContextWindowStore
import kotlinx.coroutines.launch
import org.json.JSONArray
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
        const val EXTRA_OTP_COPIED = "extra_otp_copied"    // গাইড-ওভারলের "কপি হয়েছে" থেকে ফেরা
        const val EXTRA_RESUME = "extra_resume"    // foreground-service নোটিফিকেশন থেকে ফিরলে: ডিস্কের v2-সেশন চালু করো
        const val EXTRA_TOOLS_SESSION_ID = "toolsSessionId"   // Tools মোড: এই সেশনের workspace-এ ডাউনলোড/আপলোড, ওয়ার্কস্পেস-সেতু
        const val EXTRA_TOOLS_URL = "toolsStartUrl"           // Tools মোড: শুরুতে খোলার URL (ঐচ্ছিক)
        private const val CAPTCHA_POLL_MS = 2500L  // how often we re-check whether the human solved the captcha
        private const val CAPTCHA_IGNORE_STEPS = 3 // after a manual "চালিয়ে যাও" at a captcha pause, skip the check this many steps
        private const val MAX_AUTO_STEPS = 60          // soft cap — pauses for a check-in, never a dead stop (see pauseForUserAnswer)
        private const val MAX_AUTO_STEPS_SUPERVISED = 200  // same soft cap, but for supervised ELA 4N runs (big multi-item jobs)
        // v144: কোনো স্থির অপেক্ষা (৪৫০ms / ৪০০ms) নেই, আর কোনো টাইমআউটে "স্কিপ" নেই।
        // পরের ধাপ শুরু হয় ঘটনা ঘটলে: onPageFinished / লোড-শেষ, অথবা পেজের ভেতর থেকে "তৈরি" সংকেত (LpReady)।
        // তাই এই ধ্রুবকগুলো ০ — scheduleNextStep(0) মানে "এখনই, যেহেতু যা দরকার তা এসে গেছে"।
        private const val STEP_SETTLE_DELAY_MS = 0L
    }

    private lateinit var webView: WebView
    private lateinit var real: RealBrowser      // v43: আসল-ব্রাউজারের অংশ (অ্যাড্রেস বার, ট্যাব, মেনু, ডাউনলোড…) — RealBrowser.kt
    private lateinit var statusText: TextView
    private lateinit var thinkPanel: View                      // v110: নিচের চিন্তা-বক্স
    private lateinit var autoChat: AutoPromptChat            // v147: auto-prompt chat (replaces the thinking log)
    private lateinit var thinkPanelScroll: com.hemel.lenspilot.chat.MaxHeightScrollView
    private lateinit var thinkText: TextView                 // v96: এজেন্টের চিন্তা (চাপলে আগেরগুলো)
    private val thoughtLog = ArrayList<String>()
    private lateinit var systemBadge: TextView
    private lateinit var aiSwitch: Switch
    private lateinit var closeButton: ImageButton
    private lateinit var statusDot: View                        // v100: AI activity dot (green = AI on)
    private lateinit var progressBar: ProgressBar
    private lateinit var goalRow: View
    private lateinit var goalInput: EditText
    private lateinit var goalGoButton: ImageButton
    private lateinit var goalCloseButton: ImageButton
    private lateinit var goalAttachButton: ImageButton
    private lateinit var goalImagePreviewRow: View
    private lateinit var goalImagePreview: ImageView
    private lateinit var goalImageRemoveButton: ImageButton

    // Tools মোডের সেশন-আঠা (null = সাধারণ ব্রাউজার; তখন নিচের সব হুক নিষ্ক্রিয়)
    private var toolsSession: ToolsBrowserSession? = null

    // Set while the goal row has an attached photo waiting to be sent —
    // cleared the instant it's actually included in a request (see
    // submitGoalFromInput / requestNextAction).
    private var pendingGoalImageBase64: String? = null

    // gallery picker for "AI-কে ছবিও পাঠানো যাবে" — AiBrowserActivity is
    // already an Activity, so (unlike the overlay services) it can just
    // register for this result directly, no trampoline needed.
    private val pickImageLauncher = registerForActivityResult(GalleryPick.Contract()) { uri ->
        if (uri == null) return@registerForActivityResult
        val base64 = decodeImageUri(uri)
        if (base64 == null) {
            Toast.makeText(this, "The image could not be read", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingGoalImageBase64 = base64
        goalImagePreview.setImageBitmap(BitmapFactory.decodeByteArray(
            Base64.decode(base64, Base64.NO_WRAP), 0, Base64.decode(base64, Base64.NO_WRAP).size
        ))
        goalImagePreviewRow.visibility = View.VISIBLE
    }

    /** গ্যালারির ছবি → ১০২৪px-এ ছোট করা JPEG base64 (গোল-রো ও লাইভ চ্যাট দুটোই এটা ব্যবহার করে)। */
    private fun decodeImageUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return@use null
            val scaled = downscaleBitmap(original, 1024)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }

    // ---- লাইভ চ্যাট (গ্লাস কার্ড) -----------------------------------------------
    private var liveChatDialog: LiveChatDialog? = null
    private var lastRunFinished = false
    private var lastGoalText = ""

    private val pickChatImageLauncher = registerForActivityResult(GalleryPick.Contract()) { uri ->
        if (uri == null) return@registerForActivityResult
        val base64 = decodeImageUri(uri)
        if (base64 == null) Toast.makeText(this, "The image could not be read", Toast.LENGTH_SHORT).show()
        else liveChatDialog?.setPendingImage(base64)
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) liveChatDialog?.startVoice()
        else Toast.makeText(this, "Microphone permission is required to speak", Toast.LENGTH_SHORT).show()
    }

    private val liveChatUi = object : LiveChatUi {
        override fun pickImage() { pickChatImageLauncher.launch(Unit) }
        override fun hasMicPermission(): Boolean =
            ContextCompat.checkSelfPermission(this@AiBrowserActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        override fun askMicPermission() { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private val liveChatHost = object : LiveChatHost {
        override val context: android.content.Context get() = this@AiBrowserActivity
        override fun runSnapshot(): JSONObject = buildRunSnapshot()
        override fun applyChatAction(action: String, task: String, imageBase64: String?) = applyLiveChatAction(action, task, imageBase64)
    }

    private val liveChat: LiveChatController by lazy {
        LiveChatController(liveChatHost, lifecycleScope).also { c -> c.onUnreadChanged = { n -> updateLiveChatBadge(n) } }
    }

    /** v40: 💬 আইকনের উপরে ছোট ১, ২… — চ্যাট বন্ধ থাকা অবস্থায় AI-র বার্তা/প্রশ্ন এসেছে। চ্যাট খুললে মিলিয়ে যায়। */
    private fun updateLiveChatBadge(n: Int) {
        val tv = findViewById<TextView>(R.id.browserLiveChatBadge) ?: return
        if (n <= 0) { tv.visibility = View.GONE; return }
        tv.text = if (n > 9) "9+" else n.toString()
        tv.visibility = View.VISIBLE
        tv.scaleX = 0.4f; tv.scaleY = 0.4f
        tv.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun openLiveChat() {
        if (liveChatDialog?.isShowing == true) return
        val d = LiveChatDialog(this, liveChat, liveChatUi)
        liveChatDialog = d
        d.show()
        liveChat.onStatus(statusText.text?.toString().orEmpty())
    }

    /** চ্যাটের AI-কে দেওয়া "চলতি অবস্থা" — AI শুধু এটা থেকেই বলে, তাই মিথ্যা "হয়ে গেছে" বলে না। */
    private fun buildRunSnapshot(): JSONObject {
        val goal = currentGoal
        val recent = JSONArray()
        try {
            ContextWindowStore.recentForPrompt(this, sessionId).takeLast(6).forEach { recent.put(it) }
        } catch (e: Exception) { /* ignore */ }
        return JSONObject().apply {
            put("goal", goal ?: lastGoalText)
            put("running", aiSwitch.isChecked && !goal.isNullOrBlank() && !awaitingUserAnswer)
            put("paused", !goal.isNullOrBlank() && !aiSwitch.isChecked)
            put("awaiting_user", awaitingUserAnswer)
            put("finished", lastRunFinished)
            put("status", statusText.text?.toString().orEmpty())
            put("url", webView.url.orEmpty())
            put("title", webView.title.orEmpty())
            put("recent", recent)
            put("system", browserSystem)
        }
    }

    private fun setAiSwitchSilently(on: Boolean) {
        aiSwitch.setOnCheckedChangeListener(null)
        aiSwitch.isChecked = on
        aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
    }

    /** চ্যাটের AI যে কাজ ঠিক করেছে সেটা বিদ্যমান পথেই চালানো — নতুন কোনো লুপ নয়। */
    private fun applyLiveChatAction(action: String, task: String, image: String?) {
        // সেশন ৭: টুলস-সেশন মোডে চ্যাটের নির্দেশ ToolsAgent-এর কাছে যায় (পরের decide-এ user_note); এখানকার পুরনো লুপ চলে না
        toolsSession?.let { ts ->
            val app = applicationContext
            when (action) {
                "start_task", "add_instruction", "answer_agent" ->
                    if (task.isNotBlank()) com.hemel.lenspilot.tools.agent.ToolsAgents.send(app, ts.sessionId, task)
                "pause" -> com.hemel.lenspilot.tools.agent.ToolsAgents.pause(app, ts.sessionId)
                "resume" -> com.hemel.lenspilot.tools.agent.ToolsAgents.resume(app, ts.sessionId)
                "stop" -> com.hemel.lenspilot.tools.agent.ToolsAgents.stop(app, ts.sessionId)
            }
            return
        }
        when (action) {
            "start_task" -> {
                awaitingUserAnswer = false
                manualNudgeMode = false
                setAiSwitchSilently(true)
                routeUserInput(task, image)
            }
            "add_instruction", "answer_agent" -> {
                if (currentGoal.isNullOrBlank()) {
                    // চলতি কাজ নেই — নির্দেশটাই নতুন কাজ
                    awaitingUserAnswer = false
                    manualNudgeMode = false
                    setAiSwitchSilently(true)
                    routeUserInput(task, image)
                } else if (awaitingUserAnswer) {
                    routeUserInput(task, image)          // এজেন্টের প্রশ্নের উত্তর
                } else if (aiSwitch.isChecked) {
                    manualNudgeMode = true               // চলন্ত কাজে নির্দেশ — থামায় না
                    routeUserInput(task, image)
                } else {
                    // থামানো কাজ: নির্দেশ জুড়ে একই জায়গা থেকে চালু
                    if (useAgent()) agent?.nudge(task, image)
                    else {
                        pendingStepImageBase64 = image
                        ContextWindowStore.remember(this, sessionId, "The user said something new: $task")
                    }
                    aiSwitch.isChecked = true            // listener → startAutoLoop (resume)
                }
            }
            "pause" -> if (aiSwitch.isChecked) aiSwitch.isChecked = false
            "resume" -> if (!currentGoal.isNullOrBlank() && !aiSwitch.isChecked) aiSwitch.isChecked = true
            "stop" -> {
                agent?.cancelLoop()
                AgentStateStore.clear(this)
                currentGoal = null
                v2Started = false
                awaitingUserAnswer = false
                manualNudgeMode = false
                stopAutoLoop("Stopped", clearMemory = true)
                releaseRunResources()
                hideGoalRow()
            }
        }
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStepRunnable: Runnable? = null

    // v142: পেজের মূল স্ক্রল-এলাকা খোঁজা (window নাকি ভেতরের overflow:auto বক্স) — জ্যামিতি দেখে, কোনো শব্দ-তালিকা নয়
    private val MAIN_SCROLLER_FN = """
        function lpMainScroller(){
          var de = document.scrollingElement || document.documentElement;
          if (de.scrollHeight - de.clientHeight > 40) return de;
          var best = null, ba = 0, all = document.querySelectorAll('div,main,section,ul,ol,nav,article');
          for (var i = 0; i < all.length; i++) {
            var e = all[i];
            if (e.scrollHeight - e.clientHeight > 40) {
              var oy = getComputedStyle(e).overflowY;
              if (oy === 'auto' || oy === 'scroll') {
                var r = e.getBoundingClientRect();
                var a = r.width * r.height;
                if (a > ba && r.bottom > 0 && r.top < window.innerHeight) { ba = a; best = e; }
              }
            }
          }
          return best || de;
        }
    """.trimIndent()

    private val SCROLL_INFO_JS: String get() = "(function(){" + MAIN_SCROLLER_FN +
        "var e=lpMainScroller();var de=document.scrollingElement||document.documentElement;" +
        "return JSON.stringify({y:Math.round(e.scrollTop),max:Math.round(e.scrollHeight-e.clientHeight),vh:Math.round(e.clientHeight),inner:e!==de});})();"

    private val SCROLL_DO_JS: String get() = "(function(){" + MAIN_SCROLLER_FN +
        "var e=lpMainScroller();var d=Math.max(200,Math.round(e.clientHeight*0.8));" +
        "if(e.scrollBy){e.scrollBy({top:d,behavior:'instant'});}else{e.scrollTop+=d;}" +
        "if(e===(document.scrollingElement||document.documentElement)){window.scrollBy(0,0);}})();"

    // v142: প্রতি ধাপের "কী করেছি → ফল কী হলো" মনে রাখার জন্য
    private var lastElements: List<PrunedElement> = emptyList()
    private var lastStepUrl: String = ""
    private var pendingActionNote: String? = null
    // v143: সত্যিকারের চ্যাট-context — প্রতি ধাপের (স্ক্রিন-বার্তা, মডেলের action) জোড়া, সেশন অনুযায়ী
    private val chatTurns = HashMap<String, MutableList<Pair<String, String>>>()

    // ---- Browser Agent v2 (app.py: agent_v:2) --------------------------------
    // সার্ভারে v2 বন্ধ থাকলে বা পুরনো সার্ভার হলে (plan কল agent_v!=2) এই গোটা goal-টা পুরনো লুপে চলে।
    private var agent: BrowserAgentV2? = null
    private var v2Started = false
    private var legacyForced = false
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private fun useAgent(): Boolean = !legacyForced && v2Started && agent != null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)   // বাতিল = null (WebView-কে অবশ্যই একবার জানাতে হয়)
    }

    private val agentHost = object : AgentHost {
        override fun awaitPageReady(onReady: () -> Unit) { waitUntilPageReady(onReady) }
        // v152: expectation-aware wait (see BrowserAgentV2.awaitExpectAfter)
        override fun armExpect(labels: List<String>) {
            if (labels.isEmpty()) return
            webView.evaluateJavascript(armExpectJs(org.json.JSONArray(labels).toString())) { }
        }
        override fun awaitPageExpect(labels: List<String>, onReady: (matched: Boolean) -> Unit) {
            val spec = org.json.JSONArray(labels).toString()
            awaitInPage({ t -> expectJs(t, spec) }, false) {
                webView.evaluateJavascript("window.__lpExpMatched===true") { r -> onReady(r?.trim() == "true") }
            }
        }
        override fun awaitPageChange(onReady: (idle: Boolean) -> Unit) {
            waitForPageChange {
                webView.evaluateJavascript("window.__lpIdle===true") { r -> onReady(r?.trim() == "true") }
            }
        }
        override val context: android.content.Context get() = this@AiBrowserActivity
        override val webView: WebView get() = this@AiBrowserActivity.webView
        override fun setStatus(text: String) { statusText.text = text }
        override fun onThought(step: Int, note: String, think: List<String>, deep: Boolean) {
            this@AiBrowserActivity.recordThought(step, note, think, deep)
        }
        override fun onAutoPromptSending(step: Int, headline: String, screenData: String) {
            if (::autoChat.isInitialized) autoChat.sending(step, headline, screenData)
        }
        override fun onAutoPromptAnswered(step: Int, prompt: String, hadImage: Boolean, message: String, actionCode: String) {
            if (!::autoChat.isInitialized) return
            autoChat.delivered(step, prompt, hadImage)
            autoChat.reply(step, message, actionCode)
        }
        override fun onAutoPromptParts(step: Int, message: String, memory: String, screen: String) {
            if (::autoChat.isInitialized) autoChat.parts(step, message, memory, screen)
        }
        override fun onAutoPromptFailed(step: Int, reason: String) {
            if (::autoChat.isInitialized) autoChat.failed(step, reason)
        }
        override fun isAiOn(): Boolean = aiSwitch.isChecked
        override fun onTaskFinished(message: String) {
            lastRunFinished = true
            liveChat.postAgent(message)
            currentGoal = null
            v2Started = false
            stopAutoLoop(message, clearMemory = true)
            releaseRunResources()
        }
        override fun onHandoff(kind: String, message: String) {
            // ক্যাপচা/লগইন/OTP/পেমেন্ট: লুপ থেমেছে, session টিকে আছে; ইউজার পেজেই কাজ করবে (তাই গোল-রো ঢাকা নয়)
            awaitingUserAnswer = true
            manualNudgeMode = false
            statusText.text = message
            goalInput.hint = "It will continue automatically when it finishes. If it does not, type \"continue\"."
            if (kind != "captcha") doneButton.visibility = View.VISIBLE
            notifyChatHumanNeeded(message)
        }
        override fun onAskUser(message: String) { pauseForUserAnswer(message) }
        override fun confirmIrreversible(message: String, onResult: (String) -> Unit) {
            AlertDialog.Builder(this@AiBrowserActivity)
                .setTitle("Please confirm once")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Yes, this time only") { _, _ -> onResult("ONCE") }
                .setNeutralButton("Yes to all in this task") { _, _ -> onResult("ALL") }
                .setNegativeButton("No, stop") { _, _ -> onResult("STOP") }
                .show()
        }
        override fun pickFile(ref: String, callback: ValueCallback<Array<Uri>>) {
            val ts = toolsSession
            if (ts != null) {       // Tools মোড: Storage-ফাইল অথবা সিস্টেম পিকার
                ts.chooseFile(callback) { pendingFileCallback = callback; pickFileLauncher.launch("*/*") }
                return
            }
            pendingFileCallback = callback
            pickFileLauncher.launch("*/*")
        }
        override fun fallbackToLegacy() {
            legacyForced = true
            v2Started = false
            statusText.text = "⚡ Working…"
            scheduleNextStep(STEP_SETTLE_DELAY_MS)
        }
        override fun onRunStateChanged(running: Boolean) {
            // v134: when the agent runs again (also on its own after a hand-off resolved by itself) it is no longer waiting for an answer —
            // the stale flag made later typed messages restart the loop / route as an "answer" instead of a note to the running task.
            if (running) { acquireRunResources(); doneButton.visibility = View.GONE; awaitingUserAnswer = false }
        }
        override fun askChoice(message: String, options: List<String>, onResult: (String?) -> Unit) {
            statusText.text = message
            VaultUi.askChoice(this@AiBrowserActivity, message, options, onResult)
        }
        override fun askSecret(title: String, label: String, kind: String, onResult: (Boolean) -> Unit) {
            statusText.text = "\"$title\" needs \"$label\". Enter it in the secure box."
            VaultUi.askSecret(this@AiBrowserActivity, title, label, kind, onResult)
        }
        override fun startOtpAssist(source: String, appName: String): Boolean {
            otpAssistOpen = true
            otpClipFp = OtpSupport.clipFingerprint(this@AiBrowserActivity)
            val opened = OtpSupport.openSourceApp(this@AiBrowserActivity, source, appName)
            val svc = LenspilotAccessibilityService.instance
            if (opened && svc != null) {
                // অ্যাপ সামনে আসার পর গাইডলাইন: কোড কপির জায়গা হাইলাইট + কন্ট্রোল বারে "কপি হয়েছে"
                handler.postDelayed({ if (otpAssistOpen) svc.startOtpCopyGuide(OtpSupport.guideGoal(source, appName), browserSystem) }, 1000)
            } else if (opened) {
                statusText.text = "Copy the code and return to Lenspilot. I will enter it automatically."
            }
            return opened
        }
        override fun stopOtpAssist() {
            otpAssistOpen = false
            LenspilotAccessibilityService.instance?.stopOtpCopyGuide()
        }
    }

    /** B13: রান চলাকালীন স্ক্রিন-অন + foreground service (নোটিফিকেশন + বন্ধ বাটন + WakeLock)। */
    private fun acquireRunResources() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BrowserAgentService.start(this)
    }

    private fun releaseRunResources() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BrowserAgentService.stop(this)
    }

    private var currentGoal: String? = null
    private var stepCount = 0
    private lateinit var doneButton: TextView
    private var otpAssistOpen = false
    private var otpClipFp = 0

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
        waitingForPageFinished = false
        readyToken++; readyDone = null; readyRestart = null
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
        goalInput.hint = "It will continue automatically once the captcha is solved. If it does not, type \"continue\"."
        notifyChatHumanNeeded(message)
        handler.removeCallbacks(captchaPollRunnable)
        handler.postDelayed(captchaPollRunnable, CAPTCHA_POLL_MS)
    }

    private fun resumeAfterCaptcha() {
        captchaWaiting = false
        handler.removeCallbacks(captchaPollRunnable)
        awaitingUserAnswer = false
        ContextWindowStore.remember(this, sessionId, "The user solved the captcha; the task is continuing")
        statusText.text = "✅ Captcha complete. Continuing…"
        scheduleNextStep(STEP_SETTLE_DELAY_MS)
    }
    private var waitingForPageFinished = false

    // v144: ঘটনা-ভিত্তিক অপেক্ষা (টাইমার ছাড়া)
    private var navInFlight = false                 // onPageStarted হয়েছে, লোড-শেষ এখনো আসেনি
    private var readyToken = 0                      // প্রতি অপেক্ষার নিজস্ব টোকেন — পুরনো সংকেত বাতিল
    private var readyDone: (() -> Unit)? = null     // পেজ "তৈরি" সংকেত দিলে যা চলবে
    private var readyRestart: (() -> Unit)? = null  // অপেক্ষার মাঝে পেজ বদলালে নতুন পেজে আবার অপেক্ষা শুরু

    /** পেজের ভেতরের JS এখান দিয়েই জানায় "যা দরকার তা এসেছে"। */
    private inner class LpReadyBridge {
        @JavascriptInterface
        fun ready(token: Int) {
            runOnUiThread {
                if (token != readyToken) return@runOnUiThread
                val cb = readyDone ?: return@runOnUiThread
                readyDone = null
                cb()
            }
        }
    }

    /** নেভিগেশন শুরু হয়েছে — কোনো টাইমার নেই; onPageFinished (বা লোড ১০০%) এলেই loadSettled() এগিয়ে নেবে। */
    private fun beginPageLoadWait() {
        waitingForPageFinished = true
        navInFlight = true
    }

    /** পেজ লোড শেষ (onPageFinished অথবা progress=100)। একাধিকবার ডাকলেও নিরাপদ। */
    private fun loadSettled() {
        navInFlight = false
        if (waitingForPageFinished) {
            waitingForPageFinished = false
            scheduleNextStep(STEP_SETTLE_DELAY_MS)
        } else {
            readyRestart?.let { readyRestart = null; it() }   // অপেক্ষার মাঝে পেজ বদলেছিল — নতুন পেজে আবার
        }
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
        updateStatusDot()
        if (checked) {
            if (currentGoal.isNullOrBlank()) showGoalRow() else { hideGoalRow(); startAutoLoop() }
        } else {
            if (useAgent()) agent?.pause()       // প্ল্যান + লেজার ডিস্কে থাকে; আবার চালু করলে একই জায়গা থেকে
            stopAutoLoop("Manual mode. Browse on your own.", clearMemory = false)
            releaseRunResources()
        }
    }

    /**
     * v43: প্রতিটি ট্যাবের WebView-তে একই সেটিংস/ক্লায়েন্ট বসে (প্রথম ট্যাব ও নতুন ট্যাব/পপআপ দুটোতেই)।
     * AI সুইচ চালু থাকলে আগের এজেন্ট-নিরাপদ আচরণই থাকে; বন্ধ থাকলে (ম্যানুয়াল) আসল ব্রাউজারের আচরণ।
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.setSupportZoom(true)
        st.builtInZoomControls = true
        st.displayZoomControls = false
        st.setSupportMultipleWindows(true)
        st.allowFileAccess = false
        // থার্ড-পার্টি কুকি চালু (লগইন/এমবেডেড ফ্লো আসল ব্রাউজারের মতো কাজ করার জন্য)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = buildChromeClient()
        wv.webViewClient = buildWebViewClient()
        wv.addJavascriptInterface(LpReadyBridge(), "LpReady")
        real.decorate(wv)
    }

    private fun buildChromeClient(): WebChromeClient = object : WebChromeClient() {
            // আপলোড: এজেন্ট চললে সে হ্যান্ডেল করে (আগে-দেওয়া ফাইল বা ইউজারকে বাছতে বলা); নইলে ম্যানুয়াল ব্রাউজিংয়ে সরাসরি পিকার
            override fun onShowFileChooser(
                view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                val cb = filePathCallback ?: return false
                pendingFileCallback?.onReceiveValue(null)
                if (useAgent() && aiSwitch.isChecked) return agent!!.handleFileChooser(cb)
                val ts = toolsSession
                if (ts != null) {   // Tools মোড: সেশন-Storage-এর ফাইল অথবা সিস্টেম পিকার
                    ts.chooseFile(cb) { pendingFileCallback = cb; pickFileLauncher.launch("*/*") }
                    return true
                }
                pendingFileCallback = cb
                pickFileLauncher.launch("*/*")
                return true
            }

            // JS ডায়ালগ ঘটনা হিসেবে আউটকামে যায়। confirm/prompt ডিফল্টে বাতিল (এজেন্ট `dialog{accept:true}` দিলে একবার গ্রহণ)।
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                agent?.noteDialog("alert", message.orEmpty())
                if (aiSwitch.isChecked) result?.confirm() else real.jsAlert(message) { result?.confirm() }
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val a = agent
                val accept = a?.acceptNextDialog == true
                a?.acceptNextDialog = false
                a?.noteDialog("confirm", message.orEmpty())
                if (a == null || !aiSwitch.isChecked) {
                    AlertDialog.Builder(this@AiBrowserActivity).setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }.show()
                } else if (accept) result?.confirm() else result?.cancel()
                return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                agent?.noteDialog("prompt", message.orEmpty())
                if (aiSwitch.isChecked) result?.cancel()
                else real.jsPrompt(message, defaultValue) { v -> if (v == null) result?.cancel() else result?.confirm(v) }
                return true
            }

            // ক্যামেরা/মাইক/লোকেশন ইত্যাদি: ডিফল্টে ডিনাই
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (aiSwitch.isChecked) {
                    runOnUiThread { request?.deny() }
                    agent?.noteEvent("error(permission_denied)")
                } else real.handlePermissionRequest(request)
            }

            // v43: আসল-ব্রাউজারের কলব্যাক
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                real.progressChanged(view, newProgress)
                if (newProgress >= 100 && view === webView) { agent?.onLoadProgressDone(); loadSettled() }   // onPageFinished না এলেও (ডাউনলোড/SPA) আটকে না যাওয়ার জন্য — ঘটনা, টাইমার নয়
            }
            override fun onReceivedTitle(view: WebView?, title: String?) { real.titleChanged(view, title) }
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) { real.showCustomView(view, callback) }
            override fun onHideCustomView() { real.hideCustomView() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                real.geolocationPrompt(origin, callback)
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
                real.createWindow(resultMsg)
            override fun onCloseWindow(window: WebView?) { real.closeWindow(window) }
        }

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {
            // http/https ছাড়া স্কিম ব্লক (intent://, fb://, tel:…); intent://-এ browser_fallback_url থাকলে সেটা
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // Tools মোড: গুগল সাইন-ইনে সাধারণ মোবাইল UA (একবার) / আটকালে needs_you — tools/browser/GoogleLoginAssist.kt
                val ts = toolsSession
                if (ts != null && view != null && request?.isForMainFrame == true &&
                    ts.google.onMainFrameNavigate(view, uri.toString(), request.method.equals("GET", ignoreCase = true)) { m -> ts.needsYou(m) }
                ) return true
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "intent") {
                    val fb = try { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") } catch (e: Exception) { null }
                    if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) { view?.loadUrl(fb); return true }
                }
                if (!aiSwitch.isChecked) {
                    // v43: ম্যানুয়াল ব্রাউজিংয়ে tel:/mailto:/intent: ইত্যাদি ইউজারকে জিজ্ঞেস করে খোলে
                    if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                    real.openExternalScheme(uri)
                    return true
                }
                agent?.noteEvent("error(scheme:$scheme)")
                agent?.onPageFinished()                      // V2: এই নেভিগেশন কখনো লোড হবে না — অপেক্ষা যেন না আটকায়
                if (waitingForPageFinished) loadSettled()   // V1: একই
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    agent?.noteError("${error?.errorCode}:${error?.description}".take(80))
                    if (!aiSwitch.isChecked) real.showErrorPage(view, request.url?.toString(), error?.errorCode ?: 0, error?.description?.toString())
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) agent?.noteError("http_${errorResponse?.statusCode}")
                if (request?.isForMainFrame == true) {
                    toolsSession?.let { ts -> ts.google.onMainFrameHttpError(request.url?.toString(), errorResponse?.statusCode ?: 0) { m -> ts.needsYou(m) } }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                real.pageStarted(view, url)
                navInFlight = true
                agent?.onNavStarted(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                real.pageFinished(view, url)
                toolsSession?.let { ts -> ts.flushCookies(); ts.google.onPageFinished(url) { m -> ts.needsYou(m) } }
                agent?.onPageFinished()
                loadSettled()
            }

            // v144: নতুন পেজের প্রথম কনটেন্ট দেখা গেলেই (ঘটনা) নেটওয়ার্ক-গণনা বসানো; V2 নেভিগেশনে "কাজের স্ক্রিন এসেছে" সংকেতও এখান থেকে।
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view !== webView) return
                view.evaluateJavascript("(function(){" + NET_PATCH + "})()", null)
                if (aiSwitch.isChecked && agent?.navPending() == true) {
                    awaitInPage({ t -> readyJs(t) }, true) { agent?.onPageFinished() }
                }
            }

            // v43: আসল-ব্রাউজারের কলব্যাক
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { real.urlChanged(view, url) }

            override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                val h = sslHandler ?: return
                if (aiSwitch.isChecked) { h.cancel(); return }
                real.sslProblem(error?.url.orEmpty(), { h.proceed() }, { h.cancel() })
            }

            override fun onReceivedHttpAuthRequest(view: WebView?, authHandler: HttpAuthHandler?, authHost: String?, realm: String?) {
                val h = authHandler ?: return
                if (aiSwitch.isChecked) { h.cancel(); return }
                real.httpAuth(authHost.orEmpty(), realm.orEmpty(), { u, pw -> h.proceed(u, pw) }, { h.cancel() })
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                real.rendererGone(view)
                return true
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_browser)
        // v100: beige "discussion" theme — status bar matches the control bar, dark icons
        window.statusBarColor = ContextCompat.getColor(this, R.color.bb_bar)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        webView = findViewById(R.id.aiWebView)
        statusText = findViewById(R.id.browserStatusText)
        thinkText = findViewById(R.id.browserThinkText)
        thinkText.setOnClickListener { showThoughtHistory() }
        systemBadge = findViewById(R.id.browserSystemBadge)
        aiSwitch = findViewById(R.id.browserAiSwitch)
        closeButton = findViewById(R.id.browserCloseButton)
        statusDot = findViewById(R.id.browserStatusDot)
        progressBar = findViewById(R.id.browserProgressBar)
        goalRow = findViewById(R.id.browserGoalRow)
        goalInput = findViewById(R.id.browserGoalInput)
        goalGoButton = findViewById(R.id.browserGoalGoButton)
        goalCloseButton = findViewById(R.id.browserGoalCloseButton)
        goalAttachButton = findViewById(R.id.browserGoalAttachButton)
        goalImagePreviewRow = findViewById(R.id.browserGoalImagePreviewRow)
        goalImagePreview = findViewById(R.id.browserGoalImagePreview)
        goalImageRemoveButton = findViewById(R.id.browserGoalImageRemoveButton)

        real = RealBrowser(this, object : RealBrowser.Host {
            override fun isAiOn(): Boolean = aiSwitch.isChecked
            override fun configureWebView(wv: WebView) { this@AiBrowserActivity.configureWebView(wv) }
            override fun onActiveWebViewChanged(wv: WebView) { webView = wv }
        })
        configureWebView(webView)
        real.attach(webView)
        BrowserAgentService.onStopRequested = {
            runOnUiThread {
                if (useAgent()) agent?.pause()
                stopAutoLoop("Stopped. Turn AI on to resume from the same point.", clearMemory = false)
                releaseRunResources()
            }
        }
        closeButton.setOnClickListener { finish() }
        // v110: address bar hidden by default; a small floating arrow at the top centre of the page shows/hides it
        findViewById<TextView>(R.id.browserBarHandle).let { handle ->
            val urlBarView = findViewById<View>(R.id.browserUrlBar)
            handle.setOnClickListener {
                val show = urlBarView.visibility != View.VISIBLE
                urlBarView.visibility = if (show) View.VISIBLE else View.GONE
                handle.text = if (show) "⌃" else "⌄"
            }
        }
        // v110: tap the "what is the AI doing" card → thinking opens in its own box at the bottom (tap again / ✕ to close)
        thinkPanel = findViewById(R.id.browserThinkPanel)
        thinkPanelScroll = findViewById(R.id.browserThinkPanelScroll)
        thinkPanelScroll.maxHeightPx = (340 * resources.displayMetrics.density).toInt()
        autoChat = AutoPromptChat(this, findViewById(R.id.browserChatList), thinkPanelScroll)
        findViewById<View>(R.id.browserStatusCard).setOnClickListener { toggleThinkPanel() }
        findViewById<View>(R.id.browserThinkPanelClose).setOnClickListener { toggleThinkPanel(false) }
        findViewById<ImageButton>(R.id.browserLiveChatButton).setOnClickListener { openLiveChat() }
        findViewById<ImageButton>(R.id.browserVaultButton).setOnClickListener { VaultUi.showManager(this) }
        // v115: visible AI on/off button — flips the (hidden) switch, whose listener starts / pauses the agent.
        findViewById<ImageButton>(R.id.browserAiToggleButton).setOnClickListener { aiSwitch.isChecked = !aiSwitch.isChecked }
        doneButton = findViewById(R.id.browserDoneButton)
        doneButton.setOnClickListener {
            doneButton.visibility = View.GONE
            routeUserInput("Done", null)
        }
        // এজেন্টের লাইভ স্ট্যাটাস চ্যাটের "এখন কী করছে" চিপে — স্ট্যাটাস-টেক্সটের যেকোনো বদল ধরে
        statusText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                liveChat.onStatus(s?.toString().orEmpty())
                updateStatusDot()
            }
        })
        updateStatusDot()
        goalCloseButton.setOnClickListener { hideGoalRow() }
        goalAttachButton.setOnClickListener { pickImageLauncher.launch(Unit) }
        goalImageRemoveButton.setOnClickListener {
            pendingGoalImageBase64 = null
            goalImagePreviewRow.visibility = View.GONE
        }

        aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()

        goalGoButton.setOnClickListener { submitGoalFromInput() }
        goalInput.setOnEditorActionListener { _, _, _ -> submitGoalFromInput(); true }

        browserSystem = intent.getStringExtra(EXTRA_SYSTEM)?.ifBlank { null } ?: "super_1_2"
        updateSystemBadge()
        // Tools মোড: থিন র‍্যাপার — ব্রাউজারের মূল কোড বাদে শুধু সেশন-সংযোগ (tools/browser/)
        intent.getStringExtra(EXTRA_TOOLS_SESSION_ID)?.takeIf { ToolsPaths.isValidId(it) }?.let { setupToolsSession(it) }
        val goalFromIntent = intent.getStringExtra(EXTRA_GOAL)
        // v134 (ROOT CAUSE of "back to the login page" + "forgot the task"): when Android kills this screen while the person is away
        // (Google account chooser, OTP app, password manager…) it re-creates it with the ORIGINAL launch intent, which still carries
        // EXTRA_GOAL → the branch below started the task from scratch: plan + ledger wiped, start URL / login page loaded again.
        // A re-created screen (savedInstanceState != null) with a saved run now resumes that run instead.
        val recreatedWithRun = savedInstanceState != null && toolsSession == null && AgentStateStore.load(this) != null
        if (recreatedWithRun && restoreAgentFromDisk()) {
            // same task, same plan/ledger/history, page reloaded at the last saved address
        } else if (!goalFromIntent.isNullOrBlank()) {
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
            aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
            statusText.text = "⚡ Working…"
            startAutoLoop()
        } else if (intent.getBooleanExtra(EXTRA_RESUME, false) && restoreAgentFromDisk()) {
            // নোটিফিকেশন থেকে ফেরা — ডিস্কের v2-সেশন থেকে চলছে
        } else if (toolsSession != null) {
            // Tools মোডে ডিফল্ট গুগল-সার্চ নয়: দেওয়া URL, নইলে ফাঁকা পেজ (অর্কেস্ট্রেটর open(url) দেবে)
            val start = intent.getStringExtra(EXTRA_TOOLS_URL)
            webView.loadUrl(if (!start.isNullOrBlank() && (start.startsWith("https://") || start.startsWith("http://"))) start else "about:blank")
            statusText.text = "ELORA browser ready"
        } else {
            webView.loadUrl("https://www.google.com")
        }
    }

    /** Tools মোড: সেশনের ডাউনলোড-ফোল্ডার, আপলোড-রিজলভার, ওয়ার্কস্পেস-সেতু, Shell/Storage-এর দ্রুত আইকন। */
    private fun setupToolsSession(sessionId: String) {
        val ts = ToolsBrowserSession(this, sessionId)
        toolsSession = ts
        ts.aiOn = { aiSwitch.isChecked }
        ts.onHandoffUi = { msg -> agentHost.onHandoff("login", msg) }
        real.downloadSink = ts.downloads          // ডাউনলোড → sessions/<id>/workspace/downloads/
        legacyForced = false
        val a = agent ?: BrowserAgentV2(agentHost, lifecycleScope).also { agent = it }
        a.toolsFileResolver = { name -> ts.resolveUpload(name) }
        v2Started = true
        ts.attachWorkspace({ agent }, { webView })
        // AI-নিয়ন্ত্রণ চালু (নীরবে — লিসেনার ছাড়া, নইলে গোল-রো খুলে যেত)
        aiSwitch.setOnCheckedChangeListener(null)
        aiSwitch.isChecked = true
        aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
        findViewById<View>(R.id.browserToolsShellColumn).visibility = View.VISIBLE
        findViewById<View>(R.id.browserToolsStorageColumn).visibility = View.VISIBLE
        findViewById<View>(R.id.browserToolsShellButton).setOnClickListener { ts.openShell() }
        findViewById<View>(R.id.browserToolsStorageButton).setOnClickListener { ts.openStorage() }
    }

    /** অ্যাক্টিভিটি মরে গেলে প্ল্যান + লেজার + ইতিহাস ডিস্ক থেকে ফিরিয়ে একই জায়গা থেকে চালায়। */
    private fun restoreAgentFromDisk(): Boolean {
        val st = AgentStateStore.load(this) ?: return false
        val goal = st.optString("goal")
        if (goal.isBlank()) return false
        currentGoal = goal
        browserSystem = st.optString("system", "super_1_2").ifBlank { "super_1_2" }
        updateSystemBadge()
        legacyForced = false
        val a = BrowserAgentV2(agentHost, lifecycleScope)
        agent = a
        v2Started = true
        aiSwitch.setOnCheckedChangeListener(null)
        aiSwitch.isChecked = true
        aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
        hideGoalRow()
        return if (a.resumeFrom(st)) {
            statusText.text = "Resuming the previous task…"
            true
        } else {
            v2Started = false; agent = null
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkOtpReturn()
    }

    override fun onResume() {
        super.onResume()
        checkOtpReturn()
    }

    override fun onPause() {
        toolsSession?.flushCookies()          // Tools মোড: সাইন-ইন ডিস্কে টিকিয়ে রাখা
        // v134: সব মোডে — লগইনের কুকি ডিস্কে না গিয়ে স্ক্রিন/প্রসেস মরলে "লগইন সফল হয়েও আবার লগইন পেজ" হতো; আর রান-অবস্থা (লাইভ URL সহ) এখনই সেভ
        try { CookieManager.getInstance().flush() } catch (e: Exception) { }
        agent?.saveNow()
        super.onPause()
    }

    /** OTP কপির পর ফেরা: "কপি হয়েছে" বাটন চাপা হলে, নইলে (ফলব্যাক) ক্লিপবোর্ডে নতুন কোড দেখা গেলে এজেন্ট বসিয়ে চালায়। */
    private fun checkOtpReturn() {
        if (!otpAssistOpen) return
        val a = agent ?: return
        if (OtpBridge.consumeCopied()) { doneButton.visibility = View.GONE; a.otpCopied(); return }
        val clip = OtpSupport.clipboardText(this)
        val code = OtpSupport.extractCode(clip)
        if (OtpSupport.clipFingerprint(this) != otpClipFp && code != null && code.any { it.isDigit() }) {
            doneButton.visibility = View.GONE
            a.otpCopied()
        }
    }

    override fun onDestroy() {
        // মূল স্ক্রিনের ডেটাবেজ-বক্সের তথ্য শুধু এই রানের জন্য; ঘোরানোয় নয়, সত্যিই বন্ধ হলে মোছো
        if (isFinishing) com.hemel.lenspilot.workflow.WorkflowContext.clearRunInfo()
        liveChat.listener = null
        liveChatDialog?.dismiss()
        liveChatDialog = null
        toolsSession?.release()               // কুকি ফ্লাশ + ওয়ার্কস্পেস/প্রোব রেজিস্ট্রি থেকে সরানো
        agent?.cancelLoop()                   // লেজার/প্ল্যান ডিস্কে আছে — resume করা যাবে
        BrowserAgentService.onStopRequested = null
        releaseRunResources()
        if (::real.isInitialized) real.destroy()
        super.onDestroy()
        pendingStepRunnable?.let { handler.removeCallbacks(it) }
        readyToken++; readyDone = null; readyRestart = null
        handler.removeCallbacks(captchaPollRunnable)
    }

    override fun onBackPressed() {
        if (!real.onBackPressed()) super.onBackPressed()   // v43: ফুল-স্ক্রিন/খোঁজা/অ্যাড্রেস/পেছনে/ট্যাব বন্ধ — সব RealBrowser সামলায়
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

    /** v100: small dot on the AI activity card — teal + gently pulsing while AI is on, grey when manual. */
    private fun updateStatusDot() {
        if (!::statusDot.isInitialized || !::aiSwitch.isInitialized) return
        val on = aiSwitch.isChecked
        findViewById<ImageButton?>(R.id.browserAiToggleButton)?.let { b ->   // v115: teal + white icon = AI on
            b.backgroundTintList = if (on) android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bb_accent)) else null
            b.imageTintList = android.content.res.ColorStateList.valueOf(if (on) android.graphics.Color.WHITE else ContextCompat.getColor(this, R.color.bb_ink))
            b.contentDescription = if (on) "AI automation is on. Tap to turn off" else "AI automation is off. Tap to turn on"
        }
        statusDot.setBackgroundResource(if (on) R.drawable.bb_status_dot else R.drawable.bb_status_dot_off)
        statusDot.animate().cancel()
        if (on) {
            statusDot.alpha = 1f
            statusDot.animate().alpha(0.35f).setDuration(700).withEndAction { pulseDot() }.start()
        } else {
            statusDot.alpha = 1f
        }
    }

    private fun pulseDot() {
        if (!::statusDot.isInitialized || !::aiSwitch.isInitialized || !aiSwitch.isChecked || isFinishing) return
        val toLow = statusDot.alpha > 0.7f
        statusDot.animate().alpha(if (toLow) 0.35f else 1f).setDuration(700).withEndAction { pulseDot() }.start()
    }

    private fun showGoalRow() {
        manualNudgeMode = false
        awaitingUserAnswer = false
        goalInput.hint = "What would you like to do?…"
        goalRow.visibility = View.VISIBLE
        statusText.text = "What would you like to do?"
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
            "Add something to the current task… (it will not stop; it joins the next step)"
        else
            "What would you like to do?…"
        goalRow.visibility = View.VISIBLE
        goalInput.requestFocus()
    }

    /** Starts a brand new task: fresh session id, fresh on-device context
     * window, no carried-over browsing-RAG note (a new goal may not match
     * the same one — the next request just searches again, cheaply). */
    /** v147: the thinking log is gone — the panel now shows the auto-prompt chat. (Kept only so the agent's onThought call still compiles.) */
    private fun recordThought(step: Int, note: String, think: List<String>, deep: Boolean) { /* no thinking log any more */ }

    /** v147: open/close the auto-prompt chat panel. */
    private fun toggleThinkPanel(show: Boolean? = null) {
        val open = show ?: (thinkPanel.visibility != View.VISIBLE)
        thinkPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) thinkPanelScroll.post { thinkPanelScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showThoughtHistory() {
        if (thoughtLog.isEmpty()) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = thoughtLog.asReversed().joinToString("\n\n")
            textSize = 13f
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Thinking history").setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> thoughtLog.clear(); thinkText.visibility = View.GONE }
            .show()
    }

    private fun beginNewGoal(goal: String) {
        if (::thinkText.isInitialized) { thoughtLog.clear(); thinkText.visibility = View.GONE; thinkText.alpha = 1f }
        if (::autoChat.isInitialized) autoChat.clear()     // v147: a new task starts a fresh auto-prompt chat
        currentGoal = goal
        lastGoalText = goal
        lastRunFinished = false
        stepCount = 0
        awaitingUserAnswer = false
        manualNudgeMode = false
        captchaWaiting = false
        captchaIgnoreSteps = 0
        handler.removeCallbacks(captchaPollRunnable)
        sessionId = UUID.randomUUID().toString().replace("-", "")
        browsingRagNoteId = null
        goalChangedPending = true
        agent?.cancelLoop()
        v2Started = false
        legacyForced = false
        AgentStateStore.clear(this)
        ContextWindowStore.startSession(this, sessionId, goal)
    }

    /** ক্যাপচা/লগইন/OTP/পেমেন্ট: মানুষকেই পেজে কাজ করতে হবে — চ্যাট কার্ড সরিয়ে পেজ খুলে দেয়, কথোপকথন থাকে। */
    private fun notifyChatHumanNeeded(message: String) {
        liveChat.postAgent(message)
        val d = liveChatDialog
        if (d != null && d.isShowing) {
            d.dismiss()
            Toast.makeText(this, "Complete the task on the page yourself. Tap 💬 to reopen the chat.", Toast.LENGTH_LONG).show()
        }
    }

    private fun submitGoalFromInput() {
        val text = goalInput.text?.toString()?.trim().orEmpty()
        val image = pendingGoalImageBase64
        if (text.isEmpty() && image == null) return
        goalInput.setText("")
        pendingGoalImageBase64 = null
        goalImagePreviewRow.visibility = View.GONE
        hideGoalRow()
        routeUserInput(text, image)
    }

    /** টেক্সট/ছবি কোথায় যাবে (এজেন্টের প্রশ্নের উত্তর / চলন্ত কাজে নির্দেশ / নতুন কাজ) — গোল-রো আর লাইভ চ্যাট দুটোরই একই পথ। */
    private fun routeUserInput(text: String, image: String?) {
        if (awaitingUserAnswer && !currentGoal.isNullOrBlank() && useAgent()) {
            // v2: উত্তর/নির্দেশ একই run-এ (প্ল্যান + লেজার অক্ষত) — পরের ধাপে মডেল দেখে
            awaitingUserAnswer = false
            statusText.text = "OK, continuing…"
            if (!aiSwitch.isChecked) {
                aiSwitch.setOnCheckedChangeListener(null)
                aiSwitch.isChecked = true
                aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
            }
            agent!!.provideUserText(text, image)
            return
        }

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
            ContextWindowStore.remember(this, sessionId, "The user said: ${text.ifBlank { "(sent an image)" }}")
            statusText.text = "OK, continuing…"
            if (!aiSwitch.isChecked) {
                aiSwitch.setOnCheckedChangeListener(null)
                aiSwitch.isChecked = true
                aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
            }
            scheduleNextStep(STEP_SETTLE_DELAY_MS)
            return
        }

        if (manualNudgeMode && aiSwitch.isChecked && !currentGoal.isNullOrBlank() && useAgent()) {
            manualNudgeMode = false
            agent!!.nudge(text.ifBlank { "(sent an image)" }, image)
            statusText.text = if (text.isNotBlank()) "OK: $text" else "Image added. It will be reviewed in the next step."
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
            ContextWindowStore.remember(this, sessionId, "The user said something new: ${text.ifBlank { "(sent an image)" }}")
            statusText.text = if (text.isNotBlank()) "OK: $text" else "Image added. It will be reviewed in the next step."
            return
        }

        manualNudgeMode = false
        pendingStepImageBase64 = image
        beginNewGoal(text.ifBlank { "Please help using the image I sent" })
        // Same fix as onCreate's goalFromIntent branch above — let the model's
        // own first decision pick navigate-vs-search from the real current
        // page instead of force-loading a literal Google search of the goal.
        statusText.text = "⚡ Working…"
        startAutoLoop()
        // v2: নতুন goal-এর সাথে পাঠানো ছবি (begin() রান-অবস্থা রিসেট করার পরে যোগ হয়)
        if (useAgent() && image != null) agent?.nudge("", image)
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
        goalInput.hint = "Type a reply or give new instructions…"
        // চ্যাট খোলা থাকলে প্রশ্নটা সেখানেই AI-র বার্তা হিসেবে আসে, উত্তরও সেখানেই দেওয়া যায়
        liveChat.postAgent(message)
        // v40: চ্যাট আর জোর করে খোলে না — বন্ধ থাকলে 💬 আইকনে ১, ২… ব্যাজ ওঠে; খোলা থাকলে প্রশ্ন সেখানেই আসে
        goalRow.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Auto loop
    // ------------------------------------------------------------------

    private fun startAutoLoop() {
        if (currentGoal.isNullOrBlank()) return
        // v46: সুইচ বন্ধ থাকা অবস্থায় নতুন কাজ এলে লুপ নিঃশব্দে মরে যেত ("AI বন্ধ, কিছুই কাজ করে না") —
        // কাজ শুরু মানেই AI চালু; নিঃশব্দে সুইচ অন করে তারপর লুপ।
        if (!aiSwitch.isChecked) setAiSwitchSilently(true)
        if (!legacyForced) {
            // Browser Agent v2: প্রথমবার প্ল্যান নিয়ে শুরু; থামানো রান হলে একই জায়গা থেকে resume।
            val a = agent ?: BrowserAgentV2(agentHost, lifecycleScope).also { agent = it }
            acquireRunResources()
            if (v2Started) a.resume() else { v2Started = true; a.begin(currentGoal!!, browserSystem) }
            return
        }
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
        waitingForPageFinished = false
        readyToken++; readyDone = null; readyRestart = null
        captchaWaiting = false
        handler.removeCallbacks(captchaPollRunnable)
        if (reasonMessage != null) statusText.text = reasonMessage
        if (clearMemory) ContextWindowStore.clearSession(this, sessionId)
        // Flip the switch off without re-entering this same listener.
        if (aiSwitch.isChecked) {
            aiSwitch.setOnCheckedChangeListener(null)
            aiSwitch.isChecked = false
            aiSwitch.setOnCheckedChangeListener(aiSwitchListener); updateStatusDot()
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
            pauseForUserAnswer("Many steps have run ($stepLimit). Please check that the task is on track. Type anything above (even just \"continue\") and I will keep going with the same task.")
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
                ContextWindowStore.remember(this, sessionId, "[Captcha] A person is solving it")
                pauseForCaptcha(CAPTCHA_MESSAGE, fromProbe = true)
                return@evaluateJavascript
            }
            waitUntilPageReady {
            webView.evaluateJavascript(PRUNER_JS) { rawResult ->
                val elementsJson = unwrapJsString(rawResult)
                val elements = PrunedElement.listFromJsonArray(elementsJson)
                val currentUrl = webView.url.orEmpty()
                webView.evaluateJavascript("(function(){return document.title;})();") { titleRaw ->
                    val pageTitle = unwrapJsString(titleRaw)
                    webView.evaluateJavascript(SCROLL_INFO_JS) { scrollRaw ->
                      webView.evaluateJavascript("(function(){return JSON.stringify({d:(typeof window.__lpDelta==='number')?window.__lpDelta:-1,i:window.__lpIdle===true})})()") { deltaRaw ->
                        val scrollInfo = unwrapJsString(scrollRaw)
                        val dj = try { JSONObject(unwrapJsString(deltaRaw)) } catch (e: Exception) { JSONObject() }
                        val domDelta = dj.optInt("d", -1)
                        val pageIdle = dj.optBoolean("i", false)
                        // v142: আগের ধাপের কাজের ফল (পেজ বদলালো কি না, স্ক্রল কোথায়) ইতিহাসে লিখে রাখা
                        pendingActionNote?.let { note ->
                            val changed = if (currentUrl != lastStepUrl) "page changed" else "same page"
                            // v144: আসল প্রমাণ — কাজের পর DOM সত্যিই বদলেছে কি না (না বদলালে "কাজ হয়নি/এখনো আসেনি" — একই ক্লিক অন্ধভাবে বারবার নয়)
                            val reacted = when { domDelta < 0 -> ""; domDelta == 0 && pageIdle -> "; the page did NOT react and nothing is loading (idle) — waiting will not help, try a different element or route"
                                domDelta == 0 -> "; the page did NOT react yet but is still loading — use wait"; else -> "; the page reacted ($domDelta DOM changes)" }
                            ContextWindowStore.remember(this, sessionId, "$note → now at $currentUrl «$pageTitle» ($changed$reacted) scroll=$scrollInfo")
                            pendingActionNote = null
                        }
                        lastElements = elements
                        lastStepUrl = currentUrl
                        requestNextAction(currentGoal!!, currentUrl, pageTitle, elements, ignoreCaptchaThisStep, scrollInfo)
                      }
                    }
                }
            }
            }
        }
    }

    /** v144: স্ক্যানের আগে অপেক্ষা — টাইমার নয়, ঘটনা। কোনো সময় পেরোলে স্কিপ করে না।
     *  ১) নেভিগেশন চলছে (onPageStarted এসেছে, লোড-শেষ আসেনি) → লোড-শেষের ঘটনায় নতুন পেজে আবার শুরু।
     *  ২) পেজের ভেতরে JS: readyState ≠ loading এবং ক্লিক/টাইপ করার জিনিস এসেছে (বা পেজ complete) → তারপর দুটো
     *     মেসেজ-চ্যানেল টাস্ক ছেড়ে দেওয়া (DOM-এর পেন্ডিং আপডেট বসে যাওয়ার জন্য) → LpReady.ready(token)।
     *     দরকারি জিনিস না এলে MutationObserver/readystatechange-এর পরের ঘটনার জন্য অপেক্ষা করে, ঘড়ি দেখে না।
     *  ফাঁকা তালিকা এলে মডেল নিজেই "wait" দেয় (নিচে waitForPageChange)। */
    private fun waitUntilPageReady(onReady: () -> Unit) = awaitInPage({ t -> readyJs(t) }, false, onReady)

    /** "wait" action: পেজে কিছু বদলানো (DOM পরিবর্তন / লোড-অবস্থা বদল) পর্যন্ত থাকে। সময়ে হাল ছাড়ে না — থামাতে হলে ইউজার AI বন্ধ করবে। */
    private fun waitForPageChange(onReady: () -> Unit) = awaitInPage({ t -> changeJs(t) }, false, onReady)

    private fun awaitInPage(buildJs: (Int) -> String, ignoreNavGate: Boolean = false, onReady: () -> Unit) {
        if (!aiSwitch.isChecked) return
        val token = ++readyToken
        val restart: () -> Unit = { awaitInPage(buildJs, ignoreNavGate, onReady) }
        readyDone = null; readyRestart = null
        // UI-থ্রেডের সারিতে আগে থেকে জমে থাকা ঘটনা (onPageStarted ইত্যাদি) আগে চলুক — এটা বিলম্ব নয়, শুধু সারির পিছনে দাঁড়ানো।
        handler.post {
            if (!aiSwitch.isChecked || token != readyToken) return@post
            if (!ignoreNavGate) {
                if (navInFlight) { readyRestart = restart; return@post }   // loadSettled() এসে আবার শুরু করাবে
                readyRestart = restart                                      // অপেক্ষার মাঝে পেজ বদলালে নতুন পেজে আবার
            }
            readyDone = { readyRestart = null; onReady() }              // JS চালানোর আগেই বসানো — রেস নেই
            webView.evaluateJavascript(buildJs(token)) { }
        }
    }

    /** পেজের নেটওয়ার্ক-অনুরোধ গোনা (fetch/XHR): কাজের পর যে অনুরোধগুলো শুরু হয়েছে সেগুলো শেষ না হলে "দরকারি জিনিস এখনো আসছে"। */
    private val NET_PATCH = """
        (function(){ if(window.__lpNet)return; var N=window.__lpNet={n:0};
          function dec(){ if(N.n>0)N.n--; try{ if(window.__lpNetDone)window.__lpNetDone() }catch(e){} }
          try{ var f=window.fetch; if(f){ window.fetch=function(){ N.n++; var p; try{ p=f.apply(this,arguments) }catch(e){ dec(); throw e } p.then(dec,dec); return p } } }catch(e){}
          try{ var X=XMLHttpRequest.prototype, s=X.send; X.send=function(){ var d=false; function once(){ if(!d){d=true;dec()} } this.addEventListener('loadend',once); N.n++; try{ return s.apply(this,arguments) }catch(e){ once(); throw e } } }catch(e){}
        })();
    """.trimIndent()

    private val READY_COMMON_JS = """
        var D=document,done=false;
        function cnt(){try{return D.querySelectorAll('a,button,input,textarea,select,[role=button],[role=textbox],[contenteditable]').length}catch(e){return 0}}
        function yield2(f){try{var c=new MessageChannel();c.port1.onmessage=function(){var c2=new MessageChannel();c2.port1.onmessage=f;c2.port2.postMessage(0)};c.port2.postMessage(0)}catch(e){f()}}
        function root(){return D.documentElement||D}
        $NET_PATCH
        if(window.__lpNetBase===undefined)window.__lpNetBase=window.__lpNet?window.__lpNet.n:0;
        function netIdle(){var N=window.__lpNet;return !N||N.n<=window.__lpNetBase}
        if(!window.__lpMO){window.__lpMutN=0;window.__lpSeen=0;try{window.__lpMO=new MutationObserver(function(){window.__lpMutN++;window.__lpMutT=Date.now()});window.__lpMO.observe(root(),{childList:true,subtree:true,characterData:true})}catch(e){}}
        function fin(){if(done)return;done=true;window.__lpNetDone=null;
          window.__lpDelta=window.__lpMutN-window.__lpSeen;window.__lpSeen=window.__lpMutN;
          window.__lpIdle=(D.readyState==='complete'&&netIdle());
          window.__lpNetBase=window.__lpNet?window.__lpNet.n:0;
          try{LpReady.ready(T)}catch(e){}}
    """.trimIndent()

    /** স্ক্যান/স্থির হওয়ার অপেক্ষা: লোড চলছে না, কাজের পর শুরু হওয়া অনুরোধ শেষ, আর ক্লিক/টাইপ করার জিনিস এসেছে (বা পেজ complete)। */
    private fun readyJs(token: Int): String = "(function(){var T=$token;" + READY_COMMON_JS + """
        function ok(){ if(D.readyState==='loading')return false; if(!netIdle())return false; return cnt()>0 || D.readyState==='complete'; }
        var mo=null;
        function check(){ if(!ok())return false; if(mo)mo.disconnect(); yield2(fin); return true; }
        if(!check()){
          mo=new MutationObserver(function(){ check(); });
          try{mo.observe(root(),{childList:true,subtree:true})}catch(e){}
          D.addEventListener('readystatechange',check);
          window.__lpNetDone=check;
        }
    })();"""

    /** "wait": পেজে কিছু বদলালে অথবা পেজ সত্যিই স্থির (কিছু আসছে না) হলে এগোয়। স্থির পেজে অনন্তকাল বসে থাকে না। */
    private fun changeJs(token: Int): String = "(function(){var T=$token;" + READY_COMMON_JS + """
        function busy(){ return D.readyState!=='complete' || !netIdle(); }
        function changed(){ return window.__lpMutN>window.__lpSeen; }
        var mo=null;
        function check(){ if(!(changed()||!busy()))return false; if(mo)mo.disconnect(); yield2(fin); return true; }
        if(!check()){
          mo=new MutationObserver(function(){ check(); });
          try{mo.observe(root(),{childList:true,subtree:true,characterData:true})}catch(e){}
          D.addEventListener('readystatechange',check);
          window.__lpNetDone=check;
        }
    })();"""

    /** v153: a label counts only if it is the NAME of a visible, clickable-looking control (link/button/role/onclick/tabindex…) in or near the viewport.
     *  Plain page text or a hidden/offscreen aria-label does not count (v152 matched those, so "seen" could be true while the control was not on the list
     *  the model gets). This is a speed hint only — a miss merely means "wait for the page to go quiet, then read the whole screen". */
    private val EXPECT_FN = """
        if(!window.__lpHas){
          window.__lpNorm=function(s){return (s||'').toLowerCase().replace(/\s+/g,' ').trim()};
          window.__lpHas=function(w){
            w=window.__lpNorm(w); if(w.length<2)return false;
            var D=document, vh=window.innerHeight||0, vw=window.innerWidth||0;
            try{
              var els=D.querySelectorAll('a[href],button,summary,input,select,textarea,[role=button],[role=link],[role=menuitem],[role=tab],[role=option],[role=switch],[role=checkbox],[onclick],[tabindex]'),n=els.length>3000?3000:els.length;
              for(var i=0;i<n;i++){
                var e=els[i];
                var v=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('alt')||'')+' '+(e.textContent||e.value||'');
                if(window.__lpNorm(v).indexOf(w)<0)continue;
                var r=e.getBoundingClientRect();
                if(r.width<2||r.height<2||r.bottom<-vh||r.top>2*vh||r.right<0||r.left>vw)continue;
                var cs=window.getComputedStyle(e);
                if(cs&&(cs.visibility==='hidden'||cs.display==='none'||cs.opacity==='0'))continue;
                return true;
              }
            }catch(e){}
            return false};
        }
    """.trimIndent()

    /** v152: BEFORE the action — remember which expected labels are already on the page; only a NEW appearance counts as a match. */
    private fun armExpectJs(specJson: String): String = "(function(){try{var S=" + specJson + ";" + EXPECT_FN + """
        window.__lpExpBase=[];
        for(var i=0;i<S.length;i++){ if(window.__lpHas(S[i])) window.__lpExpBase.push(String(S[i]).toLowerCase()); }
    }catch(e){window.__lpExpBase=[]}})();"""

    /** v152: after click/press/select. Matched → continue at once (no full-load wait). Not matched → continue when the page has gone quiet
     *  (4 ticks without DOM change and no request in flight; 12 when labels are expected, so a slow-drawn menu still gets its time), bounded at 100 ticks so a page that never stops animating cannot hang the run. */
    private fun expectJs(token: Int, specJson: String): String = "(function(){var T=" + token + ";var S=" + specJson + ";" + READY_COMMON_JS + EXPECT_FN + """
        window.__lpExpMatched=false;
        var base=window.__lpExpBase||[];
        function fresh(){ for(var i=0;i<S.length;i++){ var w=String(S[i]||''); if(base.indexOf(w.toLowerCase())>=0)continue; if(window.__lpHas(w))return true } return false }
        var quiet=0,ticks=0,lastN=window.__lpMutN,QUIET=(S.length?12:4),CAP=100;
        function baseOk(){ return D.readyState!=='loading' && netIdle() }
        function step(){
          if(done)return; ticks++;
          if(S.length && fresh()){ window.__lpExpMatched=true; yield2(fin); return }
          if(window.__lpMutN===lastN && baseOk()) quiet++; else { quiet=0; lastN=window.__lpMutN }
          if(quiet>=QUIET || ticks>=CAP){ yield2(fin); return }
          setTimeout(step,50);
        }
        step();
    })();"""

    private fun requestNextAction(
        goal: String, currentUrl: String, pageTitle: String, elements: List<PrunedElement>,
        captchaIgnore: Boolean = false, scrollInfo: String = ""
    ) {
        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            // Only the last few compact lines from the on-device context
            // window go over the network — the rest of what's been done
            // this task stays local (see ContextWindowStore).
            val recentHistory = ContextWindowStore.fullForPrompt(this@AiBrowserActivity, sessionId)   // v142: পুরো ইতিহাস
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
                captchaIgnore = captchaIgnore,
                scrollInfo = scrollInfo,
                turns = chatTurns[sessionId] ?: emptyList()
            )
            goalChangedPending = false
            val result = ApiClient.callAuthed(this@AiBrowserActivity, baseUrl, "/api/browser-action", body)
            result.onSuccess { bodyStr ->
                // v143: এই ধাপের চ্যাট-জোড়া জমিয়ে রাখা — পরের ধাপে সার্ভারকে ফেরত যাবে
                try {
                    JSONObject(bodyStr).optJSONObject("chat_turn")?.let { ct ->
                        val u = ct.optString("u"); val m = ct.optString("m")
                        if (u.isNotBlank() && m.isNotBlank()) chatTurns.getOrPut(sessionId) { mutableListOf() }.add(u to m)
                    }
                } catch (e: Exception) { /* চ্যাট-জোড়া না পেলে ইতিহাস-লগ দিয়েই চলবে */ }
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
                    pauseForUserAnswer("I did not understand the reply. Type anything above (even just \"continue\") and I will run the same task again.")
                    return@onSuccess
                }
                if (parsed.ragMatchId != null) browsingRagNoteId = parsed.ragMatchId
                applyAction(parsed)
            }.onFailure { e ->
                // Same fix as above — a transient network hiccup shouldn't
                // throw away the task. Pause and resume on the same goal
                // instead of forcing a restart-as-new-search.
                pauseForUserAnswer("A network problem occurred. Once it is fixed, type \"continue\" above and I will resume the same task.")
                Toast.makeText(this@AiBrowserActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyAction(result: BrowserActionResult) {
        if (result.messageToUser.isNotBlank()) {
            // v28: "তদারকি…" লেখা নয়, শুধু ছোট আইকন
            statusText.text = if (!result.supervisorNote.isNullOrBlank())
                "🛡 ${result.messageToUser}" else result.messageToUser
        }
        run {
            val elText = result.elementId?.let { id -> lastElements.firstOrNull { it.id == id } }
                ?.let { (it.text.ifBlank { it.placeholder }).ifBlank { it.tag } }
            val detail = buildString {
                append("[${result.action}]")
                if (elText != null) append(" «$elText»")
                if (!result.url.isNullOrBlank() && result.action == "navigate") append(" ${result.url}")
                if (!result.query.isNullOrBlank() && result.action == "search") append(" «${result.query}»")
                if (!result.textToType.isNullOrBlank()) append(" typed «${result.textToType}»")
                append(" on $lastStepUrl — ${result.messageToUser}")
            }
            ContextWindowStore.remember(this, sessionId, detail)
            // পরের ধাপ শুরুতে এর সাথে "ফল" জুড়ে আলাদা লাইন হবে
            pendingActionNote = if (result.action in listOf("click", "navigate", "search", "go_back", "type", "scroll"))
                "(result of the previous action)" else null
        }

        // Server-side captcha detection (URL/title/element text — things the on-device
        // probe can miss). Same hand-over as the probe: stop, ask the person, resume alone.
        if (result.captcha) {
            pauseForCaptcha(result.messageToUser.ifBlank { CAPTCHA_MESSAGE }, fromProbe = false)
            return
        }

        if (result.taskComplete || result.action == "task_complete") {
            lastRunFinished = true
            liveChat.postAgent(result.messageToUser.ifBlank { "Task finished. Please review the result." })
            stopAutoLoop(result.messageToUser.ifBlank { "Task finished. Please review the result." })
            // Task genuinely finished — this is the one case where the NEXT
            // thing the user types really should start a brand-new task
            // (submitGoalFromInput's fallback branch), so clear currentGoal
            // here rather than leaving the old goal lying around.
            currentGoal = null
            return
        }
        if (result.action == "ask_user") {
            pauseForUserAnswer(result.messageToUser.ifBlank { "I need a little help here. Please reply above." })
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
                        else "🎯 Setting the goal: $newGoal"
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
                    pauseForUserAnswer("An unsafe link was found. Tell me above what to do next.")
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
                // v142: আগে শুধু window.scrollBy(0,600) — ফেসবুকের মতো পেজে ভেতরের স্ক্রল-বক্স থাকলে কিছুই নড়ত না।
                // এখন পেজের সবচেয়ে বড় আসল স্ক্রল-এলাকা খুঁজে স্ক্রিনের ~৮০% নামে।
                webView.evaluateJavascript(SCROLL_DO_JS) {
                    scheduleNextStep(STEP_SETTLE_DELAY_MS)
                }
            }
            "wait" -> {
                // v144: স্থির সময় নয় — পেজে সত্যিই কিছু বদলালে তবেই এগোয়
                statusText.text = "⏳ Waiting for the page to show something…"
                waitForPageChange { scheduleNextStep(STEP_SETTLE_DELAY_MS) }
            }
            else -> pauseForUserAnswer("I did not understand. Tell me above what to do next.")
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
            stopAutoLoop("I could not tell which app to open. Please do it yourself.")
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
                "${result.appTarget?.replaceFirstChar { it.uppercase() } ?: "An"} app may need to be opened — " +
                    "Should I stay in the browser or go to the app? If you turn AI on again, it resumes from where it left off.",
                clearMemory = false
            )
            return
        }
        if (!isPackageInstalled(target.packageName)) {
            stopAutoLoop(
                "The ${result.appTarget?.replaceFirstChar { it.uppercase() }} app is not installed — " +
                    "Install it from the Play Store, or continue in the browser?"
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
            stopAutoLoop(result.messageToUser.ifBlank { "The app has been opened." })
        } else {
            stopAutoLoop("Could not open the app. Please try it yourself.")
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
        "🔒 A captcha verification appeared. A person must complete it, so I did not touch it. Please solve it yourself and I will continue automatically."
}
