package com.hemel.lenspilot.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser Agent v2 — ডেটা-মডেল (সার্ভারের /api/browser-plan ও /api/browser-action `agent_v:2`-এর সাথে মেলানো)।
 * সব JSON-ভিত্তিক রাখা হয়েছে যাতে সার্ভার-কনট্র্যাক্ট বদলালে এখানে ছোট বদল লাগে।
 */

/** lp_observe.js-এর ফল (একটাই কলে সব)। [raw] সার্ভারে হুবহু পাঠানো হয়। */
data class Observation(
    val raw: JSONObject,
    val url: String,
    val title: String,
    val epoch: Int,
    val layer: String,
    val hash: String,
    val captchaFrame: Boolean,
    val more: Boolean,
    val shown: Int,
    val offset: Int
) {
    companion object {
        fun parse(json: String): Observation? = try {
            val o = JSONObject(json)
            Observation(
                raw = o,
                url = o.optString("url"),
                title = o.optString("title"),
                epoch = o.optInt("epoch"),
                layer = o.optString("layer", "page"),
                hash = o.optString("hash"),
                captchaFrame = o.optBoolean("captcha_frame", false),
                more = o.optBoolean("more", false),
                shown = o.optJSONArray("elements")?.length() ?: 0,
                offset = o.optInt("offset", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** সার্ভার-নির্ধারিত একটা অ্যাকশন (ক্লায়েন্ট সরাসরি JSON-ই পড়ে — মডেল-বানানো কিছু বিশ্বাস করে না, সার্ভার আগেই যাচাই করেছে)। */
data class AgentAction(val json: JSONObject) {
    val type: String get() = json.optString("type")
    val id: String? get() = json.optString("id", "").ifBlank { null }
    val irreversible: Boolean get() = json.optBoolean("irreversible", false)
    /** ইতিহাসের জন্য ছোট বিবরণ — পেজের লেখা বা টাইপ করা লেখা এখানে রাখা হয় না। */
    fun brief(): String = when (type) {
        "navigate" -> "navigate " + json.optString("url").take(60)
        "search" -> "search"
        "type" -> "type ${id ?: ""}(${json.optString("text").length} chars)"
        "press" -> "press ${json.optString("key")}"
        "select" -> "select ${id ?: ""}"
        "scroll" -> "scroll ${json.optString("dir", json.optString("to"))}"
        else -> "$type ${id ?: ""}".trim()
    }
}

data class AgentDecision(
    val status: String,
    val actions: List<AgentAction>,
    val note: String,
    val evidence: String?,
    val blockedKind: String?,
    val blockedMessage: String?,
    val pendingActions: List<AgentAction>,
    val facts: JSONObject,
    val warn: String?,
    val supervisorNote: String?,
    val degraded: Boolean,
    val visionUsed: Int?
) {
    companion object {
        fun parse(o: JSONObject): AgentDecision {
            fun acts(a: JSONArray?): List<AgentAction> =
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::AgentAction) }
            val blocked = o.optJSONObject("blocked")
            return AgentDecision(
                status = o.optString("status", "continue"),
                actions = acts(o.optJSONArray("actions")),
                note = o.optString("note", ""),
                evidence = o.optString("evidence", "").ifBlank { null },
                blockedKind = blocked?.optString("kind"),
                blockedMessage = blocked?.optString("message"),
                pendingActions = acts(blocked?.optJSONArray("pending_actions")),
                facts = o.optJSONObject("facts") ?: JSONObject(),
                warn = o.optString("warn", "").ifBlank { null },
                supervisorNote = o.optString("supervisor_note", "").ifBlank { null },
                degraded = o.optBoolean("degraded", false),
                visionUsed = if (o.has("vision_used") && !o.isNull("vision_used")) o.optInt("vision_used") else null
            )
        }
    }
}

/** একটা অ্যাকশনের পরের মাপা ফল — তথ্য হিসেবে; সিদ্ধান্ত নেয় মডেল। */
data class Outcome(
    val result: String,                 // ok | stale | not_found | not_interactable | no_effect | error
    val flags: List<String> = emptyList(),
    val detail: String = "",
    val value: String? = null,
    val read: String? = null,
    val extra: String = ""
) {
    val failed: Boolean get() = result != "ok"
    /** হিস্ট্রি/প্রম্পটের জন্য: `click e12.3 → dom_changed(+14/-2), modal_opened` */
    fun text(actionBrief: String): String {
        val parts = ArrayList<String>()
        if (result != "ok") parts.add(result + if (detail.isNotBlank()) "($detail)" else "")
        parts.addAll(flags)
        if (!value.isNullOrBlank()) parts.add("value=\"${value.take(40)}\"")
        if (!read.isNullOrBlank()) parts.add("read=\"${read.take(600)}\"")
        if (extra.isNotBlank()) parts.add(extra)
        if (parts.isEmpty()) parts.add("ok")
        return "$actionBrief → ${parts.joinToString(", ")}"
    }
}

/**
 * লেজার (ক্লায়েন্টে, ছোট JSON) — "কোনটা শেষ, কোনটা বাকি" মডেলের মাথায় নয়, এখানে।
 * প্রতি কলে সার্ভারে যায়; ডিস্কে টিকে থাকে (অ্যাক্টিভিটি মরলেও resume)।
 */
class Ledger(val json: JSONObject = JSONObject()) {
    var subgoal: String
        get() = json.optString("subgoal", "s1")
        set(v) { json.put("subgoal", v) }
    var vision: Int
        get() = json.optInt("vision_used", 0)
        set(v) { json.put("vision_used", v) }

    private fun loop(): JSONObject = json.optJSONObject("loop") ?: JSONObject().also { json.put("loop", it) }
    val loopIndex: Int get() = loop().optInt("i", 0)
    val doneItems: Int get() = loop().optJSONArray("done_ids")?.length() ?: 0

    fun setLoopTotal(total: Int?) { if (total != null) loop().put("total", total) }
    fun itemDone(label: String) {
        val l = loop()
        val ids = l.optJSONArray("done_ids") ?: JSONArray().also { l.put("done_ids", it) }
        ids.put(label.take(40))
        // সীমিত রাখি — শুধু শেষ ২০টা; মোট গণনা `i`-তে থাকে
        while (ids.length() > 20) ids.remove(0)
        l.put("i", l.optInt("i", 0) + 1)
    }

    fun mergeFacts(f: JSONObject) {
        if (f.length() == 0) return
        val facts = json.optJSONObject("facts") ?: JSONObject().also { json.put("facts", it) }
        val it = f.keys()
        while (it.hasNext()) { val k = it.next(); facts.put(k, f.optString(k)) }
        // সর্বোচ্চ ১২টা fact
        while (facts.length() > 12) { facts.remove(facts.keys().next()) }
    }

    fun noteVisited(url: String) {
        val v = json.optJSONArray("visited") ?: JSONArray().also { json.put("visited", it) }
        val host = try { android.net.Uri.parse(url).host.orEmpty() } catch (e: Exception) { "" }
        if (host.isNotBlank() && (v.length() == 0 || v.optString(v.length() - 1) != host)) v.put(host)
        while (v.length() > 6) v.remove(0)
    }

    fun noteFail(brief: String) {
        val f = json.optJSONArray("fails") ?: JSONArray().also { json.put("fails", it) }
        f.put(brief.take(80))
        while (f.length() > 4) f.remove(0)
    }
}

/** রানের স্থায়ী অবস্থা: প্ল্যান + লেজার + ইতিহাস — অ্যাক্টিভিটি মরলে resume-এর জন্য। */
object AgentStateStore {
    private const val PREFS = "lenspilot_browser_agent_v2"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, runId: String, goal: String, system: String, plan: JSONObject, ledger: Ledger,
             history: List<String>, permissions: JSONObject, url: String = "") {
        val o = JSONObject()
        o.put("run_id", runId); o.put("goal", goal); o.put("system", system)
        o.put("plan", plan); o.put("ledger", ledger.json); o.put("history", JSONArray(history))
        o.put("permissions", permissions); o.put("url", url); o.put("saved_at", System.currentTimeMillis())
        p(c).edit().putString("active", o.toString()).apply()
    }

    fun load(c: Context): JSONObject? = try {
        p(c).getString("active", null)?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    fun clear(c: Context) { p(c).edit().remove("active").apply() }
}
