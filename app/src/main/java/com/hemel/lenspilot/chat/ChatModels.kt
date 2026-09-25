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
    val autoRun: Boolean = false,
    // ELA 4N only ("Perplexity-style" reply — see MainActivity's
    // applyWorkflowPlanResult and app.py's ELA4N_CHAT_PROMPT): when true,
    // ChatAdapter renders [text]'s light markdown (**bold**, "- " bullets,
    // bare-line headings) instead of showing it as flat text. Every other
    // system/bubble leaves this false, same plain rendering as before this
    // field existed.
    val richFormatted: Boolean = false,
    // ELA 4N only: short "which site" labels (see app.py's
    // _short_site_label / _workflow_plan_ela4n's result["sources"]),
    // rendered as a small chip row under the reply. Null/empty for every
    // other system — same as before this field existed.
    val sources: List<String>? = null
) {
    fun toJson(includeImage: Boolean = true): JSONObject = JSONObject().apply {
        put("role", role)
        put("text", text)
        if (workflow != null) put("workflow", workflow.toJson())
        if (lesson != null) put("lesson", lesson.toJson())
        if (includeImage && imageBase64 != null) put("image_base64", imageBase64)
        if (richFormatted) put("rich_formatted", true)
        if (!sources.isNullOrEmpty()) put("sources", JSONArray(sources))
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage {
            val sourcesArr = obj.optJSONArray("sources")
            val sourcesList = if (sourcesArr != null) {
                (0 until sourcesArr.length()).map { sourcesArr.optString(it, "") }.filter { it.isNotBlank() }
            } else null
            return ChatMessage(
                role = obj.optString("role", "ai"),
                text = obj.optString("text", ""),
                workflow = obj.optJSONObject("workflow")?.let { WorkflowPreview.fromJson(it) },
                lesson = obj.optJSONObject("lesson")?.let { LessonPreview.fromJson(it) },
                imageBase64 = obj.optString("image_base64", "").ifBlank { null },
                richFormatted = obj.optBoolean("rich_formatted", false),
                sources = sourcesList?.ifEmpty { null }
            )
        }
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
 * /api/analyze-screen while this step is active.
 *
 * screen/keys/type are only populated when the parent WorkflowPreview.system
 * is "super_lite" (see SUPER_LITE_PLANNER_INSTRUCTIONS in app.py) — the
 * default "super_1_2" system leaves steps empty entirely, same as before
 * these fields existed. type is one of SYSTEM_INTENT|DEEP_LINK|CLICK|
 * INPUT|SCROLL|EXPLAIN — SYSTEM_INTENT/DEEP_LINK steps are handled
 * entirely on-device (fire an Android Intent directly, no backend call);
 * everything else calls /api/analyze-screen with `keys`+`guide` so the
 * server can try a local match before its small executor model. */
data class WorkflowStepPlan(
    val stepNumber: Int,
    val goal: String,
    val screen: String = "",
    val keys: List<String> = emptyList(),
    val type: String = "CLICK"
)

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
 *
 * That "fresh brain every screen" description is specifically the
 * "super_1_2" system (chat box's system-switcher, 2nd option). The other
 * option, "super_lite", is architecturally different: steps IS a real,
 * ordered script here (from the big planner model), and the client walks
 * through it directly instead of re-deriving the whole task from scratch
 * every hop — see WorkflowStepPlan's doc above.
 */
data class WorkflowPreview(
    val title: String,
    val steps: List<WorkflowStepPlan>,
    // Short literal on-screen text for the final destination element
    // (e.g. "Name", "নাম", "Notifications"), guessed ONCE by the planner —
    // see app.py's WORKFLOW_PLAN_INSTRUCTIONS target_label_rule. Sent on
    // every /api/analyze-screen call for this workflow so the server can
    // try a free local string-match before spending an LLM call on a hop
    // where the destination label is already known. Null is normal/safe —
    // the server just falls back to full model reasoning for every hop,
    // exactly like before this field existed.
    val targetLabel: String? = null,
    // "super_1_2" (default) | "super_lite" — which chat-box system
    // produced this workflow; see the class doc above.
    val system: String = "super_1_2"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("step_number", s.stepNumber)
                put("goal", s.goal)
                if (s.screen.isNotBlank()) put("screen", s.screen)
                if (s.keys.isNotEmpty()) put("keys", JSONArray(s.keys))
                put("type", s.type)
            })
        }
        put("steps", arr)
        if (targetLabel != null) put("target_label", targetLabel)
        put("system", system)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorkflowPreview {
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).map {
                val s = stepsArr.getJSONObject(it)
                val keysArr = s.optJSONArray("keys")
                val keys = if (keysArr != null) (0 until keysArr.length()).map { i -> keysArr.optString(i, "") }.filter { k -> k.isNotBlank() } else emptyList()
                WorkflowStepPlan(
                    stepNumber = s.optInt("step_number", it + 1),
                    goal = s.optString("goal", ""),
                    screen = s.optString("screen", ""),
                    keys = keys,
                    type = s.optString("type", "CLICK").ifBlank { "CLICK" }
                )
            }
            return WorkflowPreview(
                title = obj.optString("title", ""),
                steps = steps,
                targetLabel = obj.optString("target_label", "").ifBlank { null },
                system = obj.optString("system", "super_1_2").ifBlank { "super_1_2" }
            )
        }

        /** Parses the exact `workflow` object shape /api/workflow/plan
         * returns: {"title": "...", "steps": [{"step_number":1,"goal":"..."}]} */
        fun fromPlanResult(obj: JSONObject): WorkflowPreview = fromJson(obj)
    }
}
