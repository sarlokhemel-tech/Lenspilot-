package com.hemel.lenspilot.tools.agent

import android.content.Context
import com.hemel.lenspilot.security.Redactor
import com.hemel.lenspilot.tools.SessionStore
import com.hemel.lenspilot.tools.ToolsPaths
import org.json.JSONObject
import java.io.File

/**
 * এজেন্টের অবস্থা সেশন-ফোল্ডারের agent_state.json-এ (atomic লেখা)। প্রতি ধাপের পর সেভ — প্রসেস মরলে এখান থেকে ফেরে।
 * ডিস্কে যাওয়ার আগে পুরো JSON Redactor-এর ভেতর দিয়ে যায় (ভল্টের মান কখনো ফাইলে নয়)।
 */
object AgentStateStore {
    private val lock = Any()

    private fun file(ctx: Context, sid: String): File = File(ToolsPaths.sessionDir(ctx, sid), "agent_state.json")

    fun save(ctx: Context, d: AgentData) {
        if (!ToolsPaths.isValidId(d.sessionId)) return
        try {
            val text = Redactor.redact(ctx, d.toJson().toString())
            synchronized(lock) { SessionStore.writeAtomic(file(ctx, d.sessionId), text) }
        } catch (e: Exception) { }
    }

    fun load(ctx: Context, sid: String): AgentData? {
        if (!ToolsPaths.isValidId(sid)) return null
        return try {
            val f = file(ctx, sid)
            if (!f.isFile) null else synchronized(lock) { AgentData.fromJson(sid, JSONObject(f.readText(Charsets.UTF_8))) }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(ctx: Context, sid: String): Boolean = ToolsPaths.isValidId(sid) && file(ctx, sid).isFile
}
