package com.hemel.lenspilot.learning

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.AttachmentExtractor
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * v135 — 📂 ফাইল থেকে কোর্স (সিলেবাস স্ক্রিন থেকে খোলে)।
 *
 * যেকোনো ফাইল (PDF / টেক্সট / নোট / DOCX… / প্রোগ্রামের কোড) → সার্ভার গুনে দেখে (পাতা, লাইন, শব্দ, ফাংশন…) আর টপিক-কাঠামো বের করে
 * → ছাত্র গতি বাছে (প্রতিদিন কত লাইন/পাতা, বা কত দিনে) → AI প্রতিদিনের নাম ও লক্ষ্য লেখে → কোর্স ফোনে সেভ।
 * প্রতিদিনের "▶ ক্লাস" Learning Mode খোলে — ওই দিনের লাইন/পাতাই ক্লাসের একমাত্র উৎস।
 * কোড হলে ব্লাকবোর্ডে আসল লাইন নম্বরসহ কোড আসে, আঙুল লাইন দেখায়, আর (পাইথন হলে) ছোট ডেমো সত্যিই চালিয়ে আউটপুট দেখায়।
 * সার্ভার: /api/learning/pdf/upload, /api/course/analyze, /api/course/plan।
 */
class CourseActivity : AppCompatActivity() {

    private val bg by lazy { ContextCompat.getColor(this, R.color.chat_wallpaper_beige) }
    private val chalk by lazy { ContextCompat.getColor(this, R.color.claude_text) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.claude_muted) }
    private val accent by lazy { ContextCompat.getColor(this, R.color.learning_accent) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private var busy = false

    // বর্তমানে বিশ্লেষণ-করা ফাইল (কোর্স বানানোর আগ পর্যন্ত)
    private var aName = ""
    private var aKind = ""            // code | text | pdf
    private var aSourceId: String? = null
    private var aPdfId: String? = null
    private var aText: String? = null
    private var aPdfFile: File? = null
    private var aAnalysis: JSONObject? = null
    private var openCourseId: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) analyzeUri(uri)
    }

    // ------------------------------------------------------------------ UI helpers
    private fun tv(text: String, size: Float, color: Int = chalk, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@CourseActivity, R.drawable.bg_workflow_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun button(label: String, onClick: () -> Unit) = tv(label, 14f, accent).apply {
        background = ContextCompat.getDrawable(this@CourseActivity, R.drawable.bg_input_field)
        setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, number: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint; setHintTextColor(muted); setTextColor(chalk); textSize = 14f
        background = ContextCompat.getDrawable(this@CourseActivity, R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        maxLines = 1
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun errorOf(e: Throwable): String {
        val msg = if (e is ApiException) { try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" } } else ""
        return msg.ifBlank { "A network problem occurred. Please try again." }.take(200)
    }

    private fun codeOf(e: Throwable): String =
        if (e is ApiException) runCatching { JSONObject(e.body).optString("code") }.getOrDefault("") else ""

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun baseUrl() = getString(R.string.space_base_url)

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = bg; window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@CourseActivity, R.drawable.bg_chat_whatsapp)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        bar.addView(tv("←", 24f).apply { setPadding(dp(12), dp(4), dp(16), dp(4)); setOnClickListener { onBack() } })
        titleView = tv("📂 Course from a file", 18f, chalk, true)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onBack() }
        })
        showHome()
    }

    private fun onBack() {
        if (busy) return
        if (openCourseId != null || aAnalysis != null) { openCourseId = null; aAnalysis = null; showHome() } else finish()
    }

    override fun onResume() {
        super.onResume()
        if (openCourseId != null) showCourse(openCourseId!!)
    }

    // ------------------------------------------------------------------ হোম
    private fun showHome() {
        titleView.text = "📂 Course from a file"
        content.removeAllViews()
        content.addView(tv(
            "Upload any file: a PDF book, notes, a text file, or a program's source code (even 20,000 lines). " +
                "The app counts its pages / lines / words, finds its topics, and builds a day-by-day course. " +
                "Each day's class teaches only that day's pages or lines on the blackboard — for code it shows the real lines, " +
                "points at them with a finger, and (for Python) runs small demos and shows the real output.", 13f, muted))
        content.addView(button("📎 Choose a file") { pick.launch(arrayOf("*/*")) }, lp(dp(12)))

        content.addView(tv("My courses", 16f, chalk, true).apply { setPadding(0, dp(22), 0, 0) })
        val courses = CourseStore.list(this)
        if (courses.isEmpty()) content.addView(tv("No courses yet.", 13f, muted), lp(dp(6)))
        for (c in courses) {
            val row = card()
            val days = CourseStore.daysOf(c)
            row.addView(tv(c.optString("title"), 15f, chalk, true))
            row.addView(tv("${c.optString("name")} · ${days.size} days · ${CourseStore.doneCount(c)}/${days.size} done", 12f, muted))
            row.setOnClickListener { showCourse(c.getString("id")) }
            content.addView(row)
        }
    }

    // ------------------------------------------------------------------ ফাইল পড়া + বিশ্লেষণ
    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun setBusyText(msg: String?) {
        busy = msg != null
        content.removeAllViews()
        if (msg != null) content.addView(tv("⏳ $msg", 14f, muted), lp(dp(20)))
    }

    private fun analyzeUri(uri: Uri) {
        if (busy) return
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        setBusyText("Reading $name…")
        lifecycleScope.launch {
            try {
                aName = name; aSourceId = null; aPdfId = null; aText = null; aPdfFile = null
                val body: JSONObject
                if (ext == "pdf") {
                    val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    if (bytes == null || bytes.isEmpty()) { toast("The file could not be read"); busy = false; showHome(); return@launch }
                    if (bytes.size > 28 * 1024 * 1024) { toast("This PDF is too large (max 28 MB)"); busy = false; showHome(); return@launch }
                    val tmp = File(cacheDir, "course_${UUID.randomUUID()}.pdf")
                    withContext(Dispatchers.IO) { tmp.writeBytes(bytes) }
                    aPdfFile = tmp
                    setBusyText("Uploading the PDF…")
                    val up = uploadPdf(name, bytes) ?: run { busy = false; showHome(); return@launch }
                    aPdfId = up
                    body = JSONObject().put("pdf_id", up)
                    aKind = "pdf"
                } else {
                    val text = withContext(Dispatchers.IO) { readAnyText(uri, name, ext) }
                    if (text.isNullOrBlank()) { toast("This file has no readable text, or the type is not supported"); busy = false; showHome(); return@launch }
                    aText = text
                    body = JSONObject().put("name", name).put("text", text)
                }
                setBusyText("Analysing the file…")
                val r = ApiClient.callAuthed(this@CourseActivity, baseUrl(), "/api/course/analyze", body.toString())
                r.onSuccess { s ->
                    val a = JSONObject(s)
                    aAnalysis = a; aKind = a.optString("kind"); aSourceId = a.optString("source_id").ifBlank { null }
                    busy = false; showAnalysis()
                }.onFailure { e -> toast(errorOf(e)); busy = false; showHome() }
            } catch (e: Throwable) {
                toast("Could not open this file"); busy = false; showHome()
            }
        }
    }

    /** টেক্সট/কোড সরাসরি (পুরোটা); DOCX/PPTX/XLSX/ODT ভেতরের লেখা বের করে (বড় হলে প্রথম অংশ)। */
    private fun readAnyText(uri: Uri, name: String, ext: String): String? {
        val office = setOf("docx", "pptx", "xlsx", "odt", "ods", "odp")
        if (ext in office) {
            val r = AttachmentExtractor.extract(this, uri)
            return (r as? AttachmentExtractor.Result.Text)?.text
        }
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > 7_500_000) return null
        // বাইনারি ফাইল চেনা: অনেক NUL বাইট থাকলে টেক্সট নয়
        if (bytes.take(4000).count { it.toInt() == 0 } > 20) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun uploadPdf(name: String, bytes: ByteArray): String? {
        val body = JSONObject().put("name", name).put("pdf_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val r = ApiClient.callAuthed(this, baseUrl(), "/api/learning/pdf/upload", body.toString())
        var id: String? = null
        r.onSuccess { id = JSONObject(it).optString("pdf_id").ifBlank { null } }.onFailure { toast(errorOf(it)) }
        return id
    }

    // ------------------------------------------------------------------ বিশ্লেষণ + গতি
    private fun showAnalysis() {
        val a = aAnalysis ?: return showHome()
        val st = a.optJSONObject("stats") ?: JSONObject()
        val sg = a.optJSONObject("suggest") ?: JSONObject()
        val isPdf = aKind == "pdf"
        titleView.text = "📂 $aName"
        content.removeAllViews()

        val sc = card()
        sc.addView(tv(if (isPdf) "📕 PDF" else if (aKind == "code") "💻 Code · ${a.optString("lang_name").ifBlank { "source" }}" else "📄 Text document", 14f, chalk, true))
        val facts = ArrayList<String>()
        if (isPdf) {
            facts.add("${st.optInt("pages")} pages")
            facts.add("~${st.optInt("words")} words")
            if (st.optInt("scanned_pages_est") > 0) facts.add("~${st.optInt("scanned_pages_est")} scanned pages (read by AI vision)")
            facts.add("${st.optInt("toc_entries")} contents entries")
        } else {
            facts.add("${st.optInt("lines")} lines")
            if (aKind == "code") {
                facts.add("${st.optInt("code_lines")} code · ${st.optInt("comment_lines")} comment · ${st.optInt("blank_lines")} blank lines")
                if (st.has("functions")) facts.add("${st.optInt("functions")} functions · ${st.optInt("classes")} classes/types")
            } else facts.add("${st.optInt("sections")} sections")
            facts.add("${st.optInt("words")} words · ${st.optInt("chars")} characters")
        }
        sc.addView(tv(facts.joinToString("\n"), 13f, muted), lp(dp(6)))
        if (aKind == "code" && !a.optBoolean("runnable")) {
            sc.addView(tv("Classes will show the real code lines on the blackboard. Running demos works for Python files only.", 12f, muted), lp(dp(6)))
        }
        content.addView(sc)

        val units = a.optJSONArray("units") ?: JSONArray()
        if (units.length() > 0) {
            val uc = card()
            uc.addView(tv("Topics found (${a.optInt("units_total")})", 14f, chalk, true))
            val sb = StringBuilder()
            for (i in 0 until minOf(units.length(), 14)) {
                val u = units.getJSONObject(i)
                val range = if (isPdf) "p.${u.optInt("from")}–${u.optInt("to")}" else "L${u.optInt("from")}–${u.optInt("to")}"
                sb.append("• ").append(u.optString("title")).append("  (").append(range).append(")\n")
            }
            if (a.optInt("units_total") > 14) sb.append("…")
            uc.addView(tv(sb.toString().trimEnd(), 12f, muted), lp(dp(6)))
            content.addView(uc)
        }

        val unit = sg.optString("unit", if (isPdf) "pages" else "lines")
        val pc = card()
        pc.addView(tv("How fast do you want to learn?", 14f, chalk, true))
        val perDay = field("$unit per day (suggested ${sg.optInt("per_day")})", true).apply { setText(sg.optInt("per_day").toString()) }
        val days = field("or: finish in N days (optional)", true)
        pc.addView(perDay, lp(dp(8)))
        val opts = sg.optJSONArray("options")
        if (opts != null && opts.length() > 0) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until opts.length()) {
                val v = opts.getInt(i)
                row.addView(button("$v") { perDay.setText(v.toString()); days.setText("") },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            }
            pc.addView(row, lp(dp(6)))
        }
        pc.addView(days, lp(dp(8)))
        val goal = field("Your goal (optional), e.g. “I want to modify this code”")
        pc.addView(goal, lp(dp(8)))
        val lang = field("Language of the course (default: Bengali)").apply { setText("Bengali") }
        pc.addView(lang, lp(dp(8)))
        pc.addView(button("✨ Analyse topics and create the course") {
            createCourse(perDay.text.toString().toIntOrNull() ?: 0, days.text.toString().toIntOrNull() ?: 0,
                goal.text.toString().trim(), lang.text.toString().trim().ifBlank { "Bengali" })
        }, lp(dp(12)))
        content.addView(pc)
    }

    // ------------------------------------------------------------------ কোর্স তৈরি
    private fun createCourse(perDay: Int, days: Int, goal: String, language: String) {
        if (busy) return
        setBusyText("The AI is analysing the topics and planning the days… (this can take up to a minute)")
        val body = JSONObject().put("per_day", perDay).put("goal", goal).put("language", language)
        if (days > 0) body.put("days", days)
        if (aKind == "pdf") body.put("pdf_id", aPdfId) else body.put("source_id", aSourceId)
        lifecycleScope.launch {
            val r = ApiClient.callAuthed(this@CourseActivity, baseUrl(), "/api/course/plan", body.toString())
            r.onSuccess { s ->
                try {
                    val course = JSONObject(s).getJSONObject("course")
                    val id = UUID.randomUUID().toString().take(12)
                    course.put("id", id).put("createdAt", System.currentTimeMillis())
                    if (aKind == "pdf") {
                        withContext(Dispatchers.IO) { aPdfFile?.copyTo(CourseStore.srcPdf(this@CourseActivity, id), true) }
                        course.put("pdfId", aPdfId)
                    } else {
                        withContext(Dispatchers.IO) { CourseStore.srcTxt(this@CourseActivity, id).writeText((aText ?: "").replace("\r\n", "\n").replace("\r", "\n")) }
                    }
                    CourseStore.save(this@CourseActivity, course)
                    busy = false; aAnalysis = null
                    showCourse(id)
                } catch (e: Throwable) { toast("Could not save the course"); busy = false; showAnalysis() }
            }.onFailure { e ->
                val code = codeOf(e)
                toast(errorOf(e))
                busy = false
                if (code == "SOURCE_GONE" && aText != null) reanalyzeThenPlan(perDay, days, goal, language) else showAnalysis()
            }
        }
    }

    /** সার্ভারে ফাইলের কপি মুছে গেলে (ক্যাশ শেষ) নিজে আবার পাঠিয়ে চালু। */
    private fun reanalyzeThenPlan(perDay: Int, days: Int, goal: String, language: String) {
        setBusyText("Re-sending the file…")
        lifecycleScope.launch {
            val r = ApiClient.callAuthed(this@CourseActivity, baseUrl(), "/api/course/analyze",
                JSONObject().put("name", aName).put("text", aText).toString())
            busy = false
            r.onSuccess { s -> aSourceId = JSONObject(s).optString("source_id"); createCourse(perDay, days, goal, language) }
                .onFailure { toast(errorOf(it)); showAnalysis() }
        }
    }

    // ------------------------------------------------------------------ কোর্সের পাতা
    private fun showCourse(id: String) {
        val c = CourseStore.load(this, id) ?: return showHome()
        openCourseId = id
        titleView.text = "📂 " + c.optString("title")
        content.removeAllViews()
        val days = CourseStore.daysOf(c)
        val isPdf = c.optString("kind") == "pdf"
        val unit = if (isPdf) "p." else "L"

        val head = card()
        head.addView(tv(c.optString("title"), 16f, chalk, true))
        head.addView(tv("${c.optString("name")} · ${c.optInt("total")} ${c.optString("unit")} · ${days.size} days · ${CourseStore.doneCount(c)} done", 12f, muted), lp(dp(2)))
        if (c.optString("overview").isNotBlank()) head.addView(tv(c.optString("overview"), 13f, chalk), lp(dp(8)))
        val topics = c.optJSONArray("topics")
        if (topics != null && topics.length() > 0) {
            val t = (0 until topics.length()).joinToString("\n") { "• " + topics.getString(it) }
            head.addView(tv("Main topics\n$t", 12f, muted), lp(dp(8)))
        }
        val pre = c.optJSONArray("prerequisites")
        if (pre != null && pre.length() > 0) {
            head.addView(tv("Good to know first: " + (0 until pre.length()).joinToString("; ") { pre.getString(it) }, 12f, muted), lp(dp(6)))
        }
        content.addView(head)

        // পরবর্তী না-করা দিন
        val next = days.firstOrNull { !it.optBoolean("done") }
        if (next != null) {
            content.addView(button("▶ Continue: Day ${next.optInt("day")} — ${next.optString("title")}") { startDay(c, next) }, lp(dp(12)))
        }
        for (d in days) {
            val row = card()
            val done = d.optBoolean("done")
            row.addView(tv((if (done) "✅ " else "") + "Day ${d.optInt("day")} · ${d.optString("title")}", 14f, chalk, true))
            row.addView(tv("$unit${d.optInt("from")}–${d.optInt("to")}  (${d.optInt("count")} ${c.optString("unit")})", 12f, muted))
            if (d.optString("goal").isNotBlank()) row.addView(tv(d.optString("goal"), 12f, chalk), lp(dp(4)))
            val tps = d.optJSONArray("topics")
            if (tps != null && tps.length() > 0) row.addView(tv((0 until tps.length()).joinToString(" · ") { tps.getString(it) }, 11f, muted), lp(dp(2)))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("▶ Class") { startDay(c, d) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(6) })
            actions.addView(button(if (done) "↩ Undo" else "✔ Done") {
                CourseStore.setDone(this, id, d.optInt("day"), !done); showCourse(id)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actions, lp(dp(8)))
            content.addView(row)
        }
        content.addView(button("🗑 Delete this course") {
            AlertDialog.Builder(this).setMessage("Delete this course and its saved file copy?")
                .setPositiveButton("Delete") { _, _ -> CourseStore.delete(this, id); openCourseId = null; showHome() }
                .setNegativeButton("Cancel", null).show()
        }, lp(dp(24)))
    }

    private fun startDay(c: JSONObject, d: JSONObject) {
        if (busy) return
        val id = c.getString("id")
        val from = d.optInt("from"); val to = d.optInt("to")
        val prompt = d.optString("lesson_prompt").ifBlank { d.optString("title") }
        if (c.optString("kind") == "pdf") {
            busy = true
            lifecycleScope.launch {
                val pdfId = ensurePdf(c)
                busy = false
                if (pdfId == null) return@launch
                launchClass("$prompt [[pdf:$pdfId:$from-$to]]", null, id, d)
            }
        } else {
            val slice = CourseStore.sliceJson(this, c, from, to)
            if (slice == null) { toast("The saved file copy is missing. Please create the course again."); return }
            launchClass(prompt, slice, id, d)
        }
    }

    /** সার্ভারে PDF-র কপি (২৪ ঘণ্টা) থাকলে সেটাই; মুছে গেলে ফোনের কপি থেকে আবার আপলোড। */
    private suspend fun ensurePdf(c: JSONObject): String? {
        val id = c.getString("id")
        val old = c.optString("pdfId")
        if (old.isNotBlank()) {
            val r = ApiClient.callAuthed(this, baseUrl(), "/api/learning/pdf/info", JSONObject().put("pdf_id", old).toString())
            if (r.isSuccess) return old
        }
        val f = CourseStore.srcPdf(this, id)
        if (!f.exists()) { toast("The saved PDF copy is missing. Please create the course again."); return null }
        Toast.makeText(this, "Re-sending the PDF…", Toast.LENGTH_SHORT).show()
        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
        val nid = uploadPdf(c.optString("name"), bytes) ?: return null
        c.put("pdfId", nid); CourseStore.save(this, c)
        return nid
    }

    private fun launchClass(topic: String, slice: JSONObject?, courseId: String, d: JSONObject) {
        CourseStore.setDone(this, courseId, d.optInt("day"), true)
        val i = Intent(this, LearningModeActivity::class.java).putExtra(LearningModeActivity.EXTRA_TOPIC, topic)
        if (slice != null) i.putExtra(LearningModeActivity.EXTRA_SOURCE_JSON, slice.toString())
        startActivity(i)
    }
}
