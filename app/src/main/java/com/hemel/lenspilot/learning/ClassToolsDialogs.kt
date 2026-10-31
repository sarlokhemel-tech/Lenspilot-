package com.hemel.lenspilot.learning

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v31 — ক্লাস-টুলস ও রেকর্ড-করা-ক্লাসের ডায়ালগের সাধারণ হেল্পার (ডার্ক "ব্লাকবোর্ড" থিম, কোডে বানানো)। */
internal object ClassUi {
    fun dp(a: AppCompatActivity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    fun tv(a: AppCompatActivity, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(a).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun chalk(a: AppCompatActivity): Int = ContextCompat.getColor(a, R.color.chalk_white)
    fun muted(a: AppCompatActivity): Int = ContextCompat.getColor(a, R.color.chalk_muted)
    fun accent(a: AppCompatActivity): Int = ContextCompat.getColor(a, R.color.toggle_on)

    fun button(a: AppCompatActivity, label: String, color: Int = accent(a), onClick: () -> Unit): TextView =
        tv(a, label, 13.5f, color, bold = true).apply {
            background = ContextCompat.getDrawable(a, R.drawable.bg_blackboard_input)
            setPadding(dp(a, 12), dp(a, 9), dp(a, 12), dp(a, 9))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    fun newDialog(a: AppCompatActivity, content: View, heightFrac: Float = 0.85f): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnShowListener {
            val dm = a.resources.displayMetrics
            val w = minOf((dm.widthPixels * 0.92f).toInt(), (560 * dm.density).toInt())
            d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            // বেশি লম্বা হলে স্ক্রল করার জন্য সর্বোচ্চ উচ্চতা সীমিত
            val maxH = (dm.heightPixels * heightFrac).toInt()
            val root = content
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
            )
            if (root.measuredHeight >= maxH) d.window?.setLayout(w, maxH)
        }
        return d
    }

    fun card(a: AppCompatActivity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(a, R.drawable.bg_blackboard_card)
        setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14))
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun fmtSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%d KB", (bytes / 1024).coerceAtLeast(1))
    }

    /** সেভ হওয়ার পর: কোথায় সেভ হয়েছে + খোলো/শেয়ার। */
    fun showSaved(a: AppCompatActivity, saved: MediaExport.Saved, what: String) {
        if (a.isFinishing || a.isDestroyed) return
        AlertDialog.Builder(a)
            .setTitle("$what saved ✓")
            .setMessage(saved.where)
            .setPositiveButton("Open") { _, _ ->
                if (!MediaExport.open(a, saved)) Toast.makeText(a, "No app found to open this file. Look for it in your file manager.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Share") { _, _ -> MediaExport.share(a, saved, what) }
            .setNegativeButton("OK", null)
            .show()
    }

    /** একটা ফাইল Download/Lenspilot-এ সেভ করে (ব্যাকগ্রাউন্ডে), শেষে ডায়ালগ দেখায়। */
    fun saveAndShow(a: AppCompatActivity, src: File, displayName: String, mime: String, what: String) {
        a.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { MediaExport.saveToDownloads(a, src, displayName, mime) }
            if (saved == null) {
                Toast.makeText(a, "Could not save $what. Check whether the phone storage is full.", Toast.LENGTH_LONG).show()
            } else {
                showSaved(a, saved, what)
            }
        }
    }

    /** নোট-আইটেম থেকে PDF বানিয়ে সেভ করে। [loader] ব্যাকগ্রাউন্ডে ডাকা হয়। */
    fun exportPdf(
        a: AppCompatActivity,
        title: String,
        topic: String,
        noteForce: Int,
        loader: () -> List<ClassNotesPdf.NoteItem>
    ) {
        Toast.makeText(a, "Creating PDF notes…", Toast.LENGTH_SHORT).show()
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val items = loader()
                    if (items.isEmpty()) {
                        null
                    } else {
                        val tmp = File(a.cacheDir, "class_notes_${System.currentTimeMillis()}.pdf")
                        if (!ClassNotesPdf.build(a, title, topic, items, noteForce, tmp)) {
                            null
                        } else {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            val name = MediaExport.safeName(title.ifBlank { topic }, "Class") + "_notes_" + stamp + ".pdf"
                            val saved = MediaExport.saveToDownloads(a, tmp, name, "application/pdf")
                            try { tmp.delete() } catch (_: Throwable) {}
                            saved
                        }
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (result == null) {
                Toast.makeText(a, "Could not create notes. Nothing has been written on the board yet, or saving failed.", Toast.LENGTH_LONG).show()
            } else {
                showSaved(a, result, "PDF notes")
            }
        }
    }
}

/** 📝 বাটন: Note Force (অন/অফ + পাওয়ার), PDF নোট, রেকর্ড-করা ক্লাসের তালিকা। */
object ClassToolsDialog {

    private fun powerName(level: Int): String = when {
        level <= 3 -> "Light"
        level <= 6 -> "Medium"
        else -> "Full power"
    }

    /** Note Force-এর অন/অফ + স্লাইডার — ক্লাস-টুলস ও লাইব্রেরি দুই জায়গায় একই Prefs। */
    internal fun noteForceBlock(a: AppCompatActivity): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val chalk = ClassUi.chalk(a)
        val muted = ClassUi.muted(a)

        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            ClassUi.tv(a, "Note Force", 16f, chalk, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = SwitchCompat(a).apply { isChecked = Prefs.noteForceOn(a) }
        head.addView(sw)
        box.addView(head)

        val label = ClassUi.tv(a, "", 13f, muted).apply { setPadding(0, ClassUi.dp(a, 8), 0, 0) }
        val seek = SeekBar(a).apply {
            max = Prefs.NOTE_FORCE_MAX - 1
            progress = Prefs.noteForceLevel(a) - 1
        }

        fun refresh() {
            val on = sw.isChecked
            val lvl = seek.progress + 1
            seek.isEnabled = on
            seek.alpha = if (on) 1f else 0.45f
            label.text = if (on) "Power: $lvl/10 — ${powerName(lvl)}" else "Off. The class works as usual"
        }
        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.setNoteForceOn(a, checked)
            refresh()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Prefs.setNoteForceLevel(a, p + 1)
                refresh()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refresh()
        box.addView(label)
        box.addView(seek)
        box.addView(
            ClassUi.tv(
                a,
                "When on, a higher power makes the AI write more polished, organized and detailed notes on the blackboard during class; a lower power writes fewer notes. " +
                    "When off, the class works as usual. It takes effect from your next question or the next class.",
                12f, muted
            ).apply { setPadding(0, ClassUi.dp(a, 4), 0, 0) }
        )
        return box
    }

    fun show(
        a: AppCompatActivity,
        onPdf: () -> Unit,
        onRecordings: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val chalk = ClassUi.chalk(a)
        val card = ClassUi.card(a)
        card.addView(ClassUi.tv(a, "📝 Class notes and recordings", 17f, chalk, bold = true))
        card.addView(noteForceBlock(a).apply { setPadding(0, ClassUi.dp(a, 14), 0, 0) })

        val gap = ClassUi.dp(a, 10)
        card.addView(
            ClassUi.button(a, "📄 Export this class's notes as PDF") { onPdf() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap + gap }
        )
        var dialogRef: Dialog? = null
        card.addView(
            ClassUi.button(a, "🎬 Recorded classes (replay / download)") { dialogRef?.dismiss(); onRecordings() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap }
        )
        val dialog = ClassUi.newDialog(a, ScrollView(a).apply { addView(card) })
        card.addView(
            ClassUi.button(a, "Close", ClassUi.muted(a)) { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap }
        )
        dialogRef = dialog
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}

/** 🎬 রেকর্ড করা ক্লাসের তালিকা: আবার চালাও (কোনো জেনারেশন ছাড়া), ভিডিও ডাউনলোড, PDF নোট, মোছা। */
object ClassRecordingsDialog {

    fun show(
        a: AppCompatActivity,
        onReplay: (String) -> Unit,
        onExportVideo: (String) -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        val chalk = ClassUi.chalk(a)
        val muted = ClassUi.muted(a)
        val card = ClassUi.card(a)
        card.addView(ClassUi.tv(a, "🎬 Recorded classes", 17f, chalk, bold = true))
        card.addView(
            ClassUi.tv(
                a,
                "▶ Playing runs the class exactly as before. Nothing needs to be generated again (no server or token cost).",
                12f, muted
            ).apply { setPadding(0, ClassUi.dp(a, 4), 0, ClassUi.dp(a, 6)) }
        )
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list)

        val scroll = ScrollView(a).apply { addView(card) }
        val dialog = ClassUi.newDialog(a, scroll)
        dialog.setOnDismissListener { onDismiss?.invoke() }

        fun refresh() {
            list.removeAllViews()
            val items = ClassRecordingStore.list(a)
            if (items.isEmpty()) {
                list.addView(
                    ClassUi.tv(a, "No class has been recorded yet. Tap the ⏺ button at the top during a class to record the next one.", 14f, muted)
                        .apply { setPadding(0, ClassUi.dp(a, 12), 0, ClassUi.dp(a, 6)) }
                )
            }
            for (m in items) list.addView(row(a, m, dialog, onReplay, onExportVideo) { refreshHolder?.invoke() })
            list.addView(
                ClassUi.button(a, "Close", muted) { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ClassUi.dp(a, 12) }
            )
        }
        refreshHolder = { refresh() }
        refresh()
        dialog.show()
    }

    // row-এর ভেতর থেকে তালিকা নতুন করে সাজাতে (মোছার পর)
    private var refreshHolder: (() -> Unit)? = null

    private fun row(
        a: AppCompatActivity,
        m: ClassRecordingStore.Meta,
        dialog: Dialog,
        onReplay: (String) -> Unit,
        onExportVideo: (String) -> Unit,
        onChanged: () -> Unit
    ): View {
        val chalk = ClassUi.chalk(a)
        val muted = ClassUi.muted(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(a, R.drawable.bg_blackboard_input)
            setPadding(ClassUi.dp(a, 12), ClassUi.dp(a, 10), ClassUi.dp(a, 12), ClassUi.dp(a, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ClassUi.dp(a, 10) }
        }
        box.addView(ClassUi.tv(a, m.title.ifBlank { m.topic }.ifBlank { "Class" }, 15f, chalk, bold = true).apply { maxLines = 2 })
        val whenText = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(m.createdAt))
        val info = buildString {
            append(whenText)
            if (m.durationMs > 0) append("  ·  ⏱ ").append(ClassUi.fmtDuration(m.durationMs))
            append("  ·  ").append(ClassUi.fmtSize(m.sizeBytes))
            if (m.hasVideo) append("  ·  🎞 Video available")
            if (m.noteForce > 0) append("  ·  Note Force ${m.noteForce}")
        }
        box.addView(ClassUi.tv(a, info, 11.5f, muted).apply { setPadding(0, ClassUi.dp(a, 2), 0, ClassUi.dp(a, 8)) })

        val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ClassUi.dp(a, 6), 0, 0) }
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ClassUi.dp(a, 6) }

        r1.addView(ClassUi.button(a, "▶ Play again") {
            dialog.dismiss()
            onReplay(m.id)
        }, lp())
        if (m.hasVideo) {
            r1.addView(ClassUi.button(a, "⬇ Download video") {
                val vf = ClassRecordingStore.videoFile(a, m.id)
                val name = MediaExport.safeName(m.title.ifBlank { m.topic }, "Class") + "_class.mp4"
                ClassUi.saveAndShow(a, vf, name, "video/mp4", "Class video")
            }, lp())
        } else {
            r1.addView(ClassUi.button(a, "🎞 Create video") {
                dialog.dismiss()
                onExportVideo(m.id)
            }, lp())
        }
        r2.addView(ClassUi.button(a, "📄 PDF notes") {
            ClassUi.exportPdf(a, m.title, m.topic, m.noteForce) { ClassRecordingStore.loadNoteItems(a, m.id) }
        }, lp())
        r2.addView(ClassUi.button(a, "🗑 Delete", Color.parseColor("#F87171")) {
            AlertDialog.Builder(a)
                .setTitle("Delete this recording?")
                .setMessage(m.title.ifBlank { m.topic })
                .setPositiveButton("Delete") { _, _ ->
                    ClassRecordingStore.delete(a, m.id)
                    onChanged()
                }
                .setNegativeButton("No", null)
                .show()
        }, lp())
        box.addView(r1)
        box.addView(r2)
        return box
    }
}
