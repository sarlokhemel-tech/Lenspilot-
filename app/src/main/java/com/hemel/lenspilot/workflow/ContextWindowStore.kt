package com.hemel.lenspilot.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device "RAM-style" context window for a goal-driven AI task (the AI
 * browser's decide-act loop today; written so the same idea can back the
 * main chat loop later too — see the feature note this was built from).
 *
 * The idea it implements: the AI keeps its GOAL in real memory and stays
 * anchored to it, but everything it has already DONE (which step it
 * tried, what it clicked, who it messaged) does NOT need to be resent to
 * the backend in full on every request — that's what silently inflates
 * token cost on a long task. Instead that log lives here, on-device,
 * capped and cheap:
 *
 *  - [recentForPrompt] returns only the last few compact lines — this is
 *    what actually goes into a request body (see BrowserActionRequest).
 *  - The FULL log stays local only. If the AI genuinely needs to check
 *    something from earlier in a long-running task (e.g. "reply to my
 *    1000 Facebook comments" — it won't remember every single reply
 *    verbatim, just the recent few, plus the ability to look back),
 *    [search] does a local keyword lookup — zero network calls, zero
 *    tokens spent, resolved entirely on-device.
 *  - [clearSession] auto-runs once a task finishes (task_complete /
 *    ask_user / stopped / superseded by a new goal) — "কাজ শেষ হলে অটো
 *    ডিলিট" from the spec.
 *
 * This is intentionally NOT a vector database. Running a real embedding
 * model on-device for a per-step scratch log would cost far more in
 * compute/battery/complexity than the bit of recall it buys — plain
 * substring matching over a small capped local list is the "very low
 * token" version the spec asks for, and it's good enough for "did I
 * already message this person" style lookups.
 *
 * Storage: one SharedPreferences file, one JSON blob per session
 * (session = one goal/task). Deliberately NOT a slick structured schema —
 * per the spec, this is scratch memory for the AI's own convenience, not
 * something a human is meant to read closely; the Settings → "Context
 * Window" viewer just dumps it as-is so a developer can confirm it's
 * actually being written to, nothing more.
 */
object ContextWindowStore {

    private const val PREFS_NAME = "lenspilot_context_window"
    private const val KEY_LAST_SESSION = "last_session_id"
    private const val MAX_ENTRIES_PER_SESSION = 60   // ring-buffer cap — oldest drop off
    private const val MAX_ENTRY_CHARS = 160
    const val DEFAULT_PROMPT_ENTRIES = 6             // how many lines actually go into a request

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sessionKey(sessionId: String) = "session_$sessionId"

    private fun readSession(context: Context, sessionId: String): JSONObject {
        val raw = prefs(context).getString(sessionKey(sessionId), null) ?: return JSONObject().apply {
            put("goal", "")
            put("entries", JSONArray())
        }
        return try { JSONObject(raw) } catch (e: Exception) {
            JSONObject().apply { put("goal", ""); put("entries", JSONArray()) }
        }
    }

    private fun writeSession(context: Context, sessionId: String, obj: JSONObject) {
        prefs(context).edit()
            .putString(sessionKey(sessionId), obj.toString())
            .putString(KEY_LAST_SESSION, sessionId)
            .apply()
    }

    /** Starts (or resets) a session for a fresh goal — call this whenever
     * the user submits a new goal, or the AI itself issues `edit_goal`
     * (in which case pass the SAME sessionId to keep the log, just with
     * an updated goal line at the top — see [updateGoal] for that). */
    fun startSession(context: Context, sessionId: String, goal: String) {
        val obj = JSONObject().apply {
            put("goal", goal)
            put("entries", JSONArray())
        }
        writeSession(context, sessionId, obj)
    }

    /** Updates just the goal line of an in-progress session — used by the
     * `edit_goal` action, which refines the goal without throwing away
     * what's already been done in this session. */
    fun updateGoal(context: Context, sessionId: String, newGoal: String) {
        val obj = readSession(context, sessionId)
        obj.put("goal", newGoal)
        writeSession(context, sessionId, obj)
    }

    /** Appends one compact log line — trimmed to [MAX_ENTRY_CHARS], and
     * the session capped to [MAX_ENTRIES_PER_SESSION] entries (oldest
     * dropped first, true ring-buffer behavior) so this can never grow
     * without bound across a very long task. */
    fun remember(context: Context, sessionId: String, text: String) {
        val obj = readSession(context, sessionId)
        val entries = obj.optJSONArray("entries") ?: JSONArray()
        entries.put(text.take(MAX_ENTRY_CHARS))
        val trimmedEntries = if (entries.length() > MAX_ENTRIES_PER_SESSION) {
            val overflow = entries.length() - MAX_ENTRIES_PER_SESSION
            val kept = JSONArray()
            for (i in overflow until entries.length()) kept.put(entries.get(i))
            kept
        } else entries
        obj.put("entries", trimmedEntries)
        writeSession(context, sessionId, obj)
    }

    /** The compact tail sent to the backend on the next request — same
     * shape/size the old in-memory `history.takeLast(6)` used, just now
     * persisted on-device instead of living only in an Activity field. */
    fun recentForPrompt(context: Context, sessionId: String, n: Int = DEFAULT_PROMPT_ENTRIES): List<String> {
        val obj = readSession(context, sessionId)
        val entries = obj.optJSONArray("entries") ?: return emptyList()
        val all = (0 until entries.length()).map { entries.optString(it, "") }
        return all.takeLast(n)
    }

    /** Local, on-device, zero-token keyword search over the FULL log for
     * this session — the "দরকারে RAG সিষ্টেমে সার্চ করে দেখবে" bit from the
     * spec, done as a cheap substring match instead of a real vector
     * search since this is scratch memory for one task, not a knowledge
     * base worth the embedding cost. */
    fun search(context: Context, sessionId: String, keyword: String, limit: Int = 10): List<String> {
        if (keyword.isBlank()) return emptyList()
        val obj = readSession(context, sessionId)
        val entries = obj.optJSONArray("entries") ?: return emptyList()
        val needle = keyword.trim().lowercase()
        val results = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val line = entries.optString(i, "")
            if (line.lowercase().contains(needle)) {
                results.add(line)
                if (results.size >= limit) break
            }
        }
        return results
    }

    /** Deletes one session entirely — call when a task ends (complete,
     * asked the user for help, stopped, or replaced by a brand new
     * unrelated goal). "কাজ শেষ হলে অটো ডিলিট" from the spec. */
    fun clearSession(context: Context, sessionId: String) {
        prefs(context).edit().remove(sessionKey(sessionId)).apply()
    }

    /** For Settings → "Context Window": the most recently used session's
     * goal + raw entries, so a developer can confirm this is actually
     * being written to. Deliberately unpolished — this view exists only
     * to check "is it working", not for a human to read closely (per the
     * spec: however the AI finds cheapest to remember is fine, messy or
     * not — this option is just a working indicator). Returns null if
     * nothing has been recorded yet, or the last session was already
     * cleared. */
    fun lastSessionSummaryForSettings(context: Context): String? {
        val lastId = prefs(context).getString(KEY_LAST_SESSION, null) ?: return null
        val raw = prefs(context).getString(sessionKey(lastId), null) ?: return null
        val obj = try { JSONObject(raw) } catch (e: Exception) { return null }
        val goal = obj.optString("goal", "")
        val entries = obj.optJSONArray("entries") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("সেশন: ").append(lastId).append('\n')
        sb.append("লক্ষ্য: ").append(goal.ifBlank { "(নেই)" }).append('\n')
        sb.append("এন্ট্রি (").append(entries.length()).append("টা):\n")
        for (i in 0 until entries.length()) {
            sb.append(i + 1).append(". ").append(entries.optString(i, "")).append('\n')
        }
        return sb.toString()
    }
}
