package com.hemel.lenspilot.net

import android.content.Context

/** Tiny wrapper around SharedPreferences for the one thing we need to
 * persist between app launches: the Space session token. */
object SessionStore {
    private const val PREFS_NAME = "lenspilot_session"
    private const val KEY_TOKEN = "session_token"

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .apply()
    }
}
