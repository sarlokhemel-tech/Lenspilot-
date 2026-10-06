package com.hemel.lenspilot.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.ValueCallback
import android.webkit.WebView
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.resume

/** AiBrowserActivity যা যা দেয় — এজেন্ট শুধু এই ইন্টারফেস দিয়ে UI/WebView ছোঁয়। */
interface AgentHost {
    val context: Context
    val webView: WebView
    fun setStatus(text: String)
    fun isAiOn(): Boolean
    /** কাজ সত্যিই শেষ। */
    fun onTaskFinished(message: String)
    /** মানুষের হাতে হস্তান্তর (ক্যাপচা/লগইন/OTP/পেমেন্ট): লুপ থেমে আছে, কিন্তু session টিকে আছে। */
    fun onHandoff(kind: String, message: String)
    /** ইউজারকে নির্দিষ্ট প্রশ্ন (ইনপুট দরকার / সিঁড়ির শেষ ধাপ)। উত্তর এলে [BrowserAgentV2.provideUserText]। */
    fun onAskUser(message: String)
    /** অপরিবর্তনীয় ধাপের এক-ট্যাপ নিশ্চিতকরণ। result: ONCE | ALL | STOP */
    fun confirmIrreversible(message: String, onResult: (String) -> Unit)
    /** ফাইল-চুজার: ইউজার ফাইল বাছবে। */
    fun pickFile(ref: String, callback: ValueCallback<Array<Uri>>)
    /** v2 চালানো গেল না (সার্ভারে বন্ধ / পুরনো সার্ভার) — পুরনো লুপে ফিরে যাও। */
    fun fallbackToLegacy()
    fun onRunStateChanged(running: Boolean)
}

/**
 * Browser Agent v2 (স্পেক B1–B13): একবার ভালো পর্যবেক্ষণ → প্ল্যান ও লেজার → এক কলে সিদ্ধান্ত →
 * অ্যাকশনের পর আসল ফল মাপা → ব্যর্থ হলে সিঁড়ি বেয়ে নিজে শোধরানো → শুধু সত্যিকারের মানুষের কাজে থামা।
 *
 * লুপটা একটা coroutine (main thread)। সিদ্ধান্ত নেয় সার্ভারের AI; এখানে কোনো শব্দ-তালিকা/regex নেই —
 * শুধু কাঠামোগত সংকেত (URL/DOM-hash বদল, আউটকাম) আর খরচ/লুপ-ব্রেক।
 */
class BrowserAgentV2(private val host: AgentHost, private val scope: CoroutineScope) {

    companion object {
        private const val STEP_LIMIT_CHECKIN = 60          // পজ নয়, শুধু "চেক-ইন" (স্পেক B8)
        private const val STEP_LIMIT_CHECKIN_4N = 200
        private const val SETTLE_QUIET_MS = 120L           // MutationObserver এ এতক্ষণ শান্ত থাকলে এগোই
        private const val SETTLE_MAX_MS = 1600L
        private const val NAV_WATCHDOG_MS = 2500L          // শেষ ভরসা — মূল অপেক্ষা অবস্থা-ভিত্তিক
        private const val OBSERVE_BUDGET_TOKENS = 1800
        private const val MAX_REPLANS = 2
        private const val HANDOFF_POLL_MS = 2500L
        private const val ELEMENT_RETRY = 3
    }

    // ---- রান-অবস্থা (লেজারসহ ডিস্কে সংরক্ষিত) ----
    var active = false; private set
    private var job: Job? = null
    private var runId = UUID.randomUUID().toString().replace("-", "").take(24)
    private var goal = ""
    private var goalExtra = ""
    private var system = "super_1_2"
    private var plan = JSONObject()
    private var ledger = Ledger()
    private val history = ArrayList<String>()
    private var perms = JSONObject()
    private var stepNo = 0
    private var epoch = 0
    private var nextOffset = 0
    private var lastShown = 0

    // ---- ব্যর্থতার সিঁড়ি ----
    private var fails = 0
    private var replans = 0
    private var sameState = 0
    private var lastStateKey = ""
    private var lastActionSig = ""
    private var warn: String? = null
    private var pendingImage: String? = null
    private var lastOutcomeText: String? = null
    private var lastObs: Observation? = null
    private var captchaIgnoreSteps = 0

    // ---- হস্তান্তর / প্রশ্ন ----
    private var handoffKind: String? = null
    private var handoffHash = ""
    private var handoffUrl = ""
    private var awaitingInputs = false
    private var pendingConfirm: List<AgentAction> = emptyList()

    // ---- ইভেন্ট (WebViewClient/ChromeClient থেকে) ----
    private var navStarted = false
    private var navFinished: CompletableDeferred<Unit>? = null
    private var lastError: String? = null
    private val events = ArrayList<String>()
    var acceptNextDialog = false
    private var currentUploadRef = "file"
    private val fileRefs = HashMap<String, Uri>()
    private var fileDeferred: CompletableDeferred<String>? = null

    private val obsJs: String by lazy { host.context.assets.open("lp_observe.js").bufferedReader().use { it.readText() } }
    private val actJs: String by lazy { host.context.assets.open("lp_act.js").bufferedReader().use { it.readText() } }
    private val web: WebView get() = host.webView

    // =================================================================== শুরু / resume
    fun begin(goalText: String, systemMode: String) {
        cancelLoop()
        goal = goalText; system = systemMode; goalExtra = ""
        resetRunState()
        job = scope.launch {
            host.setStatus("প্ল্যান বানাচ্ছি…")
            val planned = requestPlan(replan = false, reason = "")
            if (planned == null) { host.fallbackToLegacy(); return@launch }   // v2 বন্ধ বা সার্ভার পুরনো
            plan = planned
            persist()
            val needs = plan.optJSONArray("inputs_needed")
            if (needs != null && needs.length() > 0) {
                // শুরুতেই একবার সব তথ্য জিজ্ঞেস — মাঝপথে বারবার নয়
                val q = StringBuilder("শুরুর আগে একবারে কয়েকটা তথ্য লাগবে:\n")
                for (i in 0 until needs.length()) q.append("• ").append(needs.optJSONObject(i)?.optString("ask").orEmpty()).append('\n')
                q.append("এক মেসেজে সব লিখে দাও।")
                awaitingInputs = true
                host.onAskUser(q.toString())
                return@launch
            }
            startLoop()
        }
    }

    /** অ্যাক্টিভিটি মরে গেলে ডিস্ক থেকে ফিরে আসা। */
    fun resumeFrom(state: JSONObject): Boolean {
        return try {
            cancelLoop()
            runId = state.optString("run_id", runId); goal = state.optString("goal"); system = state.optString("system", "super_1_2")
            plan = state.optJSONObject("plan") ?: return false
            ledger = Ledger(state.optJSONObject("ledger") ?: JSONObject())
            perms = state.optJSONObject("permissions") ?: JSONObject()
            history.clear(); state.optJSONArray("history")?.let { for (i in 0 until it.length()) history.add(it.optString(i)) }
            fails = 0; replans = 0; sameState = 0
            lastOutcomeText = "অ্যাপ আবার চালু হয়ে একই কাজ থেকে চলছে"
            val u = state.optString("url", "")
            if (u.startsWith("http://") || u.startsWith("https://")) {
                navFinished = CompletableDeferred(); navStarted = true
                web.loadUrl(u)
            }
            startLoop(); true
        } catch (e: Exception) { false }
    }

    private fun resetRunState() {
        runId = UUID.randomUUID().toString().replace("-", "").take(24)
        ledger = Ledger(); history.clear(); perms = JSONObject(); stepNo = 0; epoch = 0; nextOffset = 0; lastShown = 0
        fails = 0; replans = 0; sameState = 0; lastStateKey = ""; lastActionSig = ""; warn = null; pendingImage = null
        lastOutcomeText = null; lastObs = null; handoffKind = null; awaitingInputs = false; pendingConfirm = emptyList()
        captchaIgnoreSteps = 0; events.clear(); lastError = null
    }

    private fun startLoop() {
        active = true
        host.onRunStateChanged(true)
        // প্ল্যানে "এই URL-এ যাও" যান্ত্রিক ধাপ/start_url থাকলে LLM ছাড়াই সরাসরি (সিদ্ধান্ত নিয়েছে প্ল্যানার AI)
        job?.cancel()
        job = scope.launch { loop() }
    }

    fun cancelLoop() { job?.cancel(); job = null; active = false; handoffKind = null }

    /** AI সুইচ বন্ধ বা ইউজার থামালে — লেজার/প্ল্যান ডিস্কে থাকে। */
    fun pause() { job?.cancel(); job = null; active = false; persist(); host.onRunStateChanged(false) }
    fun resume() { if (plan.length() > 0 && !awaitingInputs) startLoop() }

    fun finishAndClear() {
        job?.cancel(); job = null; active = false
        AgentStateStore.clear(host.context)
        host.onRunStateChanged(false)
    }

    // =================================================================== ইউজার-ইনপুট
    fun provideUserText(text: String, imageBase64: String?) {
        if (text.isNotBlank()) history.add("ইউজার বলেছে: ${text.take(300)}")
        if (imageBase64 != null) pendingImage = imageBase64
        if (awaitingInputs) {
            awaitingInputs = false
            goalExtra = "\n\nইউজারের দেওয়া অতিরিক্ত তথ্য: ${text.take(800)}"
            startLoop(); return
        }
        if (handoffKind == "captcha") captchaIgnoreSteps = 3     // ইউজার নিজে "এগোও" বলেছে — ভুল-সতর্কে আটকাবে না
        handoffKind = null
        fails = 0; replans = 0
        lastOutcomeText = "ইউজার সাড়া দিয়েছে: ${text.take(120)}"
        startLoop()
    }

    /** চলার সময় নতুন কথা — থামায় না, পরের ধাপে ইতিহাসে যোগ হয়। */
    fun nudge(text: String, imageBase64: String?) {
        history.add("ইউজার নতুন করে বলেছে: ${text.take(300)}")
        if (imageBase64 != null) pendingImage = imageBase64
    }

    // =================================================================== WebView ইভেন্ট (host থেকে)
    fun onNavStarted() { navStarted = true }
    fun onPageFinished() { navFinished?.complete(Unit) }
    fun noteError(e: String) { lastError = e }
    fun noteDialog(kind: String, text: String) { events.add("dialog($kind: ${text.take(80)})") }
    fun noteEvent(e: String) { events.add(e) }

    /** WebChromeClient.onShowFileChooser */
    fun handleFileChooser(cb: ValueCallback<Array<Uri>>): Boolean {
        val known = fileRefs[currentUploadRef]
        if (known != null) { cb.onReceiveValue(arrayOf(known)); fileDeferred?.complete("used:$currentUploadRef"); return true }
        host.setStatus("📎 ফাইলটা বেছে নাও — তারপর আমি চালিয়ে যাব")
        host.pickFile(currentUploadRef, ValueCallback<Array<Uri>> { uris ->
            val first = uris?.firstOrNull()
            if (first != null) fileRefs[currentUploadRef] = first
            cb.onReceiveValue(uris)
            fileDeferred?.complete(if (first != null) "picked" else "cancelled")
        })
        return true
    }

    // =================================================================== মূল লুপ
    private suspend fun loop() {
        // শুরুর যান্ত্রিক নেভিগেশন — LLM ছাড়া
        if (stepNo == 0 && ledger.json.length() == 0) {
            val start = plan.optString("start_url", "").ifBlank {
                plan.optJSONArray("subgoals")?.optJSONObject(0)?.optJSONObject("mechanical")?.optString("url", "").orEmpty()
            }
            if (start.startsWith("http://") || start.startsWith("https://")) {
                val o = navigateTo(start)
                history.add(o.text("navigate(plan)"))
                lastOutcomeText = o.text("navigate(plan)")
            }
            plan.optJSONArray("subgoals")?.optJSONObject(0)?.optString("id")?.let { ledger.subgoal = it }
            markMechDone(ledger.subgoal)
        }
        val checkIn = if (system == "ela_4n") STEP_LIMIT_CHECKIN_4N else STEP_LIMIT_CHECKIN
        while (active && host.isAiOn()) {
            // যান্ত্রিক ধাপ (প্ল্যানার AI ঠিক করেছে "এই URL-এ যাও") — LLM কল ছাড়া সরাসরি নেভিগেট
            val mech = pendingMechanicalUrl()
            if (mech != null) {
                stepNo++
                val o = navigateTo(mech)
                history.add(o.text("navigate(plan)")); lastOutcomeText = o.text("navigate(plan)")
                markMechDone(ledger.subgoal); persist()
                continue
            }
            stepNo++
            if (stepNo % checkIn == 0) {
                // পজ নয় — অগ্রগতি দেখিয়ে নিজে চলতে থাকে
                host.setStatus("📊 চেক-ইন: $stepNo ধাপ, ${ledger.doneItems} আইটেম শেষ — চলছি…")
            }
            val obs = observeWithRetry() ?: run {
                fails++; lastOutcomeText = "পেজ পড়া গেল না (লোড হচ্ছে?)"; delay(400); null
            } ?: continue
            lastObs = obs
            ledger.noteVisited(obs.url)

            // কাঠামোগত ক্যাপচা-iframe (শব্দ-ভিত্তিক নয়) — মানুষের কাজ
            if (obs.captchaFrame) {
                if (captchaIgnoreSteps > 0) captchaIgnoreSteps-- else { handoff("captcha", "🔒 ক্যাপচা এসেছে — এটা মানুষকেই করতে হয়, আমি ছুঁইনি। তুমি সমাধান করো; হলে আমি নিজেই চালিয়ে যাব।", obs); return }
            }

            // লুপ-ব্রেক: একই পেজ-অবস্থা + একই অ্যাকশন ফিরলে (মডেলের বার্তা নয়, আসল অবস্থা তুলনা)
            val stateKey = obs.hash + "|" + lastActionSig
            sameState = if (stateKey == lastStateKey) sameState + 1 else 0
            lastStateKey = stateKey
            if (sameState >= 2) { fails++; sameState = 0; ledger.noteFail("same_state") }

            // সিঁড়ির ধাপ ৫: পুনঃপরিকল্পনা
            if (fails >= 4 && replans < MAX_REPLANS) {
                host.setStatus("🧭 নতুন করে পরিকল্পনা করছি…")
                val np = requestPlan(replan = true, reason = "বারবার ব্যর্থ: " + lastOutcomeText.orEmpty().take(200), obs = obs)
                replans++
                if (np != null) { plan = np; persist(); warn = null; lastOutcomeText = "নতুন প্ল্যান নেওয়া হয়েছে" }
                fails = 0
                continue
            }
            val level = when {
                fails >= 4 -> "ask"          // পুনঃপরিকল্পনাও ফুরিয়েছে → ইউজারকে নির্দিষ্ট প্রশ্ন
                fails == 3 -> "vision"
                fails == 2 -> "alt"
                replans > 0 && fails == 0 && stepNo > 0 && lastOutcomeText == "নতুন প্ল্যান নেওয়া হয়েছে" -> "replan"
                else -> ""
            }
            // ধাপ ৪ (Super 1.2): সিঁড়িতে ১ বার স্ক্রিনশট; ELA-তে মডেল নিজে need_vision চায়
            if (level == "vision" && system == "super_1_2" && ledger.vision < 1 && pendingImage == null) {
                pendingImage = captureScreenshot(); ledger.vision = ledger.vision + 1
            }

            val dec = requestDecision(obs, level) ?: return   // সংযোগ/টোকেন সমস্যায় লুপ থেমেছে (host জানানো হয়েছে)
            if (dec.supervisorNote != null) host.setStatus(dec.supervisorNote)
            else if (dec.note.isNotBlank()) host.setStatus(dec.note)
            warn = dec.warn
            ledger.mergeFacts(dec.facts)
            dec.visionUsed?.let { ledger.vision = it }

            if (dec.degraded) {            // দুবারেও অবৈধ উত্তর — নীরবে থামা নয়, সিঁড়িতে এক ধাপ
                fails++; ledger.noteFail("invalid_reply"); delay(300); continue
            }

            when (dec.status) {
                "task_done" -> { history.add("task_done ✔"); host.onTaskFinished(dec.note.ifBlank { "কাজ শেষ — নিজে দেখে নাও।" }); finishAndClear(); return }
                "subgoal_done" -> { advanceSubgoal(); history.add("subgoal_done → ${ledger.subgoal}"); lastOutcomeText = "সাব-গোল শেষ, পরেরটায় যাচ্ছি"; fails = 0; persist(); continue }
                "item_done" -> { ledger.itemDone(dec.note.ifBlank { "item" }); history.add("item_done #${ledger.loopIndex}"); lastOutcomeText = "আইটেম #${ledger.loopIndex} শেষ"; fails = 0; persist(); continue }
                "blocked" -> { handleBlocked(dec, obs); return }
                "need_vision" -> {
                    pendingImage = captureScreenshot(); lastOutcomeText = "স্ক্রিনশট নেওয়া হলো"
                    // পরের ধাপে ছবিসহ (level=vision)
                    fails = maxOf(fails, 3); continue
                }
                "replan" -> { fails = maxOf(fails, 4); continue }
            }

            // ---- অ্যাকশন চালানো (ব্যাচ) ----
            val outcomes = ArrayList<String>()
            var failedHere = false
            for (a in dec.actions) {
                val o = executeAction(a)
                val line = o.text(a.brief())
                outcomes.add(line); history.add(line)
                lastActionSig = a.brief()
                if (o.failed) { failedHere = true; ledger.noteFail(a.brief()); break }
                if (o.result == "ok") fails = 0
            }
            if (failedHere) fails++
            while (history.size > 40) history.removeAt(0)
            lastOutcomeText = outcomes.joinToString(" ; ").take(900)
            persist()
        }
    }

    // =================================================================== সার্ভার কল
    private fun baseUrl() = host.context.getString(R.string.space_base_url)

    private suspend fun requestPlan(replan: Boolean, reason: String, obs: Observation? = null): JSONObject? {
        val body = JSONObject().apply {
            put("goal", goal + goalExtra); put("system", system); put("replan", replan); put("reason", reason)
            com.hemel.lenspilot.Prefs.userInfoJsonArrayOrNull(host.context)?.let { put("user_info", it) }
            if (replan) {
                put("ledger", ledger.json)
                obs?.let {
                    put("page", JSONObject().apply {
                        put("url", it.url); put("title", it.title)
                        put("elements", it.raw.optJSONArray("elements") ?: JSONArray())
                        put("digest", it.raw.optJSONArray("digest") ?: JSONArray())
                    })
                }
            }
        }
        val r = ApiClient.callAuthed(host.context, baseUrl(), "/api/browser-plan", body.toString())
        val s = r.getOrNull() ?: return null
        return try {
            val o = JSONObject(s)
            if (o.optInt("agent_v", 1) != 2) null else o.optJSONObject("plan")
        } catch (e: Exception) { null }
    }

    private suspend fun requestDecision(obs: Observation, level: String): AgentDecision? {
        val image = pendingImage; pendingImage = null
        val body = JSONObject().apply {
            put("agent_v", 2); put("goal", goal + goalExtra); put("system", system)
            put("plan", plan); put("ledger", ledger.json)
            put("observation", obs.raw)
            put("last_outcome", JSONObject().put("text", lastOutcomeText ?: "(এখনো কোনো অ্যাকশন হয়নি)"))
            put("history", JSONArray(history.takeLast(8)))
            put("ladder", JSONObject().put("level", level).put("fails", fails).put("same_state", sameState))
            put("permissions", perms); put("run_id", runId); put("step_number", stepNo)
            warn?.let { put("warn", it) }
            if (image != null) put("image_base64", image)
            com.hemel.lenspilot.Prefs.userInfoJsonArrayOrNull(host.context)?.let { put("user_info", it) }
        }
        var attempt = 0
        while (true) {
            val r = ApiClient.callAuthed(host.context, baseUrl(), "/api/browser-action", body.toString())
            val s = r.getOrNull()
            if (s != null) {
                // ভাঙা উত্তর = ব্যর্থ ধাপ (সিঁড়িতে এক ধাপ), লুপ থামে না
                return try { AgentDecision.parse(JSONObject(s)) } catch (e: Exception) { degradedDecision() }
            }
            val msg = r.exceptionOrNull()?.message.orEmpty()
            if (msg.contains("TOKEN_LIMIT")) {
                active = false; persist(); host.onRunStateChanged(false)
                host.onAskUser("টোকেন শেষ — বিজ্ঞাপন দেখে টোকেন নিয়ে উপরে \"চালিয়ে যাও\" লিখলে একই জায়গা থেকে চলবে।"); return null
            }
            if (msg.contains("V2_OFF")) { host.fallbackToLegacy(); return null }
            attempt++
            if (attempt >= ELEMENT_RETRY) {
                active = false; persist(); host.onRunStateChanged(false)
                host.onAskUser("নেটওয়ার্ক সমস্যা — ঠিক হলে উপরে \"চালিয়ে যাও\" লিখো, একই জায়গা থেকে চলবে।"); return null
            }
            host.setStatus("নেটওয়ার্ক সমস্যা, আবার চেষ্টা করছি ($attempt)…")
            delay(1000L * (1 shl (attempt - 1)))
        }
    }

    private fun degradedDecision() = AgentDecision("continue", emptyList(), "", null, null, null, emptyList(),
        JSONObject(), null, null, true, null)

    // =================================================================== সংকেত: সিদ্ধান্তের পর
    private fun currentSubgoalJson(): JSONObject? {
        val subs = plan.optJSONArray("subgoals") ?: return null
        for (i in 0 until subs.length()) if (subs.optJSONObject(i)?.optString("id") == ledger.subgoal) return subs.optJSONObject(i)
        return null
    }

    private fun markMechDone(id: String) {
        val arr = ledger.json.optJSONArray("mech_done") ?: JSONArray().also { ledger.json.put("mech_done", it) }
        for (i in 0 until arr.length()) if (arr.optString(i) == id) return
        arr.put(id)
    }

    private fun pendingMechanicalUrl(): String? {
        val sg = currentSubgoalJson() ?: return null
        val url = sg.optJSONObject("mechanical")?.optString("url", "").orEmpty()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null
        val arr = ledger.json.optJSONArray("mech_done")
        if (arr != null) for (i in 0 until arr.length()) if (arr.optString(i) == ledger.subgoal) return null
        return url
    }

    private fun advanceSubgoal() {
        val subs = plan.optJSONArray("subgoals") ?: return
        var idx = -1
        for (i in 0 until subs.length()) if (subs.optJSONObject(i)?.optString("id") == ledger.subgoal) idx = i
        if (idx >= 0 && idx + 1 < subs.length()) ledger.subgoal = subs.optJSONObject(idx + 1)?.optString("id") ?: ledger.subgoal
    }

    private suspend fun handleBlocked(dec: AgentDecision, obs: Observation) {
        val kind = dec.blockedKind ?: "missing_info"
        val msg = dec.blockedMessage ?: "এখানে তোমার সাহায্য লাগবে।"
        when (kind) {
            "confirm_irreversible" -> {
                pendingConfirm = dec.pendingActions
                active = false
                host.confirmIrreversible(msg) { choice ->
                    when (choice) {
                        "STOP" -> { host.onAskUser("ঠিক আছে, থামলাম। কী করব বলো।"); }
                        else -> {
                            if (choice == "ALL") perms.put("allow_irreversible", true)
                            scope.launch { runPendingThenContinue(choice == "ALL") }
                        }
                    }
                }
            }
            "missing_info" -> { active = false; host.onRunStateChanged(false); host.onAskUser(msg) }
            else -> handoff(kind, msg, obs)    // captcha | login | otp | payment
        }
    }

    private suspend fun runPendingThenContinue(all: Boolean) {
        active = true; host.onRunStateChanged(true)
        val outs = ArrayList<String>()
        for (a in pendingConfirm) {
            val o = executeAction(a)
            val line = o.text(a.brief() + "[confirmed]"); outs.add(line); history.add(line)
            if (o.failed) break
        }
        pendingConfirm = emptyList()
        lastOutcomeText = outs.joinToString(" ; ").take(900)
        job?.cancel(); job = scope.launch { loop() }
    }

    /** সব হস্তান্তর একই পথে: PAUSE + কারণ + ইউজার শেষ করলে নিজে চালু। */
    private fun handoff(kind: String, message: String, obs: Observation) {
        active = false
        handoffKind = kind; handoffHash = obs.hash; handoffUrl = obs.url
        history.add("[handoff:$kind]"); persist()
        host.onRunStateChanged(false)
        host.onHandoff(kind, message)
        job?.cancel()
        job = scope.launch {
            while (handoffKind != null) {
                delay(HANDOFF_POLL_MS)
                val o = observe(budget = 200) ?: continue
                val resolved = if (kind == "captcha") !o.captchaFrame else (o.url != handoffUrl || o.hash != handoffHash)
                if (resolved) {
                    handoffKind = null
                    lastOutcomeText = "user_handoff_finished($kind) — পেজ বদলেছে, যাচাই করে এগোও"
                    history.add("[handoff:$kind → শেষ]")
                    host.setStatus("✅ ধন্যবাদ — চালিয়ে যাচ্ছি…")
                    fails = 0
                    startLoop()
                    return@launch
                }
            }
        }
    }

    // =================================================================== পর্যবেক্ষণ
    private suspend fun js(script: String): String? = suspendCancellableCoroutine { c ->
        web.evaluateJavascript(script) { c.resume(it) }
    }

    private fun unwrap(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try { JSONObject("""{"v":$raw}""").getString("v") } catch (e: Exception) { raw.trim('"') }
    }

    private suspend fun observe(budget: Int = OBSERVE_BUDGET_TOKENS, offset: Int = 0): Observation? {
        epoch++
        val needDigest = plan.optBoolean("needs_reading", false) || system == "ela_1st" || system == "ela_4n"
        val digestChars = when (system) { "super_lite" -> 700; "super_1_2" -> 1000; else -> 1600 }
        val opts = JSONObject().put("epoch", epoch).put("budget", budget).put("offset", offset)
            .put("digest", needDigest).put("digestChars", digestChars)
        val raw = js(obsJs + "\nwindow.__lp.observe(" + opts.toString() + ");")
        return Observation.parse(unwrap(raw))
    }

    private suspend fun observeWithRetry(): Observation? {
        val off = nextOffset; nextOffset = 0
        for (i in 0 until ELEMENT_RETRY) {
            if (navStarted) waitForNavigation()
            val o = observe(offset = off)
            if (o != null) { lastShown = o.shown; return o }
            delay(250)
        }
        return null
    }

    private suspend fun waitForNavigation() {
        val d = navFinished ?: CompletableDeferred<Unit>().also { navFinished = it }
        withTimeoutOrNull(NAV_WATCHDOG_MS) { d.await() }
        navStarted = false; navFinished = null
        awaitSettle(900)
    }

    /** স্থির পুরো-অপেক্ষার বদলে অবস্থা-ভিত্তিক: DOM ~১২০ms শান্ত + readyState (স্পেক B11.২)। */
    private suspend fun awaitSettle(maxMs: Long = SETTLE_MAX_MS) {
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < maxMs) {
            val q = unwrap(js("(window.__lp && window.__lp.quiet) ? window.__lp.quiet() : ''"))
            if (q.isEmpty()) { delay(80); if (!navStarted) return else continue }
            try {
                val o = JSONObject(q)
                if (o.optLong("sinceLast") >= SETTLE_QUIET_MS && o.optString("ready") != "loading") return
            } catch (e: Exception) { return }
            delay(60)
        }
    }

    // =================================================================== অ্যাকশন
    private fun drainEvents(): String { val s = events.joinToString(", "); events.clear(); return s }

    private suspend fun navigateTo(url: String): Outcome {
        val before = web.url.orEmpty()
        lastError = null
        navFinished = CompletableDeferred(); navStarted = true
        web.loadUrl(url)
        withTimeoutOrNull(NAV_WATCHDOG_MS + 1500) { navFinished?.await() }
        navStarted = false; navFinished = null
        awaitSettle(900)
        return afterNavigation(before)
    }

    private fun afterNavigation(before: String): Outcome {
        val err = lastError; lastError = null
        val now = web.url.orEmpty()
        val flags = ArrayList<String>()
        if (now != before) flags.add("url_changed")
        val ev = drainEvents()
        return if (err != null) Outcome("error", flags, detail = err, extra = ev)
        else Outcome("ok", flags, extra = (if (web.title.isNullOrBlank()) "" else "title=\"${web.title.orEmpty().take(40)}\"") + (if (ev.isNotBlank()) " $ev" else ""))
    }

    private fun resExtra(res: JSONObject): String {
        val parts = ArrayList<String>()
        if (res.has("atBottom")) parts.add("atBottom=" + res.optBoolean("atBottom"))
        if (res.has("atTop")) parts.add("atTop=" + res.optBoolean("atTop"))
        if (res.optString("via").isNotBlank()) parts.add("via=" + res.optString("via"))
        return parts.joinToString(" ")
    }

    private fun parseRunResult(raw: String): JSONObject = try { JSONObject(unwrap(raw)) } catch (e: Exception) { JSONObject().put("result", "error").put("detail", "page_changed") }

    private suspend fun executeAction(a: AgentAction): Outcome {
        val j = a.json
        return try {
            when (a.type) {
                "navigate" -> navigateTo(j.optString("url"))
                "search" -> navigateTo("https://www.google.com/search?q=" + URLEncoder.encode(j.optString("query"), "UTF-8"))
                "go_back" -> {
                    if (!web.canGoBack()) Outcome("no_effect", detail = "no_history")
                    else { val b = web.url.orEmpty(); lastError = null; navFinished = CompletableDeferred(); navStarted = true; web.goBack()
                        withTimeoutOrNull(NAV_WATCHDOG_MS) { navFinished?.await() }; navStarted = false; navFinished = null; awaitSettle(900); afterNavigation(b) }
                }
                "wait" -> { delay(j.optLong("ms", 600).coerceIn(100, 5000)); Outcome("ok") }
                "wait_for" -> waitFor(j.optJSONObject("condition") ?: JSONObject())
                "more" -> { nextOffset = lastObsOffset() + lastShown; Outcome("ok", extra = "more_requested") }
                "dialog" -> { acceptNextDialog = j.optBoolean("accept", false); Outcome("ok", extra = "dialog_policy=" + (if (acceptNextDialog) "accept" else "dismiss")) }
                "tap_xy" -> tapFraction(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
                "upload" -> doUpload(j)
                else -> doPageAction(a)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Outcome("error", detail = (e.message ?: "exception").take(80)) }
    }

    private fun lastObsOffset(): Int = lastObs?.offset ?: 0

    private suspend fun waitFor(cond: JSONObject): Outcome {
        val ms = cond.optLong("ms", 1500).coerceIn(100, 8000)
        js(actJs + "\nwindow.__lp.begin();")
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < ms) {
            val r = unwrap(js("window.__lp.check(" + cond.toString() + ")"))
            if (r.contains("\"ok\":true")) return Outcome("ok", extra = "condition_met")
            delay(80)
        }
        return Outcome("no_effect", detail = "condition_timeout")
    }

    private suspend fun doPageAction(a: AgentAction): Outcome {
        val j = a.json
        val needEffect = a.type == "click" || a.type == "press" || a.type == "select"
        val before = web.url.orEmpty()
        lastError = null; navStarted = false
        js(actJs + "\nwindow.__lp.begin();")
        val res = parseRunResult(js("window.__lp.run(" + j.toString() + ")") ?: "")
        val result = res.optString("result", "error")
        if (result != "ok") return Outcome(result, detail = res.optString("detail"), extra = (resExtra(res) + " " + drainEvents()).trim())

        // trusted কী-ইভেন্ট (Enter ফর্ম-সাবমিট না হলে, এবং অন্য সব কী)
        val tk = res.optString("trusted_key", "")
        if (tk.isNotEmpty()) { web.requestFocus(); sendKey(tk); }

        if (res.optBoolean("upload_requested", false)) return Outcome("ok", extra = "upload_clicked")

        // নেভিগেশন শুরু হলে সেটা শেষ হওয়া পর্যন্ত; নইলে DOM শান্ত হওয়া পর্যন্ত
        delay(60)
        if (navStarted) { waitForNavigation() } else awaitSettle()
        var out = readOutcome(needEffect, res, before)

        // no_effect → trusted ট্যাপে একবার ফলব্যাক (Android MotionEvent)
        if (a.type == "click" && out.result == "no_effect" && a.id != null) {
            js(actJs + "\nwindow.__lp.begin();")
            val tapped = tapElement(a.id!!)
            if (tapped) {
                delay(60); if (navStarted) waitForNavigation() else awaitSettle()
                out = readOutcome(true, res, before)
                if (out.result == "no_effect") out = out.copy(extra = (out.extra + " tapped_trusted").trim())
                else out = out.copy(extra = (out.extra + " via_trusted_tap").trim())
            }
        }
        return out
    }

    private suspend fun readOutcome(needEffect: Boolean, res: JSONObject, before: String): Outcome {
        val now = web.url.orEmpty()
        val raw = unwrap(js("(window.__lp && window.__lp.outcome) ? window.__lp.outcome(" + needEffect + ") : ''"))
        val ev = drainEvents()
        val err = lastError; lastError = null
        if (raw.isEmpty()) {            // পেজ বদলে গেছে — JS কনটেক্সট নেই
            val flags = if (now != before) listOf("url_changed") else emptyList()
            return if (err != null) Outcome("error", flags, detail = err, extra = ev) else Outcome("ok", flags, extra = ev)
        }
        val o = JSONObject(raw)
        val flags = ArrayList<String>()
        o.optJSONArray("flags")?.let { for (i in 0 until it.length()) flags.add(it.optString(i)) }
        if (ev.contains("dialog(")) { /* ডায়ালগ নিজেই তথ্য */ }
        var result = o.optString("result", "ok")
        if (ev.isNotBlank() && result == "no_effect") result = "ok"     // ডায়ালগ এলে "কিছুই হয়নি" নয়
        if (err != null) result = "error"
        val valueStr = res.optString("value", "").ifBlank { null }
        val read = res.optString("read", "").ifBlank { null }
        val detail = if (err != null) err else res.optString("detail", "")
        return Outcome(result, flags, detail = detail, value = valueStr, read = read, extra = (resExtra(res) + " " + ev).trim())
    }

    // ---- trusted ইনপুট (Android) ----
    private fun keyCode(k: String): Int = when (k) {
        "Enter" -> KeyEvent.KEYCODE_ENTER; "Tab" -> KeyEvent.KEYCODE_TAB; "Escape" -> KeyEvent.KEYCODE_ESCAPE
        "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN; "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
        "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT; "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "Backspace" -> KeyEvent.KEYCODE_DEL; "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL; "Space" -> KeyEvent.KEYCODE_SPACE
        "PageDown" -> KeyEvent.KEYCODE_PAGE_DOWN; "PageUp" -> KeyEvent.KEYCODE_PAGE_UP
        "Home" -> KeyEvent.KEYCODE_MOVE_HOME; "End" -> KeyEvent.KEYCODE_MOVE_END
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun sendKey(k: String) {
        val code = keyCode(k)
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }

    private suspend fun tapViewPx(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(down); down.recycle()
        delay(50)
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        web.dispatchTouchEvent(up); up.recycle()
    }

    private suspend fun tapElement(id: String): Boolean {
        val r = try { JSONObject(unwrap(js("window.__lp.rectOf(" + JSONObject.quote(id) + ")"))) } catch (e: Exception) { return false }
        if (r.optString("result") != "ok") return false
        val iw = r.optDouble("iw", 0.0); val ih = r.optDouble("ih", 0.0)
        if (iw <= 0 || ih <= 0) return false
        tapViewPx((r.optDouble("cx") / iw * web.width).toFloat(), (r.optDouble("cy") / ih * web.height).toFloat())
        return true
    }

    private suspend fun tapFraction(fx: Float, fy: Float): Outcome {
        val before = web.url.orEmpty()
        js(actJs + "\nwindow.__lp.begin();")
        tapViewPx(fx.coerceIn(0f, 1f) * web.width, fy.coerceIn(0f, 1f) * web.height)
        delay(60); if (navStarted) waitForNavigation() else awaitSettle()
        return readOutcome(true, JSONObject(), before)
    }

    private suspend fun doUpload(j: JSONObject): Outcome {
        currentUploadRef = j.optString("file_ref", "file").ifBlank { "file" }
        fileDeferred = CompletableDeferred()
        val res = parseRunResult(js(actJs + "\nwindow.__lp.begin();window.__lp.run(" + j.toString() + ")") ?: "")
        if (res.optString("result") != "ok") return Outcome(res.optString("result", "error"), detail = res.optString("detail"))
        val r = withTimeoutOrNull(180_000) { fileDeferred?.await() } ?: "timeout"
        fileDeferred = null
        awaitSettle()
        return if (r == "cancelled" || r == "timeout") Outcome("no_effect", detail = "upload_$r") else Outcome("ok", extra = "upload=$r")
    }

    // =================================================================== স্ক্রিনশট (ডাউনস্কেল)
    private fun captureScreenshot(): String? = try {
        val w = web.width; val h = web.height
        if (w <= 0 || h <= 0) null else {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(full))
            val scale = 768f / maxOf(w, h).toFloat()
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(full, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else full
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }

    private fun persist() {
        try { AgentStateStore.save(host.context, runId, goal, system, plan, ledger, history, perms, lastObs?.url ?: web.url.orEmpty()) } catch (e: Exception) { }
    }
}
