package com.hemel.lenspilot.tools.runner

import android.content.Context
import com.hemel.lenspilot.tools.ToolsPaths
import org.json.JSONObject
import java.io.File

/** একটা নজরদারি-সংজ্ঞা। detectorJson কাঁচা JSON (ভ্যালিডেশন Runner.arm-এ)। */
class WatcherSpec(
    val id: String,
    /** null = গ্লোবাল workspace। */
    val sessionId: String?,
    val stepId: String,
    val detectorJson: String,
    val stuckAfterS: Int,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("sessionId", sessionId ?: JSONObject.NULL).put("stepId", stepId)
        .put("detector", JSONObject(detectorJson)).put("stuckAfterS", stuckAfterS).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): WatcherSpec? {
            val id = o.optString("id")
            val det = o.optJSONObject("detector") ?: return null
            if (id.isEmpty()) return null
            val sid = if (o.isNull("sessionId")) null else o.optString("sessionId").ifEmpty { null }
            return WatcherSpec(id, sid, o.optString("stepId", ""), det.toString(),
                o.optInt("stuckAfterS", 1800), o.optLong("createdAt", 0L))
        }
    }
}

/**
 * watcher-সংজ্ঞা সেশন-ফোল্ডারের watchers.json-এ (সেশন ছাড়া হলে tools/watchers.json-এ) — প্রসেস মরলে বা রিবুটে ফেরানোর জন্য।
 */
object WatcherStore {
    private const val FILE = "watchers.json"
    private val lock = Any()

    private fun fileFor(ctx: Context, sessionId: String?): File =
        if (sessionId == null) File(ToolsPaths.root(ctx), FILE) else File(ToolsPaths.sessionDir(ctx, sessionId), FILE)

    private fun read(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun write(f: File, o: JSONObject) {
        try {
            if (o.length() == 0) { f.delete(); return }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) { }
    }

    fun put(ctx: Context, s: WatcherSpec) = synchronized(lock) {
        val f = fileFor(ctx, s.sessionId)
        val o = read(f)
        o.put(s.id, s.toJson())
        write(f, o)
    }

    fun remove(ctx: Context, s: WatcherSpec) = synchronized(lock) {
        val f = fileFor(ctx, s.sessionId)
        val o = read(f)
        if (o.has(s.id)) { o.remove(s.id); write(f, o) }
    }

    /** সব সেশনের + গ্লোবাল watcher। */
    fun all(ctx: Context): List<WatcherSpec> = synchronized(lock) {
        val out = ArrayList<WatcherSpec>()
        val files = ArrayList<File>()
        files.add(fileFor(ctx, null))
        ToolsPaths.sessionsDir(ctx).listFiles()?.forEach {
            if (it.isDirectory && ToolsPaths.isValidId(it.name)) files.add(File(it, FILE))
        }
        for (f in files) {
            if (!f.exists()) continue
            val o = read(f)
            val ids = o.keys().asSequence().toList()
            for (id in ids) {
                val spec = o.optJSONObject(id)?.let { WatcherSpec.fromJson(it) } ?: continue
                out.add(spec)
            }
        }
        out
    }
}
