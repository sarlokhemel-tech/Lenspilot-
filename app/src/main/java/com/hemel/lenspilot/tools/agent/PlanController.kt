package com.hemel.lenspilot.tools.agent

import android.content.Context
import android.os.Build
import com.hemel.lenspilot.tools.ToolsAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** স্বয়ংক্রিয়-শুরু সেটিং (পরিকল্পনা তৈরির পর Start না চেপেই চালু)। ডিফল্ট বন্ধ। */
object ToolsAgentPrefs {
    private const val PREFS = "lenspilot_tools_agent"
    private const val K_AUTO = "auto_start"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoStart(c: Context): Boolean = p(c).getBoolean(K_AUTO, false)
    fun setAutoStart(c: Context, on: Boolean) { p(c).edit().putBoolean(K_AUTO, on).apply() }
}

/**
 * হোম-চ্যাটের প্রথম বার্তা → /api/tools/plan। স্টেজ-ইভেন্ট "ভাবছে…" স্ট্যাটাস হিসেবে UI-তে যায়;
 * ফলাফল [pending]-এ থাকে (Start চাপা বা auto-start হলে [ToolsAgents.start])।
 */
object PlanController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PlanResult>()

    fun pendingPlan(sessionId: String): PlanResult? = pending[sessionId]

    fun clear(sessionId: String) { pending.remove(sessionId) }

    /** onStage/onResult main থ্রেডে আসে। */
    fun plan(
        ctx: Context,
        sessionId: String,
        goal: String,
        onStage: (stage: String, status: String) -> Unit,
        onResult: (Result<PlanResult>) -> Unit
    ) {
        val app = ctx.applicationContext
        scope.launch {
            val device = JSONObject()
                .put("platform", "android").put("sdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL ?: "").put("locale", java.util.Locale.getDefault().toLanguageTag())
            val r = try {
                ToolsApi.plan(app, goal, sessionId, device) { st, status -> ToolsAsync.ui { onStage(st, status) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // সেশন ৮: বাতিল গিলে ফেলা নয়
            } catch (e: Exception) {
                Result.failure(e)
            }
            r.getOrNull()?.let { pending[sessionId] = it }
            ToolsAsync.ui { onResult(r) }
        }
    }
}
