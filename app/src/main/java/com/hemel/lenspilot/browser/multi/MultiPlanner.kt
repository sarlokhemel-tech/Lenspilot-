package com.hemel.lenspilot.browser.multi

import android.content.Context
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.security.SecretVault
import org.json.JSONObject

/** v162: asks the server (Gemini 3.5 Flash) how many sessions a big task needs and what each one does. */
object MultiPlanner {

    class Plan(val sessions: List<SessionSpec>, val why: String, val error: String?)

    suspend fun plan(ctx: Context, goal: String, system: String = "ela_4n"): Plan {
        val body = JSONObject().apply {
            put("goal", goal)
            put("system", system)
            put("max_sessions", SessionManager.MAX_SESSIONS)
            Prefs.userInfoJsonArrayOrNull(ctx)?.let { put("user_info", it) }
            SecretVault.promptIndex(ctx, goal, "", "").let { if (it.length() > 0) put("vault", it) }
        }
        val r = ApiClient.callAuthed(ctx, ctx.getString(R.string.space_base_url), "/api/browser-multi-plan", body.toString())
        val s = r.getOrNull()
        if (s == null) {
            val msg = r.exceptionOrNull()?.message.orEmpty()
            return single(goal, if (msg.contains("TOKEN_LIMIT")) "Tokens are used up. Watch an ad to get more tokens." else "Could not plan the sessions — running as one session.")
        }
        return try {
            val o = JSONObject(s)
            val arr = o.optJSONArray("sessions")
            val out = ArrayList<SessionSpec>()
            if (arr != null) for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val task = it.optString("task").trim()
                if (task.isEmpty()) continue
                val url = it.optString("start_url").takeIf { u -> u.startsWith("http://") || u.startsWith("https://") }
                out.add(SessionSpec(it.optString("title").ifBlank { "Session ${out.size + 1}" }, task, url))
            }
            if (out.isEmpty()) single(goal, "") else Plan(out.take(SessionManager.MAX_SESSIONS), o.optString("why"), null)
        } catch (e: Exception) {
            single(goal, "Could not read the plan — running as one session.")
        }
    }

    private fun single(goal: String, error: String) =
        Plan(listOf(SessionSpec(goal.take(24).ifBlank { "Session 1" }, goal)), "", error.ifBlank { null })
}
