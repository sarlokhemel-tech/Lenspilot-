package com.hemel.lenspilot.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * One clickable/typeable element pruned from the current page's DOM by
 * lp_dom_pruner.js. `id` matches the `data-lp-id` attribute the pruner
 * stamped onto the real DOM node, so a later click/type action can find
 * the exact same element again without ever touching coordinates or HTML.
 */
data class PrunedElement(
    val id: Int,
    val tag: String,
    val type: String,
    val placeholder: String,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tag", tag)
        put("type", type)
        put("placeholder", placeholder)
        put("text", text)
    }

    companion object {
        /** Parses the raw JSON-array string returned by evaluating
         * lp_dom_pruner.js in the WebView (evaluateJavascript wraps the
         * JS return value in an extra pair of quotes + escaping, which
         * the caller must strip before passing the array text here). */
        fun listFromJsonArray(json: String): List<PrunedElement> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PrunedElement(
                    id = o.optInt("id", i),
                    tag = o.optString("tag", ""),
                    type = o.optString("type", ""),
                    placeholder = o.optString("placeholder", ""),
                    text = o.optString("text", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * One atomic next step returned by POST /api/browser-action. Mirrors the
 * style of the existing highlight/action_type schema used for on-screen
 * guidance, but scoped to what a WebView (+ a small native-app allow-list)
 * can actually do:
 *
 *   navigate | search | click | type | scroll | wait | go_back |
 *   open_native_app | edit_goal | task_complete | ask_user
 */
data class BrowserActionResult(
    val action: String,
    val url: String?,
    val query: String?,
    val elementId: Int?,
    val textToType: String?,
    val submitAfterType: Boolean,
    val appTarget: String?,
    val newGoal: String?,
    val messageToUser: String,
    val taskComplete: Boolean,
    val ragMatchId: String?,
    // true = server found a captcha / human-verification challenge and paused
    // for the person (never solved by the AI) — see AiBrowserActivity.pauseForCaptcha.
    val captcha: Boolean = false,
    // short note when ELA 4N's supervisor AI caught + fixed a mistake for this
    // step (e.g. "🧐 তদারকি AI ভুল ধরে ঠিক করে দিয়েছে") — shown in the status line.
    val supervisorNote: String? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): BrowserActionResult {
            val ragMatch = obj.optJSONObject("rag_match")
            return BrowserActionResult(
                action = obj.optString("action", "ask_user"),
                url = obj.optString("url", "").ifBlank { null },
                query = obj.optString("query", "").ifBlank { null },
                elementId = if (obj.has("element_id") && !obj.isNull("element_id")) obj.optInt("element_id") else null,
                textToType = obj.optString("text_to_type", "").ifBlank { null },
                submitAfterType = obj.optBoolean("submit_after_type", false),
                appTarget = obj.optString("app_target", "").ifBlank { null },
                newGoal = obj.optString("new_goal", "").ifBlank { null },
                messageToUser = obj.optString("message_to_user", ""),
                taskComplete = obj.optBoolean("task_complete", false),
                ragMatchId = ragMatch?.optString("id", "")?.ifBlank { null },
                captcha = obj.optBoolean("captcha", false),
                supervisorNote = obj.optString("supervisor_note", "").ifBlank { null }
            )
        }
    }
}

/** Builds the JSON body POSTed to /api/browser-action. */
object BrowserActionRequest {
    fun build(
        goal: String,
        stepNumber: Int,
        history: List<String>,
        currentUrl: String,
        pageTitle: String,
        elements: List<PrunedElement>,
        browsingRagNoteId: String? = null,
        goalChanged: Boolean = false,
        // Optional photo the user attached from the goal row (an answer,
        // a mid-run nudge, or part of a brand-new goal) — see
        // AiBrowserActivity.pendingStepImageBase64. Sent only on the one
        // step it was attached for.
        imageBase64: String? = null,
        // Context to read Prefs.userInfoJsonArrayOrNull from — optional
        // only so this object can still be unit-tested without a real
        // Context; every real caller (AiBrowserActivity) passes itself.
        context: android.content.Context? = null,
        // Which chat-box system launched this run ("ela_4n" turns on the
        // supervisor AI server-side; anything else behaves exactly as before).
        system: String? = null,
        // On-device captcha probe result for this step (see lp_captcha_probe.js).
        captchaDetected: Boolean = false,
        // Set for a few steps after the person manually said "চালিয়ে যাও" at a
        // captcha pause, so a false alarm can't trap the task in a pause loop.
        captchaIgnore: Boolean = false
    ): String {
        val body = JSONObject()
        body.put("goal", goal)
        body.put("step_number", stepNumber)
        body.put("history", JSONArray(history))
        body.put("current_url", currentUrl)
        body.put("page_title", pageTitle)
        val elArr = JSONArray()
        elements.forEach { elArr.put(it.toJson()) }
        body.put("elements", elArr)
        if (!browsingRagNoteId.isNullOrBlank()) body.put("browsing_rag_note_id", browsingRagNoteId)
        body.put("goal_changed", goalChanged)
        if (!imageBase64.isNullOrBlank()) body.put("image_base64", imageBase64)
        if (!system.isNullOrBlank()) body.put("system", system)
        if (captchaDetected) body.put("captcha_detected", true)
        if (captchaIgnore) body.put("captcha_ignore", true)
        if (context != null) {
            com.hemel.lenspilot.Prefs.userInfoJsonArrayOrNull(context)?.let { body.put("user_info", it) }
        }
        return body.toString()
    }
}

/**
 * Small, deliberately hardcoded map from the server's whitelisted
 * `app_target` key to what the client actually needs to try opening it —
 * see BROWSER_AUTOMATION_INSTRUCTIONS / _NATIVE_APP_TARGETS server-side.
 * The model only ever sends a short key (e.g. "facebook"); it never
 * supplies a package name or deep-link URI itself, so there is no way a
 * bad model response turns into an arbitrary app launch.
 */
data class NativeAppTarget(
    val packageName: String,
    val deepLink: String,
    val playStoreUrl: String,
    val webFallbackUrl: String
)

object NativeAppTargets {
    private val targets = mapOf(
        "facebook" to NativeAppTarget(
            packageName = "com.facebook.katana",
            deepLink = "fb://facewebmodal/f?href=https://www.facebook.com/",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.facebook.katana",
            webFallbackUrl = "https://www.facebook.com/"
        ),
        "messenger" to NativeAppTarget(
            packageName = "com.facebook.orca",
            deepLink = "fb-messenger://",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.facebook.orca",
            webFallbackUrl = "https://www.messenger.com/"
        ),
        "whatsapp" to NativeAppTarget(
            packageName = "com.whatsapp",
            deepLink = "whatsapp://",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.whatsapp",
            webFallbackUrl = "https://web.whatsapp.com/"
        ),
        "instagram" to NativeAppTarget(
            packageName = "com.instagram.android",
            deepLink = "instagram://app",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.instagram.android",
            webFallbackUrl = "https://www.instagram.com/"
        ),
        "youtube" to NativeAppTarget(
            packageName = "com.google.android.youtube",
            deepLink = "vnd.youtube://",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.google.android.youtube",
            webFallbackUrl = "https://www.youtube.com/"
        )
    )

    fun forKey(key: String?): NativeAppTarget? = targets[key?.trim()?.lowercase()]
}
