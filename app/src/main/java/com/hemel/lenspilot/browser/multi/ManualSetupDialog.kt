package com.hemel.lenspilot.browser.multi

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hemel.lenspilot.security.SecretVault

/** Reads a picked file's name / type and, for text-like files, a preview the agent can read. */
object DocReader {
    private const val MAX_BYTES = 24_000
    private val TEXT_EXT = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "sql", "js", "py", "kt", "java", "css", "rtf")

    fun read(ctx: Context, uri: Uri): AttachedDoc {
        var name = "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) { }
        if (name == "file") name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = try { ctx.contentResolver.getType(uri).orEmpty() } catch (e: Exception) { "" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val textLike = mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in TEXT_EXT
        var text: String? = null
        if (textLike) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ByteArray(MAX_BYTES)
                    var n = 0
                    while (n < MAX_BYTES) {
                        val r = ins.read(buf, n, MAX_BYTES - n)
                        if (r <= 0) break
                        n += r
                    }
                    text = String(buf, 0, n, Charsets.UTF_8)
                }
            } catch (e: Exception) { }
        }
        return AttachedDoc(name, uri, mime, text)
    }
}

/**
 * v162: manual session setup (long-press the master bubble, or long-press "Multi-session" on the start screen).
 * For every session: write what it should do, an optional start address, attach any files, and pick vault items.
 * [onRun] gets the finished list. [existingCount] > 0 → the current sessions will be stopped and replaced.
 */
class ManualSetupDialog(
    private val activity: Activity,
    private val existingCount: Int,
    private val pickDocs: (onPicked: (List<AttachedDoc>) -> Unit) -> Unit,
    private val onRun: (List<SessionSpec>) -> Unit,
) {
    private class Draft {
        var task = ""
        var url = ""
        val docs = ArrayList<AttachedDoc>()
        val vault = ArrayList<String>()
    }

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val drafts = ArrayList<Draft>().apply { add(Draft()); add(Draft()) }
    private lateinit var list: LinearLayout
    private lateinit var addButton: TextView
    private lateinit var dialog: Dialog
    /** Called whenever the dialog closes (after [onRun] when it was run). */
    var onDismissed: (() -> Unit)? = null

    fun show() {
        dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F5F8.toInt()) }

        // ---- top bar
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(8), dp(10)); setBackgroundColor(Color.WHITE) }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Manual sessions", 18f, 0xFF1B1E27.toInt(), true))
        col.addView(text(
            if (existingCount > 0) "Running sessions will be stopped and replaced." else "Write what each session does. They all run at the same time.",
            12f, 0xFF6A7080.toInt(), false
        ))
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = text("✕", 20f, 0xFF4A4F5C.toInt(), false).apply { gravity = Gravity.CENTER; minWidth = dp(46); minHeight = dp(46); isClickable = true; setOnClickListener { dialog.dismiss() } }
        bar.addView(x)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- cards
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        addButton = pill("＋  Add session", 0xFFE3E9FF.toInt(), 0xFF2F6BFF.toInt()).apply {
            setOnClickListener {
                if (drafts.size >= SessionManager.MAX_SESSIONS) Toast.makeText(activity, "At most ${SessionManager.MAX_SESSIONS} sessions", Toast.LENGTH_SHORT).show()
                else { drafts.add(Draft()); render() }
            }
        }
        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- run bar
        val runBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE) }
        val hint = text("Text files are read by the AI. Other files can be uploaded to websites that ask for a file.", 11f, 0xFF6A7080.toInt(), false)
        runBar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val run = pill("▶  Run", 0xFF2F6BFF.toInt(), Color.WHITE).apply { setOnClickListener { runNow() } }
        runBar.addView(run, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        root.addView(runBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setOnDismissListener { onDismissed?.invoke() }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        render()
        dialog.show()
    }

    private fun runNow() {
        val specs = ArrayList<SessionSpec>()
        for (dr in drafts) {
            val task = dr.task.trim()
            if (task.isEmpty()) continue
            val url = dr.url.trim().let { u ->
                when {
                    u.isEmpty() -> null
                    u.startsWith("http://") || u.startsWith("https://") -> u
                    u.contains('.') && !u.contains(' ') -> "https://$u"
                    else -> null
                }
            }
            val title = task.split(Regex("\\s+")).take(3).joinToString(" ").take(24)
            specs.add(SessionSpec(title.ifBlank { "Session ${specs.size + 1}" }, task, url, dr.docs.toList(), dr.vault.toList()))
        }
        if (specs.isEmpty()) {
            Toast.makeText(activity, "Write at least one session's task", Toast.LENGTH_SHORT).show()
            return
        }
        onRun(specs)
        dialog.dismiss()
    }

    private fun render() {
        list.removeAllViews()
        for ((i, dr) in drafts.withIndex()) list.addView(card(i, dr), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        (addButton.parent as? ViewGroup)?.removeView(addButton)
        list.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun card(index: Int, dr: Draft): View {
        val c = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(1), 0xFFE2E5EE.toInt()) }
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Session ${index + 1}", 14.5f, 0xFF1B1E27.toInt(), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (drafts.size > 1) {
            head.addView(text("Remove", 12.5f, 0xFFD93036.toInt(), true).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6)); isClickable = true
                setOnClickListener { drafts.remove(dr); render() }
            })
        }
        c.addView(head)

        c.addView(field("What should this session do?", dr.task, true) { dr.task = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        c.addView(field("Start address (optional) — e.g. amazon.com", dr.url, false) { dr.url = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // ---- files + vault
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (doc in dr.docs.toList()) chips.addView(chip("📎 ${doc.name}") { dr.docs.remove(doc); render() })
        for (v in dr.vault.toList()) chips.addView(chip("🔐 $v") { dr.vault.remove(v); render() })
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("📎  Add files", 0xFFEEF0F6.toInt(), 0xFF2A2E3A.toInt()).apply {
            setOnClickListener { pickDocs { picked -> dr.docs.addAll(picked.filter { p -> dr.docs.none { it.uri == p.uri } }); render() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actions.addView(pill("🔐  Vault", 0xFFEEF0F6.toInt(), 0xFF2A2E3A.toInt()).apply { setOnClickListener { pickVault(dr) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        c.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (chips.childCount > 0) c.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return c
    }

    private fun pickVault(dr: Draft) {
        val items = try { SecretVault.list(activity) } catch (e: Exception) { emptyList() }
        if (items.isEmpty()) {
            Toast.makeText(activity, "The vault is empty. Add logins to the vault first.", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = items.map { it.title }.toTypedArray()
        val checked = BooleanArray(titles.size) { dr.vault.contains(titles[it]) }
        AlertDialog.Builder(activity)
            .setTitle("Vault items this session may use")
            .setMultiChoiceItems(titles.map { t -> t + "  (" + items.first { it.title == t }.fields.joinToString(", ") + ")" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                dr.vault.clear()
                for (i in titles.indices) if (checked[i]) dr.vault.add(titles[i])
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- small view helpers
    private fun text(t: String, size: Float, color: Int, bold: Boolean) = TextView(activity).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun pill(label: String, bg: Int, fg: Int) = TextView(activity).apply {
        text = label; textSize = 13.5f; setTextColor(fg); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11)); isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
    }

    private fun chip(label: String, onRemove: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFFF1F3F9.toInt()) }
        val t = TextView(activity).apply { text = label; textSize = 12.5f; setTextColor(0xFF2A2E3A.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply { text = "✕"; textSize = 14f; setTextColor(0xFF6A7080.toInt()); gravity = Gravity.CENTER; minWidth = dp(30); minHeight = dp(26); isClickable = true; setOnClickListener { onRemove() } })
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) } }

    private fun field(hint: String, value: String, multiline: Boolean, onChange: (String) -> Unit) = EditText(activity).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        setTextColor(0xFF1B1E27.toInt())
        setHintTextColor(0xFF8A90A0.toInt())
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        if (multiline) { minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFF4F5F8.toInt()) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
    }
}
