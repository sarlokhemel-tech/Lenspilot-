package com.hemel.lenspilot.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Random

/**
 * WorkSession-এর JSON-ফাইল স্টোর (Room ছাড়া)। সব অপারেশন একটাই lock-এর নিচে —
 * থ্রেড-সেফ; লেখা হয় temp ফাইলে, তারপর atomic rename।
 */
object SessionStore {
    private val lock = Any()
    private val random = Random()

    fun create(ctx: Context, goal: String, defaultTitle: String): WorkSession = synchronized(lock) {
        val now = System.currentTimeMillis()
        val id = "s" + now.toString(36) + Integer.toString(random.nextInt(36 * 36 * 36 * 36), 36)
        val cleanGoal = goal.trim()
        val title = cleanGoal.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(48)
            ?: defaultTitle
        val session = WorkSession(id, title, cleanGoal, SessionState.IDLE, now, now, "")
        ToolsPaths.workspaceDir(ctx, id).mkdirs()
        writeAtomic(ToolsPaths.sessionJson(ctx, id), session.toJson().toString())
        session
    }

    fun get(ctx: Context, id: String): WorkSession? = synchronized(lock) { readLocked(ctx, id) }

    fun list(ctx: Context): List<WorkSession> = synchronized(lock) {
        val dirs = ToolsPaths.sessionsDir(ctx).listFiles() ?: return@synchronized emptyList()
        dirs.filter { it.isDirectory && ToolsPaths.isValidId(it.name) }
            .mapNotNull { readLocked(ctx, it.name) }
            .sortedByDescending { it.updatedAt }
    }

    /** transform-এর ফলাফল লেখা হয় (id ও createdAt অপরিবর্তিত, updatedAt এখনকার সময়)। */
    fun update(ctx: Context, id: String, transform: (WorkSession) -> WorkSession): WorkSession? =
        synchronized(lock) {
            val old = readLocked(ctx, id) ?: return@synchronized null
            val next = transform(old).copy(id = old.id, createdAt = old.createdAt, updatedAt = System.currentTimeMillis())
            writeAtomic(ToolsPaths.sessionJson(ctx, id), next.toJson().toString())
            next
        }

    fun delete(ctx: Context, id: String): Boolean = synchronized(lock) {
        if (!ToolsPaths.isValidId(id)) return@synchronized false
        val dir = ToolsPaths.sessionDir(ctx, id)
        dir.exists() && SafeFs.deleteTree(dir)
    }

    private fun readLocked(ctx: Context, id: String): WorkSession? {
        if (!ToolsPaths.isValidId(id)) return null
        val f = ToolsPaths.sessionJson(ctx, id)
        if (!f.isFile) return null
        return try {
            WorkSession.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            null // নষ্ট ফাইল এড়িয়ে যাই
        }
    }

    /** temp ফাইলে লিখে atomic rename — মাঝপথে ক্র্যাশ করলেও আসল ফাইল অক্ষত থাকে। */
    internal fun writeAtomic(target: File, text: String) {
        val dir = target.parentFile ?: throw IOException("প্যারেন্ট ফোল্ডার নেই")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
