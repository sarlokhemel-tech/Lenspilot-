package com.hemel.lenspilot.chat

import org.json.JSONArray
import org.json.JSONObject

/** One bubble in the chat list. [workflow] is non-null only for the
 * special "workflow card" item type (AI decided the message was an
 * actionable task rather than a plain question). [lesson] is non-null
 * only for the "class card" item type (AI Learning Mode was toggled on
 * when this message was sent) — mirrors [workflow] exactly, just for a
 * different follow-up action (Run opens LearningModeActivity instead of
 * running a workflow). */
data class ChatMessage(
    val role: String,               // "user" | "ai"
    var text: String,
    val workflow: WorkflowPreview? = null,
    val lesson: LessonPreview? = null,
    // Base64 JPEG of a screenshot the user attached via the "+" button
    // (Gemini-style "give a screenshot of the problem") — user bubbles
    // only. Not re-sent to the server on history reload, just shown.
    val imageBase64: String? = null,
    // True only for a workflow card created from the voice-first ball /
    // Quick Settings tile shortcut (sendMessage(..., autoRun = true)) —
    // that path calls runWorkflow() itself the instant the card is
    // created, so the card must NOT show an idle, tappable Run button:
    // that looked exactly like "auto-run did nothing" and a second tap
    // on it could start the same workflow a second time.
    val autoRun: Boolean = false
) {
    fun toJson(includeImage: Boolean = true): JSONObject = JSONObject().apply {
        put("role", role)
        put("text", text)
        if (workflow != null) put("workflow", workflow.toJson())
        if (lesson != null) put("lesson", lesson.toJson())
        if (includeImage && imageBase64 != null) put("image_base64", imageBase64)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
            role = obj.optString("role", "ai"),
            text = obj.optString("text", ""),
            workflow = obj.optJSONObject("workflow")?.let { WorkflowPreview.fromJson(it) },
            lesson = obj.optJSONObject("lesson")?.let { LessonPreview.fromJson(it) },
            imageBase64 = obj.optString("image_base64", "").ifBlank { null }
        )
    }
}

/**
 * A "class" card — created ENTIRELY on-device the instant the user sends
 * a message while AI Learning Mode is toggled on (see MainActivity's
 * learningModeEnabled / toggleLearningMode). Deliberately just the raw
 * topic text with no server round-trip at creation time (the app owner
 * wants this fast — the actual research/compose/TTS/diagram work only
 * starts once "Run" is tapped and LearningModeActivity opens and calls
 * /api/learning/lesson). No steps/outline are shown on the card itself
 * — that's revealed inside the full-screen lesson, behind its own
 * "outline" toggle, once segments start arriving.
 */
data class LessonPreview(val topic: String) {
    fun toJson(): JSONObject = JSONObject().put("topic", topic)
    companion object {
        fun fromJson(obj: JSONObject): LessonPreview = LessonPreview(topic = obj.optString("topic", ""))
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
