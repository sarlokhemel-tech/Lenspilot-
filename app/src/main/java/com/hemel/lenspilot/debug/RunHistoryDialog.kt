package com.hemel.lenspilot.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.R

/**
 * v167 — the "History log" screen (profile menu → 📋 History log; also: long-press the status line in the automation browser).
 * Shows what [RunHistoryLog] recorded for the automation browser and the screen guide. One tap copies it as plain text,
 * ready to paste into a message: the problem and how the run went are in it, nothing else is needed.
 */
object RunHistoryDialog {

    private const val SHOW_LAST = 40     // entries drawn on screen at once (copy always takes the whole selection)

    fun show(ctx: Context) {
        RunHistoryLog.init(ctx)
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.text_primary_light)
        val secondary = ContextCompat.getColor(ctx, R.color.text_secondary_light)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        root.addView(TextView(ctx).apply {
            text = "📋 History log"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primary)
        })
        val status = TextView(ctx).apply {
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(status)

        val row1 = LinearLayout(ctx)
        val row2 = LinearLayout(ctx)
        fun btn(label: String) = Button(ctx).apply { text = label; isAllCaps = false; textSize = 12f }
        val copyTask = btn("Copy last task")
        val copyAll = btn("Copy all")
        val problemsBtn = btn("Problems only: off")
        val clearBtn = btn("Clear")
        row1.addView(copyTask, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(copyAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(problemsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)
        root.addView(row2)

        val body = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(primary)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(ctx).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.5).toInt()
        ))

        val dialog = AlertDialog.Builder(ctx).setView(root).setNegativeButton("Close", null).create()
        var onlyProblems = false

        fun copy(text: String, what: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Lenspilot history log", text))
            Toast.makeText(ctx, "$what copied", Toast.LENGTH_SHORT).show()
        }

        fun refresh() {
            val all = RunHistoryLog.entries()
            val bad = all.count { it.problem }
            val shown = (if (onlyProblems) RunHistoryLog.problemsOnly(all) else all).takeLast(SHOW_LAST)
            status.text = if (all.isEmpty())
                "Nothing recorded yet. Run the automation browser or the screen guide once; every step (and every problem) is kept here."
            else "${all.size} entries · $bad with a problem · showing the last ${shown.size}${if (onlyProblems) " (problems only)" else ""}. " +
                "Long-press the text to select part of it."
            body.text = RunHistoryLog.render(shown)
            problemsBtn.text = "Problems only: ${if (onlyProblems) "on" else "off"}"
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        copyTask.setOnClickListener {
            val t = RunHistoryLog.render(RunHistoryLog.lastTask(RunHistoryLog.entries()))
            copy(t, "Last task")
        }
        copyAll.setOnClickListener {
            val all = RunHistoryLog.entries()
            copy(RunHistoryLog.render(if (onlyProblems) RunHistoryLog.problemsOnly(all) else all), "History log")
        }
        problemsBtn.setOnClickListener { onlyProblems = !onlyProblems; refresh() }
        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx).setMessage("Delete the whole history log?")
                .setPositiveButton("Delete") { _, _ -> RunHistoryLog.clear(); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        refresh()
        dialog.show()
    }
}
