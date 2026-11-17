package com.hemel.lenspilot.tools.agent

import org.json.JSONArray
import org.json.JSONObject

/** প্ল্যান/ডিসাইডের একটা ধাপ (সার্ভারের স্যানিটাইজ-করা JSON থেকে)। args-এ {{vault:…}} প্লেসহোল্ডার থাকতে পারে, রিজলভ-করা মান কখনো নয়। */
class PlanStep(
    val id: String,
    val workspace: String,
    val action: String,
    val args: JSONObject,
    val detector: JSONObject?,
    val timeoutS: Int,
    val risk: String,
    val onFail: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("workspace", workspace).put("action", action).put("args", args)
        .put("detector", detector ?: JSONObject.NULL).put("timeout_s", timeoutS)
        .put("risk", risk).put("on_fail", onFail)

    /** সার্ভারে remaining_steps হিসেবে যায় — ছোট রূপ। */
    fun compactJson(): JSONObject = JSONObject()
        .put("id", id).put("workspace", workspace).put("action", action).put("args", args)

    /** লগ/স্ট্যাটাসের ছোট বর্ণনা, যেমন "shell.run git status"। */
    fun brief(): String {
        val a = firstArg()
        return (workspace + "." + action + (if (a.isNotEmpty()) " $a" else "")).take(90)
    }

    /** ঝুঁকি-মাপার লেখা (RiskClassifier-এর জন্য): কমান্ড/URL/পাথ ইত্যাদি। */
    fun riskText(): String {
        val keys = listOf("command", "url", "path", "file", "from", "to", "id", "text", "query", "name")
        val sb = StringBuilder()
        for (k in keys) {
            val v = args.optString(k, "")
            if (v.isNotEmpty()) sb.append(v).append(' ')
        }
        return sb.toString().trim().take(400)
    }

    private fun firstArg(): String {
        val keys = listOf("command", "url", "query", "path", "file", "id", "name", "question")
        for (k in keys) {
            val v = args.optString(k, "")
            if (v.isNotEmpty()) return v.replace('\n', ' ').take(60)
        }
        return ""
    }

    companion object {
        fun fromJson(o: JSONObject?): PlanStep? {
            if (o == null) return null
            val ws = o.optString("workspace", "")
            val act = o.optString("action", "")
            if (ws.isEmpty() || act.isEmpty()) return null
            val det = o.optJSONObject("detector")
            return PlanStep(
                id = o.optString("id", "").ifEmpty { "s" + System.currentTimeMillis().toString(36) }.take(24),
                workspace = ws,
                action = act,
                args = o.optJSONObject("args") ?: JSONObject(),
                detector = det,
                timeoutS = o.optInt("timeout_s", 120).coerceIn(1, 86_400),
                risk = o.optString("risk", "high"),
                onFail = o.optString("on_fail", "retry")
            )
        }

        fun listFrom(a: JSONArray?): List<PlanStep> {
            val out = ArrayList<PlanStep>()
            if (a == null) return out
            for (i in 0 until a.length()) PlanStep.fromJson(a.optJSONObject(i))?.let { out.add(it) }
            return out
        }
    }
}

/** /api/tools/plan-এর চূড়ান্ত ফল (UI-তে সারাংশ-কার্ড ও এজেন্টের শুরুর তালিকা)। */
class PlanResult(
    val steps: List<PlanStep>,
    val pipelineId: String,
    val pipelineName: String,
    val summary: String,
    val rationale: String,
    val timeText: String,
    val costText: String,
    val costUsd: Double,
    val warnings: List<String>
)

/** /api/tools/decide-এর পরিষ্কার উত্তর। */
class DecideResult(
    val note: String,
    val status: String,
    val say: String,
    val steps: List<PlanStep>,
    val waitUntilSec: Long?,
    val askQuestion: String?,
    val askOptions: List<String>,
    val editGoal: String?,
    val done: Boolean,
    val tokens: Int
)

/** এজেন্টের ধাপ (state-machine-এর ফেজ)। wire নাম agent_state.json-এ যায়। */
enum class Phase(val wire: String) {
    DECIDE("decide"),
    EXEC("exec"),
    AWAIT_DETECTOR("await_detector"),
    WAIT_TIME("wait_time"),
    ASK("ask"),
    NEEDS_YOU("needs_you"),
    DONE("done"),
    FAILED("failed"),
    STOPPED("stopped");

    val finished: Boolean get() = this == DONE || this == FAILED || this == STOPPED

    companion object {
        fun fromWire(s: String?): Phase = values().firstOrNull { it.wire == s } ?: DECIDE
    }
}

/** ইউজারের কাছে প্রশ্ন (গ্লাস-কার্ডে দেখায়)। */
class AgentQuestion(val text: String, val options: List<String>)

/** চ্যাটের এক লাইন (from: "elora" | "user")। */
class AgentChatLine(val from: String, val text: String, val ts: Long)

/** UI যা দেখে। প্রতিটি পরিবর্তনে নতুন অবজেক্ট (StateFlow ঠিকমতো জাগে)। */
data class AgentUi(
    val sessionId: String,
    val state: com.hemel.lenspilot.tools.SessionState = com.hemel.lenspilot.tools.SessionState.IDLE,
    /** browser | shell | storage | none */
    val workspace: String = "none",
    val stepLabel: String = "",
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val paused: Boolean = false,
    val note: String = "",
    val question: AgentQuestion? = null,
    val lastResult: String = "",
    val chat: List<AgentChatLine> = emptyList(),
    val alive: Boolean = false
)

/** ডিস্কে রাখা অবস্থা — প্রতি ধাপের পর সেভ; প্রসেস মরলে এখান থেকেই চলে। */
class AgentData(val sessionId: String) {
    var goal: String = ""
    var phase: Phase = Phase.DECIDE
    var queue: MutableList<PlanStep> = ArrayList()
    var planRemaining: MutableList<PlanStep> = ArrayList()
    var executedIds: MutableList<String> = ArrayList()
    var planTotal: Int = 0
    var stepsDone: Int = 0
    var retries: Int = 0
    var retriesTotal: Int = 0
    var stuckDecides: Int = 0
    var emptyDecides: Int = 0
    var decideCalls: Int = 0
    var lastErrSig: String = ""
    var failureHistory: MutableList<String> = ArrayList()
    var lastResult: String = ""
    var userNote: String = ""
    var forceDecide: Boolean = false
    var waitUntilMs: Long = 0L
    var waitReason: String = ""
    var awaitStepId: String = ""
    var awaitWatcherId: String = ""
    var awaitDeadlineMs: Long = 0L
    var awaitDetector: String = ""
    var waitSchedId: String = ""
    var askText: String = ""
    var askOptions: MutableList<String> = ArrayList()
    var askAfterFailure: Boolean = false
    var pipelineId: String = ""
    var pipelineName: String = ""
    var costUsd: Double = 0.0
    var executedSig: MutableList<String> = ArrayList()
    var startedAt: Long = System.currentTimeMillis()
    var paused: Boolean = false
    var failNote: String = ""
    var learnPending: Boolean = false
    var learnOutcome: String = ""
    var learnDone: Boolean = false
    var chat: MutableList<AgentChatLine> = ArrayList()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("goal", goal).put("phase", phase.wire)
        o.put("queue", JSONArray().also { a -> queue.forEach { a.put(it.toJson()) } })
        o.put("planRemaining", JSONArray().also { a -> planRemaining.forEach { a.put(it.toJson()) } })
        o.put("executedIds", JSONArray(executedIds))
        o.put("planTotal", planTotal).put("stepsDone", stepsDone).put("retries", retries)
        o.put("retriesTotal", retriesTotal).put("stuckDecides", stuckDecides).put("emptyDecides", emptyDecides)
        o.put("decideCalls", decideCalls).put("lastErrSig", lastErrSig)
        o.put("failureHistory", JSONArray(failureHistory))
        o.put("lastResult", lastResult).put("userNote", userNote).put("forceDecide", forceDecide)
        o.put("waitUntilMs", waitUntilMs).put("waitReason", waitReason)
        o.put("awaitStepId", awaitStepId).put("awaitWatcherId", awaitWatcherId).put("awaitDeadlineMs", awaitDeadlineMs)
        o.put("awaitDetector", awaitDetector).put("waitSchedId", waitSchedId)
        o.put("askText", askText).put("askOptions", JSONArray(askOptions)).put("askAfterFailure", askAfterFailure)
        o.put("pipelineId", pipelineId).put("pipelineName", pipelineName).put("costUsd", costUsd)
        o.put("executedSig", JSONArray(executedSig))
        o.put("startedAt", startedAt).put("paused", paused).put("failNote", failNote)
        o.put("learnPending", learnPending).put("learnOutcome", learnOutcome).put("learnDone", learnDone)
        o.put("chat", JSONArray().also { a ->
            chat.takeLast(40).forEach { a.put(JSONObject().put("f", it.from).put("t", it.text).put("ts", it.ts)) }
        })
        return o
    }

    companion object {
        private fun strList(a: JSONArray?): MutableList<String> {
            val out = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) out.add(a.optString(i, ""))
            return out
        }

        fun fromJson(sessionId: String, o: JSONObject): AgentData {
            val d = AgentData(sessionId)
            d.goal = o.optString("goal", "")
            d.phase = Phase.fromWire(o.optString("phase", "decide"))
            d.queue = ArrayList(PlanStep.listFrom(o.optJSONArray("queue")))
            d.planRemaining = ArrayList(PlanStep.listFrom(o.optJSONArray("planRemaining")))
            d.executedIds = strList(o.optJSONArray("executedIds"))
            d.planTotal = o.optInt("planTotal", 0)
            d.stepsDone = o.optInt("stepsDone", 0)
            d.retries = o.optInt("retries", 0)
            d.retriesTotal = o.optInt("retriesTotal", 0)
            d.stuckDecides = o.optInt("stuckDecides", 0)
            d.emptyDecides = o.optInt("emptyDecides", 0)
            d.decideCalls = o.optInt("decideCalls", 0)
            d.lastErrSig = o.optString("lastErrSig", "")
            d.failureHistory = strList(o.optJSONArray("failureHistory"))
            d.lastResult = o.optString("lastResult", "")
            d.userNote = o.optString("userNote", "")
            d.forceDecide = o.optBoolean("forceDecide", false)
            d.waitUntilMs = o.optLong("waitUntilMs", 0L)
            d.waitReason = o.optString("waitReason", "")
            d.awaitStepId = o.optString("awaitStepId", "")
            d.awaitWatcherId = o.optString("awaitWatcherId", "")
            d.awaitDeadlineMs = o.optLong("awaitDeadlineMs", 0L)
            d.awaitDetector = o.optString("awaitDetector", "")
            d.waitSchedId = o.optString("waitSchedId", "")
            d.askText = o.optString("askText", "")
            d.askOptions = strList(o.optJSONArray("askOptions"))
            d.askAfterFailure = o.optBoolean("askAfterFailure", false)
            d.pipelineId = o.optString("pipelineId", "")
            d.pipelineName = o.optString("pipelineName", "")
            d.costUsd = o.optDouble("costUsd", 0.0)
            d.executedSig = strList(o.optJSONArray("executedSig"))
            d.startedAt = o.optLong("startedAt", System.currentTimeMillis())
            d.paused = o.optBoolean("paused", false)
            d.failNote = o.optString("failNote", "")
            d.learnPending = o.optBoolean("learnPending", false)
            d.learnOutcome = o.optString("learnOutcome", "")
            d.learnDone = o.optBoolean("learnDone", false)
            val c = o.optJSONArray("chat")
            if (c != null) for (i in 0 until c.length()) {
                val x = c.optJSONObject(i) ?: continue
                d.chat.add(AgentChatLine(x.optString("f", "elora"), x.optString("t", ""), x.optLong("ts", 0L)))
            }
            return d
        }
    }
}
