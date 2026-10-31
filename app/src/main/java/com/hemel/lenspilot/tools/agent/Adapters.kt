package com.hemel.lenspilot.tools.agent

import android.content.Context
import com.hemel.lenspilot.security.Redactor
import com.hemel.lenspilot.tools.StorageManager
import com.hemel.lenspilot.tools.ToolsFormat
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.browser.BrowserWorkspace
import com.hemel.lenspilot.tools.browser.BrowserWorkspaces
import com.hemel.lenspilot.tools.shell.ShellBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * একটা ধাপের ছোট, গঠনবদ্ধ ফল — কখনো কাঁচা মেগাবাইট নয়।
 * [line] ≤ ~১৬০ অক্ষরের এক লাইন (ContextWindowStore-এ যায়); [full] ≤ ~১৪০০ (পরের decide-এর last_result)।
 * দুটোই Redactor-এর ভেতর দিয়ে গেছে।
 */
class StepResult(
    val ok: Boolean,
    line: String,
    full: String = line,
    val needsYou: Boolean = false,
    val denied: Boolean = false
) {
    val line: String = clean(line, 160)
    val full: String = clean(full, 1400)

    companion object {
        private fun clean(s: String, max: Int): String {
            val r = Redactor.redact(s).replace('\r', ' ').trim()
            return if (r.length <= max) r else r.take(max - 1) + "…"
        }

        fun fail(msg: String) = StepResult(false, msg)
    }
}

/** আর্গুমেন্টের নামের আলাদা-আলাদা রূপ মেনে নেয় (মডেল স্টোরেজ-আর্গের নাম নিজে ঠিক করে)। */
internal fun JSONObject.argOf(vararg keys: String): String {
    for (k in keys) {
        val v = optString(k, "")
        if (v.isNotEmpty()) return v
    }
    return ""
}

// ====================================================================================== Storage
/** StorageManager-এর অ্যাকশন: list, find, read(head), write, mkdir, move, delete, unzip, zip, export। */
object StorageAdapter {
    private const val READ_BYTES = 8 * 1024
    private const val WRITE_MAX = 1_000_000
    private const val LIST_MAX = 25

    suspend fun run(ctx: Context, sid: String, step: PlanStep): StepResult = withContext(Dispatchers.IO) {
        try {
            exec(ctx.applicationContext, sid, step)
        } catch (e: SecurityException) {
            StepResult.fail("storage.${step.action}: নিষিদ্ধ — ওয়ার্কস্পেসের বাইরে")
        } catch (e: java.io.FileNotFoundException) {
            StepResult.fail("storage.${step.action}: ফাইল নেই")
        } catch (e: Exception) {
            StepResult.fail("storage.${step.action}: " + (e.message ?: e.javaClass.simpleName).take(100))
        }
    }

    private fun exec(app: Context, sid: String, step: PlanStep): StepResult {
        val mgr = StorageManager(ToolsPaths.workspaceDir(app, sid))
        val a = step.args
        when (step.action) {
            "list" -> {
                val rel = a.argOf("path", "dir", "folder")
                val items = mgr.list(rel)
                val names = items.take(LIST_MAX).joinToString(", ") { if (it.isDir) it.name + "/" else it.name }
                val more = if (items.size > LIST_MAX) " … +" + (items.size - LIST_MAX) else ""
                val head = "list /$rel: ${items.size} আইটেম"
                return StepResult(true, head + (if (names.isNotEmpty()) " — $names$more" else ""))
            }
            "find" -> {
                val q = a.argOf("query", "name", "pattern", "q")
                if (q.isEmpty()) return StepResult.fail("storage.find: query দরকার")
                val hits = mgr.search(a.argOf("path", "dir", "folder"), q)
                val names = hits.take(15).joinToString(", ") { it.relPath }
                return StepResult(true, "find \"${q.take(30)}\": ${hits.size} মিলেছে" + (if (names.isNotEmpty()) " — $names" else ""))
            }
            "read" -> {
                val rel = a.argOf("path", "file", "name")
                val f = mgr.resolve(rel)
                if (!f.isFile) return StepResult.fail("storage.read: ফাইল নয়/নেই ($rel)")
                val buf = ByteArray(READ_BYTES)
                val n = FileInputStream(f).use { it.read(buf) }.coerceAtLeast(0)
                var binary = false
                for (i in 0 until minOf(n, 512)) if (buf[i].toInt() == 0) { binary = true; break }
                if (binary) return StepResult(true, "read $rel: বাইনারি, ${ToolsFormat.size(f.length())}")
                val want = a.optInt("max_chars", 1200).coerceIn(100, 3000)
                val text = String(buf, 0, n, Charsets.UTF_8).take(want)
                val flat = text.replace('\n', ' ')
                return StepResult(true, "read $rel (${ToolsFormat.size(f.length())}): ${flat.take(110)}", "read $rel (${ToolsFormat.size(f.length())}, প্রথম ${text.length} অক্ষর):\n$text")
            }
            "write" -> {
                val rel = a.argOf("path", "file", "name")
                if (rel.isEmpty()) return StepResult.fail("storage.write: path দরকার")
                val content = a.argOf("content", "text", "data")
                if (content.length > WRITE_MAX) return StepResult.fail("storage.write: লেখা ১ MB-এর বেশি")
                val f = mgr.resolve(rel)
                if (f.isDirectory) return StepResult.fail("storage.write: এটা ফোল্ডার")
                f.parentFile?.mkdirs()
                if (a.optBoolean("append", false)) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
                return StepResult(true, "write $rel ✓ (${ToolsFormat.size(f.length())})")
            }
            "mkdir" -> {
                val rel = a.argOf("path", "dir", "folder", "name")
                if (rel.isEmpty()) return StepResult.fail("storage.mkdir: path দরকার")
                val d = mgr.resolve(rel)
                if (d.isFile) return StepResult.fail("storage.mkdir: এই নামে ফাইল আছে")
                if (!d.exists() && !d.mkdirs()) return StepResult.fail("storage.mkdir: বানানো যায়নি")
                return StepResult(true, "mkdir $rel ✓")
            }
            "move" -> {
                val from = a.argOf("from", "src", "source", "path")
                val to = a.argOf("to", "dest", "destination", "target")
                if (from.isEmpty() || to.isEmpty()) return StepResult.fail("storage.move: from ও to দরকার")
                val src = mgr.resolve(from)
                if (mgr.relOf(src).isEmpty()) return StepResult.fail("storage.move: রুট সরানো যায় না")
                if (!src.exists()) return StepResult.fail("storage.move: উৎস নেই ($from)")
                var dst = mgr.resolve(to)
                if (dst.isDirectory) dst = File(dst, src.name)
                if (dst.exists()) return StepResult.fail("storage.move: গন্তব্যে আগে থেকেই আছে")
                if (dst.path.startsWith(src.path + File.separator)) return StepResult.fail("storage.move: নিজের ভেতরে সরানো যায় না")
                dst.parentFile?.mkdirs()
                Files.move(src.toPath(), dst.toPath())
                return StepResult(true, "move $from → ${mgr.relOf(dst)} ✓")
            }
            "delete" -> {
                val rel = a.argOf("path", "file", "name")
                if (rel.isEmpty()) return StepResult.fail("storage.delete: path দরকার")
                mgr.delete(rel)
                return StepResult(true, "delete $rel ✓")
            }
            "unzip" -> {
                val rel = a.argOf("path", "file", "zip", "name")
                val n = mgr.unzip(rel)
                return StepResult(true, "unzip $rel ✓ ($n আইটেম)")
            }
            "zip" -> {
                val rel = a.argOf("path", "dir", "folder", "name")
                val out = mgr.zipFolder(rel)
                return StepResult(true, "zip $rel → $out ✓")
            }
            "export" -> {
                val rel = a.argOf("path", "file", "name")
                val res = mgr.exportToDownloads(app, rel)
                return StepResult(true, "export $rel → ${res.take(80)} ✓")
            }
        }
        return StepResult.fail("storage: অজানা অ্যাকশন '${step.action.take(20)}'")
    }

    /** UI-র Storage-ট্যাবের জন্য: রুটের ছোট তালিকা (ব্লকিং — IO থ্রেডে ডাকুন)। */
    fun peek(ctx: Context, sid: String, max: Int = 40): List<String> {
        return try {
            val mgr = StorageManager(ToolsPaths.workspaceDir(ctx.applicationContext, sid))
            mgr.list("").take(max).map { (if (it.isDir) "📁 " else "📄 ") + it.name + (if (it.isDir) "" else "  · " + ToolsFormat.size(it.size)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ====================================================================================== Shell
/** ShellEngine + ShellBus-এর টাইপিং-অ্যানিমেশন। ফল: exit কোড + শেষ কয়েক লাইন। */
object ShellAdapter {
    suspend fun run(ctx: Context, sid: String, step: PlanStep): StepResult {
        val cmd = step.args.optString("command", "").trim()
        if (cmd.isEmpty()) return StepResult.fail("shell.run: command দরকার")
        val app = ctx.applicationContext
        val sess = try { ShellBus.session(app, sid) } catch (e: Exception) { return StepResult.fail("shell: সেশন খোলা যায়নি") }
        // UI খোলা থাকলে লিসেনার বসা পর্যন্ত একটু অপেক্ষা — নইলে টাইপিং-অ্যানিমেশন দেখা যায় না
        if (ToolsAgents.hasViewer(sid) && !sess.hasListener()) {
            var waited = 0
            while (waited < 800 && !sess.hasListener()) { delay(100); waited += 100 }
        }
        val r = try {
            ShellBus.typeAndRun(app, sid, cmd)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return StepResult.fail("shell: " + (e.message ?: e.javaClass.simpleName).take(100))
        }
        val tail = sess.tailText(10).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val last = tail.lastOrNull { !it.endsWith("$") } ?: tail.lastOrNull().orEmpty()
        val full = "\$ ${cmd.take(120)}\n" + tail.takeLast(6).joinToString("\n") { it.take(160) }
        return when {
            r.cancelled -> StepResult(false, "shell: রদ হয়েছে (Stop)", full)
            r.code == 0 -> StepResult(true, "exit=0 ${last.take(110)}", full)
            else -> StepResult(false, "exit=${r.code} ${last.take(110)}", full)
        }
    }
}

// ====================================================================================== Browser
/** BrowserWorkspace (ধাপ ৫)-এর মোড়ক। ব্রাউজার না খোলা থাকলে খোলার চেষ্টা করে। */
object BrowserAdapter {
    private val FAIL_PREFIX = listOf("stale", "not_found", "not_interactable", "no_effect", "error")

    private suspend fun ensure(ctx: Context, sid: String, url: String?): BrowserWorkspace? {
        BrowserWorkspaces.current(sid)?.let { return it }
        try {
            BrowserWorkspaces.launch(ctx.applicationContext, sid, url)
        } catch (e: Exception) {
            return null
        }
        var waited = 0
        while (waited < 20_000) {
            delay(500)
            waited += 500
            BrowserWorkspaces.current(sid)?.let { return it }
        }
        return null
    }

    suspend fun run(ctx: Context, sid: String, step: PlanStep, observeAfter: Boolean): StepResult {
        val a = step.args
        val firstUrl = if (step.action == "navigate") a.argOf("url") else null
        val ws = ensure(ctx, sid, firstUrl)
            ?: return StepResult.fail("browser: ব্রাউজার খোলেনি (অ্যাপ সামনে না থাকলে খোলা যায় না)")
        val text: String = when (step.action) {
            "navigate" -> {
                val u = a.argOf("url")
                if (u.isEmpty()) return StepResult.fail("browser.navigate: url দরকার")
                ws.open(u)
            }
            "read" -> {
                val o = ws.observe(BrowserWorkspaces.DEFAULT_OBSERVE_CHARS)
                if (o.startsWith("error(") || o.startsWith("observe_failed")) return StepResult.fail("browser.read: " + o.take(80))
                val first = o.lineSequence().firstOrNull().orEmpty()
                return StepResult(true, "read ${first.take(100)}", o.take(1400))
            }
            else -> {
                val o = JSONObject(a.toString()).put("type", step.action)
                ws.act(o)
            }
        }
        val failed = isFailed(text)
        val needs = text.contains("needs_you(")
        if (failed || needs) return StepResult(false, text.take(160), text, needsYou = needs)
        if (!observeAfter) return StepResult(true, text.take(160), text)
        val obs = try { ws.observe(1200) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
        return StepResult(true, text.take(160), text + (if (obs.isNotEmpty()) "\n" + obs else ""))
    }

    private fun isFailed(text: String): Boolean {
        if (text.startsWith("error(")) return true
        val tail = text.substringAfter("→ ", text).trimStart()
        return FAIL_PREFIX.any { tail.startsWith(it) }
    }
}
