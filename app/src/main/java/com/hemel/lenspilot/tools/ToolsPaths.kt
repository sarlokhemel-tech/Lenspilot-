package com.hemel.lenspilot.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tools মোডের ফাইল-কাঠামো (সব কিছু অ্যাপের নিজস্ব filesDir-এর ভেতরে):
 *   filesDir/tools/
 *     workspace/                  ← সেশন ছাড়া ব্যবহারের গ্লোবাল স্টোরেজ
 *     sessions/<id>/
 *       workspace/                ← ওই সেশনের Storage রুট (শেল এর ভেতরেই কাজ করবে)
 *       session.json              ← WorkSession
 *       steps.jsonl               ← ধাপের লগ (পরের ধাপে ভরবে)
 */
object ToolsPaths {
    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidId(id: String?): Boolean = id != null && ID_REGEX.matches(id)

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "tools").apply { mkdirs() }

    fun sessionsDir(ctx: Context): File = File(root(ctx), "sessions").apply { mkdirs() }

    fun globalWorkspace(ctx: Context): File = File(root(ctx), "workspace").apply { mkdirs() }

    /** id অবৈধ হলে IllegalArgumentException — পাথ-ট্র্যাভার্সাল ঠেকাতে। */
    fun sessionDir(ctx: Context, id: String): File {
        require(isValidId(id)) { "অবৈধ সেশন আইডি" }
        return File(sessionsDir(ctx), id)
    }

    fun workspaceDir(ctx: Context, id: String): File = File(sessionDir(ctx, id), "workspace")

    fun sessionJson(ctx: Context, id: String): File = File(sessionDir(ctx, id), "session.json")

    fun stepsLog(ctx: Context, id: String): File = File(sessionDir(ctx, id), "steps.jsonl")
}

/** ব্যাকগ্রাউন্ড IO ও UI-তে ফেরার ছোট হেল্পার (নতুন লাইব্রেরি ছাড়া)। */
object ToolsAsync {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun io(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.e("ToolsAsync", "ব্যাকগ্রাউন্ড কাজ ব্যর্থ", t)
            }
        }
    }

    fun ui(block: () -> Unit) {
        mainHandler.post { block() }
    }
}
