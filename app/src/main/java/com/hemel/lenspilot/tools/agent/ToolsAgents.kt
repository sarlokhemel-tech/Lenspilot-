package com.hemel.lenspilot.tools.agent

import android.content.Context
import com.hemel.lenspilot.tools.SessionState
import com.hemel.lenspilot.tools.SessionStore
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.runner.Runner
import com.hemel.lenspilot.tools.runner.RunnerEvent
import com.hemel.lenspilot.tools.runner.RunnerService
import com.hemel.lenspilot.tools.runner.ScheduleFired
import com.hemel.lenspilot.tools.runner.Scheduler
import com.hemel.lenspilot.tools.runner.ToolsEvents
import com.hemel.lenspilot.tools.shell.ShellBus
import com.hemel.lenspilot.workflow.ContextWindowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * সব এজেন্টের রেজিস্ট্রি + প্রসেস-জুড়ে একটাই ইভেন্ট-কালেক্টর + RunnerService-এ "জীবিত রাখা"।
 *  * প্রতি সেশনে সর্বোচ্চ একটা [ToolsAgent]।
 *  * [init] (LenspilotApplication.onCreate): কালেক্টর চালু, অসমাপ্ত সেশন ফেরানো, বাকি learn পাঠানো।
 *  * অপেক্ষার সময় এজেন্ট RunnerService-কে জীবিত রাখে ([keepAlive]) — সার্ভিসের স্থায়ী নোটিফিকেশনে দেখা যায়।
 */
object ToolsAgents {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agents = ConcurrentHashMap<String, ToolsAgent>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val uis = ConcurrentHashMap<String, MutableStateFlow<AgentUi>>()
    private val viewers = ConcurrentHashMap<String, Int>()
    private val keep: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var inited = false

    // ------------------------------------------------------------------------------------ শুরু
    /** Application.onCreate থেকে। ভারী কিছু নয় — সব ব্যাকগ্রাউন্ডে। */
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        scope.launch {
            ToolsEvents.flow.collect { e ->
                when (e) {
                    is RunnerEvent -> e.sessionId?.let { agents[it]?.events?.trySend(e) }
                    is ScheduleFired -> e.sessionId?.let { sid ->
                        val a = agents[sid]
                        if (a == null) ensureRunning(app, sid) else a.events.trySend(e)
                    }
                    else -> { }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try { recoverAll(app) } catch (t: Throwable) { android.util.Log.e("ToolsAgents", "পুনরুদ্ধার ব্যর্থ", t) }
        }
    }

    /** প্রসেস মরার পর: চলমান/অপেক্ষমাণ সেশন আবার চালু; শেষ হয়েও learn না-পাঠানো থাকলে পাঠানো। */
    private suspend fun recoverAll(app: Context) {
        for (s in SessionStore.list(app)) {
            val d = AgentStateStore.load(app, s.id) ?: continue
            if (!d.phase.finished) {
                ensureRunning(app, s.id)
            } else if (d.learnPending && !d.learnDone) {
                try { ToolsAgent.sendLearn(app, d) } catch (e: Exception) { }
            }
        }
    }

    /** নতুন কাজ: পরিকল্পনা থেকে। আগের এজেন্ট থাকলে আগে থামানো হয়। */
    fun start(ctx: Context, sessionId: String, goal: String, plan: PlanResult) {
        val app = ctx.applicationContext
        if (!ToolsPaths.isValidId(sessionId)) return
        stopQuiet(app, sessionId)
        val d = AgentData(sessionId)
        d.goal = goal
        d.phase = Phase.DECIDE
        d.planRemaining = plan.steps.toMutableList()
        d.planTotal = plan.steps.size
        d.pipelineId = plan.pipelineId
        d.pipelineName = plan.pipelineName
        d.costUsd = plan.costUsd
        try { ContextWindowStore.startSession(app, sessionId, goal) } catch (e: Exception) { }
        AgentStateStore.save(app, d)
        launchAgent(app, d)
    }

    /** কোনো অসমাপ্ত সেশন থাকলে তার এজেন্ট চালু (আগে থেকে জীবিত থাকলে কিছু নয়)। */
    fun ensureRunning(ctx: Context, sessionId: String) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (agents.containsKey(sessionId)) return
            val d = AgentStateStore.load(app, sessionId) ?: return
            if (d.phase.finished) return
            launchAgent(app, d)
        }
    }

    private fun launchAgent(app: Context, d: AgentData) {
        synchronized(this) {
            val sid = d.sessionId
            val ui = uiHolder(app, sid)
            ui.value = ui.value.copy(chat = d.chat.toList(), paused = d.paused)
            val agent = ToolsAgent(app, d, ui)
            agents[sid] = agent
            val job = scope.launch {
                try {
                    agent.run()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.e("ToolsAgents", "এজেন্ট ভেঙেছে", t)
                    d.failNote = (t.message ?: t.javaClass.simpleName).take(200)
                    d.phase = Phase.FAILED
                    AgentStateStore.save(app, d)
                    SessionStore.update(app, sid) { it.copy(state = SessionState.FAILED, lastNote = d.failNote) }
                } finally {
                    synchronized(this@ToolsAgents) {
                        if (agents[sid] === agent) { agents.remove(sid); jobs.remove(sid) }
                    }
                }
            }
            jobs[sid] = job
        }
    }

    // ------------------------------------------------------------------------------------ নিয়ন্ত্রণ
    fun isAlive(sessionId: String): Boolean = agents.containsKey(sessionId)

    fun pause(ctx: Context, sessionId: String) {
        agents[sessionId]?.pause()
    }

    fun resume(ctx: Context, sessionId: String) {
        val a = agents[sessionId]
        if (a != null) a.resume() else ensureRunning(ctx, sessionId)
    }

    /** ইউজারের বার্তা (চ্যাটবক্স/ব্রাউজারের চ্যাট থেকে)। এজেন্ট না থাকলে (শেষ/থামানো সেশন) নতুন নোট দিয়ে আবার খোলে। */
    fun send(ctx: Context, sessionId: String, text: String) {
        val app = ctx.applicationContext
        val t = text.trim()
        if (t.isEmpty() || !ToolsPaths.isValidId(sessionId)) return
        val live = agents[sessionId]
        if (live != null) { live.userMessage(t); return }
        val d = AgentStateStore.load(app, sessionId) ?: return
        d.chat.add(AgentChatLine("user", t.take(600), System.currentTimeMillis()))
        d.userNote = t.take(500)
        d.forceDecide = true
        d.phase = Phase.DECIDE
        d.paused = false
        d.queue.clear()
        d.failNote = ""
        d.learnDone = false
        d.learnPending = false
        d.startedAt = System.currentTimeMillis()
        d.retriesTotal = 0
        d.executedSig.clear()
        AgentStateStore.save(app, d)
        launchAgent(app, d)
    }

    /** Stop বাটন: এজেন্ট বন্ধ, নজরদারি/শিডিউল বাতিল, চলমান শেল কমান্ড থামানো। learn পাঠানো হয় না। */
    fun stop(ctx: Context, sessionId: String) {
        val app = ctx.applicationContext
        stopQuiet(app, sessionId)
        val d = AgentStateStore.load(app, sessionId)
        if (d != null && !d.phase.finished) {
            d.phase = Phase.STOPPED
            AgentStateStore.save(app, d)
        }
        SessionStore.update(app, sessionId) { it.copy(state = SessionState.IDLE, lastNote = "থামানো হয়েছে") }
        uiHolder(app, sessionId).let { f ->
            f.value = f.value.copy(state = SessionState.IDLE, question = null, alive = false, stepLabel = "", workspace = "none")
        }
    }

    private fun stopQuiet(app: Context, sessionId: String) {
        synchronized(this) {
            jobs.remove(sessionId)?.cancel()
            agents.remove(sessionId)
        }
        keep.remove(sessionId)
        try { ShellBus.get(sessionId)?.stop() } catch (e: Exception) { }
        try { Runner.active(app).filter { it.sessionId == sessionId }.forEach { Runner.cancel(app, it.id) } } catch (e: Exception) { }
        try { Scheduler.upcoming(app).filter { it.sessionId == sessionId }.forEach { Scheduler.cancel(app, it.id) } } catch (e: Exception) { }
        RunnerService.start(app)
    }

    // ------------------------------------------------------------------------------------ UI
    private fun uiHolder(app: Context, sessionId: String): MutableStateFlow<AgentUi> =
        uis.getOrPut(sessionId) { MutableStateFlow(initialUi(app, sessionId)) }

    fun uiFlow(ctx: Context, sessionId: String): StateFlow<AgentUi> = uiHolder(ctx.applicationContext, sessionId).asStateFlow()

    private fun initialUi(app: Context, sessionId: String): AgentUi {
        val s = SessionStore.get(app, sessionId)
        val d = AgentStateStore.load(app, sessionId)
        val q = if (d != null && (d.phase == Phase.ASK || d.phase == Phase.NEEDS_YOU) && d.askText.isNotEmpty())
            AgentQuestion(d.askText, d.askOptions.toList()) else null
        return AgentUi(
            sessionId = sessionId,
            state = s?.state ?: SessionState.IDLE,
            note = s?.lastNote ?: "",
            paused = d?.paused ?: false,
            question = q,
            chat = d?.chat?.toList() ?: emptyList(),
            alive = agents.containsKey(sessionId),
            lastResult = d?.lastResult?.take(160) ?: ""
        )
    }

    fun viewerEnter(sessionId: String) { viewers.merge(sessionId, 1) { a, b -> a + b } }

    fun viewerExit(sessionId: String) {
        viewers.computeIfPresent(sessionId) { _, v -> if (v <= 1) null else v - 1 }
    }

    fun hasViewer(sessionId: String): Boolean = (viewers[sessionId] ?: 0) > 0

    // ------------------------------------------------------------------------------------ জীবিত রাখা
    fun keepAlive(ctx: Context, sessionId: String, on: Boolean) {
        val changed = if (on) keep.add(sessionId) else keep.remove(sessionId)
        if (changed) RunnerService.start(ctx.applicationContext)
    }

    fun keepAliveSessions(): List<String> = keep.toList()
}
