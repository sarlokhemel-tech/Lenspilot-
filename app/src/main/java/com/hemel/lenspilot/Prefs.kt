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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
