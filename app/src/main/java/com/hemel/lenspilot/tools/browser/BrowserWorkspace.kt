package com.hemel.lenspilot.tools.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.hemel.lenspilot.browser.AgentAction
import com.hemel.lenspilot.browser.AiBrowserActivity
import com.hemel.lenspilot.browser.BrowserAgentV2
import com.hemel.lenspilot.browser.Observation
import com.hemel.lenspilot.security.Redactor
import com.hemel.lenspilot.security.SensitiveFieldGuard
import com.hemel.lenspilot.tools.SessionState
import com.hemel.lenspilot.tools.SessionStore
import com.hemel.lenspilot.tools.StorageActivity
import com.hemel.lenspilot.tools.ToolsAsync
import com.hemel.lenspilot.tools.runner.BrowserProbe
import com.hemel.lenspilot.tools.runner.BrowserProbes
import com.hemel.lenspilot.tools.runner.Runner
import com.hemel.lenspilot.tools.runner.RunnerEvent
import com.hemel.lenspilot.tools.runner.RunnerKind
import com.hemel.lenspilot.tools.shell.ShellActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * অর্কেস্ট্রেটরের জন্য ব্রাউজার ওয়ার্কস্পেস। ফল সবসময় ছোট, redacted টেক্সট — সরাসরি LLM-এর কনটেক্সটে দেওয়া যায়।
 * এখানে কোনো ব্রাউজার-কোড কপি নেই: সব কাজ বিদ্যমান [BrowserAgentV2]-এর অ্যাকশন/observe দিয়ে।
 */
interface BrowserWorkspace {
    val sessionId: String

    /** URL খোলা (শুধু http/https)। ফল: অ্যাকশনের আউটকাম-লাইন। */
    suspend fun open(url: String): String

    /** compact সারাংশ (lp_observe.js + budget-ছাঁটাই), [maxChars]-এ সীমিত। */
    suspend fun observe(maxChars: Int = BrowserWorkspaces.DEFAULT_OBSERVE_CHARS): String

    /**
     * BrowserAgentV2-এর অ্যাকশন JSON ({"type":"click","id":"e3.4"} …)। অতিরিক্ত: upload_file{id,file},
     * capture_secret{title,field} (PermissionGate-এর ভেতর দিয়ে), type-এর text-এ {{vault:Title.field}}।
     */
    suspend fun act(action: JSONObject): String

    /** নতুন ডাউনলোড শেষ হলে workspace-আপেক্ষিক পাথ (যেমন "downloads/a.pdf"); সময় পেরোলে null। */
    suspend fun waitForDownload(timeoutS: Int): String?

    /** পেজের দৃশ্যমান টেক্সট (page_text detector-এর জন্য); পেজ না থাকলে null। */
    suspend fun pageText(): String?
}

/** চলতি ওয়ার্কস্পেসের রেজিস্ট্রি (ব্রাউজার অ্যাক্টিভিটি জীবিত থাকা পর্যন্ত)। */
object BrowserWorkspaces {
    const val DEFAULT_OBSERVE_CHARS = 3000
    const val MAX_OBSERVE_CHARS = 6000

    @Volatile
    private var active: BrowserWorkspace? = null

    /** [sessionId] null হলে যেকোনো চলতিটা। */
    fun current(sessionId: String?): BrowserWorkspace? {
        val w = active ?: return null
        return if (sessionId == null || w.sessionId == sessionId) w else null
    }

    internal fun register(w: BrowserWorkspace) {
        active = w
        BrowserProbes.provider = ToolsBrowserProbe
    }

    internal fun unregister(w: BrowserWorkspace) {
        if (active === w) {
            active = null
            if (BrowserProbes.provider === ToolsBrowserProbe) BrowserProbes.provider = null
        }
    }

    /** ব্রাউজার চালু না থাকলে টুলস-সেশন-মোডে খোলে। ওয়ার্কস্পেস তৈরি হতে একটু সময় লাগে — current() দিয়ে দেখুন। */
    fun launch(ctx: Context, sessionId: String, url: String? = null) {
        val i = Intent(ctx, AiBrowserActivity::class.java).putExtra(AiBrowserActivity.EXTRA_TOOLS_SESSION_ID, sessionId)
        if (!url.isNullOrBlank()) i.putExtra(AiBrowserActivity.EXTRA_TOOLS_URL, url)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

/** page_text detector-এর প্রোব। */
object ToolsBrowserProbe : BrowserProbe {
    override suspend fun pageText(sessionId: String?): String? = BrowserWorkspaces.current(sessionId)?.pageText()
}

/** আসল বাস্তবায়ন। সব WebView-কাজ main thread-এ (withContext(Main))। */
class ToolsBrowserWorkspace(
    private val ctx: Context,
    override val sessionId: String,
    private val agent: () -> BrowserAgentV2?,
    private val web: () -> WebView,
    private val downloads: ToolsDownloads,
    private val google: GoogleLoginAssist,
    private val needsYou: (String) -> Unit
) : BrowserWorkspace {

    companion object {
        private val ALLOWED = setOf(
            "navigate", "search", "click", "type", "press", "select", "scroll", "hover", "upload", "upload_file",
            "read", "wait_for", "tap_xy", "go_back", "wait", "more", "dialog", "type_secret", "capture_secret"
        )
        private val TYPING = setOf("type", "type_secret", "capture_secret", "upload", "upload_file")
        private const val PAGE_TEXT_CAP = 100_000
    }

    override suspend fun open(url: String): String {
        val u = url.trim()
        val l = u.lowercase()
        if (!l.startsWith("http://") && !l.startsWith("https://")) return "error(bad_url)"
        return act(JSONObject().put("type", "navigate").put("url", u))
    }

    override suspend fun observe(maxChars: Int): String = withContext(Dispatchers.Main) {
        val a = agent() ?: return@withContext "error(browser_not_ready)"
        val cap = maxChars.coerceIn(500, BrowserWorkspaces.MAX_OBSERVE_CHARS)
        val o = try { a.externalObserve(cap / 2) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ?: return@withContext "observe_failed(page_not_ready)"
        if (o.captchaFrame) needsYou("পেজে ক্যাপচা/\"আমি রোবট নই\" যাচাই আছে — এটা আপনি নিজে সমাধান করুন। আমি ছোঁব না।")
        compact(o, cap)
    }

    override suspend fun act(action: JSONObject): String = withContext(Dispatchers.Main) {
        val a = agent() ?: return@withContext "error(browser_not_ready)"
        val type = action.optString("type")
        if (type !in ALLOWED) return@withContext "error(unsupported_action:" + type.take(24) + ")"
        guard(type, web().url)?.let { return@withContext it }
        val brief = briefOf(action, type)
        val out = try {
            a.externalAct(AgentAction(action))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext "$brief → error(" + (e.message ?: "exception").take(60) + ")"
        }
        Redactor.redact(ctx, out.text(brief)).take(900)
    }

    override suspend fun waitForDownload(timeoutS: Int): String? = downloads.waitForDownload(timeoutS)

    override suspend fun pageText(): String? = withContext(Dispatchers.Main) {
        val w = web()
        val raw = suspendCancellableCoroutine<String?> { c ->
            w.evaluateJavascript("(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()") { c.resume(it) }
        }
        val s = try { JSONTokener(raw ?: "null").nextValue() as? String } catch (e: Exception) { null }
        if (s == null) null else if (s.length > PAGE_TEXT_CAP) s.take(PAGE_TEXT_CAP) else s
    }

    // ------------------------------------------------------------------ গার্ড (কাঠামোগত, শব্দ-তালিকা নয়)
    /** null = এগোও। নইলে needs_you ফল (অ্যাকশন চলে না)। */
    private fun guard(type: String, url: String?): String? {
        if (GoogleLoginAssist.isAuthUrl(url)) {
            if (!google.consentGiven()) {
                needsYou(google.noConsentMessage())
                return "needs_you(google_login_consent_off)"
            }
            if (google.isChallenge(url) || type in TYPING) {
                needsYou("গুগলের এই ধাপে (পাসওয়ার্ড/যাচাই/২FA/ক্যাপচা) আপনাকে নিজে করতে হবে। আমি এটা করব না। শেষ হলে \"Done\" চাপুন।")
                return "needs_you(google_login_user_must_do)"
            }
        }
        if (type == "type_secret" || type == "capture_secret" || type == "type") {
            val host = try { Uri.parse(url ?: "").host } catch (e: Exception) { null }
            if (SensitiveFieldGuard.isSensitivePackage(host)) {
                needsYou("এই সাইটটি ব্যাংক/ওয়ালেট/পেমেন্ট ধরনের — এখানে টাইপ আমি করব না, আপনি নিজে করুন।")
                return "needs_you(sensitive_site_user_must_type)"
            }
        }
        return null
    }

    private fun briefOf(action: JSONObject, type: String): String {
        // টাইপ-করা টেক্সট ইচ্ছাকৃতভাবে নেই (প্লেসহোল্ডার/গোপন মান লগে যাবে না)
        val target = action.optString("id").ifBlank {
            action.optString("url").ifBlank { action.optString("query").ifBlank { action.optString("file") } }
        }
        return (type + " " + target.take(60)).trim()
    }

    /** observation → ছোট টেক্সট। id role "name" [state] — আর কিছু নয়। */
    private fun compact(o: Observation, cap: Int): String {
        val sb = StringBuilder()
        sb.append("url: ").append(o.url.take(200)).append('\n')
        if (o.title.isNotBlank()) sb.append("title: ").append(o.title.take(100).replace('\n', ' ')).append('\n')
        if (o.layer != "page") sb.append("layer: ").append(o.layer).append('\n')
        if (o.captchaFrame) sb.append("captcha_present\n")
        val els = o.raw.optJSONArray("elements")
        if (els != null) {
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                sb.append(e.optString("id")).append(' ').append(e.optString("role"))
                val nm = e.optString("name")
                if (nm.isNotBlank()) sb.append(" \"").append(nm.take(60).replace('\n', ' ')).append('"')
                val st = e.optString("state")
                if (st.isNotBlank()) sb.append(" [").append(st.take(30)).append(']')
                sb.append('\n')
                if (sb.length > cap) break
            }
        }
        val dg = o.raw.optJSONArray("digest")
        if (dg != null && sb.length < cap) {
            for (i in 0 until dg.length()) {
                val d = dg.optJSONObject(i) ?: continue
                val t = d.optString("t")
                if (t.isNotBlank()) sb.append("# ").append(t.take(160).replace('\n', ' ')).append('\n')
                if (sb.length > cap) break
            }
        }
        if (o.more) sb.append("more_elements_available (action: more)\n")
        val text = Redactor.redact(ctx, sb.toString())
        return if (text.length <= cap) text else text.take(cap - 1) + "…"
    }
}

/**
 * টুলস-সেশন-মোডে AiBrowserActivity-র সাথে লেগে থাকা আঠা: ডাউনলোড, আপলোড, গুগল-সহায়তা, কুকি-ফ্লাশ,
 * ওয়ার্কস্পেস রেজিস্ট্রেশন, needs_you তোলা, Shell/Storage-এ দ্রুত যাওয়া। ব্রাউজারের মূল কোড অপরিবর্তিত।
 */
class ToolsBrowserSession(private val act: AppCompatActivity, val sessionId: String) {

    val downloads = ToolsDownloads(act, sessionId)
    val google = GoogleLoginAssist(act)

    /** ইউজারের জন্য UI-তে থামার বার্তা দেখানো (অ্যাক্টিভিটি বসায়)। */
    var onHandoffUi: ((String) -> Unit)? = null

    /** AI চালাচ্ছে কিনা (ইউজার নিজে ঘুরলে needs_you তোলা অর্থহীন)। */
    var aiOn: () -> Boolean = { true }

    private var workspace: ToolsBrowserWorkspace? = null

    fun attachWorkspace(agent: () -> BrowserAgentV2?, web: () -> WebView): BrowserWorkspace {
        val w = ToolsBrowserWorkspace(act, sessionId, agent, web, downloads, google) { msg -> needsYou(msg) }
        workspace = w
        BrowserWorkspaces.register(w)
        return w
    }

    /** upload_file অ্যাকশনের রিজলভার: নাম/পাথ → Uri (workspace-এর ভেতরেই)। */
    fun resolveUpload(nameOrPath: String): Uri? =
        ToolsUploads.resolve(act, sessionId, nameOrPath)?.let { ToolsUploads.uriOf(it) }

    /** ফাইল-চুজার: Storage-এর ফাইল অথবা সিস্টেম পিকার। */
    fun chooseFile(cb: ValueCallback<Array<Uri>>, systemPicker: () -> Unit) {
        ToolsUploads.showChooser(
            act, sessionId,
            { uri -> cb.onReceiveValue(if (uri != null) arrayOf(uri) else null) },
            systemPicker
        )
    }

    /** সেশন-স্টেট needs_you + ঘটনা + নোটিফিকেশন + UI-তে বার্তা। */
    fun needsYou(message: String) {
        if (!aiOn()) return
        val msg = Redactor.redact(act, message)
        ToolsAsync.ui { onHandoffUi?.invoke(msg) }
        val app = act.applicationContext
        ToolsAsync.io {
            SessionStore.update(app, sessionId) { it.copy(state = SessionState.NEEDS_YOU, lastNote = msg.take(200)) }
            Runner.deliver(app, RunnerEvent(RunnerKind.NEEDS_YOU, sessionId, "browser", msg))
        }
    }

    /** একটাই শেয়ার্ড প্রোফাইল: কুকি ডিস্কে নামিয়ে দেওয়া (প্রতি পেজ শেষে ও পজে) — সেশন বদলালেও সাইন-ইন টিকে থাকে। */
    fun flushCookies() {
        try { CookieManager.getInstance().flush() } catch (e: Exception) { }
    }

    fun openShell() {
        act.startActivity(Intent(act, ShellActivity::class.java).putExtra(ShellActivity.EXTRA_SESSION_ID, sessionId))
    }

    fun openStorage() {
        act.startActivity(Intent(act, StorageActivity::class.java).putExtra(StorageActivity.EXTRA_SESSION_ID, sessionId))
    }

    fun release() {
        flushCookies()
        workspace?.let { BrowserWorkspaces.unregister(it) }
        workspace = null
    }
}
