package com.hemel.lenspilot.tools.runner

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * সম্পন্ন-হওয়া শনাক্তকারী (detector) DSL। সব সংখ্যা সীমিত (caps); অজানা ফিল্ড উপেক্ষিত।
 * কোনো সার্ভিসের নাম কোডে বসানো নেই — URL/repo/কমান্ড সবই ডেটা হিসেবে আসে।
 */
sealed class Detector {
    /** এর বেশি চললে জোর করে শেষ (FAILED)। */
    abstract val maxDurationS: Int

    /** শুরুর পোলিং-বিরতি (সেকেন্ড); অভিযোজিত বাড়ে-কমে। */
    abstract val intervalS: Int

    abstract val typeName: String

    class FileAppears(
        val glob: String,
        val stableForS: Int,
        override val intervalS: Int,
        override val maxDurationS: Int
    ) : Detector() { override val typeName = "file_appears" }

    class HttpJson(
        val url: String,
        val method: String,
        /** মানে {{vault:…}} থাকতে পারে — প্রতিটি পোলে ডিভাইসে রিজলভ হয়। */
        val headers: Map<String, String>,
        val body: String?,
        val jsonPath: String,
        val op: String,
        val value: String,
        override val intervalS: Int,
        override val maxDurationS: Int
    ) : Detector() { override val typeName = "http_json" }

    class GithubRun(
        val repo: String,
        val ref: String?,
        val runId: String?,
        val apiBase: String?,
        override val intervalS: Int,
        override val maxDurationS: Int
    ) : Detector() { override val typeName = "github_run" }

    class CommandExit(
        val commandLine: String,
        val expectedExit: Int,
        /** false = একবার চালিয়ে মিলল/মিলল না; true = মিলবে না পর্যন্ত বারবার চালায়। */
        val retry: Boolean,
        override val intervalS: Int,
        override val maxDurationS: Int
    ) : Detector() { override val typeName = "command_exit" }

    class PageText(
        val regex: String,
        override val intervalS: Int,
        override val maxDurationS: Int
    ) : Detector() { override val typeName = "page_text" }

    class Timeout(val seconds: Int) : Detector() {
        override val maxDurationS: Int get() = seconds + 5
        override val intervalS: Int get() = 5
        override val typeName = "timeout"
    }
}

class DetectorSpecException(message: String) : Exception(message)

/** JSON → [Detector] পার্সার ও ভ্যালিডেটর। ভুল হলে Bangla বার্তাসহ [DetectorSpecException]। */
object DetectorParser {
    private const val MAX_DURATION_CAP = 7 * 24 * 3600
    private const val DEFAULT_DURATION = 3600
    private val OPS = setOf("eq", "ne", "contains", "gt", "lt")
    private val REPO = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
    private val DIGITS = Regex("^[0-9]{1,20}$")
    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,60}$")

    fun parse(text: String): Detector {
        val o = try { JSONObject(text) } catch (e: Exception) { throw DetectorSpecException("detector JSON পড়া যায়নি") }
        return parse(o)
    }

    fun parse(o: JSONObject): Detector {
        val type = o.optString("type").trim()
        val maxDur = clamp(o.optInt("max_duration_s", DEFAULT_DURATION), 10, MAX_DURATION_CAP)
        return when (type) {
            "file_appears" -> {
                val glob = str(o, "glob", 200)
                if (glob.isEmpty()) bad("glob দরকার")
                if (glob.startsWith("/") || glob.contains("..") || glob.contains("\\")) bad("glob সেশনের ভেতরের আপেক্ষিক পথ হতে হবে")
                Detector.FileAppears(glob, clamp(o.optInt("stable_for_s", 3), 0, 600), interval(o, 5), maxDur)
            }
            "http_json" -> {
                val url = str(o, "url", 2000)
                if (!(url.startsWith("https://") || url.startsWith("http://"))) bad("url http/https হতে হবে")
                val method = o.optString("method", "GET").uppercase()
                if (method != "GET" && method != "POST") bad("method শুধু GET বা POST")
                val op = o.optString("op", "eq")
                if (op !in OPS) bad("op হতে হবে eq|ne|contains|gt|lt")
                val path = str(o, "json_path", 200)
                if (path.isEmpty()) bad("json_path দরকার")
                val headers = LinkedHashMap<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    val it = h.keys()
                    while (it.hasNext() && headers.size < 10) {
                        val k = it.next()
                        if (!HEADER_NAME.matches(k)) bad("অবৈধ header নাম")
                        headers[k] = h.optString(k).take(500)
                    }
                }
                val body = if (method == "POST") o.optString("body", "").take(4000).ifEmpty { null } else null
                Detector.HttpJson(url, method, headers, body, path, op, o.opt("value")?.toString().orEmpty().take(500), interval(o, 10), maxDur)
            }
            "github_run" -> {
                val repo = str(o, "repo", 201)
                if (!REPO.matches(repo)) bad("repo হতে হবে owner/name")
                val runId = str(o, "run_id", 20).ifEmpty { null }
                if (runId != null && !DIGITS.matches(runId)) bad("run_id শুধু সংখ্যা")
                val base = str(o, "api_base", 200).ifEmpty { null }
                if (base != null && !base.startsWith("https://")) bad("api_base https হতে হবে")
                Detector.GithubRun(repo, str(o, "ref", 200).ifEmpty { null }, runId, base, interval(o, 15), maxDur)
            }
            "command_exit" -> {
                val cmd = str(o, "command_line", 500)
                if (cmd.isEmpty()) bad("command_line দরকার")
                Detector.CommandExit(cmd, clamp(o.optInt("expected_exit", 0), 0, 255), o.optBoolean("retry", false), interval(o, 10), maxDur)
            }
            "page_text" -> {
                val rx = str(o, "regex", 300)
                if (rx.isEmpty()) bad("regex দরকার")
                try { Pattern.compile(rx) } catch (e: Exception) { bad("regex অবৈধ") }
                Detector.PageText(rx, interval(o, 5), maxDur)
            }
            "timeout" -> Detector.Timeout(clamp(o.optInt("seconds", 0), 1, MAX_DURATION_CAP))
            else -> bad("অজানা detector type")
        }
    }

    private fun str(o: JSONObject, k: String, cap: Int): String = o.optString(k, "").trim().take(cap)
    private fun interval(o: JSONObject, def: Int): Int = clamp(o.optInt("interval_s", def), 2, 3600)
    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    private fun bad(msg: String): Nothing = throw DetectorSpecException(msg)
}
