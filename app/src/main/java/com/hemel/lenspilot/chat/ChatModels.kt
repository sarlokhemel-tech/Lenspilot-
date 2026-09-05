package com.hemel.lenspilot.chat

import org.json.JSONArray
import org.json.JSONObject

/** One bubble in the chat list. [workflow] is non-null only for the
 * special "workflow card" item type (AI decided the message was an
 * actionable task rather than a plain question). */
data class ChatMessage(
    val role: String,               // "user" | "ai"
    var text: String,
    val workflow: WorkflowPreview? = null,
    // Base64 JPEG of a screenshot the user attached via the "+" button
    // (Gemini-style "give a screenshot of the problem") — user bubbles
    // only. Not re-sent to the server on history reload, just shown.
    val imageBase64: String? = null
) {
    fun toJson(includeImage: Boolean = true): JSONObject = JSONObject().apply {
        put("role", role)
        put("text", text)
        if (workflow != null) put("workflow", workflow.toJson())
        if (includeImage && imageBase64 != null) put("image_base64", imageBase64)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
            role = obj.optString("role", "ai"),
            text = obj.optString("text", ""),
            workflow = obj.optJSONObject("workflow")?.let { WorkflowPreview.fromJson(it) },
            imageBase64 = obj.optString("image_base64", "").ifBlank { null }
        )
    }
}

/** One AI-planned step: `goal` is sent verbatim as `user_goal` to
 * /api/analyze-screen while this step is active. */
data class WorkflowStepPlan(val stepNumber: Int, val goal: String)

/**
 * What /api/workflow/plan returns for an actionable request — matches the
 * Space's `workflow: {title, steps:[{step_number, goal}]}` shape exactly.
 * NOTE: `steps` is a loose outline/itinerary now, not a rigid script the
 * run loop counts down through — LenspilotAccessibilityService.
 * startWorkflow() sends `title` as the overall goal and `steps` as context,
 * then keeps re-checking the actual screen (bubble tap / genuine screen
 * change) until the model itself reports the goal is done
 * (task_complete=true), regardless of how many steps were originally
 * guessed.
 */
data class WorkflowPreview(
    val title: String,
    val steps: List<WorkflowStepPlan>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply { put("step_number", s.stepNumber); put("goal", s.goal) })
        }
        put("steps", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorkflowPreview {
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).map {
                val s = stepsArr.getJSONObject(it)
                WorkflowStepPlan(s.optInt("step_number", it + 1), s.optString("goal", ""))
            }
            return WorkflowPreview(title = obj.optString("title", ""), steps = steps)
        }

        /** Parses the exact `workflow` object shape /api/workflow/plan
         * returns: {"title": "...", "steps": [{"step_number":1,"goal":"..."}]} */
        fun fromPlanResult(obj: JSONObject): WorkflowPreview = fromJson(obj)
    }
}
