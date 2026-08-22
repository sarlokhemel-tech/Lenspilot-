package com.hemel.lenspilot.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local (on-device) chat history — a JSON array of past sessions in
 * SharedPreferences. Each session is: {id, title, updated_at, messages:[...]}.
 * Good enough for "tap history -> reopen an old conversation"; not synced
 * across devices. If that's ever needed, this is the seam to swap for a
 * Space-backed endpoint instead.
 */
object HistoryStore {
    private const val PREFS_NAME = "lenspilot_history"
    private const val KEY_SESSIONS = "sessions"
    private const val MAX_SESSIONS = 40

    data class Session(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listSessions(context: Context): List<Session> {
        val raw = prefs(context).getString(KEY_SESSIONS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val msgsArr = o.optJSONArray("messages") ?: JSONArray()
            val msgs = (0 until msgsArr.length()).map { ChatMessage.fromJson(msgsArr.getJSONObject(it)) }
            out.add(Session(o.optString("id"), o.optString("title"), o.optLong("updated_at"), msgs))
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /** Saves/overwrites a session by [id] (pass the same id across saves of
     * the same conversation; a new id starts a new history entry). */
    fun saveSession(context: Context, id: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.text?.take(60) ?: "চ্যাট"
        val existing = JSONArray(prefs(context).getString(KEY_SESSIONS, null) ?: "[]")
        val updated = JSONArray()
        var replaced = false
        for (i in 0 until existing.length()) {
            val o = existing.getJSONObject(i)
            if (o.optString("id") == id) {
                updated.put(sessionJson(id, title, messages))
                replaced = true
            } else {
                updated.put(o)
            }
        }
        if (!replaced) updated.put(sessionJson(id, title, messages))

        // Trim to MAX_SESSIONS, keeping the most recently updated ones.
        val list = (0 until updated.length()).map { updated.getJSONObject(it) }
            .sortedByDescending { it.optLong("updated_at") }
            .take(MAX_SESSIONS)
        val trimmed = JSONArray()
        list.forEach { trimmed.put(it) }

        prefs(context).edit().putString(KEY_SESSIONS, trimmed.toString()).apply()
    }

    private fun sessionJson(id: String, title: String, messages: List<ChatMessage>): JSONObject {
        val msgsArr = JSONArray()
        messages.forEach { msgsArr.put(it.toJson()) }
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("updated_at", System.currentTimeMillis())
            put("messages", msgsArr)
        }
    }
}
