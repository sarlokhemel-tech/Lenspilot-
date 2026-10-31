package com.hemel.lenspilot.tools.agent

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiException
import com.hemel.lenspilot.security.SecretVault
import com.hemel.lenspilot.tools.SessionState
import com.hemel.lenspilot.tools.SessionStore
import com.hemel.lenspilot.tools.StorageManager
import com.hemel.lenspilot.tools.ToolsFormat
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.browser.BrowserWorkspaces
import com.hemel.lenspilot.tools.perm.Decision
import com.hemel.lenspilot.tools.perm.PermissionGate
import com.hemel.lenspilot.tools.perm.Risk
import com.hemel.lenspilot.tools.perm.RiskClassifier
import com.hemel.lenspilot.tools.perm.RiskInput
import com.hemel.lenspilot.tools.perm.ToolsSecurityPrefs
import com.hemel.lenspilot.tools.runner.DetectorSpecException
import com.hemel.lenspilot.tools.runner.RunnerEvent
import com.hemel.lenspilot.tools.runner.RunnerKind
import com.hemel.lenspilot.tools.runner.Runner
import com.hemel.lenspilot.tools.runner.ScheduleAction
import com.hemel.lenspilot.tools.runner.ScheduleSpecException
import com.hemel.lenspilot.tools.runner.Scheduler
import com.hemel.lenspilot.tools.runner.ToolsEvent
import com.hemel.lenspilot.tools.runner.ToolsNotifier
import com.hemel.lenspilot.tools.shell.ShellBus
import com.hemel.lenspilot.workflow.ContextWindowStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolsAgent — প্রতি সেশনে একটা কোরুটিন state-machine।
 *
 * প্রবাহ: plan (ToolsApi) → লুপ { decide → ধাপ চালানো (ওয়ার্কস্পেসের অ্যাডাপ্টারে) → RunnerEvent / PermissionGate / ইউজারের উত্তরের অপেক্ষা
 *   → ছোট এক-লাইন ফল ContextWindowStore-এ → পরেরটা }। প্রতি ধাপের পর agent_state.json-এ সেভ — প্রসেস মরলে ফেরে।
 *
 * বাজেট: প্রতি ধাপে সর্বোচ্চ ৩ রিট্রাই, তারপর ইউজারকে জিজ্ঞেস। একই ত্রুটি পরপর দুবার হলে failure_history সহ decide আবার ডাকা (পদ্ধতি বদলাতে)।
 * অপেক্ষার সময় LLM-এ কোনো কল নেই — শুধু Runner-ইভেন্ট, Scheduler-অ্যালার্ম বা ইউজারের সাড়া জাগায়।
 * সার্ভার-কল সব [ToolsApi] → ApiClient (Redactor প্রযোজ্য)।
 */
class ToolsAgent(
    private val app: Context,
    private val data: AgentData,
    private val ui: MutableStateFlow<AgentUi>
) {
    companion object {
        const val MAX_RETRIES = 3
        const val MAX_STUCK_DECIDES = 2
        const val MAX_DECIDES = 150
        const val MAX_STEPS = 400
        private const val SHORT_SLEEP_S = 60

        /** শেষে একবার — সফল হলে true। ব্যর্থ হলে learnPending থেকে যায় (পরের অ্যাপ-চালুতে আবার)। */
        suspend fun sendLearn(app: Context, d: AgentData): Boolean {
            if (d.learnDone) return true
            val steps = JSONArray()
            for (sig in d.executedSig.takeLast(40)) {
                val ws = sig.substringBefore('.')
                val act = sig.substringAfter('.', "")
                if (ws.isNotEmpty() && act.isNotEmpty()) steps.put(JSONObject().put("workspace", ws).put("action", act))
            }
            val pipe = JSONObject().put("name", d.pipelineName.take(100)).put("steps", steps)
            val elapsed = ((System.currentTimeMillis() - d.startedAt) / 1000L).coerceAtLeast(0L)
            val body = JSONObject()
                .put("goal_summary", d.goal.take(280))
                .put("pipeline_used", pipe)
                .put("outcome", d.learnOutcome.ifEmpty { "failure" })
                .put("metrics", JSONObject().put("time_s", elapsed).put("cost", d.costUsd).put("retries", d.retriesTotal))
                .put("failure_reasons", JSONArray(d.failureHistory.takeLast(5)))
            if (d.pipelineId.isNotEmpty()) body.put("pipeline_id", d.pipelineId)
            body.put("session_id", d.sessionId)
            if (pipe.optJSONArray("steps")?.length() == 0 && d.pipelineName.isEmpty()) return true   // জানানোর মতো কিছু নেই
            var ok = ToolsApi.learn(app, body)
            if (!ok) { delay(1500); ok = ToolsApi.learn(app, body) }
            if (ok) {
                d.learnDone = true
                d.learnPending = false
                AgentStateStore.save(app, d)
            }
            return ok
        }
    }

    private val sid: String = data.sessionId
    val events = Channel<ToolsEvent>(Channel.UNLIMITED)
    private val userSignal = Channel<String>(Channel.UNLIMITED)
    private val resumeSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastState: SessionState? = null

    // ------------------------------------------------------------------------------------ বাইরের দরজা
    fun phase(): Phase = data.phase

    fun pause() {
        if (data.phase.finished) return
        data.paused = true
        save()
    }

    fun resume() {
        data.paused = false
        save()
        resumeSignal.trySend(Unit)
    }

    /** ইউজারের বার্তা: প্রশ্ন/needs_you চলছে হলে উত্তর, নইলে পরের decide-এ user_note। */
    fun userMessage(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        addChat("user", t)
        if (data.phase == Phase.ASK || data.phase == Phase.NEEDS_YOU) {
            userSignal.trySend(t)
        } else {
            data.userNote = (if (data.userNote.isEmpty()) t else data.userNote + " | " + t).takeLast(500)
            data.forceDecide = true
            save()
        }
    }

    // ------------------------------------------------------------------------------------ মূল লুপ
    suspend fun run() {
        ToolsAgents.keepAlive(app, sid, true)
        publish { it.copy(alive = true) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (data.phase.finished) break
                if (data.paused) { awaitResume(); continue }
                when (data.phase) {
                    Phase.DECIDE -> stepDecide()
                    Phase.EXEC -> stepExec()
                    Phase.AWAIT_DETECTOR -> stepAwaitDetector()
                    Phase.WAIT_TIME -> stepWaitTime()
                    Phase.ASK, Phase.NEEDS_YOU -> stepAwaitUser()
                    else -> break
                }
            }
            finish()
        } finally {
            withContext(NonCancellable) {
                ToolsAgents.keepAlive(app, sid, false)
                publish { it.copy(alive = false) }
            }
        }
    }

    // ------------------------------------------------------------------------------------ decide
    private suspend fun stepDecide() {
        if (data.decideCalls >= MAX_DECIDES || data.stepsDone >= MAX_STEPS) {
            failWith(app.getString(R.string.tools_agent_limit))
            return
        }
        setState(SessionState.RUNNING, app.getString(R.string.tools_agent_thinking))
        publish { it.copy(stepLabel = app.getString(R.string.tools_agent_thinking), question = null) }
        val req = buildDecideRequest()
        data.decideCalls++
        var res = ToolsApi.decide(app, req)
        if (res.isFailure) {
            val err = res.exceptionOrNull()
            if (err is ApiException && (err.code == 402 || err.code == 429)) {
                failWith(ToolsApi.friendlyError(app, err))
                return
            }
            delay(2000)
            res = ToolsApi.decide(app, req)
        }
        val d = res.getOrNull()
        if (d == null) {
            val err = res.exceptionOrNull()
            if (err is ApiException && err.code == 402) { failWith(ToolsApi.friendlyError(app, err)); return }
            if (data.planRemaining.isNotEmpty()) {
                // সার্ভার পাওয়া যায়নি — পরিকল্পনার বাকি ধাপ সরাসরি চালাই
                data.queue = data.planRemaining.take(5).toMutableList()
                data.phase = Phase.EXEC
                addChat("elora", app.getString(R.string.tools_agent_offline_plan))
                save()
            } else {
                val why = if (err != null) ToolsApi.friendlyError(app, err) else ""
                askUser(app.getString(R.string.tools_agent_decide_failed, why), listOf(retryLabel(), stopLabel()), afterFailure = true)
            }
            return
        }
        data.userNote = ""
        data.forceDecide = false
        handleDecision(d)
    }

    private fun handleDecision(d: DecideResult) {
        if (d.editGoal != null) editGoal(d.editGoal)
        if (d.say.isNotEmpty()) addChat("elora", d.say)
        if (d.status == "replan") data.planRemaining.clear()
        if (d.done || d.status == "done") {
            data.phase = Phase.DONE
            data.failNote = ""
            save()
            return
        }
        if (d.status == "failed") {
            failNow(d.say.ifEmpty { d.note }.ifEmpty { app.getString(R.string.tools_agent_failed_generic) })
            return
        }
        if (d.askQuestion != null) {
            askUser(d.askQuestion, d.askOptions, afterFailure = false)
            return
        }
        if ((d.status == "blocked" || d.status == "needs_you") && d.steps.isEmpty()) {
            askUser(d.say.ifEmpty { d.note }.ifEmpty { app.getString(R.string.tools_agent_need_help) }, d.askOptions, afterFailure = false)
            return
        }
        val wu = d.waitUntilSec
        if (d.steps.isNotEmpty()) {
            data.queue = d.steps.toMutableList()
            data.emptyDecides = 0
            data.retries = 0
            if (wu != null) data.waitUntilMs = wu * 1000L
            data.phase = Phase.EXEC
            save()
            return
        }
        if (wu != null) {
            data.emptyDecides = 0
            startWait(wu * 1000L, d.note)
            return
        }
        data.emptyDecides++
        if (data.emptyDecides >= 2) {
            data.emptyDecides = 0
            askUser(app.getString(R.string.tools_agent_no_idea), emptyList(), afterFailure = false)
        } else {
            save()
        }
    }

    private fun editGoal(newGoal: String) {
        data.goal = newGoal
        try { ContextWindowStore.updateGoal(app, sid, newGoal) } catch (e: Exception) { }
        SessionStore.update(app, sid) { it.copy(goal = newGoal) }
        addChat("elora", app.getString(R.string.tools_agent_goal_edited, newGoal.take(120)))
    }

    private fun buildDecideRequest(): JSONObject {
        val hist = ContextWindowStore.recentForPrompt(app, sid, 12)
        val titles = try { SecretVault.list(app).map { it.title }.take(40) } catch (e: Exception) { emptyList() }
        val remaining = JSONArray()
        data.planRemaining.take(8).forEach { remaining.put(it.compactJson()) }
        val o = JSONObject()
        o.put("goal", data.goal.take(600))
        o.put("session_id", sid)
        o.put("plan_position", JSONObject().put("index", data.stepsDone).put("total", maxOf(data.planTotal, data.stepsDone)))
        o.put("remaining_steps", remaining)
        o.put("context_history", JSONArray(hist))
        o.put("last_result", data.lastResult.take(1500))
        o.put("workspace_states", workspaceStates())
        o.put("vault_titles", JSONArray(titles))
        o.put("permission_states", permissionStates())
        if (data.userNote.isNotEmpty()) o.put("user_note", data.userNote.take(500))
        o.put("failure_history", JSONArray(data.failureHistory.takeLast(6)))
        return o
    }

    private fun workspaceStates(): JSONObject {
        val o = JSONObject()
        o.put("browser", if (BrowserWorkspaces.current(sid) != null) "open" else "closed")
        o.put("shell", try { ShellBus.get(sid)?.prompt() ?: "idle" } catch (e: Exception) { "idle" })
        o.put("storage", try { "files=" + StorageManager(ToolsPaths.workspaceDir(app, sid)).list("").size } catch (e: Exception) { "-" })
        return o
    }

    private fun permissionStates(): JSONObject {
        val o = JSONObject()
        o.put("notifications", if (PermissionGate.canNotify(app)) "granted" else "denied")
        val exact = try {
            if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } else true
        } catch (e: Exception) { false }
        o.put("exact_alarm", if (exact) "granted" else "denied")
        o.put("signed_in_session", if (ToolsSecurityPrefs.signedInSessionConsent(app)) "granted" else "denied")
        return o
    }

    // ------------------------------------------------------------------------------------ ধাপ চালানো
    private suspend fun stepExec() {
        val step = data.queue.firstOrNull()
        if (step == null) { afterBatch(); return }
        setState(SessionState.RUNNING, step.brief())
        publish {
            it.copy(
                workspace = wsOf(step), stepLabel = step.brief(), question = null,
                stepIndex = data.stepsDone + 1, stepTotal = maxOf(data.planTotal, data.stepsDone + data.queue.size)
            )
        }

        // wait ও ask_user ধাপ ডিভাইসেই সামলানো — কোনো ওয়ার্কস্পেস লাগে না
        if (step.workspace == "wait") { handleWaitStep(step); return }
        if (step.workspace == "ask_user") {
            popHead(step)
            val opts = ArrayList<String>()
            val oa = step.args.optJSONArray("options")
            if (oa != null) for (i in 0 until oa.length()) opts.add(oa.optString(i, ""))
            askUser(step.args.optString("question", app.getString(R.string.tools_agent_need_help)), opts.filter { it.isNotBlank() }, afterFailure = false)
            return
        }

        val gated = riskGate(step)
        val r = gated ?: dispatch(step)
        afterResult(step, r)
    }

    private suspend fun dispatch(step: PlanStep): StepResult = when (step.workspace) {
        "browser" -> BrowserAdapter.run(app, sid, step, observeAfter = data.queue.size <= 1)
        "shell" -> ShellAdapter.run(app, sid, step)
        "storage" -> StorageAdapter.run(app, sid, step)
        else -> StepResult.fail("অজানা workspace '${step.workspace.take(20)}'")
    }

    /** ঝুঁকি: সার্ভারের risk=high অথবা ডিভাইসের RiskClassifier। শেলের কমান্ড ShellApprovalGate নিজেই জিজ্ঞেস করে — দুবার নয়। */
    private suspend fun riskGate(step: PlanStep): StepResult? {
        val cls = RiskClassifier.classify(RiskInput(step.workspace, step.action, step.riskText()))
        if (step.workspace == "shell" && cls.risk != Risk.LOW) return null
        val risk = if (step.risk == "high") Risk.HIGH else cls.risk
        if (risk == Risk.LOW) return null
        setState(SessionState.NEEDS_YOU, app.getString(R.string.tools_agent_waiting_approval))
        val title = app.getString(R.string.elora_name) + ": " + step.workspace + "." + step.action
        val detail = (if (cls.reason.isNotEmpty()) cls.reason + "\n" else "") + step.riskText()
        val d = PermissionGate.request(risk, title, detail, 180, sid)
        setState(SessionState.RUNNING, step.brief())
        return if (d == Decision.APPROVED) null
        else StepResult(false, app.getString(R.string.tools_agent_denied, step.brief()), denied = true)
    }

    private suspend fun afterResult(step: PlanStep, r: StepResult) {
        val tag = step.workspace + "." + step.action
        remember(tag + (if (r.ok) " ✓ " else " ✗ ") + r.line)
        data.lastResult = r.full
        publish { it.copy(lastResult = r.line) }

        if (r.denied) {
            data.failureHistory.add(r.line)
            trimFailures()
            data.queue.clear()
            // একই ধাপ দ্বিতীয়বার অননুমোদিত: মডেল জেদ ধরলেও লুপ নয় — কাজ পরিষ্কারভাবে ব্যর্থ হয়ে থামে
            if (data.failureHistory.count { it == r.line } >= 2) {
                failNow(r.line)
                return
            }
            data.forceDecide = true
            data.phase = Phase.DECIDE
            save()
            return
        }
        if (r.needsYou) {
            data.failureHistory.add(r.line)
            trimFailures()
            raiseNeedsYou(r.line)
            return
        }
        if (r.ok) {
            popHead(step)
            data.stepsDone++
            if (data.executedSig.size < 200) data.executedSig.add(tag)
            data.retries = 0
            data.stuckDecides = 0
            data.lastErrSig = ""
            data.failureHistory.clear()
            if (data.forceDecide && data.userNote.isNotEmpty()) data.queue.clear()   // ইউজারের নোট — পরের decide-এ
            val det = effectiveDetector(step)
            if (det != null && armDetector(step, det)) return
            if (data.queue.isEmpty()) afterBatch() else { data.phase = Phase.EXEC; save() }
            return
        }
        // ব্যর্থ
        data.failureHistory.add(step.brief() + ": " + r.line)
        trimFailures()
        val sig = errSig(r.line)
        val same = sig == data.lastErrSig
        data.lastErrSig = sig
        when (step.onFail) {
            "skip" -> { popHead(step); if (data.queue.isEmpty()) afterBatch() else { data.phase = Phase.EXEC; save() }; return }
            "abort" -> { failNow(r.line); return }
            "ask_user" -> {
                askUser(app.getString(R.string.tools_agent_step_failed, step.brief(), r.line), listOf(retryLabel(), skipLabel(), stopLabel()), afterFailure = true)
                return
            }
        }
        if (same) { handleStuck(r.line); return }
        data.retries++
        data.retriesTotal++
        if (data.retries > MAX_RETRIES) {
            askUser(app.getString(R.string.tools_agent_retries_over, step.brief(), r.line), listOf(retryLabel(), skipLabel(), stopLabel()), afterFailure = true)
            return
        }
        if (step.onFail == "replan") {
            data.queue.clear()
            data.phase = Phase.DECIDE
        } else {
            delay(1500L * data.retries)   // ছোট বিরতির পর একই ধাপ আবার
            data.phase = Phase.EXEC
        }
        save()
    }

    /** একই ত্রুটি দ্বিতীয়বার: পদ্ধতি বদলাতে failure_history সহ decide। বারবার হলে ইউজারকে জিজ্ঞেস। */
    private fun handleStuck(line: String) {
        data.stuckDecides++
        if (data.stuckDecides > MAX_STUCK_DECIDES) {
            data.stuckDecides = 0
            askUser(app.getString(R.string.tools_agent_stuck_ask, line.take(120)), listOf(retryLabel(), stopLabel()), afterFailure = true)
            return
        }
        addChat("elora", app.getString(R.string.tools_agent_stuck_change))
        data.queue.clear()
        data.forceDecide = true
        data.phase = Phase.DECIDE
        save()
    }

    private fun afterBatch() {
        if (data.waitUntilMs > System.currentTimeMillis()) {
            startWait(data.waitUntilMs, data.waitReason)
        } else {
            data.waitUntilMs = 0L
            data.phase = Phase.DECIDE
            save()
        }
    }

    private fun popHead(step: PlanStep) {
        if (data.queue.isNotEmpty() && data.queue[0].id == step.id) data.queue.removeAt(0)
        else data.queue.removeAll { it.id == step.id }
        data.executedIds.add(step.id)
        data.planRemaining.removeAll { it.id == step.id && it.workspace == step.workspace && it.action == step.action }
    }

    private fun trimFailures() {
        while (data.failureHistory.size > 6) data.failureHistory.removeAt(0)
    }

    private fun errSig(line: String): String =
        line.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim().take(80)

    private fun wsOf(step: PlanStep): String = when (step.workspace) {
        "browser", "shell", "storage" -> step.workspace
        else -> "none"
    }

    // ------------------------------------------------------------------------------------ ডিটেক্টর
    private fun effectiveDetector(step: PlanStep): JSONObject? {
        val d = step.detector ?: return null
        val type = d.optString("type", "")
        if (type.isEmpty()) return null
        if (type == "timeout" && d.optInt("seconds", 0) <= 0) return null
        if (type == "command_exit" && step.workspace == "shell") return null     // শেলের আসল exit কোডই যথেষ্ট
        return d
    }

    /** true = arm হয়েছে (ফেজ AWAIT_DETECTOR); false = detector অবৈধ, এগোনো যাবে। */
    private fun armDetector(step: PlanStep, det: JSONObject): Boolean {
        return try {
            val wid = Runner.arm(app, sid, step.id, det, step.timeoutS.coerceIn(60, 3600))
            data.awaitStepId = step.id
            data.awaitWatcherId = wid
            data.awaitDetector = det.toString()
            data.awaitDeadlineMs = System.currentTimeMillis() + step.timeoutS * 1000L
            data.phase = Phase.AWAIT_DETECTOR
            setStateSync(SessionState.WAITING, app.getString(R.string.tools_agent_waiting_detector, step.brief()))
            save()
            true
        } catch (e: DetectorSpecException) {
            data.lastResult = (data.lastResult + " | detector অবৈধ: " + (e.message ?: "")).takeLast(1400)
            false
        }
    }

    private suspend fun stepAwaitDetector() {
        val stepId = data.awaitStepId
        var wid = data.awaitWatcherId
        val det = try { JSONObject(data.awaitDetector) } catch (e: Exception) { null }
        if (det == null) { data.phase = Phase.DECIDE; data.queue.clear(); save(); return }
        // প্রসেস মরে ফিরলে watcher হারিয়ে গেলে আবার arm (probe-গুলো পুনরাবৃত্তিযোগ্য)
        if (wid.isEmpty() || Runner.active(app).none { it.id == wid }) {
            drainEvents()
            try {
                wid = Runner.arm(app, sid, stepId, det, 1800)
                data.awaitWatcherId = wid
                save()
            } catch (e: DetectorSpecException) {
                data.phase = Phase.DECIDE; data.queue.clear(); save(); return
            }
        }
        setState(SessionState.WAITING, app.getString(R.string.tools_agent_waiting_detector, stepId))
        publish { it.copy(note = app.getString(R.string.tools_agent_waiting_short)) }
        val remaining = data.awaitDeadlineMs - System.currentTimeMillis()
        val ev: RunnerEvent? = if (remaining <= 0) null else withTimeoutOrNull(remaining) { nextRunnerEvent(stepId) }
        try { Runner.cancel(app, wid) } catch (e: Exception) { }
        data.awaitWatcherId = ""
        data.awaitDetector = ""
        when {
            ev == null -> failedDetector(app.getString(R.string.tools_agent_detector_timeout, stepId))
            ev.kind == RunnerKind.DONE -> {
                data.lastResult = ("ডিটেক্টর সফল: " + ev.evidence).take(1400)
                remember("$stepId detector ✓ " + ev.evidence.replace('\n', ' '))
                data.failureHistory.clear()
                if (data.forceDecide && data.userNote.isNotEmpty()) data.queue.clear()
                if (data.queue.isEmpty()) afterBatch() else { data.phase = Phase.EXEC; save() }
            }
            ev.kind == RunnerKind.NEEDS_YOU -> raiseNeedsYou(ev.evidence.ifEmpty { stepId })
            else -> failedDetector(ev.kind.wire + ": " + ev.evidence)
        }
    }

    /** ব্যর্থ/আটকে/সময়-শেষ — LLM জাগে: failure_history সহ decide (সেই ধাপ আগেই শেষ হয়েছে, তাই পুনরায় নয়)। */
    private fun failedDetector(text: String) {
        data.lastResult = text.take(1400)
        remember("detector ✗ " + text.replace('\n', ' '))
        data.failureHistory.add(text.take(190))
        trimFailures()
        val sig = errSig(text)
        val same = sig == data.lastErrSig
        data.lastErrSig = sig
        if (same) { handleStuck(text); return }
        data.queue.clear()
        data.forceDecide = true
        data.phase = Phase.DECIDE
        save()
    }

    /** এই ধাপের (বা যেকোনো needs_you) ঘটনা না আসা পর্যন্ত অপেক্ষা; অন্য ইভেন্ট উপেক্ষিত। */
    private suspend fun nextRunnerEvent(stepId: String): RunnerEvent {
        while (true) {
            val e = events.receive()
            if (e is RunnerEvent && (e.stepId == stepId || e.kind == RunnerKind.NEEDS_YOU)) return e
        }
    }

    private fun drainEvents() {
        while (events.tryReceive().isSuccess) { /* বাসি ইভেন্ট ফেলা */ }
    }

    // ------------------------------------------------------------------------------------ সময়ের অপেক্ষা
    private suspend fun handleWaitStep(step: PlanStep) {
        popHead(step)
        data.stepsDone++
        if (data.executedSig.size < 200) data.executedSig.add(step.workspace + "." + step.action)
        if (step.action == "until") {
            val ms = step.args.optLong("until_ts", 0L) * 1000L
            if (ms > System.currentTimeMillis() + 1000L) { startWait(ms, app.getString(R.string.tools_agent_wait_until_note)); return }
            remember("wait.until ✓ (সময় আগেই পেরিয়েছে)")
        } else {
            val secs = step.args.optInt("seconds", 60).coerceIn(1, 86_400)
            if (secs <= SHORT_SLEEP_S) {
                setState(SessionState.WAITING, app.getString(R.string.tools_agent_sleeping, secs))
                delay(secs * 1000L)
                remember("wait.sleep ${secs}s ✓")
            } else {
                startWait(System.currentTimeMillis() + secs * 1000L, app.getString(R.string.tools_agent_wait_until_note)); return
            }
        }
        if (data.queue.isEmpty()) afterBatch() else { data.phase = Phase.EXEC; save() }
    }

    private fun startWait(targetMs: Long, reason: String) {
        data.waitUntilMs = targetMs
        data.waitReason = reason.take(160)
        data.phase = Phase.WAIT_TIME
        // Scheduler: exact alarm (না পারলে WorkManager) — প্রসেস মরলেও সময়ে জাগাবে
        try {
            if (data.waitSchedId.isNotEmpty()) Scheduler.cancel(app, data.waitSchedId)
            val (s, _) = Scheduler.create(app, sid, targetMs, ScheduleAction.ResumeStep(null), reason.take(120))
            data.waitSchedId = s.id
        } catch (e: ScheduleSpecException) {
            data.waitSchedId = ""
        }
        save()
    }

    private suspend fun stepWaitTime() {
        val target = data.waitUntilMs
        val label = app.getString(R.string.tools_agent_waiting_until, ToolsFormat.date(target))
        setState(SessionState.WAITING, label)
        publish { it.copy(note = label, stepLabel = label, workspace = "none") }
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0L) break
            // শূন্য LLM-টোকেন: শুধু ঘুম। অ্যালার্ম ফায়ার হলে প্রসেস জেগে একই কাজ করে।
            delay(minOf(remaining, 30_000L))
        }
        if (data.waitSchedId.isNotEmpty()) {
            try { Scheduler.cancel(app, data.waitSchedId) } catch (e: Exception) { }
            data.waitSchedId = ""
        }
        data.waitUntilMs = 0L
        data.lastResult = app.getString(R.string.tools_agent_wait_over)
        remember("wait ✓ সময় হয়েছে")
        addChat("elora", app.getString(R.string.tools_agent_wait_over))
        if (data.queue.isEmpty()) { data.forceDecide = true; data.phase = Phase.DECIDE } else data.phase = Phase.EXEC
        save()
    }

    // ------------------------------------------------------------------------------------ ইউজারের অপেক্ষা
    private fun askUser(text: String, options: List<String>, afterFailure: Boolean) {
        data.askText = text.take(300)
        data.askOptions = options.take(4).toMutableList()
        data.askAfterFailure = afterFailure
        data.phase = Phase.ASK
        save()
    }

    private fun raiseNeedsYou(text: String) {
        data.askText = text.take(300)
        data.askOptions = arrayListOf(doneLabel())
        data.askAfterFailure = false
        data.phase = Phase.NEEDS_YOU
        save()
    }

    private suspend fun stepAwaitUser() {
        val q = data.askText.ifEmpty { app.getString(R.string.tools_agent_need_help) }
        val isAsk = data.phase == Phase.ASK
        setState(SessionState.NEEDS_YOU, q)
        publish { it.copy(question = AgentQuestion(q, data.askOptions.toList()), workspace = "none", stepLabel = "") }
        if (isAsk) addChat("elora", q)
        ToolsNotifier.event(app, app.getString(R.string.tools_agent_needs_you_title), q, sid)
        drainEvents()
        while (userSignal.tryReceive().isSuccess) { /* আগের বাসি উত্তর (ডাবল-ট্যাপ ইত্যাদি) ফেলা */ }
        val reply = userSignal.receive().trim()
        val afterFailure = data.askAfterFailure
        val wasAsk = isAsk
        data.askText = ""
        data.askOptions = ArrayList()
        data.askAfterFailure = false
        publish { it.copy(question = null) }
        if (afterFailure) {
            when (reply) {
                retryLabel() -> {
                    data.retries = 0
                    data.stuckDecides = 0
                    data.phase = if (data.queue.isEmpty()) Phase.DECIDE else Phase.EXEC
                }
                skipLabel() -> {
                    data.queue.firstOrNull()?.let { popHead(it) }
                    data.retries = 0
                    data.phase = if (data.queue.isEmpty()) Phase.DECIDE else Phase.EXEC
                }
                stopLabel() -> { failNow(app.getString(R.string.tools_agent_user_stopped)); return }
                else -> {
                    data.userNote = reply.take(500)
                    data.queue.clear()
                    data.forceDecide = true
                    data.phase = Phase.DECIDE
                }
            }
        } else {
            data.lastResult = if (wasAsk) "ইউজারের উত্তর: " + reply.take(300)
            else "ইউজার নিজে হাতে ধাপটা সেরেছেন" + (if (reply.isNotEmpty() && reply != doneLabel()) ": " + reply.take(200) else "")
            remember(data.lastResult)
            data.queue.clear()
            data.forceDecide = true
            data.phase = Phase.DECIDE
        }
        save()
    }

    private suspend fun awaitResume() {
        ToolsAgents.keepAlive(app, sid, false)
        setState(SessionState.WAITING, app.getString(R.string.tools_agent_paused))
        publish { it.copy(paused = true, note = app.getString(R.string.tools_agent_paused)) }
        while (data.paused) resumeSignal.receive()
        ToolsAgents.keepAlive(app, sid, true)
        publish { it.copy(paused = false, note = "") }
    }

    // ------------------------------------------------------------------------------------ সমাপ্তি
    private fun failNow(msg: String) {
        data.failNote = msg.take(300)
        data.phase = Phase.FAILED
        save()
    }

    private fun failWith(msg: String) = failNow(msg)

    private suspend fun finish() {
        when (data.phase) {
            Phase.DONE -> {
                val say = data.chat.lastOrNull { it.from == "elora" }?.text ?: ""
                val note = say.ifEmpty { app.getString(R.string.tools_agent_done) }
                setStateSync(SessionState.DONE, note)
                publish { it.copy(question = null, workspace = "none", stepLabel = "", note = note) }
                ToolsNotifier.event(app, app.getString(R.string.tools_agent_done_title), note, sid)
                data.learnOutcome = "success"
            }
            Phase.FAILED -> {
                val note = data.failNote.ifEmpty { app.getString(R.string.tools_agent_failed_generic) }
                setStateSync(SessionState.FAILED, note)
                addChat("elora", note)
                publish { it.copy(question = null, workspace = "none", stepLabel = "", note = note) }
                ToolsNotifier.event(app, app.getString(R.string.tools_agent_failed_title), note, sid)
                data.learnOutcome = "failure"
            }
            else -> {
                setStateSync(SessionState.IDLE, app.getString(R.string.tools_agent_user_stopped))
                publish { it.copy(question = null, workspace = "none", stepLabel = "") }
                return
            }
        }
        data.learnPending = true
        save()
        // শেষ ঘটনায় একবারই /api/tools/learn (সফল না হলে পরের অ্যাপ-চালুতে আবার চেষ্টা)
        withContext(NonCancellable) { try { sendLearn(app, data) } catch (e: Exception) { } }
    }

    // ------------------------------------------------------------------------------------ ছোট হেল্পার
    private fun retryLabel() = app.getString(R.string.tools_ask_retry)
    private fun skipLabel() = app.getString(R.string.tools_ask_skip)
    private fun stopLabel() = app.getString(R.string.tools_ask_stop)
    private fun doneLabel() = app.getString(R.string.tools_ask_done)

    private fun remember(line: String) {
        try { ContextWindowStore.remember(app, sid, line.replace('\n', ' ')) } catch (e: Exception) { }
    }

    private fun addChat(from: String, text: String) {
        if (text.isBlank()) return
        data.chat.add(AgentChatLine(from, text.take(600), System.currentTimeMillis()))
        while (data.chat.size > 40) data.chat.removeAt(0)
        publish { it.copy(chat = data.chat.toList()) }
    }

    private fun save() {
        AgentStateStore.save(app, data)
        publish { it.copy(paused = data.paused, chat = data.chat.toList()) }
    }

    private inline fun publish(crossinline f: (AgentUi) -> AgentUi) {
        ui.update { f(it) }
    }

    /** সেশন-স্টেট শুধু বদলালে ডিস্কে লেখে (প্রতি ধাপে নয়)। */
    private fun setState(st: SessionState, note: String) {
        if (lastState != st || ui.value.note != note) {
            SessionStore.update(app, sid) { it.copy(state = st, lastNote = note.take(200)) }
        }
        lastState = st
        publish { it.copy(state = st, note = note) }
    }

    private fun setStateSync(st: SessionState, note: String) = setState(st, note)
}
