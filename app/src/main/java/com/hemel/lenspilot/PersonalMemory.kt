package com.hemel.lenspilot

import android.content.Context
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v78: ব্যক্তিগতকরণ (Personalization) — Claude-এর memory-র মতো, তবে সবকিছু ফোনে।
 *
 *  - দীর্ঘমেয়াদি স্মৃতি: ছোট ছোট নোট (পছন্দ / অপছন্দ / ইচ্ছা / এড়াতে চাই / লক্ষ্য / উত্তরের ধরন / পরিচয়)।
 *    সর্বোচ্চ [MAX_ITEMS]টা, প্রতিটা এক লাইন। ইউজার সেটিংসে দেখে, এডিট করে, পিন করে, মুছে ফেলতে পারে।
 *  - শেখা: সব মোডের (চ্যাট / আলোচনা / মাল্টি-এজেন্ট / ব্রাউজার / লার্নিং / এজেন্ট-বাবল) ইউজার-বার্তা এখানে জমে।
 *    প্রতি বার্তায় কল নয় — [FLUSH_EVERY]টা জমলে বা অ্যাপ ছাড়ার সময় একটাই সস্তা কল (/api/memory/update)।
 *  - ব্যবহার: Claude-এর context window-এর মতো সব স্মৃতি পাঠানো হয় না — প্রতি অনুরোধে শুধু প্রাসঙ্গিক ≤[MAX_INJECT]টা
 *    (পিন করা + সবসময়-প্রযোজ্য ধরন + বার্তার সাথে শব্দ মেলে এমন)। স্মৃতি খালি বা বন্ধ থাকলে কিছুই যায় না = টোকেন ০।
 */
object PersonalMemory {

    data class Item(val id: String, val text: String, val cat: String, val pinned: Boolean, val ts: Long)

    const val MAX_ITEMS = 60
    private const val MAX_INJECT = 8
    private const val MAX_INJECT_CHARS = 700
    private const val FLUSH_EVERY = 6
    private const val QUEUE_MAX = 14
    private const val FILE = "lenspilot_personal_memory"

    val CATS = listOf("like", "dislike", "want", "avoid", "goal", "style", "about")
    fun emoji(cat: String) = when (cat) {
        "like" -> "👍"; "dislike" -> "👎"; "want" -> "✨"; "avoid" -> "🚫"
        "goal" -> "🎯"; "style" -> "💬"; else -> "👤"
    }

    // যেগুলো প্রায় সব উত্তরেই কাজে লাগে (বাকিগুলো শুধু বার্তার সাথে মিললে যায়)
    private val ALWAYS_ON = setOf("style", "about", "avoid", "dislike")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushing = AtomicBoolean(false)
    private val lock = Any()

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- চালু/বন্ধ ----------------

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) sp(ctx).edit().remove("queue").apply()
    }

    // ---------------- স্মৃতির তালিকা ----------------

    fun items(ctx: Context): List<Item> = synchronized(lock) {
        val raw = sp(ctx).getString("items", null) ?: return emptyList()
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("text", "").trim()
                if (t.isEmpty()) null
                else Item(o.optString("id", UUID.randomUUID().toString().take(8)), t,
                    o.optString("cat", "about"), o.optBoolean("pinned", false), o.optLong("ts", 0L))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        for (it in list) arr.put(JSONObject().apply {
            put("id", it.id); put("text", it.text); put("cat", it.cat); put("pinned", it.pinned); put("ts", it.ts)
        })
        sp(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun add(ctx: Context, text: String, cat: String = "about", pinned: Boolean = false) = synchronized(lock) {
        val t = text.trim().take(140)
        if (t.isEmpty()) return@synchronized
        val list = items(ctx).toMutableList()
        if (list.any { it.text.equals(t, ignoreCase = true) }) return@synchronized
        list.add(Item(UUID.randomUUID().toString().take(8), t, if (cat in CATS) cat else "about", pinned, System.currentTimeMillis()))
        while (list.size > MAX_ITEMS) {
            val victim = list.filter { !it.pinned }.minByOrNull { it.ts } ?: break
            list.remove(victim)
        }
        save(ctx, list)
    }

    fun update(ctx: Context, id: String, text: String? = null, pinned: Boolean? = null) = synchronized(lock) {
        val list = items(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        val old = list[i]
        val t = text?.trim()?.take(140)?.takeIf { it.isNotEmpty() } ?: old.text
        list[i] = old.copy(text = t, pinned = pinned ?: old.pinned, ts = if (text != null) System.currentTimeMillis() else old.ts)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        save(ctx, items(ctx).filter { it.id != id })
    }

    fun clearAll(ctx: Context) = synchronized(lock) {
        sp(ctx).edit().remove("items").remove("queue").apply()
    }

    // ---------------- শেখা: বার্তা জমানো → একসাথে পাঠানো ----------------

    private val SECRET_HINT = Regex("(\\d{9,})|password|passcode|\\botp\\b|পাসওয়ার্ড|ওটিপি", RegexOption.IGNORE_CASE)

    /** যেকোনো মোডে ইউজার বার্তা পাঠালে এটা ডাকুন। [mode]: chat/discussion/multi/browser/learning/agent… */
    fun noteUserMessage(ctx: Context, mode: String, rawText: String) {
        if (!enabled(ctx)) return
        // "📎 ফাইল" লাইন বাদ; খুব ছোট বা পাসওয়ার্ড/নম্বর-ধরনের বার্তা জমে না
        val t = rawText.lineSequence().filterNot { it.startsWith("📎") }.joinToString(" ").trim().take(280)
        if (t.length < 12 || SECRET_HINT.containsMatchIn(t)) return
        val shouldFlush = synchronized(lock) {
            val q = readQueue(ctx)
            q.add(JSONObject().apply { put("mode", mode); put("text", t) })
            while (q.size > QUEUE_MAX) q.removeAt(0)
            writeQueue(ctx, q)
            q.size >= FLUSH_EVERY
        }
        if (shouldFlush) flush(ctx)
    }

    /** অ্যাপ ছাড়ার সময় (onStop): অন্তত ৩টা জমে থাকলে পাঠায়। */
    fun flushIfPending(ctx: Context) {
        if (!enabled(ctx)) return
        val n = synchronized(lock) { readQueue(ctx).size }
        if (n >= 3) flush(ctx)
    }

    private fun readQueue(ctx: Context): MutableList<JSONObject> {
        val raw = sp(ctx).getString("queue", null) ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeQueue(ctx: Context, q: List<JSONObject>) {
        val a = JSONArray(); q.forEach { a.put(it) }
        sp(ctx).edit().putString("queue", a.toString()).apply()
    }

    private fun flush(ctx: Context) {
        if (!flushing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val (sent, mem) = synchronized(lock) { readQueue(app).toList() to items(app) }
        if (sent.isEmpty()) { flushing.set(false); return }
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("messages", JSONArray().also { a -> sent.forEach { a.put(it) } })
                    put("memory", JSONArray().also { a ->
                        mem.forEach { a.put(JSONObject().apply { put("id", it.id); put("text", it.text); put("cat", it.cat) }) }
                    })
                }.toString()
                val res = ApiClient.callAuthed(app, app.getString(R.string.space_base_url), "/api/memory/update", body)
                res.onSuccess { raw ->
                    val ops = runCatching { JSONObject(raw).optJSONArray("ops") }.getOrNull() ?: JSONArray()
                    applyOps(app, ops)
                    // পাঠানো বার্তাগুলো কিউ থেকে বাদ (এর মধ্যে নতুন যা এসেছে তা থাকে)
                    synchronized(lock) {
                        val cur = readQueue(app)
                        val sentTexts = sent.map { it.optString("text") }.toMutableList()
                        val keep = cur.filter { c -> !sentTexts.remove(c.optString("text")) }
                        writeQueue(app, keep)
                    }
                }
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun applyOps(ctx: Context, ops: JSONArray) {
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            when (o.optString("op")) {
                "add" -> add(ctx, o.optString("text"), o.optString("cat", "about"))
                "update" -> update(ctx, o.optString("id"), text = o.optString("text").ifBlank { null })
                "delete" -> {
                    // পিন করা নোট AI মুছতে পারে না — শুধু ইউজার
                    val id = o.optString("id")
                    if (items(ctx).firstOrNull { it.id == id }?.pinned != true) delete(ctx, id)
                }
            }
        }
    }

    // ---------------- ব্যবহার: প্রাসঙ্গিক কয়েক লাইন ----------------

    private fun words(s: String): Set<String> {
        val out = HashSet<String>()
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch)
            else { if (sb.length >= 3) out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.length >= 3) out.add(sb.toString())
        return out
    }

    /** সার্ভারে পাঠানোর ছোট ব্লক (প্রতি লাইনে একটা নোট), বা null। */
    fun relevantFor(ctx: Context, message: String): String? {
        if (!enabled(ctx)) return null
        val all = items(ctx)
        if (all.isEmpty()) return null
        val mw = words(message)
        val scored = all.map { it to score(it, mw) }.filter { it.second > 0 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        val sb = StringBuilder()
        var n = 0
        for ((it, _) in scored) {
            val line = it.text
            if (n >= MAX_INJECT || sb.length + line.length > MAX_INJECT_CHARS) break
            sb.append(line).append('\n'); n++
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun score(it: Item, mw: Set<String>): Int {
        var s = 0
        if (it.pinned) s += 100
        if (it.cat in ALWAYS_ON) s += 20
        val overlap = words(it.text).count { w -> w in mw }
        s += overlap * 30
        return if (s == 0) 0 else s + (it.ts / 1_000_000_000_000L).toInt() // সামান্য নতুনত্ব-ঝোঁক
    }

    /** অনুরোধের JSON-এ `personal_memory` যোগ (থাকলে)। */
    fun attach(ctx: Context, body: JSONObject, message: String) {
        relevantFor(ctx, message)?.let { body.put("personal_memory", it) }
    }
}
