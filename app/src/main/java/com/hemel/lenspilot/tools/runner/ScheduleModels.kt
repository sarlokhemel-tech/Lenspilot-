package com.hemel.lenspilot.tools.runner

import com.hemel.lenspilot.security.VaultRefs
import org.json.JSONObject

class ScheduleSpecException(message: String) : Exception(message)

/**
 * নির্ধারিত সময়ে কী হবে। সময় (timestamp) AI দেয়; ডিভাইস শুধু রাখে ও ঠিক সময়ে জাগায়।
 *  * resume_step — সেশনের ধাপ আবার চালু;
 *  * type_text — লক্ষ্য ওয়ার্কস্পেসে লেখা টাইপ (literal অথবা {{vault:…}} রেফারেন্স; রেফারেন্স ফায়ারের সময় শুধু টাইপ করার মুহূর্তে রিজলভ হয়);
 *  * notify — শুধু নোটিফিকেশন।
 */
sealed class ScheduleAction(val wire: String) {
    class ResumeStep(val stepId: String?) : ScheduleAction("resume_step")
    class TypeText(val targetWorkspace: String, val textRef: String?, val literal: String?) : ScheduleAction("type_text")
    object Notify : ScheduleAction("notify")
}

class ScheduledResume(
    val id: String,
    /** null = গ্লোবাল। */
    val sessionId: String?,
    val atMillis: Long,
    val action: ScheduleAction,
    val note: String
) {
    fun toJson(): JSONObject {
        val a = JSONObject().put("type", action.wire)
        when (action) {
            is ScheduleAction.ResumeStep -> a.put("step_id", action.stepId ?: "")
            is ScheduleAction.TypeText -> {
                a.put("target_workspace", action.targetWorkspace)
                a.put("text_ref", action.textRef ?: "")
                a.put("literal", action.literal ?: "")
            }
            ScheduleAction.Notify -> { }
        }
        return JSONObject().put("id", id).put("sessionId", sessionId ?: JSONObject.NULL)
            .put("at", atMillis).put("action", a).put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduledResume? {
            val id = o.optString("id")
            val a = o.optJSONObject("action") ?: return null
            if (id.isEmpty()) return null
            val action: ScheduleAction = when (a.optString("type")) {
                "resume_step" -> ScheduleAction.ResumeStep(a.optString("step_id").ifEmpty { null })
                "type_text" -> ScheduleAction.TypeText(
                    a.optString("target_workspace"),
                    a.optString("text_ref").ifEmpty { null },
                    a.optString("literal").ifEmpty { null }
                )
                "notify" -> ScheduleAction.Notify
                else -> return null
            }
            val sid = if (o.isNull("sessionId")) null else o.optString("sessionId").ifEmpty { null }
            return ScheduledResume(id, sid, o.optLong("at"), action, o.optString("note"))
        }
    }
}

/** AI-র দেওয়া JSON-এর ভ্যালিডেটর (অজানা ফিল্ড উপেক্ষিত, আকার সীমিত)। */
object ScheduleParser {
    private val WORKSPACES = setOf("browser", "shell", "storage")

    fun parseAction(a: JSONObject): ScheduleAction = when (a.optString("type")) {
        "resume_step" -> ScheduleAction.ResumeStep(a.optString("step_id").trim().take(64).ifEmpty { null })
        "type_text" -> {
            val ws = a.optString("target_workspace").trim()
            if (ws !in WORKSPACES) throw ScheduleSpecException("target_workspace হতে হবে browser|shell|storage")
            val ref = a.optString("text_ref").trim().take(200).ifEmpty { null }
            val lit = a.optString("literal").take(1000).ifEmpty { null }
            if ((ref == null) == (lit == null)) throw ScheduleSpecException("text_ref অথবা literal — ঠিক একটা দিন")
            if (ref != null && !VaultRefs.hasRef(ref)) throw ScheduleSpecException("text_ref হতে হবে {{vault:Title.field}}")
            ScheduleAction.TypeText(ws, ref, lit)
        }
        "notify" -> ScheduleAction.Notify
        else -> throw ScheduleSpecException("অজানা action type")
    }
}
