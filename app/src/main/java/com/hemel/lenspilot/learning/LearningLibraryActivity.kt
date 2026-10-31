package com.hemel.lenspilot.learning

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * লার্নিং লাইব্রেরি: কোর্স / অধ্যায় / বিষয় / ক্লাস → AI (অনলাইনে খুঁজে) পরিকল্পনা বানায় → YouTube-এর মতো প্লেলিস্ট।
 *
 *  • কোর্স  → N দিনের প্ল্যান = প্লেলিস্ট
 *  • অধ্যায় → অধ্যায়ের পাঠগুলো খুঁজে কয়েক দিনের প্ল্যান = প্লেলিস্ট
 *  • বিষয়  → অধ্যায়-তালিকা খুঁজে প্রতি অধ্যায়ে একটা সেক্টর; সেক্টরে চাপলে সেই অধ্যায়ের প্লেলিস্ট
 *  • ক্লাস  → বিষয়-তালিকা খুঁজে প্রতি বিষয়ে সেক্টর → (বিষয়ের মতোই) অধ্যায় → প্লেলিস্ট
 * প্লেলিস্টে কভার (লার্নিং-রোবট) + শিরোনাম; ভেতরে পরিকল্পনা পড়া/এডিট/সাজানো, আইটেম ▶ চাপলে পাঠ চলে,
 * ✓ দিয়ে "পড়া শেষ" চিহ্ন। সব জায়গায় একই "বিস্তারিত স্তর" স্লাইডার (কম = সংক্ষিপ্ত, বেশি = অনেক ধাপ)।
 */
class LearningLibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CREATE_KIND = "extra_create_kind"
        private val KIND_ICON = mapOf("course" to "📚", "chapter" to "📖", "subject" to "🧪", "class" to "🏫")
        private val KIND_NAME = mapOf("course" to "Course", "chapter" to "Chapter", "subject" to "Subject", "class" to "Class")
        private val EST_MIN = mapOf(1 to "≈ 2–3", 2 to "≈ 4–5", 3 to "≈ 6–8", 4 to "≈ 8–10", 5 to "≈ 10–15", 6 to "≈ 15–20", 7 to "≈ 25–30", 8 to "≈ 35–40", 9 to "≈ 45–50", 10 to "≈ 50–60")
        private val DETAIL_NAME = mapOf(1 to "Very brief", 2 to "Brief", 3 to "Less", 4 to "Slightly less", 5 to "Medium", 6 to "Slightly more", 7 to "Detailed", 8 to "Very detailed", 9 to "Very detailed", 10 to "Maximum detail")
    }

    private lateinit var roots: MutableList<LibNode>
    private val stack = mutableListOf<LibNode>()
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private var busy = false
    /** ▶ চেপে পাঠে গিয়ে ফিরলে "শেষ হয়েছে?" জিজ্ঞেস করার জন্য */
    private var pendingPlay: LibItem? = null

    private val bg by lazy { ContextCompat.getColor(this, R.color.chat_wallpaper_beige) }
    private val chalk by lazy { ContextCompat.getColor(this, R.color.claude_text) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.claude_muted) }
    private val accent by lazy { ContextCompat.getColor(this, R.color.learning_accent) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        roots = LearningLibraryStore.load(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ContextCompat.getDrawable(this@LearningLibraryActivity, R.drawable.bg_chat_whatsapp) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(chalk); setPadding(dp(12), dp(4), dp(16), dp(4))
            setOnClickListener { goBack() }
        }
        titleView = TextView(this).apply { textSize = 18f; setTextColor(chalk); typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
        bar.addView(back); bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })

        render()
        intent.getStringExtra(EXTRA_CREATE_KIND)?.takeIf { it in KIND_NAME }?.let { showCreateDialog(it) }
    }

    override fun onResume() {
        super.onResume()
        val item = pendingPlay ?: return
        pendingPlay = null
        if (item.done) return
        AlertDialog.Builder(this)
            .setTitle("Has the lesson been completed?")
            .setMessage(item.title)
            .setPositiveButton("Yes, mark with ✓") { _, _ -> item.done = true; persist(); render() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun openRecordedClasses() {
        ClassRecordingsDialog.show(
            this,
            onReplay = { id ->
                startActivity(Intent(this, LearningModeActivity::class.java).putExtra(LearningModeActivity.EXTRA_REPLAY_ID, id))
            },
            onExportVideo = { id ->
                startActivity(
                    Intent(this, LearningModeActivity::class.java)
                        .putExtra(LearningModeActivity.EXTRA_REPLAY_ID, id)
                        .putExtra(LearningModeActivity.EXTRA_REPLAY_EXPORT, true)
                )
            }
        )
    }

    private fun goBack() {
        if (stack.isNotEmpty()) { stack.removeAt(stack.size - 1); render() } else finish()
    }

    private fun persist() = LearningLibraryStore.save(this, roots)

    // ------------------------------------------------------------------ UI helpers
    private fun tv(text: String, size: Float, color: Int = chalk, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@LearningLibraryActivity, R.drawable.bg_workflow_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun button(label: String, onClick: () -> Unit) = tv(label, 14f, accent).apply {
        background = ContextCompat.getDrawable(this@LearningLibraryActivity, R.drawable.bg_input_field)
        setPadding(dp(14), dp(9), dp(14), dp(9)); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    /** "বিস্তারিত স্তর" স্লাইডার — সব জায়গায় একই Prefs; কমালে সংক্ষেপে, বাড়ালে অনেক ধাপ/বিস্তারিত। */
    private fun detailBar(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = tv("", 13f, muted)
        fun refresh(level: Int) { label.text = "Detail level: ${DETAIL_NAME[level]} ($level/10)  ·  ${EST_MIN[level]} minutes (approximate, varies by topic)" }
        val seek = SeekBar(this).apply {
            max = 9; progress = Prefs.learningDetail(this@LearningLibraryActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    Prefs.setLearningDetail(this@LearningLibraryActivity, p + 1); refresh(p + 1)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        refresh(Prefs.learningDetail(this))
        box.addView(label); box.addView(seek)
        return box
    }

    private fun progressText(n: LibNode): String {
        val (d, t) = n.progress()
        return if (t == 0) "" else "$d/$t done"
    }

    private fun chainTitles(): String = stack.joinToString(" · ") { it.title }

    // ------------------------------------------------------------------ rendering
    private fun render() {
        content.removeAllViews()
        val node = stack.lastOrNull()
        when {
            node == null -> renderHome()
            node.items != null -> renderPlaylist(node)
            else -> renderSections(node)
        }
    }

    private fun renderHome() {
        titleView.text = "📚 My library"
        content.addView(tv("What do you want to learn? Choose one. The AI will search online and build a plan and playlist.", 14f, muted))
        content.addView(button("📖 My syllabus: add by typing or with a picture") {
            startActivity(Intent(this, SyllabusActivity::class.java))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        for (k in listOf("course", "chapter", "subject", "class")) {
            val b = button("${KIND_ICON[k]}\n${KIND_NAME[k]}") { showCreateDialog(k) }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        content.addView(row)
        content.addView(detailBar().apply { setPadding(0, dp(14), 0, dp(4)) })
        // v31: Note Force (ব্লাকবোর্ডে বিস্তারিত নোট) + রেকর্ড করা ক্লাস
        content.addView(card().apply { addView(ClassToolsDialog.noteForceBlock(this@LearningLibraryActivity)) })
        content.addView(button("🎬 Recorded classes: replay / download / PDF notes") { openRecordedClasses() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        if (roots.isEmpty()) {
            content.addView(tv("Nothing here yet. Create one from the top to get started.", 14f, muted).apply { setPadding(0, dp(24), 0, 0) })
            return
        }
        for (n in roots.sortedByDescending { it.createdAt }) content.addView(nodeCard(n, root = true))
    }

    private fun nodeCard(n: LibNode, root: Boolean): View {
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.robot_happy_front) },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("${KIND_ICON[n.kind] ?: ""} ${n.title}", 16f, chalk, true))
        val sub = when {
            !n.generated -> "✨ Tap and the AI will create it"
            n.items != null -> "Playlist · ${n.items!!.size} items · ${progressText(n)}"
            else -> "${n.sections.size} ${if (n.kind == "class") "subjects" else "chapters"} · ${progressText(n)}"
        }
        col.addView(tv(sub, 12f, muted))
        if (n.summary.isNotBlank() && !root) col.addView(tv(n.summary, 12f, muted).apply { maxLines = 2 })
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(head)
        c.setOnClickListener { openNode(n) }
        c.setOnLongClickListener { showNodeMenu(n, root); true }
        return c
    }

    private fun renderSections(node: LibNode) {
        titleView.text = "${KIND_ICON[node.kind] ?: ""} ${node.title}"
        content.addView(tv(node.summary, 14f, muted))
        content.addView(detailBar().apply { setPadding(0, dp(10), 0, dp(4)) })
        if (!node.generated) {
            content.addView(button("✨ Create ${KIND_NAME[node.kind]}") { generateFor(node) }.apply { setPadding(dp(14), dp(14), dp(14), dp(14)) })
            return
        }
        content.addView(tv("${node.sections.size} ${if (node.kind == "class") "subjects" else "chapters"}. Tap to open; if none exist the AI will create them. Press and hold to rename or delete.", 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
        for (s in node.sections) content.addView(nodeCard(s, root = false))
        if (node.sources.isNotEmpty()) content.addView(tv("Source: " + node.sources.joinToString(", "), 11f, muted).apply { setPadding(0, dp(14), 0, 0) })
    }

    private fun renderPlaylist(node: LibNode) {
        val items = node.items ?: return
        titleView.text = "${KIND_ICON[node.kind] ?: ""} Playlist"
        // কভার + শিরোনাম (YouTube প্লেলিস্টের মতো)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.robot_happy_front)
            background = ContextCompat.getDrawable(this@LearningLibraryActivity, R.drawable.bg_workflow_card)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(110), dp(110)).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(node.title, 20f, chalk, true))
        col.addView(tv("${items.size} items · ${progressText(node)}", 13f, muted))
        col.addView(tv("✏️ Edit title / description", 13f, accent).apply {
            setPadding(0, dp(8), 0, 0); setOnClickListener { showEditNodeDialog(node) }
        })
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(head)
        if (node.summary.isNotBlank()) content.addView(tv(node.summary, 14f, muted).apply { setPadding(0, dp(10), 0, 0) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        actions.addView(button("▶ Play") {
            val next = items.firstOrNull { !it.done } ?: items.firstOrNull()
            if (next != null) playItem(node, next)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actions.addView(button("➕ Item") { showEditItemDialog(node, null) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actions.addView(button("🔄 Regenerate") { confirmRegenerate(node) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(actions)
        content.addView(detailBar().apply { setPadding(0, dp(14), 0, dp(4)) })

        for (item in items) content.addView(itemRow(node, item))
        if (node.sources.isNotEmpty()) content.addView(tv("Source: " + node.sources.joinToString(", "), 11f, muted).apply { setPadding(0, dp(14), 0, 0) })
    }

    private fun itemRow(node: LibNode, item: LibItem): View {
        val c = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val check = CheckBox(this).apply {
            isChecked = item.done
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked -> item.done = checked; persist(); render() }
        }
        top.addView(check)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dayPrefix = if (item.day > 0) "Day ${item.day} · " else ""
        val t = tv(dayPrefix + item.title, 15f, if (item.done) muted else chalk, true)
        if (item.done) t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(t)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(tv("▶", 20f, accent).apply { setPadding(dp(12), dp(4), dp(8), dp(4)); setOnClickListener { playItem(node, item) } })
        top.addView(tv("⋮", 20f, muted).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { showEditItemDialog(node, item) } })
        c.addView(top)
        if (item.description.isNotBlank()) c.addView(tv(item.description, 13f, muted).apply { setPadding(dp(8), dp(6), 0, 0) })
        if (item.topics.isNotEmpty()) c.addView(tv(item.topics.joinToString("\n") { "• $it" }, 13f, chalk).apply { setPadding(dp(8), dp(6), 0, 0) })
        c.setOnClickListener { showEditItemDialog(node, item) }
        return c
    }

    // ------------------------------------------------------------------ navigation / play
    private fun openNode(n: LibNode) {
        if (busy) return
        if (!n.generated) generateFor(n, thenOpen = true) else { stack.add(n); render() }
    }

    private fun playItem(node: LibNode, item: LibItem) {
        val topic = buildString {
            append(item.title)
            if (item.topics.isNotEmpty()) append(" — ").append(item.topics.joinToString(", "))
            val ctx = (stack.map { it.title }).distinct().joinToString(" · ")
            if (ctx.isNotBlank()) append(" (").append(ctx).append(")")
        }
        pendingPlay = item
        startActivity(Intent(this, LearningModeActivity::class.java).putExtra(LearningModeActivity.EXTRA_TOPIC, topic))
    }

    // ------------------------------------------------------------------ create / generate
    private fun showCreateDialog(kind: String) {
        val hint = when (kind) {
            "course" -> "Course name"
            "chapter" -> "Topic"
            "subject" -> "Subject"
            else -> "Class or grade"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val q = EditText(this).apply { this.hint = hint; setSingleLine(false); maxLines = 3 }
        box.addView(q)
        var daysInput: EditText? = null
        if (kind == "course" || kind == "chapter") {
            daysInput = EditText(this).apply {
                this.hint = "How many days? (Leave empty and the AI will decide)"; inputType = InputType.TYPE_CLASS_NUMBER
            }
            box.addView(daysInput)
        }
        var ctxInput: EditText? = null
        if (kind != "course") {
            ctxInput = EditText(this).apply { this.hint = "Grade / board / country (optional)" }
            box.addView(ctxInput)
        }
        box.addView(detailBar().apply { setPadding(0, dp(14), 0, dp(4)) })
        AlertDialog.Builder(this)
            .setTitle("${KIND_ICON[kind]} New ${KIND_NAME[kind]}")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val query = q.text.toString().trim()
                if (query.isEmpty()) { Toast.makeText(this, "Enter what you want to learn first", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val node = LibNode(kind = kind, title = query, query = query,
                    context = ctxInput?.text?.toString()?.trim().orEmpty())
                val days = daysInput?.text?.toString()?.toIntOrNull() ?: 0
                roots.add(node)
                stack.clear()
                generateFor(node, days = days, thenOpen = true, isNewRoot = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun contextFor(node: LibNode): String {
        // বিষয়/অধ্যায় সেক্টরের প্রসঙ্গ = ওপরের ধাপের নামগুলো + নিজের context (যেমন "নবম শ্রেণি · পদার্থবিজ্ঞান")
        val parts = mutableListOf<String>()
        parts.addAll(stack.map { it.title })
        if (node.context.isNotBlank()) parts.add(node.context)
        return parts.distinct().joinToString(" · ")
    }

    private fun generateFor(node: LibNode, days: Int = 0, thenOpen: Boolean = false, isNewRoot: Boolean = false) {
        if (busy) return
        busy = true
        val progress = AlertDialog.Builder(this)
            .setTitle("${KIND_ICON[node.kind]} ${node.title}")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
                addView(ProgressBar(this@LearningLibraryActivity))
                addView(tv("Searching online and building a plan… (10–30 seconds)", 13f, muted).apply { setPadding(0, dp(10), 0, 0) })
            })
            .setCancelable(false).show()
        lifecycleScope.launch {
            val body = JSONObject()
                .put("kind", node.kind).put("query", node.query.ifBlank { node.title })
                .put("detail", Prefs.learningDetail(this@LearningLibraryActivity))
            if (days > 0) body.put("days", days)
            val ctx = if (isNewRoot) node.context else contextFor(node)
            if (ctx.isNotBlank()) body.put("context", ctx)
            val res = ApiClient.callAuthed(this@LearningLibraryActivity, getString(R.string.space_base_url),
                "/api/learning/curriculum", body.toString())
            busy = false
            runCatching { progress.dismiss() }
            res.onSuccess { raw ->
                val r = runCatching { JSONObject(raw) }.getOrNull()
                if (r == null || !applyResult(node, r, isNewRoot)) {
                    Toast.makeText(this@LearningLibraryActivity, "Could not build the plan. Please try again.", Toast.LENGTH_LONG).show()
                    if (isNewRoot) roots.remove(node)
                } else {
                    persist()
                    if (thenOpen) { if (isNewRoot) stack.clear(); stack.add(node) }
                }
                render()
            }.onFailure {
                Toast.makeText(this@LearningLibraryActivity, it.message ?: "Network problem", Toast.LENGTH_LONG).show()
                if (isNewRoot) { roots.remove(node); persist() }
                render()
            }
        }
    }


    private fun applyResult(node: LibNode, r: JSONObject, isNewRoot: Boolean): Boolean {
        if (isNewRoot) r.optString("title").takeIf { it.isNotBlank() }?.let { node.title = it }
        r.optString("summary").takeIf { it.isNotBlank() }?.let { node.summary = it }
        val src = r.optJSONArray("sources")
        node.sources = if (src == null) emptyList() else (0 until src.length()).map { src.optString(it) }.filter { it.isNotBlank() }
        val itemsArr = r.optJSONArray("items")
        val secArr = r.optJSONArray("sections")
        if (itemsArr != null && itemsArr.length() > 0) {
            val old = node.items.orEmpty().associateBy { it.title.trim().lowercase() }
            node.sections.clear()
            node.items = (0 until itemsArr.length()).map { i ->
                val o = itemsArr.getJSONObject(i)
                val topics = o.optJSONArray("topics")
                val title = o.optString("title")
                LibItem(
                    title = title, description = o.optString("description"), day = o.optInt("day", i + 1),
                    topics = if (topics == null) mutableListOf() else (0 until topics.length()).map { topics.optString(it) }.filter { it.isNotBlank() }.toMutableList(),
                    done = old[title.trim().lowercase()]?.done ?: false   // আবার বানালে একই নামের শেষ-হওয়া আইটেম ✓ থাকে
                )
            }.toMutableList()
            return true
        }
        if (secArr != null && secArr.length() > 0) {
            node.items = null
            val childKind = if (node.kind == "class") "subject" else "chapter"
            val oldByTitle = node.sections.associateBy { it.title.trim().lowercase() }
            val fresh = (0 until secArr.length()).map { i ->
                val o = secArr.getJSONObject(i)
                val t = o.optString("title")
                oldByTitle[t.trim().lowercase()]?.also { it.summary = o.optString("summary") }   // আগে বানানো সেক্টর হারায় না
                    ?: LibNode(kind = childKind, title = t, summary = o.optString("summary"), query = t)
            }
            node.sections.clear(); node.sections.addAll(fresh)
            return true
        }
        return false
    }

    private fun confirmRegenerate(node: LibNode) {
        AlertDialog.Builder(this)
            .setTitle("Regenerate?")
            .setMessage("This builds a new plan at the current detail level (${DETAIL_NAME[Prefs.learningDetail(this)]}). Completed items ✓ with the same name are kept; text you edited by hand will be replaced in the new plan.")
            .setPositiveButton("Create") { _, _ ->
                generateFor(node)
            }
            .setNegativeButton("No", null).show()
    }

    // ------------------------------------------------------------------ edit / delete
    private fun showNodeMenu(n: LibNode, root: Boolean) {
        val parent = stack.lastOrNull()
        AlertDialog.Builder(this).setTitle(n.title)
            .setItems(arrayOf("✏️ Rename", "🗑 Delete")) { _, which ->
                if (which == 0) showEditNodeDialog(n) else {
                    AlertDialog.Builder(this).setMessage("Delete \"${n.title}\" and everything inside it?")
                        .setPositiveButton("Delete") { _, _ ->
                            if (root || parent == null) roots.remove(n) else parent.sections.remove(n)
                            persist(); render()
                        }.setNegativeButton("No", null).show()
                }
            }.show()
    }

    private fun showEditNodeDialog(n: LibNode) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val t = EditText(this).apply { setText(n.title); hint = "Title" }
        val s = EditText(this).apply { setText(n.summary); hint = "Description"; setSingleLine(false); maxLines = 4 }
        box.addView(t); box.addView(s)
        AlertDialog.Builder(this).setTitle("Edit").setView(box)
            .setPositiveButton("Save") { _, _ ->
                n.title = t.text.toString().trim().ifBlank { n.title }
                n.summary = s.text.toString().trim()
                persist(); render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showEditItemDialog(node: LibNode, item: LibItem?) {
        val list = node.items ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val t = EditText(this).apply { setText(item?.title.orEmpty()); hint = "Title" }
        val d = EditText(this).apply { setText(item?.description.orEmpty()); hint = "Plan (what you will learn)"; setSingleLine(false); maxLines = 6 }
        val tp = EditText(this).apply {
            setText(item?.topics?.joinToString("\n").orEmpty()); hint = "Lessons, one per line"; setSingleLine(false); maxLines = 8
        }
        box.addView(t); box.addView(d); box.addView(tp)
        if (item != null) {
            val mv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            mv.addView(button("⬆ Up") { moveItem(list, item, -1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            mv.addView(button("⬇ Down") { moveItem(list, item, +1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(mv)
        }
        val b = AlertDialog.Builder(this).setTitle(if (item == null) "New item" else "Edit item")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val title = t.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                val topics = tp.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (item == null) list.add(LibItem(title = title, description = d.text.toString().trim(), topics = topics, day = list.size + 1))
                else { item.title = title; item.description = d.text.toString().trim(); item.topics = topics }
                persist(); render()
            }
            .setNegativeButton("Cancel", null)
        if (item != null) b.setNeutralButton("🗑 Delete") { _, _ -> list.remove(item); persist(); render() }
        val dlg = b.show()
        dialogForMove = dlg
    }

    private var dialogForMove: AlertDialog? = null

    private fun moveItem(list: MutableList<LibItem>, item: LibItem, delta: Int) {
        val i = list.indexOf(item)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        list.removeAt(i); list.add(j, item)
        list.forEachIndexed { idx, it -> if (it.day > 0) it.day = idx + 1 }
        persist(); render()
        dialogForMove?.dismiss()
    }
}
