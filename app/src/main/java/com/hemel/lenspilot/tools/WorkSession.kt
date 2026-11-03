package com.hemel.lenspilot.tools

import org.json.JSONObject

/** সেশনের অবস্থা — wire নামটাই session.json-এ লেখা হয়। */
enum class SessionState(val wire: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    NEEDS_YOU("needs_you"),
    DONE("done"),
    FAILED("failed");

    companion object {
        fun fromWire(s: String?): SessionState = values().firstOrNull { it.wire == s } ?: IDLE
    }
}

data class WorkSession(
    val id: String,
    val title: String,
    val goal: String,
    val state: SessionState,
    val createdAt: Long,
    val updatedAt: Long,
    val lastNote: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("goal", goal)
        put("state", state.wire)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastNote", lastNote)
    }

    companion object {
        fun fromJson(o: JSONObject): WorkSession = WorkSession(
            id = o.getString("id"),
            title = o.optString("title", ""),
            goal = o.optString("goal", ""),
            state = SessionState.fromWire(o.optString("state", "idle")),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            lastNote = o.optString("lastNote", "")
        )
    }
}
