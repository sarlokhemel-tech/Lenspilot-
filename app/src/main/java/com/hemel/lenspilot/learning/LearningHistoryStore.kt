package com.hemel.lenspilot.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device history of AI Learning Mode lessons — completely separate from the normal chat
 * history ([com.hemel.lenspilot.chat.HistoryStore]). Learning questions are never written to the
 * chat history any more; they only live here, and are only shown from the dark Learning-mode
 * screens (home page in learning mode + the lesson screen).
 *
 * Each entry keeps the topic the student asked, the lesson title, the board notes (everything the
 * teacher wrote) and the step list. Audio / pictures are NOT stored (they are big and re-creatable);
 * "Teach again" re-runs the topic.
 */
object LearningHistoryStore {
    private const val PREFS_NAME = "lenspilot_learning_history"
    private const val KEY_LESSONS = "lessons"
    private const val MAX_LESSONS = 40
    private const val MAX_BOARD_CHARS = 12000

    data class Lesson(
        val id: String,
        val title: String,
        val topic: String,
        val updatedAt: Long,
        val board: String,
        val outline: List<String>
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<Lesson> {
        val raw = prefs(context).getString(KEY_LESSONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val outlineArr = o.optJSONArray("outline") ?: JSONArray()
                Lesson(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    topic = o.optString("topic"),
                    updatedAt = o.optLong("updated_at"),
                    board = o.optString("board"),
                    outline = (0 until outlineArr.length()).map { outlineArr.optString(it) }
                )
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Saves / overwrites the lesson with [id] (same id across saves of the same lesson). */
    fun save(context: Context, id: String, title: String, topic: String, board: String, outline: List<String>) {
        if (topic.isBlank() || (board.isBlank() && outline.isEmpty())) return
        val entry = JSONObject().apply {
            put("id", id)
            put("title", title.ifBlank { topic }.take(80))
            put("topic", topic.take(300))
            put("updated_at", System.currentTimeMillis())
            put("board", board.take(MAX_BOARD_CHARS))
            put("outline", JSONArray().also { arr -> outline.take(60).forEach { arr.put(it.take(200)) } })
        }
        val existing = try {
            JSONArray(prefs(context).getString(KEY_LESSONS, null) ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val all = mutableListOf<JSONObject>()
        for (i in 0 until existing.length()) {
            val o = existing.optJSONObject(i) ?: continue
            if (o.optString("id") != id) all.add(o)
        }
        all.add(entry)
        val trimmed = JSONArray()
        all.sortedByDescending { it.optLong("updated_at") }.take(MAX_LESSONS).forEach { trimmed.put(it) }
        prefs(context).edit().putString(KEY_LESSONS, trimmed.toString()).apply()
    }

    fun delete(context: Context, id: String) {
        val existing = try {
            JSONArray(prefs(context).getString(KEY_LESSONS, null) ?: "[]")
        } catch (_: Exception) {
            return
        }
        val kept = JSONArray()
        for (i in 0 until existing.length()) {
            val o = existing.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        prefs(context).edit().putString(KEY_LESSONS, kept.toString()).apply()
    }
}
