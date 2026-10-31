package com.hemel.lenspilot.tools.agent

import android.content.Context
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.ApiException
import org.json.JSONArray
import org.json.JSONObject

/**
 * /api/tools/plan (SSE), /decide, /learn — সব কল [ApiClient]-এর ভেতর দিয়ে, তাই বেরোনোর আগে Redactor-এর OkHttp interceptor
 * প্রতিটি বডিতে ভল্টের মান মাস্ক করে। এই ফাইলে নেটওয়ার্ক-কোড আর নেই।
 */
object ToolsApi {

    private fun base(ctx: Context): String = ctx.applicationContext.getString(R.string.space_base_url)

    /** ব্যর্থতার সরল বাংলা বার্তা (HTTP কোড/সার্ভারের error থেকে)। */
    fun friendlyError(ctx: Context, t: Throwable): String {
        val e = t as? ApiException
        if (e != null) {
            val serverMsg = try { JSONObject(e.body).optString("error", "") } catch (x: Exception) { "" }
            return when (e.code) {
                402 -> ctx.getString(R.string.tools_err_tokens)
                429 -> ctx.getString(R.string.tools_err_rate)
                401 -> ctx.getString(R.string.tools_err_login)
                else -> ctx.getString(R.string.tools_err_server, e.code) + (if (serverMsg.isNotEmpty()) ": " + serverMsg.take(120) else "")
            }
        }
        return ctx.getString(R.string.tools_err_network) + (t.message?.let { ": " + it.take(100) } ?: "")
    }

    /**
     * পরিকল্পনা। [onStage] স্টেজ-ইভেন্টে ডাকা হয় (stage, status) — কলব্যাক ব্যাকগ্রাউন্ড থ্রেডে আসে।
     * ফলাফল না এলে Result.failure।
     */
    suspend fun plan(
        ctx: Context,
        goal: String,
        sessionId: String,
        deviceInfo: JSONObject,
        onStage: (String, String) -> Unit
    ): Result<PlanResult> {
        val body = JSONObject().put("goal", goal).put("device_info", deviceInfo).put("session_id", sessionId)
        var result: PlanResult? = null
        var errorText: String? = null
        val r = ApiClient.streamAuthed(ctx, base(ctx), "/api/tools/plan", body.toString()) { ev ->
            when (ev.optString("type")) {
                "stage" -> onStage(ev.optString("stage"), ev.optString("status"))
                "error" -> errorText = ev.optString("error", "")
                "done" -> result = parsePlan(ev.optJSONObject("result"))
            }
        }
        val fail = r.exceptionOrNull()
        if (fail != null) return Result.failure(fail)
        val res = result
        if (res != null) return Result.success(res)
        return Result.failure(Exception(errorText?.takeIf { it.isNotEmpty() } ?: ctx.getString(R.string.tools_err_no_plan)))
    }

    private fun parsePlan(o: JSONObject?): PlanResult? {
        if (o == null) return null
        val plan = o.optJSONObject("plan") ?: return null
        val steps = PlanStep.listFrom(plan.optJSONArray("steps"))
        if (steps.isEmpty()) return null
        val pipe = plan.optJSONObject("pipeline") ?: JSONObject()
        val est = o.optJSONObject("estimate") ?: JSONObject()
        val warn = ArrayList<String>()
        val wa: JSONArray? = o.optJSONArray("warnings")
        if (wa != null) for (i in 0 until wa.length()) warn.add(wa.optString(i, ""))
        return PlanResult(
            steps = steps,
            pipelineId = pipe.optString("id", ""),
            pipelineName = pipe.optString("name", ""),
            summary = pipe.optString("summary", ""),
            rationale = o.optString("rationale", ""),
            timeText = est.optString("time", ""),
            costText = est.optString("cost", ""),
            costUsd = est.optDouble("cost_usd", 0.0),
            warnings = warn
        )
    }

    /** পরবর্তী ধাপ ঠিক করা। [req] = AgentDecideRequest (এজেন্ট বানায়)। */
    suspend fun decide(ctx: Context, req: JSONObject): Result<DecideResult> {
        val r = ApiClient.callAuthed(ctx, base(ctx), "/api/tools/decide", req.toString())
        val fail = r.exceptionOrNull()
        if (fail != null) return Result.failure(fail)
        return try {
            val o = JSONObject(r.getOrNull().orEmpty())
            val au = o.optJSONObject("ask_user")
            val opts = ArrayList<String>()
            val oa = au?.optJSONArray("options")
            if (oa != null) for (i in 0 until oa.length()) opts.add(oa.optString(i, ""))
            Result.success(
                DecideResult(
                    note = o.optString("note", ""),
                    status = o.optString("status", "continue"),
                    say = o.optString("say", ""),
                    steps = PlanStep.listFrom(o.optJSONArray("steps")),
                    waitUntilSec = if (o.isNull("wait_until")) null else o.optLong("wait_until").takeIf { it > 0L },
                    askQuestion = au?.optString("question", "")?.takeIf { it.isNotEmpty() },
                    askOptions = opts.filter { it.isNotBlank() },
                    editGoal = if (o.isNull("edit_goal")) null else o.optString("edit_goal", "").takeIf { it.isNotEmpty() },
                    done = o.optBoolean("done", false),
                    tokens = o.optInt("tokens", 0)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** শেষে একবার ফলাফল জানানো। সফল হলে true। */
    suspend fun learn(ctx: Context, body: JSONObject): Boolean =
        ApiClient.callAuthed(ctx, base(ctx), "/api/tools/learn", body.toString()).isSuccess
}
