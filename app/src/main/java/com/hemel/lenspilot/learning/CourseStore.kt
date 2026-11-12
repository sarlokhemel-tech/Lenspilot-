package com.hemel.lenspilot.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v135 — ফাইল-থেকে-কোর্স: ফোনে সংরক্ষিত কোর্স।
 * filesDir/courses/<id>/course.json (দিনভিত্তিক প্ল্যান + অগ্রগতি) আর src.txt (টেক্সট/কোডের পুরো লেখা) বা src.pdf (আসল PDF)।
 * সার্ভারে ফাইলের কপি ২৪–৪৮ ঘণ্টা থাকে; কোর্স চলে অনেক দিন — তাই প্রতিদিনের লাইন/পাতা ফোন থেকেই পাঠানো হয়।
 */
object CourseStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "courses").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    fun srcTxt(ctx: Context, id: String) = File(dir(ctx, id), "src.txt")
    fun srcPdf(ctx: Context, id: String) = File(dir(ctx, id), "src.pdf")

    fun list(ctx: Context): List<JSONObject> =
        (root(ctx).listFiles() ?: emptyArray()).mapNotNull { d ->
            runCatching { JSONObject(File(d, "course.json").readText()) }.getOrNull()
        }.sortedByDescending { it.optLong("createdAt") }

    fun load(ctx: Context, id: String): JSONObject? =
        runCatching { JSONObject(File(dir(ctx, id), "course.json").readText()) }.getOrNull()

    fun save(ctx: Context, course: JSONObject) {
        val id = course.getString("id")
        File(dir(ctx, id), "course.json").writeText(course.toString())
    }

    fun delete(ctx: Context, id: String) { runCatching { File(root(ctx), id).deleteRecursively() } }

    fun doneCount(c: JSONObject): Int {
        val days = c.optJSONArray("days") ?: return 0
        var n = 0
        for (i in 0 until days.length()) if (days.getJSONObject(i).optBoolean("done")) n++
        return n
    }

    fun setDone(ctx: Context, id: String, day: Int, done: Boolean) {
        val c = load(ctx, id) ?: return
        val days = c.optJSONArray("days") ?: return
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i)
            if (d.optInt("day") == day) d.put("done", done)
        }
        save(ctx, c)
    }

    /** আজকের লাইনগুলো (from..to, ১-ভিত্তিক) → সার্ভারের source_slice। */
    fun sliceJson(ctx: Context, c: JSONObject, from: Int, to: Int): JSONObject? {
        val f = srcTxt(ctx, c.getString("id"))
        if (!f.exists()) return null
        val out = StringBuilder()
        var n = 0
        f.bufferedReader().useLines { seq ->
            for (line in seq) {
                n++
                if (n < from) continue
                if (n > to) break
                out.append(line).append('\n')
            }
        }
        if (out.isBlank()) return null
        return JSONObject()
            .put("name", c.optString("name"))
            .put("lang", c.optString("lang"))
            .put("kind", c.optString("kind"))
            .put("from", from)
            .put("to", minOf(to, n))
            .put("total", c.optInt("total"))
            .put("text", out.toString())
    }

    fun daysOf(c: JSONObject): List<JSONObject> {
        val a: JSONArray = c.optJSONArray("days") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
}
