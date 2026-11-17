package com.hemel.lenspilot.browser

import org.json.JSONObject
import java.util.Locale

/**
 * v161 — running cost of the browser agent's current task, both agents together.
 *
 * The server sends, with every step, the real token counts the provider returned and the dollar cost (tokens × the model's list price;
 * "usd" = null when the price of that model is not known — then "$ ?" is shown, never a guess). This object only ADDS UP those numbers
 * and formats them; the two places that show them are the 💰 bubble of the Auto prompt chat and the small $ icon under every message of the
 * live chat. A new task starts from zero ([reset]).
 */
object RunCost {
    private class Tally {
        var calls = 0; var tin = 0L; var tout = 0L; var cached = 0L; var usd = 0.0; var unknown = false; var model = ""
        fun clear() { calls = 0; tin = 0; tout = 0; cached = 0; usd = 0.0; unknown = false; model = "" }
    }

    private val a1 = Tally()
    private val a2 = Tally()
    private var steps = 0
    private var lastStep = ""

    @Synchronized fun reset() { a1.clear(); a2.clear(); steps = 0; lastStep = "" }

    @Synchronized fun hasData(): Boolean = steps > 0

    /** Adds one step's cost JSON ({a1:{model,calls,tin,tout,cached,usd}, a2:{…}}). Returns false when it held nothing. */
    @Synchronized fun add(step: Int, costJson: String): Boolean {
        if (costJson.isBlank()) return false
        val o = try { JSONObject(costJson) } catch (e: Exception) { return false }
        val j1 = o.optJSONObject("a1"); val j2 = o.optJSONObject("a2")
        if (j1 == null && j2 == null) return false
        steps++
        fun add(t: Tally, j: JSONObject?) {
            if (j == null) return
            t.calls += j.optInt("calls"); t.tin += j.optLong("tin"); t.tout += j.optLong("tout"); t.cached += j.optLong("cached")
            if (j.isNull("usd")) t.unknown = true else t.usd += j.optDouble("usd", 0.0)
            if (t.model.isBlank()) t.model = j.optString("model")
        }
        add(a1, j1); add(a2, j2)
        fun line(name: String, j: JSONObject?): String? = j?.let {
            "$name · ${it.optString("model").ifBlank { "?" }}\n   in ${n(it.optLong("tin"))} · out ${n(it.optLong("tout"))} · ${usd(if (it.isNull("usd")) null else it.optDouble("usd"))}"
        }
        lastStep = "Step #$step\n" + listOfNotNull(line("Agent 1", j1), line("Agent 2", j2)).joinToString("\n")
        return true
    }

    /** This step only (for the Auto prompt chat). */
    @Synchronized fun stepText(): String = lastStep

    /** Everything so far: per agent and both together. Empty when nothing was recorded yet. */
    @Synchronized fun totalText(): String {
        if (steps == 0) return ""
        fun tot(name: String, t: Tally): String? = if (t.calls == 0) null else
            "$name · ${t.model.ifBlank { "?" }}\n   in ${n(t.tin)} · out ${n(t.tout)} · ${usd(if (t.unknown && t.usd == 0.0) null else t.usd)}"
        val sumUsd = a1.usd + a2.usd
        val anyUnknown = a1.unknown || a2.unknown
        val sb = StringBuilder("Cost so far · $steps steps\n")
        listOfNotNull(tot("Agent 1", a1), tot("Agent 2", a2)).forEach { sb.append(it).append('\n') }
        sb.append("Both · in ${n(a1.tin + a2.tin)} · out ${n(a1.tout + a2.tout)} · ${usd(if (anyUnknown && sumUsd == 0.0) null else sumUsd)}")
        if (anyUnknown && sumUsd > 0.0) sb.append(" (+ a model with unknown price)")
        return sb.toString()
    }

    private fun n(v: Long) = String.format(Locale.US, "%,d", v)
    private fun usd(v: Double?): String = when {
        v == null -> "$ ?"
        v < 0.01 -> "$" + String.format(Locale.US, "%.5f", v)
        else -> "$" + String.format(Locale.US, "%.4f", v)
    }
}
