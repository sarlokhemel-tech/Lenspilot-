package com.hemel.lenspilot.debug

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v167 — "History log": one place that remembers what went wrong (and how it went) in BOTH the automation browser and the
 * screen guide mode. Always on, kept on the phone (no server round trip), newest entries last.
 *
 * Every step is written in plain text that is meant to be copied and pasted as it is: the goal, the page, what the previous move
 * led to, the model and the seconds it took, the model's own answer, what the app did, and — in a PROBLEM line — what was refused,
 * which call timed out, which fallback ran. Nothing here makes a decision; it only records.
 *
 * Passwords never get here: the server already strips field values, and the log never receives typed text (only action types/ids).
 */
object RunHistoryLog {

    private const val FILE_NAME = "run_history.jsonl"
    private const val MAX_BYTES = 700_000L          // older entries are dropped from the front when the file grows past this
    private const val FIELD_CAP = 2_400             // one field (prompt part, reply …) is cut after this many characters
    private val lock = Any()
    @Volatile private var appCtx: Context? = null
    private val timeFmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    class Entry(val time: Long, val source: String, val problem: Boolean, val task: Int, val text: String)

    fun init(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    private fun file(): File? = appCtx?.let { File(it.filesDir, FILE_NAME) }

    // ------------------------------------------------------------------ recording

    private var taskNo = 0
    private var taskSource = ""

    /** A new task begins (browser or guide). */
    fun taskStart(ctx: Context, source: String, goal: String, system: String) {
        init(ctx)
        synchronized(lock) { taskNo++; taskSource = source }
        add(source, false, "TASK STARTED\nGOAL: ${cap(goal, 600)}\nMODE: $system")
    }

    fun taskEnd(source: String, how: String, problem: Boolean = false) {
        add(source, problem, "TASK ENDED — $how")
    }

    /** Something went wrong outside a normal step (network, token limit, parse failure …). */
    fun problem(source: String, step: Int, what: String) {
        add(source, true, "step $step · PROBLEM: ${cap(what, 600)}")
    }

    /** One automation-browser step, after the server answered. */
    fun browserStep(
        step: Int, goal: String, system: String, url: String, title: String, shown: Int,
        previousOutcome: String?, timing: String, status: String, degraded: Boolean, warn: String?,
        blocked: String?, diagJson: String, message: String, screen: String, reply: String,
        actionCode: String, note: String, hadImage: Boolean
    ) {
        val diag = parse(diagJson)
        val problems = ArrayList<String>()
        if (degraded) problems.add("the server could not produce a usable move (a short wait was sent instead)")
        if (!warn.isNullOrBlank()) problems.add("warning to the model: ${cap(warn, 240)}")
        if (!blocked.isNullOrBlank()) problems.add("the run stopped and asked the user: ${cap(blocked, 240)}")
        diagProblems(diag, problems)
        val sb = StringBuilder()
        sb.append("step $step · BROWSER · $system\n")
        sb.append("GOAL: ").append(cap(goal, 300)).append('\n')
        sb.append("PAGE: ").append(cap(title, 90)).append(" | ").append(cap(url, 200)).append(" | $shown items listed")
        if (hadImage) sb.append(" | screenshot sent")
        sb.append('\n')
        if (!previousOutcome.isNullOrBlank()) sb.append("WHAT THE PREVIOUS MOVE LED TO: ").append(cap(previousOutcome, 500)).append('\n')
        sb.append("TIME: ").append(timing.ifBlank { "-" }).append(modelLine(diag)).append('\n')
        if (message.isNotBlank()) sb.append("QUESTION (task + what was already done):\n").append(cap(message, FIELD_CAP)).append('\n')
        if (screen.isNotBlank()) sb.append("SCREEN DATA (first lines of what the model saw):\n").append(firstLines(screen, 24)).append('\n')
        sb.append("MODEL ANSWER:\n").append(cap(reply.ifBlank { note }, FIELD_CAP)).append('\n')
        sb.append("APP DID (").append(status).append("): ").append(cap(actionCode.ifBlank { "(no action)" }, 500)).append('\n')
        events(diag, sb)
        finish("browser", sb, problems)
    }

    /** One screen-guide step, after the server answered. [r] = the whole /api/guide-v3 result. */
    fun guideStep(no: Int, goal: String, system: String, r: JSONObject, elementCount: Int) {
        val chat = r.optJSONObject("chat")
        val diag = chat?.optJSONObject("diag")
        val problems = ArrayList<String>()
        if (r.optBoolean("give_up", false)) problems.add("the model gave up on this screen")
        if (r.optBoolean("need_image", false)) problems.add("the model asked for a screenshot (the screen list was not enough)")
        val extra = chat?.optString("extra").orEmpty()
        if (extra.isNotBlank()) problems.add("a correction was needed: ${cap(extra, 300)}")
        val retryErr = diag?.optString("client_retry_error").orEmpty()
        if (retryErr.isNotBlank()) problems.add("the app's earlier attempt failed: ${cap(retryErr, 200)}")
        if ((chat?.optInt("tries", 1) ?: 1) > 1) problems.add("answered only on try ${chat?.optInt("tries", 1)}")
        val sb = StringBuilder()
        sb.append("step $no · SCREEN GUIDE · $system\n")
        sb.append("GOAL: ").append(cap(goal, 300)).append('\n')
        sb.append("SCREEN: $elementCount elements · ${diag?.optString("screen_source").orEmpty().ifBlank { "tree" }}")
        if (chat?.optBoolean("image", false) == true) sb.append(" | screenshot sent")
        sb.append(" | mode ${diag?.optString("mode").orEmpty().ifBlank { "?" }}\n")
        val msg = chat?.optString("message").orEmpty()
        if (msg.isNotBlank()) sb.append("QUESTION (task + what was already done):\n").append(cap(msg, FIELD_CAP)).append('\n')
        val scr = chat?.optString("screen").orEmpty()
        if (scr.isNotBlank()) sb.append("SCREEN DATA (first lines of what the model saw):\n").append(firstLines(scr, 24)).append('\n')
        sb.append("MODEL ANSWER:\n").append(cap(chat?.optString("reply").orEmpty().ifBlank { r.optString("say") }, FIELD_CAP)).append('\n')
        sb.append("APP SHOWED: ").append(cap(r.optString("say").ifBlank { r.optString("note") }, 300))
        r.optJSONObject("do")?.let { sb.append("\nACTION: ").append(cap(it.toString(), 400)) }
        sb.append('\n')
        finish("guide", sb, problems)
    }

    // ------------------------------------------------------------------ reading

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        synchronized(lock) {
            val f = file()
            if (f != null && f.exists()) {
                f.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            val o = JSONObject(line)
                            out.add(Entry(o.optLong("t"), o.optString("s"), o.optBoolean("p"), o.optInt("n"), o.optString("x")))
                        } catch (e: Exception) { /* skip a damaged line */ }
                    }
                }
            }
        }
        return out
    }

    fun render(list: List<Entry>): String {
        if (list.isEmpty()) return "(the history log is empty)"
        val sb = StringBuilder()
        for (e in list) {
            sb.append("━━ ").append(timeFmt.format(Date(e.time))).append(if (e.problem) "  ⚠ PROBLEM" else "").append(" ━━\n")
            sb.append(e.text.trimEnd()).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** Everything from the latest "TASK STARTED" (of any source) to the end — what to paste when one run went wrong. */
    fun lastTask(all: List<Entry>): List<Entry> {
        val idx = all.indexOfLast { it.text.startsWith("TASK STARTED") }
        return if (idx < 0) all.takeLast(40) else all.subList(idx, all.size)
    }

    fun problemsOnly(all: List<Entry>): List<Entry> = all.filter { it.problem }

    fun count(): Int = entries().size

    fun clear() {
        synchronized(lock) { file()?.delete() }
    }

    // ------------------------------------------------------------------ internals

    private fun add(source: String, problem: Boolean, text: String) {
        val f = file() ?: return
        try {
            synchronized(lock) {
                val o = JSONObject().put("t", System.currentTimeMillis()).put("s", source).put("p", problem).put("n", taskNo).put("x", text)
                f.appendText(o.toString() + "\n")
                if (f.length() > MAX_BYTES) trimFile(f)
            }
        } catch (e: Exception) { /* a log must never crash the app */ }
    }

    private fun trimFile(f: File) {
        val lines = f.readLines()
        var size = 0L
        val keep = ArrayList<String>()
        for (l in lines.asReversed()) {
            size += l.length + 1
            if (size > MAX_BYTES * 3 / 4) break
            keep.add(l)
        }
        f.writeText(keep.asReversed().joinToString("\n") + "\n")
    }

    private fun finish(source: String, sb: StringBuilder, problems: List<String>) {
        if (problems.isNotEmpty()) {
            sb.append("PROBLEM:\n")
            problems.forEach { sb.append("  ✗ ").append(it).append('\n') }
        } else {
            sb.append("OK — no problem noticed on this step\n")
        }
        add(source, problems.isNotEmpty(), sb.toString())
    }

    private fun parse(json: String): JSONObject? = try { if (json.isBlank()) null else JSONObject(json) } catch (e: Exception) { null }

    private fun modelLine(d: JSONObject?): String {
        if (d == null) return ""
        val m = d.optString("model")
        val mode = d.optString("mode")
        val secs = d.optDouble("secs", -1.0)
        return " · server ${if (secs >= 0) "%.1fs".format(secs) else "?"}" + (if (mode.isNotBlank()) " · mode $mode" else "") +
            (if (m.isNotBlank() && m != "null") " · model $m" else "")
    }

    private fun diagProblems(d: JSONObject?, into: MutableList<String>) {
        val ev = d?.optJSONArray("events") ?: return
        for (i in 0 until ev.length()) {
            val t = ev.optString(i)
            if (t.contains("stopped") || t.contains("refused") || t.contains("NO ANSWER") || t.contains("could not be used") ||
                t.contains("DEGRADED") || t.contains("falling back")) into.add(cap(t, 320))
        }
    }

    private fun events(d: JSONObject?, sb: StringBuilder) {
        val ev = d?.optJSONArray("events") ?: return
        if (ev.length() == 0) return
        sb.append("SERVER NOTES:\n")
        for (i in 0 until ev.length()) sb.append("  · ").append(cap(ev.optString(i), 320)).append('\n')
    }

    private fun firstLines(s: String, n: Int): String {
        val lines = s.lines()
        val head = lines.take(n).joinToString("\n") { cap(it, 160) }
        return if (lines.size > n) head + "\n… (${lines.size - n} more lines)" else head
    }

    private fun cap(s: String, n: Int): String = if (s.length <= n) s else s.take(n) + "…"
}
