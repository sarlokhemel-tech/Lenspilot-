package com.hemel.lenspilot.learning

import com.hemel.lenspilot.GalleryPick
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.ApiException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * ম্যানুয়াল সিলেবাস / পাঠ / সূচিপত্র ইনপুট।
 *
 * প্রবাহ: লিখুন বা ছবি দিন → "পড়ো ও যাচাই করো" (সার্ভারের AI হুবহু পড়ে, বানায় না — অস্পষ্ট অংশ আলাদা করে দেখায়)
 * → আপনি দেখে নিশ্চিত করলে "সেভ" → প্রতিটা অংশ embedding সহ ভেক্টর ডেটাবেজে (Firestore) জমা হয়।
 * পরে Learning Mode ওই সিলেবাসের কাছের অংশ তুলে এনে পাঠ/পরিকল্পনা সেই সিলেবাস মেনে বানায়।
 * সার্ভার: /api/syllabus/{parse,save,list,delete} (app.py, "ম্যানুয়াল সিলেবাস" ব্লক)।
 */
class SyllabusActivity : AppCompatActivity() {

    private class Item(val id: String, val subject: String, val classLevel: String, val title: String, val content: String)

    private val bg by lazy { ContextCompat.getColor(this, R.color.blackboard_bg) }
    private val chalk by lazy { ContextCompat.getColor(this, R.color.chalk_white) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.chalk_muted) }
    private val accent by lazy { ContextCompat.getColor(this, R.color.browser_accent) }
    private val warn by lazy { ContextCompat.getColor(this, R.color.warning_caution) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var subjectInput: EditText
    private lateinit var classInput: EditText
    private lateinit var textInput: EditText
    private lateinit var imagesRow: LinearLayout
    private lateinit var readButton: TextView
    private lateinit var reviewBox: LinearLayout
    private lateinit var listBox: LinearLayout

    private val images = ArrayList<String>()
    private var draft: JSONObject? = null
    private var draftSource = "text"
    private var busy = false
    private var items: List<Item> = emptyList()
    private val expanded = HashSet<String>()

    private val pickImage = registerForActivityResult(GalleryPick.Contract()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (images.size >= MAX_IMAGES) {
            Toast.makeText(this, "Up to $MAX_IMAGES images", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val b64 = decode(uri)
        if (b64 == null) Toast.makeText(this, "The image could not be read", Toast.LENGTH_SHORT).show()
        else { images.add(b64); renderImages() }
    }

    private companion object {
        const val MAX_IMAGES = 4
    }

    // ------------------------------------------------------------------ UI helpers
    private fun tv(text: String, size: Float, color: Int = chalk, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@SyllabusActivity, R.drawable.bg_blackboard_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun button(label: String, onClick: () -> Unit) = tv(label, 14f, accent).apply {
        background = ContextCompat.getDrawable(this@SyllabusActivity, R.drawable.bg_blackboard_input)
        setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, lines: Int = 1): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(chalk)
        textSize = 14f
        background = ContextCompat.getDrawable(this@SyllabusActivity, R.drawable.bg_blackboard_input)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        bar.addView(tv("←", 24f).apply { setPadding(dp(12), dp(4), dp(16), dp(4)); setOnClickListener { finish() } })
        bar.addView(tv("📖 My syllabus", 18f, chalk, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        content.addView(tv(
            "Enter your syllabus, lessons or a book's table of contents, or add pictures. The AI reads it exactly as written and adds nothing of its own. It is saved only after you review and confirm. " +
                "After this, Learning Mode will teach according to your syllabus.", 13f, muted))

        // ---- ইনপুট কার্ড ----
        val input = card()
        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        subjectInput = field("Subject / book (optional)")
        classInput = field("Grade / level (optional)")
        labels.addView(subjectInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        labels.addView(classInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        input.addView(labels)
        textInput = field("Type or paste the syllabus, lessons or table of contents here…", 6)
        input.addView(textInput, lp(top = dp(8)))
        imagesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input.addView(imagesRow, lp(top = dp(8)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("🖼 Add image") { pickImage.launch(Unit) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        readButton = button("🔍 Read and verify") { readDraft() }
        actions.addView(readButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        input.addView(actions, lp(top = dp(10)))
        content.addView(input)

        reviewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        content.addView(reviewBox)

        content.addView(tv("Saved syllabus", 16f, chalk, true).apply { setPadding(0, dp(22), 0, 0) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)

        renderImages()
        loadList()
    }

    // ------------------------------------------------------------------ images
    private fun decode(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return@use null
            val longest = maxOf(original.width, original.height)
            val bmp = if (longest > 1600) {
                val s = 1600f / longest
                Bitmap.createScaledBitmap(original, (original.width * s).toInt().coerceAtLeast(1), (original.height * s).toInt().coerceAtLeast(1), true)
            } else original
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)   // লেখা পড়ার জন্য একটু বেশি মান
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }

    private fun renderImages() {
        imagesRow.removeAllViews()
        imagesRow.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
        images.forEachIndexed { idx, b64 ->
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val frame = FrameLayout(this)
            frame.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(dp(64), dp(64)))
            frame.addView(tv("✕", 12f, chalk, true).apply {
                setBackgroundColor(0xCC000000.toInt()); setPadding(dp(5), dp(1), dp(5), dp(1))
                setOnClickListener { images.removeAt(idx); renderImages() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            imagesRow.addView(frame, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) })
        }
    }

    // ------------------------------------------------------------------ parse (খসড়া)
    private fun errorOf(e: Throwable): String {
        val msg = if (e is ApiException) { try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" } } else ""
        return msg.ifBlank { "A network problem occurred. Please try again." }.take(160)
    }

    private fun readDraft() {
        if (busy) return
        val text = textInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && images.isEmpty()) {
            Toast.makeText(this, "Type something or add at least one image", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        val body = JSONObject().apply {
            put("text", text)
            put("images_base64", JSONArray(images))
            put("subject", subjectInput.text?.toString()?.trim().orEmpty())
            put("class_or_level", classInput.text?.toString()?.trim().orEmpty())
        }
        val src = if (text.isEmpty() && images.isNotEmpty()) "image" else "text"
        lifecycleScope.launch {
            val r = ApiClient.callAuthed(this@SyllabusActivity, getString(R.string.space_base_url), "/api/syllabus/parse", body.toString())
            r.onSuccess { s ->
                val d = try { JSONObject(s).getJSONObject("draft") } catch (e: Exception) { null }
                if (d == null) Toast.makeText(this@SyllabusActivity, "Could not read the response", Toast.LENGTH_SHORT).show()
                else { draft = d; draftSource = src; renderReview() }
            }.onFailure { e ->
                Toast.makeText(this@SyllabusActivity, errorOf(e), Toast.LENGTH_LONG).show()
            }
            setBusy(false)
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        readButton.text = if (b) "⏳ Reading…" else "🔍 Read and verify"
        readButton.alpha = if (b) 0.6f else 1f
    }

    private fun renderReview() {
        reviewBox.removeAllViews()
        val d = draft ?: run { reviewBox.visibility = View.GONE; return }
        reviewBox.visibility = View.VISIBLE
        val c = card()
        if (!d.optBoolean("ok", true)) {
            c.addView(tv("This does not look like a syllabus or table of contents", 15f, warn, true))
            c.addView(tv(d.optString("reason").ifBlank { "The text or image is not clear." }, 13f, muted).apply { setPadding(0, dp(6), 0, 0) })
            c.addView(button("OK") { draft = null; renderReview() }, lp(top = dp(10)))
            reviewBox.addView(c)
            return
        }
        val entries = d.optJSONArray("entries") ?: JSONArray()
        val subject = d.optString("subject")
        val level = d.optString("class_or_level")
        c.addView(tv("Review it, then save if it is correct", 15f, chalk, true))
        c.addView(tv(
            "Subject: ${subject.ifBlank { "—" }} · Level: ${level.ifBlank { "—" }} · ${entries.length()} sections",
            12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        if (subject.isBlank()) {
            c.addView(tv("The subject name was not found. Enter it in the \"Subject / book\" field above and read again.", 12f, warn).apply { setPadding(0, dp(6), 0, 0) })
        }
        val unclear = d.optJSONArray("unclear")
        if (d.optString("confidence") == "low" || (unclear != null && unclear.length() > 0)) {
            val sb = StringBuilder("⚠️ Some parts could not be read correctly. Please check below.")
            if (unclear != null) for (i in 0 until minOf(unclear.length(), 6)) sb.append("\n• ").append(unclear.optString(i))
            c.addView(tv(sb.toString(), 12f, warn).apply { setPadding(0, dp(6), 0, 0) })
        }
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            c.addView(tv(e.optString("title"), 13f, accent, true).apply { setPadding(0, dp(10), 0, 0) })
            c.addView(tv(e.optString("content"), 12f, chalk).apply { maxLines = 6 })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("✅ It is correct, save") { saveDraft() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply { marginEnd = dp(6) })
        row.addView(button("✖ Cancel") { draft = null; renderReview() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row, lp(top = dp(12)))
        reviewBox.addView(c)
    }

    // ------------------------------------------------------------------ save
    private fun saveDraft() {
        val d = draft ?: return
        if (busy) return
        val entries = d.optJSONArray("entries") ?: return
        setBusy(true)
        val body = JSONObject().apply {
            put("subject", d.optString("subject"))
            put("class_or_level", d.optString("class_or_level"))
            put("source", draftSource)
            put("entries", entries)
        }
        lifecycleScope.launch {
            val r = ApiClient.callAuthed(this@SyllabusActivity, getString(R.string.space_base_url), "/api/syllabus/save", body.toString())
            r.onSuccess { s ->
                val n = try { JSONObject(s).optInt("saved") } catch (e: Exception) { 0 }
                Toast.makeText(this@SyllabusActivity, "✅ $n sections saved", Toast.LENGTH_SHORT).show()
                draft = null
                textInput.setText(""); images.clear(); renderImages(); renderReview()
                loadList()
            }.onFailure { e ->
                Toast.makeText(this@SyllabusActivity, errorOf(e), Toast.LENGTH_LONG).show()
            }
            setBusy(false)
        }
    }

    // ------------------------------------------------------------------ saved list
    private fun loadList() {
        lifecycleScope.launch {
            val r = ApiClient.getAuthed(this@SyllabusActivity, getString(R.string.space_base_url), "/api/syllabus/list")
            r.onSuccess { s ->
                val arr = try { JSONObject(s).optJSONArray("items") } catch (e: Exception) { null }
                val out = ArrayList<Item>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(Item(o.optString("id"), o.optString("subject"), o.optString("class_or_level"), o.optString("title"), o.optString("content")))
                }
                items = out
                renderList()
            }.onFailure {
                listBox.removeAllViews()
                listBox.addView(tv("Could not load the list. Check your connection and open it again.", 13f, muted))
            }
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(tv("Nothing is saved yet. Add something above by typing or with a picture.", 13f, muted).apply { setPadding(0, dp(6), 0, 0) })
            return
        }
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (entry in items) groups.getOrPut(entry.subject) { ArrayList() }.add(entry)
        for ((subject, list) in groups) {
            val c = card()
            val level = list.firstOrNull()?.classLevel.orEmpty()
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = subject.ifBlank { "Syllabus" } + (if (level.isNotBlank()) " ($level)" else "")
            head.addView(tv("📘 $label · ${list.size} sections", 15f, chalk, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(tv("🗑", 18f).apply {
                setPadding(dp(10), dp(2), dp(2), dp(2))
                setOnClickListener { confirmDelete("Delete all sections of \"$label\"?", subject = subject) }
            })
            c.addView(head)
            val open = expanded.contains(subject)
            c.addView(tv(if (open) "▲ Collapse" else "▼ Show", 12f, accent).apply { setPadding(0, dp(4), 0, 0) })
            head.setOnClickListener { toggle(subject) }
            c.setOnClickListener { toggle(subject) }
            if (open) {
                for (entry in list) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(tv(entry.title, 13f, accent, true))
                    col.addView(tv(entry.content, 12f, chalk).apply { maxLines = 8 })
                    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(tv("🗑", 16f).apply {
                        setPadding(dp(10), dp(2), dp(2), dp(2))
                        setOnClickListener { confirmDelete("Delete this section?", id = entry.id) }
                    })
                    c.addView(row)
                }
            }
            listBox.addView(c)
        }
    }

    private fun toggle(subject: String) {
        if (!expanded.add(subject)) expanded.remove(subject)
        renderList()
    }

    private fun confirmDelete(message: String, id: String? = null, subject: String? = null) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                val body = JSONObject().apply {
                    if (id != null) put("id", id)
                    if (subject != null) put("subject", subject)
                }
                lifecycleScope.launch {
                    val r = ApiClient.callAuthed(this@SyllabusActivity, getString(R.string.space_base_url), "/api/syllabus/delete", body.toString())
                    r.onFailure { e -> Toast.makeText(this@SyllabusActivity, errorOf(e), Toast.LENGTH_LONG).show() }
                    loadList()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }
}
