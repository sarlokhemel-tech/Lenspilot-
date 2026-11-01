package com.hemel.lenspilot.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** প্লেলিস্টের একটা আইটেম (একদিনের/একটা পাঠের পরিকল্পনা)। */
class LibItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var description: String = "",
    var topics: MutableList<String> = mutableListOf(),
    var done: Boolean = false,
    var day: Int = 0
)

/**
 * লাইব্রেরির একটা নোড। kind: "course" | "class" | "subject" | "chapter".
 *  - course/chapter → [items] (প্লেলিস্ট)
 *  - class/subject  → [sections] (ক্লাসের বিষয়, বিষয়ের অধ্যায় — প্রতিটা নিজেই একটা নোড)
 * items == null && sections.isEmpty() মানে "এখনো তৈরি হয়নি" (সেক্টরে চাপলে AI তখন বানায়)।
 */
class LibNode(
    val id: String = UUID.randomUUID().toString(),
    var kind: String,
    var title: String,
    var summary: String = "",
    var query: String = "",
    var context: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val sections: MutableList<LibNode> = mutableListOf(),
    var items: MutableList<LibItem>? = null,
    var sources: List<String> = emptyList()
) {
    val generated: Boolean get() = items != null || sections.isNotEmpty()

    /** (শেষ, মোট) — নিচের সব তৈরি-হওয়া প্লেলিস্ট ধরে। */
    fun progress(): Pair<Int, Int> {
        items?.let { list -> return list.count { it.done } to list.size }
        var d = 0; var t = 0
        for (c in sections) { val (cd, ct) = c.progress(); d += cd; t += ct }
        return d to t
    }
}

/** লাইব্রেরি ডিভাইসেই থাকে (filesDir/learning_library.json); ছবি/অডিও রাখে না, শুধু পরিকল্পনা। */
object LearningLibraryStore {
    private const val FILE = "learning_library.json"

    fun load(context: Context): MutableList<LibNode> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { nodeFromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, roots: List<LibNode>) {
        try {
            val arr = JSONArray()
            roots.forEach { arr.put(nodeToJson(it)) }
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(context.filesDir, FILE))
        } catch (_: Exception) { }
    }

    private fun strList(a: JSONArray?): MutableList<String> =
        if (a == null) mutableListOf() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toMutableList()

    private fun itemToJson(i: LibItem) = JSONObject()
        .put("id", i.id).put("title", i.title).put("description", i.description)
        .put("topics", JSONArray(i.topics)).put("done", i.done).put("day", i.day)

    private fun itemFromJson(o: JSONObject) = LibItem(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        title = o.optString("title"), description = o.optString("description"),
        topics = strList(o.optJSONArray("topics")), done = o.optBoolean("done"), day = o.optInt("day")
    )

    private fun nodeToJson(n: LibNode): JSONObject {
        val o = JSONObject()
            .put("id", n.id).put("kind", n.kind).put("title", n.title).put("summary", n.summary)
            .put("query", n.query).put("context", n.context).put("created_at", n.createdAt)
            .put("sources", JSONArray(n.sources))
        val secs = JSONArray(); n.sections.forEach { secs.put(nodeToJson(it)) }
        o.put("sections", secs)
        n.items?.let { list -> val a = JSONArray(); list.forEach { a.put(itemToJson(it)) }; o.put("items", a) }
        return o
    }

    private fun nodeFromJson(o: JSONObject): LibNode {
        val n = LibNode(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = o.optString("kind", "course"), title = o.optString("title"), summary = o.optString("summary"),
            query = o.optString("query"), context = o.optString("context"),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            sources = strList(o.optJSONArray("sources"))
        )
        o.optJSONArray("sections")?.let { a -> for (i in 0 until a.length()) n.sections.add(nodeFromJson(a.getJSONObject(i))) }
        o.optJSONArray("items")?.let { a ->
            n.items = (0 until a.length()).map { itemFromJson(a.getJSONObject(it)) }.toMutableList()
        }
        return n
    }
}
