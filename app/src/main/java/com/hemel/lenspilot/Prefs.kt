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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
