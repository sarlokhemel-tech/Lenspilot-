package com.hemel.lenspilot.browser.multi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import com.hemel.lenspilot.browser.AgentHost
import com.hemel.lenspilot.browser.BrowserAgentV2
import com.hemel.lenspilot.browser.VaultUi
import kotlinx.coroutines.CoroutineScope

/**
 * v162: ONE session of the ELA 4N multi-session browser = its own WebView + its own [BrowserAgentV2]
 * (same agent, same server loop and supervisor AI as the normal automation browser — nothing is re-implemented).
 * Everything the UI shows about a session comes from the fields here (code only, no LLM call for status).
 */
class BrowserSession(
    val id: String,
    val spec: SessionSpec,
    private val env: SessionEnv,
    scope: CoroutineScope,
    private val system: String = "ela_4n",
) : AgentHost {

    private val handler = Handler(Looper.getMainLooper())
    private val act get() = env.activity

    val web: WebView = WebView(env.activity)
    private val waiter = PageWaiter(web, handler) { aiOn }

    @Volatile var aiOn = false; private set
    var state = SessionState.IDLE; private set
    /** Short line from the agent's own status (set by code in the agent, e.g. "⚡ Working…"). */
    var statusText = ""; private set
    /** The last one-line message the agent gave for what it is doing. */
    var lastMessage = ""; private set
    var step = 0; private set
    /** Why the session needs the person (captcha / login / a question) — shown on the tile. */
    var needsText = ""; private set
    var resultText = ""; private set
    private var pendingQuestion = false
    private var began = false
    private var uploadCursor = 0
    private val agent = BrowserAgentV2(this, scope, persistState = false)

    /** Host name of the page the session is on (no "www."). */
    fun hostName(): String {
        val u = try { web.url.orEmpty() } catch (e: Exception) { "" }
        return try { Uri.parse(u).host.orEmpty().removePrefix("www.") } catch (e: Exception) { "" }
    }

    fun line(index: Int) = SessionLine(index, spec.title, state, hostName(), step, shownStatus())

    private fun shownStatus(): String = when (state) {
        SessionState.NEEDS_YOU -> needsText.ifBlank { statusText }
        SessionState.DONE -> resultText.ifBlank { statusText }
        else -> statusText.ifBlank { lastMessage }
    }

    private fun changed() { env.onSessionChanged(this) }

    init {
        configureWebView()
    }

    // ------------------------------------------------------------------ control
    fun start() {
        if (began) return
        began = true
        aiOn = true
        state = SessionState.RUNNING
        statusText = "⚡ Working…"
        val (goal, extra) = buildGoal()
        agent.begin(goal, system, extra)
        changed()
    }

    fun pause() {
        if (state != SessionState.RUNNING && state != SessionState.NEEDS_YOU) return
        aiOn = false
        agent.pause()
        state = SessionState.PAUSED
        statusText = "⏸ Paused"
        changed()
    }

    fun resume() {
        if (state != SessionState.PAUSED) return
        aiOn = true
        state = SessionState.RUNNING
        statusText = "⚡ Working…"
        agent.resume()
        // paused before the very first plan arrived (or the plan call had not finished): nothing to resume — start over from the goal
        if (!agent.active && !pendingQuestion) {
            val (goal, extra) = buildGoal()
            agent.begin(goal, system, extra)
        }
        changed()
    }

    /** Stops the session for good (the page stays visible). */
    fun stop() {
        if (state == SessionState.DONE || state == SessionState.STOPPED) return
        aiOn = false
        agent.cancelLoop()
        try { web.stopLoading() } catch (e: Exception) { }
        state = SessionState.STOPPED
        statusText = "■ Stopped"
        changed()
    }

    /** The person answers a question / finishes a hand-over ("Done"). */
    fun reply(text: String) {
        if (text.isBlank()) return
        pendingQuestion = false
        aiOn = true
        state = SessionState.RUNNING
        needsText = ""
        statusText = "⚡ Working…"
        agent.provideUserText(text.trim(), null)
        changed()
    }

    fun handOverDone() = reply("Done")

    fun destroy() {
        aiOn = false
        try { agent.cancelLoop() } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        try {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeJavascriptInterface("LpReady")
            web.destroy()
        } catch (e: Exception) { }
    }

    /** The goal handed to the agent (task + vault hint) and the extra context (start address + attached files). */
    private fun buildGoal(): Pair<String, String> {
        val goal = StringBuilder(spec.task.trim())
        if (spec.vaultTitles.isNotEmpty()) {
            goal.append("\n\n[Use these saved logins from the secure vault when a site needs them: ")
                .append(spec.vaultTitles.joinToString(", ")).append("]")
        }
        val extra = StringBuilder()
        spec.startUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let {
            extra.append("\n\nStart at this address: ").append(it)
        }
        if (spec.docs.isNotEmpty()) {
            extra.append("\n\nFiles the user gave for this task:")
            for (d in spec.docs) {
                if (d.text != null) extra.append("\n• ").append(d.name).append(":\n").append(d.text)
                else extra.append("\n• ").append(d.name).append(" (a file you can upload when a website asks for a file)")
            }
        }
        return goal.toString() to extra.toString()
    }

    // ------------------------------------------------------------------ WebView
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val st = web.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.setSupportZoom(true)
        st.builtInZoomControls = true
        st.displayZoomControls = false
        st.setSupportMultipleWindows(true)
        st.allowFileAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = chromeClient()
        web.webViewClient = webClient()
        web.addJavascriptInterface(waiter.bridge, "LpReady")
        web.setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun chromeClient() = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            val cb = filePathCallback ?: return false
            if (aiOn) return agent.handleFileChooser(cb)
            env.pickFileFor(this@BrowserSession, cb)
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            agent.noteDialog("alert", message.orEmpty()); result?.confirm(); return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val accept = agent.acceptNextDialog
            agent.acceptNextDialog = false
            agent.noteDialog("confirm", message.orEmpty())
            if (accept) result?.confirm() else result?.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            agent.noteDialog("prompt", message.orEmpty()); result?.cancel(); return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            handler.post { request?.deny() }
            agent.noteEvent("error(permission_denied)")
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress >= 100 && view === web) { agent.onLoadProgressDone(); waiter.settled() }
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            // a site opens a new window: load its address in THIS session's page instead (sessions have one page each)
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val tmp = WebView(act)
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    r?.url?.toString()?.let { web.loadUrl(it) }
                    handler.post { try { tmp.destroy() } catch (e: Exception) { } }
                    return true
                }
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        web.loadUrl(url)
                        handler.post { try { tmp.destroy() } catch (e: Exception) { } }
                    }
                }
            }
            transport.webView = tmp
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun webClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == "http" || scheme == "https") return false
            if (scheme == "intent") {
                val fb = try { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") } catch (e: Exception) { null }
                if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) { view?.loadUrl(fb); return true }
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            agent.noteEvent("error(scheme:$scheme)")
            agent.onPageFinished()
            waiter.settled()
            return true
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) agent.noteError("${error?.errorCode}:${error?.description}".take(80))
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) agent.noteError("http_${errorResponse?.statusCode}")
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            waiter.onPageStarted()
            agent.onNavStarted(url)
            changed()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            agent.onPageFinished()
            waiter.settled()
            changed()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (view !== web) return
            waiter.patchNetworkCounter()
            if (aiOn && agent.navPending()) waiter.awaitReady(ignoreNavGate = true) { agent.onPageFinished() }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            aiOn = false
            try { agent.cancelLoop() } catch (e: Exception) { }
            state = SessionState.FAILED
            statusText = "⚠ The page crashed"
            changed()
            return true
        }
    }

    // ------------------------------------------------------------------ AgentHost
    override val context: Context get() = act
    override val webView: WebView get() = web

    override fun setStatus(text: String) { statusText = text; changed() }

    override fun onAutoPromptSending(step: Int, headline: String, screenData: String) {
        this.step = step
        changed()
    }

    override fun onAutoPromptAnswered(step: Int, prompt: String, hadImage: Boolean, message: String, actionCode: String) {
        if (message.isNotBlank()) { lastMessage = message.trim().take(140); changed() }
    }

    override fun isAiOn(): Boolean = aiOn

    override fun onTaskFinished(message: String) {
        aiOn = false
        state = SessionState.DONE
        resultText = message.trim().take(200)
        statusText = "✓ Done"
        changed()
    }

    override fun onHandoff(kind: String, message: String) {
        state = SessionState.NEEDS_YOU
        needsText = message.trim().take(200)
        pendingQuestion = false
        changed()
    }

    override fun onAskUser(message: String) {
        pendingQuestion = true
        state = SessionState.NEEDS_YOU
        needsText = message.trim().take(300)
        changed()
    }

    override fun confirmIrreversible(message: String, onResult: (String) -> Unit) {
        state = SessionState.NEEDS_YOU
        needsText = message.trim().take(200)
        changed()
        AlertDialog.Builder(act)
            .setTitle(spec.title + " — please confirm once")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Yes, this time only") { _, _ -> resumeFromConfirm(); onResult("ONCE") }
            .setNeutralButton("Yes to all in this task") { _, _ -> resumeFromConfirm(); onResult("ALL") }
            .setNegativeButton("No, stop") { _, _ -> resumeFromConfirm(); onResult("STOP") }
            .show()
    }

    private fun resumeFromConfirm() {
        if (state == SessionState.NEEDS_YOU) { state = SessionState.RUNNING; needsText = ""; changed() }
    }

    override fun pickFile(ref: String, callback: ValueCallback<Array<Uri>>) {
        val docs = spec.docs
        if (docs.isNotEmpty()) {
            val r = ref.lowercase()
            val byName = docs.firstOrNull { d ->
                val n = d.name.lowercase()
                r.contains(n) || n.substringBeforeLast('.').let { b -> b.length >= 3 && r.contains(b) }
            }
            val doc = byName ?: docs[uploadCursor++ % docs.size]
            setStatus("📎 Uploading ${doc.name}")
            callback.onReceiveValue(arrayOf(doc.uri))
            return
        }
        env.pickFileFor(this, callback)
    }

    override fun fallbackToLegacy() {
        aiOn = false
        state = SessionState.FAILED
        statusText = "⚠ The new browser agent is off on the server"
        changed()
    }

    override fun onRunStateChanged(running: Boolean) {
        if (running) {
            if (state == SessionState.NEEDS_YOU || state == SessionState.IDLE) { state = SessionState.RUNNING; needsText = "" }
        } else if (state == SessionState.RUNNING && !aiOn) {
            state = SessionState.PAUSED
        }
        changed()
    }

    override fun askChoice(message: String, options: List<String>, onResult: (String?) -> Unit) {
        state = SessionState.NEEDS_YOU
        needsText = message.trim().take(200)
        changed()
        VaultUi.askChoice(act, spec.title + ": " + message, options) { v -> resumeFromConfirm(); onResult(v) }
    }

    override fun askSecret(title: String, label: String, kind: String, onResult: (Boolean) -> Unit) {
        state = SessionState.NEEDS_YOU
        needsText = "\"$title\" needs \"$label\" — enter it in the secure box."
        changed()
        VaultUi.askSecret(act, title, label, kind) { ok -> resumeFromConfirm(); onResult(ok) }
    }

    // OTP from another app needs the whole screen to leave this app; in multi-session the person gives the code in the tile instead.
    override fun startOtpAssist(source: String, appName: String): Boolean = false
    override fun stopOtpAssist() { }

    override fun awaitPageReady(onReady: () -> Unit) { waiter.awaitReady(false, onReady) }
    override fun awaitPageChange(onReady: (idle: Boolean) -> Unit) { waiter.awaitChange(onReady) }
    override fun armExpect(labels: List<String>) { waiter.arm(labels) }
    override fun awaitPageExpect(labels: List<String>, onReady: (matched: Boolean) -> Unit) { waiter.awaitExpect(labels, onReady) }
}
