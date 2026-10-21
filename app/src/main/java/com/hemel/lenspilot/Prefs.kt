package com.hemel.lenspilot

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for the two new Settings toggles:
 *   - autoSpeak: guidance is spoken (LocalTts) the instant it's ready,
 *     no need to tap the speaker button first.
 *   - voiceFirstLaunch: opening the app shows the big voice-command ball
 *     instead of the chat screen, until the × is tapped (session-only
 *     override — the setting itself has to be turned off in Settings to
 *     change the default permanently).
 */
object Prefs {
    private const val FILE = "lenspilot_prefs"
    private const val KEY_AUTO_SPEAK = "auto_speak_guidance"
    private const val KEY_VOICE_FIRST = "voice_first_launch"
    private const val KEY_KEYBOARD_MODE_INTRO_SEEN = "keyboard_mode_intro_seen"
    private const val KEY_LAST_AI_REPLY = "keyboard_last_ai_reply"
    private const val KEY_KEYBOARD_AUTO_TYPE = "keyboard_auto_type_enabled"
    private const val KEY_KEYBOARD_SIZE_SCALE = "keyboard_size_scale"
    private const val KEY_KEYBOARD_BANGLA_MODE = "keyboard_bangla_mode"
    private const val KEY_TRANSLATE_ENABLED = "translate_enabled"
    private const val KEY_TRANSLATE_TARGET_LANG = "translate_target_lang"
    // v24: নতুন key — আগের "screen_guide_mode" (ডিফল্ট accessibility) আর পড়া হয় না, তাই সবাই নতুন
    // ডিফল্ট "vision" পায়; ইউজার চাইলে সেটিংস থেকে পুরনো মোডে ফিরতে পারে।
    private const val KEY_SCREEN_GUIDE_MODE = "screen_guide_mode_v2"
    private const val KEY_VISION_COLOR = "vision_color_high_quality"
    private const val KEY_VISION_RES = "vision_resolution_level"
    private const val KEY_VISION_AUTO_BOOST = "vision_auto_boost"
    private const val KEY_SELECTED_SYSTEM = "selected_system_mode"

    /** "accessibility" (default) = use the accessibility-tree tier whenever
     * Accessibility permission is granted, falling back to the vision
     * pipeline only when it's not. "vlm" = ALWAYS use the Screen VLM vision
     * pipeline (server-side Florence-2 via /api/vision/screen-elements),
     * even if Accessibility is granted — the user's explicit override.
     * "yolo" = ALWAYS use the on-device icon-detector engine
     * (IconDetector/ui_detector_w8a32.tflite — offline, zero network cost)
     * inside the same fallback vision pipeline, even if Accessibility is
     * granted — previously this engine only ever ran as an implicit
     * fallback when Accessibility permission was missing and VLM wasn't
     * picked; this makes it an explicit, always-on choice too. "ocr" =
     * ALWAYS use the fallback pipeline with ONLY ML Kit OCR (step 1) —
     * skips icon/button detection (step 2) entirely, for the
     * fastest/cheapest possible read of a screen that's mostly text. */
    const val SCREEN_GUIDE_MODE_ACCESSIBILITY = "accessibility"
    const val SCREEN_GUIDE_MODE_VLM = "vlm"
    const val SCREEN_GUIDE_MODE_YOLO = "yolo"
    const val SCREEN_GUIDE_MODE_OCR = "ocr"
    /** "হাইব্রিড" — always runs the SAME on-device engine as "yolo" (offline,
     * no server round-trip), but two things stronger on purpose: (1) icon
     * boxes are kept even when they overlap an OCR text block instead of
     * being dropped as a duplicate (see VisionFallbackManager — "yolo" mode
     * discards those; hybrid keeps both readings for the same region), and
     * (2) IconDetector.detect() is called with HYBRID_CONF_THRESHOLD
     * instead of its default, so borderline icons "yolo" mode would miss
     * still show up. Trades a few more (and occasionally duplicate)
     * candidate elements for genuinely not missing things — the LLM on the
     * other end already has to pick the one right element out of many. */
    const val SCREEN_GUIDE_MODE_HYBRID = "hybrid"

    /** "vision" (v24 ডিফল্ট) = স্ক্রিনশটই মূল উৎস, accessibility tree ছাড়া। স্ক্রিনশট JPG-তে বদলে
     * (ডিফল্টে সাদাকালো + সামান্য ছোট করে) Gemini/Groq vision মডেলে যায়; হাইলাইটের জায়গা মডেলই বলে।
     * Accessibility সার্ভিস তবু লাগে — স্ক্রিনশট নেওয়া, ওভারলে আঁকা ও ট্যাপ-সহায়তার জন্য, tree পড়ার জন্য নয়। */
    const val SCREEN_GUIDE_MODE_VISION = "vision"

    fun screenGuideMode(context: Context): String =
        prefs(context).getString(KEY_SCREEN_GUIDE_MODE, SCREEN_GUIDE_MODE_VISION)
            ?: SCREEN_GUIDE_MODE_VISION

    fun isVisionPrimary(context: Context): Boolean =
        screenGuideMode(context) == SCREEN_GUIDE_MODE_VISION

    // ---- Learning "বিস্তারিত স্তর" (১ = খুব সংক্ষেপে … ৫ = অনেক ধাপ, অনেক বিস্তারিত) — লাইব্রেরি, প্লেলিস্ট ও পাঠ সবখানে একই ----
    // v28: ১–১০ পয়েন্ট (৫ = মাঝারি, ১০ ≈ ১ ঘণ্টার ক্লাস)। পুরনো ১–৫ মান আলাদা key-তে ছিল, তাই নতুন key।
    private const val KEY_LEARNING_DETAIL = "learning_detail_level_v10"

    fun learningDetail(context: Context): Int =
        prefs(context).getInt(KEY_LEARNING_DETAIL, 5).coerceIn(1, 10)

    fun setLearningDetail(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LEARNING_DETAIL, value.coerceIn(1, 10)).apply()
    }

    // ---- v31 NOTE FORCE: Learning ক্লাসে ব্লাকবোর্ডে কত বিস্তারিত/সুন্দর নোট উঠবে ----------------------------
    // বন্ধ = আগের মতো স্বাভাবিক ক্লাস; চালু + পাওয়ার ১–১০ (বাড়ালে বেশি ও বিস্তারিত নোট, কমালে কম)।
    private const val KEY_NOTE_FORCE_ON = "note_force_on_v31"
    private const val KEY_NOTE_FORCE_LEVEL = "note_force_level_v31"
    const val NOTE_FORCE_MAX = 10

    fun noteForceOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTE_FORCE_ON, false)

    fun setNoteForceOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTE_FORCE_ON, value).apply()
    }

    /** সেভ-করা পাওয়ার (১–১০) — বন্ধ থাকলেও মনে থাকে, আবার চালালে আগের পাওয়ারেই ফেরে। */
    fun noteForceLevel(context: Context): Int =
        prefs(context).getInt(KEY_NOTE_FORCE_LEVEL, 5).coerceIn(1, NOTE_FORCE_MAX)

    fun setNoteForceLevel(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_NOTE_FORCE_LEVEL, value.coerceIn(1, NOTE_FORCE_MAX)).apply()
    }

    /** সার্ভারে যায় যে মান: বন্ধ হলে ০ (সার্ভার তখন কিছুই বদলায় না), নইলে ১–১০। */
    fun noteForceEffective(context: Context): Int =
        if (noteForceOn(context)) noteForceLevel(context) else 0

    // ---- Vision ছবির মান (সেটিংস: "ছবির মান") --------------------------------------------------
    // ডিফল্ট = দ্রুত/কম-টোকেন: সাদাকালো + সামান্য ছোট। হাই কোয়ালিটি = রঙিন + ভালো JPEG; রেজুলেশন আলাদা বাড়ানো যায়।
    // v29: ৪ ধাপ। রঙ টোকেন বাড়ায় না (Gemini/Groq ছবির টোকেন গোনে শুধু পিক্সেল-মাপে, রঙে নয়) — তাই ডিফল্ট রঙিন।
    // রেজুলেশন বাড়ালে টোকেন সামান্য বাড়ে; ডিফল্ট "মাঝারি" (~১৮০০px) — ছোট আইকন/লেখা পড়া যায়।
    const val VISION_RES_TINY = "tiny"    // লম্বা দিক সর্বোচ্চ ~১০০০px (সবচেয়ে দ্রুত/হালকা)
    const val VISION_RES_LOW = "low"      // ~১৪০০px (v28-এর ডিফল্ট)
    const val VISION_RES_MID = "mid"      // ~১৮০০px (v29-এর ডিফল্ট)
    const val VISION_RES_FULL = "full"    // কমানো নেই, আসল রেজুলেশন

    fun visionColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VISION_COLOR, true)

    /** AI "পাচ্ছি না/নিশ্চিত না" বললে বা অফেরতযোগ্য ধাপে — সেই এক কলের জন্য আসল রেজুলেশন + রঙিন ছবি পাঠানো। */
    fun visionAutoBoost(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VISION_AUTO_BOOST, true)

    fun setVisionAutoBoost(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VISION_AUTO_BOOST, value).apply()
    }

    fun setVisionColor(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VISION_COLOR, value).apply()
    }

    fun visionResolution(context: Context): String =
        prefs(context).getString(KEY_VISION_RES, VISION_RES_MID) ?: VISION_RES_MID

    fun setVisionResolution(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VISION_RES, value).apply()
    }

    fun setScreenGuideMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SCREEN_GUIDE_MODE, value).apply()
    }

    /** Which chat-box "system"/model (super_1_2 / super_lite / ela_1st /
     * ela_4n — see MainActivity's showSystemModeMenu) plans the next
     * workflow. Used to be session-only (reset to "super_1_2" every time
     * MainActivity was recreated) — persisted now so whatever the user
     * picks stays picked until they change it again, across app
     * restarts too ("যতক্ষণ চেঞ্জ না করি"). */
    fun selectedSystem(context: Context): String =
        prefs(context).getString(KEY_SELECTED_SYSTEM, "super_1_2") ?: "super_1_2"

    fun setSelectedSystem(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SELECTED_SYSTEM, value).apply()
    }

    fun autoSpeak(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SPEAK, false)

    fun setAutoSpeak(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SPEAK, value).apply()
    }

    fun voiceFirstLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_FIRST, false)

    fun setVoiceFirstLaunch(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_FIRST, value).apply()
    }

    /** Whether the one-time "how Lenspilot Keyboard works" guidance
     * message has already been shown (see MainActivity's keyboard-mode
     * dialog) — shown once per device, not every time the mode dialog
     * is opened. */
    fun keyboardModeIntroSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_MODE_INTRO_SEEN, false)

    fun setKeyboardModeIntroSeen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_MODE_INTRO_SEEN, value).apply()
    }

    /** The most recent AI chat reply text, so Lenspilot Keyboard's
     * "সর্বশেষ AI উত্তর বসাও" quick action can type it into whatever
     * field the user is focused on in ANY app — read/written from both
     * MainActivity (chat) and LenspilotInputMethodService (keyboard),
     * same process so plain SharedPreferences is enough, no IPC needed. */
    fun lastAiReply(context: Context): String? =
        prefs(context).getString(KEY_LAST_AI_REPLY, null)

    fun setLastAiReply(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LAST_AI_REPLY, text).apply()
    }

    /** Whether Lenspilot Keyboard should automatically figure out and
     * type the needed text on its own (default: on) — the user can pause
     * this per-field from the keyboard's own toggle and go back to typing
     * manually; this pref is the persistent default for the NEXT field. */
    fun keyboardAutoTypeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_AUTO_TYPE, true)

    fun setKeyboardAutoTypeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_AUTO_TYPE, value).apply()
    }

    /** Keyboard key/row size multiplier — cycled by the resize (⤢) key:
     * 0.85 (ছোট), 1.0 (মাঝারি, default), 1.15 (বড়). Applied to every
     * dp() call in LenspilotInputMethodService.buildKeyRows(). */
    fun keyboardSizeScale(context: Context): Float =
        prefs(context).getFloat(KEY_KEYBOARD_SIZE_SCALE, 1.0f)

    fun setKeyboardSizeScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_KEYBOARD_SIZE_SCALE, value).apply()
    }

    /** True = keyboard shows the traditional Bangla layout (কার/ব্যঞ্জনবর্ণ
     * keys) instead of the QWERTY letter rows; toggled by the "বাং/EN" key. */
    fun keyboardBanglaMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_BANGLA_MODE, false)

    fun setKeyboardBanglaMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_BANGLA_MODE, value).apply()
    }

    /** Whether the translate feature is on at all — gates BOTH the
     * keyboard's translate (🌐) key AND the Quick Settings tile's
     * OCR-and-translate mode (see QuickScanTrampolineActivity, which
     * branches to translateCurrentScreen() instead of the normal
     * quickScanCurrentScreen() guidance flow when this is true). */
    fun translateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRANSLATE_ENABLED, false)

    fun setTranslateEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRANSLATE_ENABLED, value).apply()
    }

    /** Target language for translation, as a plain human-readable name
     * sent straight to the backend prompt (see /api/translate) — no
     * language-code table needed since the model reads it directly.
     * Cycled by long-pressing the keyboard's translate key. */
    fun translateTargetLang(context: Context): String =
        prefs(context).getString(KEY_TRANSLATE_TARGET_LANG, "বাংলা") ?: "বাংলা"

    fun setTranslateTargetLang(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TRANSLATE_TARGET_LANG, value).apply()
    }

    // ------------------------------------------------------------------
    // "আমার তথ্য" — the user's own profile info table (Name/Address/etc,
    // or whatever label/value rows the user adds), entered once from the
    // top-bar icon next to newChatButton and reused on every request
    // afterwards. Stored as a plain JSON array string
    // ([{"label":"...","value":"..."}, ...]) — see
    // com.hemel.lenspilot.userinfo.UserInfoEntry for the in-memory shape.
    //
    // COST RULE (matches app.py's build_user_info_block): an empty table
    // must add zero request bytes. [userInfoJsonArrayOrNull] returns null
    // for an empty/never-filled table so every call site can just do
    // `Prefs.userInfoJsonArrayOrNull(context)?.let { put("user_info", it) }`
    // and skip the field entirely rather than sending "[]".
    // ------------------------------------------------------------------
    private const val KEY_USER_INFO = "user_info_rows"

    fun userInfoEntries(context: Context): MutableList<com.hemel.lenspilot.userinfo.UserInfoEntry> {
        val raw = prefs(context).getString(KEY_USER_INFO, null) ?: return mutableListOf()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = mutableListOf<com.hemel.lenspilot.userinfo.UserInfoEntry>()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                out.add(
                    com.hemel.lenspilot.userinfo.UserInfoEntry(
                        label = row.optString("label", ""),
                        value = row.optString("value", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun setUserInfoEntries(context: Context, entries: List<com.hemel.lenspilot.userinfo.UserInfoEntry>) {
        val arr = org.json.JSONArray()
        for (entry in entries) {
            if (entry.label.isBlank() || entry.value.isBlank()) continue
            arr.put(org.json.JSONObject().apply {
                put("label", entry.label.trim())
                put("value", entry.value.trim())
            })
        }
        prefs(context).edit().putString(KEY_USER_INFO, arr.toString()).apply()
    }

    /** Non-empty rows only, ready to drop straight into a request body's
     * "user_info" field — null when there's nothing usable yet, so
     * callers add zero JSON to the request instead of an empty "[]".
     *
     * Reads ONLY whatever one-time rows are currently active on
     * [com.hemel.lenspilot.workflow.WorkflowContext.oneTimeUserInfo] —
     * i.e. what the user typed into a specific card's "এই কাজের জন্য
     * শুধু" icon for the run that's actually in progress right now
     * (see item_workflow_card.xml / item_ela_run_button.xml +
     * MainActivity.showOneTimeUserInfoDialog). There is deliberately no
     * persistent, always-attached table anymore — the old header
     * "আমার তথ্য" button sent its saved rows with EVERY request,
     * everywhere; this scopes data to exactly the one run it was
     * attached to. Doing the read here, once, means every existing call
     * site (analyze-screen, workflow/plan, browser-action) picks up
     * one-time data automatically without touching each one
     * individually. Cleared the moment WorkflowContext.stop() runs so
     * it can never bleed into a later, unrelated run. */
    fun userInfoJsonArrayOrNull(context: Context): org.json.JSONArray? {
        val oneTime = com.hemel.lenspilot.workflow.WorkflowContext.oneTimeUserInfo
        val merged = LinkedHashMap<String, String>()
        for (entry in oneTime) {
            if (entry.label.isBlank() || entry.value.isBlank()) continue
            merged[entry.label.trim()] = entry.value.trim()
        }
        if (merged.isEmpty()) return null
        val arr = org.json.JSONArray()
        for ((label, value) in merged) {
            arr.put(org.json.JSONObject().apply {
                put("label", label)
                put("value", value)
            })
        }
        return arr
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
