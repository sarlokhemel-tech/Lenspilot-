package com.hemel.lenspilot.tools.runner

import android.content.Context
import com.hemel.lenspilot.R
import com.hemel.lenspilot.security.SecretVault
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.shell.ShellBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern

enum class Outcome { WAIT, MET, FAILED, NEEDS_YOU, ERROR }

/** একটা পোলের ফল। signature = "অবস্থার ছাপ" — বদলালে ধরা হয় কাজ এগোচ্ছে (stuck-গণনা রিসেট)। */
class PollResult(val outcome: Outcome, val signature: String, val evidence: String = "")

/** page_text detector-এর জন্য ইন্টারফেস — ধাপ ৫-এ ব্রাউজার ওয়ার্কস্পেস এটা বসাবে। */
interface BrowserProbe {
    /** সক্রিয় পেজের দৃশ্যমান টেক্সট; পেজ না থাকলে null। */
    suspend fun pageText(sessionId: String?): String?
}

object BrowserProbes {
    @Volatile
    var provider: BrowserProbe? = null
}

/** প্রতিটি watcher-এর নিজস্ব পোল-অবস্থা (ফাইলের স্থিরতা, শুরুর সময়)। */
class ProbeState {
    val startedAt: Long = System.currentTimeMillis()
    class FileSig(var size: Long, var mtime: Long, var stableSince: Long)
    val files = HashMap<String, FileSig>()
}

/** প্রতিটি detector-এর একবারের পোল। নেটওয়ার্ক/IO ব্যর্থতা ছুঁড়ে দেয় না, ERROR হিসেবে ফেরে। */
object Probes {
    private const val HTTP_CAP = 512L * 1024
    private const val LOG_CAP = 4L * 1024 * 1024

    suspend fun poll(ctx: Context, sessionId: String?, d: Detector, st: ProbeState): PollResult = when (d) {
        is Detector.FileAppears -> withContext(Dispatchers.IO) { fileAppears(ctx, sessionId, d, st) }
        is Detector.HttpJson -> withContext(Dispatchers.IO) { httpJson(ctx, d) }
        is Detector.GithubRun -> withContext(Dispatchers.IO) { githubRun(ctx, d) }
        is Detector.CommandExit -> commandExit(ctx, sessionId, d)
        is Detector.PageText -> pageText(sessionId, d)
        is Detector.Timeout -> timeout(d, st)
    }

    // ---------------------------------------------------------------- file_appears
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Pattern.quote(c.toString()))
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    private val TEMP_SUFFIX = listOf(".crdownload", ".part", ".tmp", ".download")

    private fun fileAppears(ctx: Context, sessionId: String?, d: Detector.FileAppears, st: ProbeState): PollResult {
        val root: File = if (sessionId == null) ToolsPaths.globalWorkspace(ctx) else ToolsPaths.workspaceDir(ctx, sessionId)
        if (!root.exists()) return PollResult(Outcome.WAIT, "no_workspace")
        val rx = globToRegex(d.glob)
        val rootPath = root.absolutePath
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        var stableName: String? = null
        var stableSize = 0L
        val walker = root.walkTopDown().maxDepth(6).onEnter { it.name != ".git" }
        for (f in walker.take(5000)) {
            if (!f.isFile) continue
            val rel = f.absolutePath.removePrefix(rootPath).removePrefix("/")
            if (!rx.matches(rel)) continue
            if (TEMP_SUFFIX.any { rel.endsWith(it, ignoreCase = true) }) continue
            seen.add(rel)
            val size = f.length()
            val mt = f.lastModified()
            val sig = st.files[rel]
            if (sig == null) {
                st.files[rel] = ProbeState.FileSig(size, mt, now)
            } else if (sig.size != size || sig.mtime != mt) {
                sig.size = size; sig.mtime = mt; sig.stableSince = now
            } else if (stableName == null && now - sig.stableSince >= d.stableForS * 1000L) {
                stableName = rel; stableSize = size
            }
        }
        st.files.keys.retainAll(seen)
        if (stableName != null) return PollResult(Outcome.MET, "found", "ফাইল পাওয়া গেছে: $stableName ($stableSize বাইট)")
        if (seen.isEmpty()) return PollResult(Outcome.WAIT, "none")
        return PollResult(Outcome.WAIT, "growing:" + seen.sorted().joinToString(",") + ":" + st.files.values.sumOf { it.size })
    }

    // ---------------------------------------------------------------- http_json
    private fun httpJson(ctx: Context, d: Detector.HttpJson): PollResult {
        val res = try {
            RunnerHttp.call(ctx, d.method, d.url, d.headers, d.body, HTTP_CAP)
        } catch (e: RunnerHttp.SecretMissing) {
            return PollResult(Outcome.NEEDS_YOU, "secret_missing", "হেডারের গোপন রেফারেন্স ভল্টে নেই: ${e.ref}")
        } catch (e: IOException) {
            return PollResult(Outcome.ERROR, "io:" + e.javaClass.simpleName, "নেটওয়ার্ক ত্রুটি: ${e.message ?: e.javaClass.simpleName}")
        }
        if (res.code == 401 || res.code == 403) {
            return PollResult(Outcome.NEEDS_YOU, "auth", "অনুমতি/লগইন দরকার (HTTP ${res.code})")
        }
        if (res.code !in 200..299) return PollResult(Outcome.ERROR, "http:${res.code}", "HTTP ${res.code}")
        val v = RunnerHttp.jsonPath(res.body, d.jsonPath)
            ?: return PollResult(Outcome.WAIT, "path_missing", "json_path এখনো নেই")
        val shown = if (v == JSONObject.NULL) "null" else v.toString()
        return if (RunnerHttp.compare(v, d.op, d.value)) {
            PollResult(Outcome.MET, "met", "${d.jsonPath} = ${shown.take(300)}")
        } else {
            PollResult(Outcome.WAIT, "val:" + shown.take(120), "${d.jsonPath} = ${shown.take(300)}")
        }
    }

    // ---------------------------------------------------------------- github_run
    private fun githubRun(ctx: Context, d: Detector.GithubRun): PollResult {
        val base = (d.apiBase ?: ctx.getString(R.string.runner_github_api_base)).trimEnd('/')
        // টোকেন ভল্টে থাকলে হেডারে যায় (শুধু এই রিকোয়েস্টে); না থাকলে বেনামে — পাবলিক রিপোর জন্য চলে
        val token = SecretVault.get(ctx, SecretVault.GITHUB_TITLE, SecretVault.GITHUB_FIELD)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
        fun get(url: String, cap: Long): RunnerHttp.Res {
            val h = LinkedHashMap(headers)
            if (token != null) h["Authorization"] = "Bearer $token"   // রিজলভের দরকার নেই; লগ/ইভেন্টে যায় না
            return RunnerHttp.call(ctx, "GET", url, h, null, cap)
        }
        try {
            val runUrl = if (d.runId != null) {
                "$base/repos/${d.repo}/actions/runs/${d.runId}"
            } else {
                var u = "$base/repos/${d.repo}/actions/runs?per_page=1"
                val ref = d.ref
                if (ref != null) {
                    u += if (Regex("^[0-9a-fA-F]{40}$").matches(ref)) "&head_sha=$ref" else "&branch=" + URLEncoder.encode(ref, "UTF-8")
                }
                u
            }
            val r = get(runUrl, HTTP_CAP)
            if (r.code == 401 || r.code == 403) return PollResult(Outcome.NEEDS_YOU, "auth", "অনুমতি/লগইন দরকার (HTTP ${r.code}) — GitHub লগইন করুন")
            if (r.code == 404) return PollResult(Outcome.ERROR, "http:404", "রিপো/রান পাওয়া যায়নি (HTTP 404)")
            if (r.code !in 200..299) return PollResult(Outcome.ERROR, "http:${r.code}", "HTTP ${r.code}")
            val root = JSONObject(r.body)
            val run: JSONObject = if (d.runId != null) root else (root.optJSONArray("workflow_runs")?.optJSONObject(0)
                ?: return PollResult(Outcome.WAIT, "no_run", "এখনো কোনো রান শুরু হয়নি"))
            val id = run.optLong("id")
            val status = run.optString("status")
            if (status != "completed") return PollResult(Outcome.WAIT, "run:$id:$status", "রান $id: $status")
            val conclusion = run.optString("conclusion")
            if (conclusion == "success") return PollResult(Outcome.MET, "met", "conclusion=success run=$id")
            return PollResult(Outcome.FAILED, "failed", failureEvidence(::get, token != null, base, d.repo, id, conclusion))
        } catch (e: RunnerHttp.SecretMissing) {
            return PollResult(Outcome.NEEDS_YOU, "secret_missing", "গোপন রেফারেন্স ভল্টে নেই: ${e.ref}")
        } catch (e: IOException) {
            return PollResult(Outcome.ERROR, "io:" + e.javaClass.simpleName, "নেটওয়ার্ক ত্রুটি: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: org.json.JSONException) {
            return PollResult(Outcome.ERROR, "json", "উত্তর পড়া যায়নি")
        }
    }

    /** ব্যর্থ রানের: conclusion + ব্যর্থ job/step-এর নাম + ব্যর্থ job-এর লগের শেষ অংশ। */
    private fun failureEvidence(
        get: (String, Long) -> RunnerHttp.Res,
        hasToken: Boolean,
        base: String,
        repo: String,
        runId: Long,
        conclusion: String
    ): String {
        val sb = StringBuilder("conclusion=$conclusion run=$runId")
        try {
            val jr = get("$base/repos/$repo/actions/runs/$runId/jobs?per_page=30", HTTP_CAP)
            if (jr.code in 200..299) {
                val jobs = JSONObject(jr.body).optJSONArray("jobs")
                var failedJob: JSONObject? = null
                if (jobs != null) {
                    for (i in 0 until jobs.length()) {
                        val j = jobs.optJSONObject(i) ?: continue
                        val c = j.optString("conclusion")
                        if (c == "failure" || c == "timed_out" || c == "cancelled") { failedJob = j; break }
                    }
                }
                if (failedJob != null) {
                    sb.append("\njob: ").append(failedJob.optString("name"))
                    val steps = failedJob.optJSONArray("steps")
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val s = steps.optJSONObject(i) ?: continue
                            if (s.optString("conclusion") == "failure") { sb.append("\nstep: ").append(s.optString("name")); break }
                        }
                    }
                    if (hasToken) {
                        val lr = get("$base/repos/$repo/actions/jobs/${failedJob.optLong("id")}/logs", LOG_CAP)
                        if (lr.code in 200..299 && lr.body.isNotEmpty()) {
                            sb.append("\n--- লগের শেষ অংশ ---\n").append(Evidence.tail(lr.body, 2200))
                        } else {
                            sb.append("\n(লগ পাওয়া যায়নি: HTTP ${lr.code})")
                        }
                    } else {
                        sb.append("\n(লগ পড়তে GitHub লগইন দরকার)")
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("\n(ব্যর্থতার বিবরণ আনা যায়নি)")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- command_exit
    private suspend fun commandExit(ctx: Context, sessionId: String?, d: Detector.CommandExit): PollResult {
        val res = ShellBus.typeAndRun(ctx, sessionId, d.commandLine)
        val tail = ShellBus.get(sessionId)?.tailText(25).orEmpty()
        if (res.code == d.expectedExit) return PollResult(Outcome.MET, "met", "exit=${res.code}\n$tail")
        if (!d.retry || res.cancelled) return PollResult(Outcome.FAILED, "failed", "exit=${res.code} (আশা ছিল ${d.expectedExit})\n$tail")
        return PollResult(Outcome.WAIT, "exit:${res.code}", "exit=${res.code}")
    }

    // ---------------------------------------------------------------- page_text
    private suspend fun pageText(sessionId: String?, d: Detector.PageText): PollResult {
        val probe = BrowserProbes.provider
            ?: return PollResult(Outcome.ERROR, "no_probe", "ব্রাউজার প্রোব সংযুক্ত নেই")
        val text = probe.pageText(sessionId) ?: return PollResult(Outcome.WAIT, "no_page")
        val capped = if (text.length > 100_000) text.take(100_000) else text
        val m = try { Pattern.compile(d.regex).matcher(capped) } catch (e: Exception) {
            return PollResult(Outcome.FAILED, "bad_regex", "regex অবৈধ")
        }
        if (m.find()) {
            val from = maxOf(0, m.start() - 60)
            val to = minOf(capped.length, m.end() + 60)
            return PollResult(Outcome.MET, "met", "পেজে মিলেছে: …" + capped.substring(from, to).replace('\n', ' ') + "…")
        }
        return PollResult(Outcome.WAIT, "p" + capped.length / 500)
    }

    // ---------------------------------------------------------------- timeout
    private fun timeout(d: Detector.Timeout, st: ProbeState): PollResult {
        val elapsed = (System.currentTimeMillis() - st.startedAt) / 1000L
        if (elapsed >= d.seconds) return PollResult(Outcome.MET, "met", "নির্ধারিত ${d.seconds} সেকেন্ডের অপেক্ষা শেষ")
        return PollResult(Outcome.WAIT, "t:" + (d.seconds - elapsed) / 60)
    }
}
