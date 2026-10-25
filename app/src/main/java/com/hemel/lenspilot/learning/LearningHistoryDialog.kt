package com.hemel.lenspilot.learning

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R
import com.hemel.lenspilot.chat.HistoryAdapter

/**
 * Dark "blackboard" history of Learning Mode lessons (see [LearningHistoryStore]). Shown from:
 *   - the home page's history button while Learning Mode is on (dark theme), and
 *   - the history button on the lesson screen itself.
 * Completely separate from the normal chat history. Tapping a lesson opens its saved board notes
 * with a "Teach again" button ([onTeachAgain] gets the original topic).
 */
object LearningHistoryDialog {

    fun show(activity: Activity, onTeachAgain: (String) -> Unit, onDismiss: (() -> Unit)? = null) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_learning_history, null)
        val list = view.findViewById<RecyclerView>(R.id.learningHistoryList)
        val empty = view.findViewById<TextView>(R.id.learningHistoryEmpty)
        val dialog = newDarkDialog(activity, view)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        view.findViewById<ImageButton>(R.id.learningHistoryClose).setOnClickListener { dialog.dismiss() }

        var lessons = LearningHistoryStore.list(activity)
        lateinit var adapter: Adapter
        fun refresh() {
            lessons = LearningHistoryStore.list(activity)
            adapter.submit(lessons)
            empty.visibility = if (lessons.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (lessons.isEmpty()) View.GONE else View.VISIBLE
        }
        adapter = Adapter(
            onOpen = { lesson ->
                showNotes(activity, lesson, onTeachAgain = {
                    dialog.dismiss()
                    onTeachAgain(lesson.topic)
                }, onDeleted = { refresh() })
            },
            onDelete = { lesson ->
                LearningHistoryStore.delete(activity, lesson.id)
                refresh()
            }
        )
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        refresh()
        dialog.show()
        sizeDialog(activity, dialog)
    }

    private fun showNotes(
        activity: Activity,
        lesson: LearningHistoryStore.Lesson,
        onTeachAgain: () -> Unit,
        onDeleted: () -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_learning_notes, null)
        view.findViewById<TextView>(R.id.lnTitle).text = lesson.title.ifBlank { lesson.topic }
        val body = when {
            lesson.board.isNotBlank() -> lesson.board
            lesson.outline.isNotEmpty() -> lesson.outline.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
            else -> lesson.topic
        }
        view.findViewById<TextView>(R.id.lnBody).text = body
        val dialog = newDarkDialog(activity, view)
        view.findViewById<TextView>(R.id.lnClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.lnTeachAgain).setOnClickListener {
            dialog.dismiss()
            onTeachAgain()
        }
        dialog.show()
        sizeDialog(activity, dialog)
    }

    private fun newDarkDialog(activity: Activity, content: View): Dialog {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return d
    }

    private fun sizeDialog(activity: Activity, d: Dialog) {
        val dm = activity.resources.displayMetrics
        val maxW = (560 * dm.density).toInt()
        val w = minOf((dm.widthPixels * 0.92f).toInt(), maxW)
        val h = (dm.heightPixels * 0.82f).toInt()
        d.window?.setLayout(w, h)
    }

    private class Adapter(
        private val onOpen: (LearningHistoryStore.Lesson) -> Unit,
        private val onDelete: (LearningHistoryStore.Lesson) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var rows: List<LearningHistoryStore.Lesson> = emptyList()

        fun submit(list: List<LearningHistoryStore.Lesson>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_learning_history_row, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val l = rows[position]
            holder.title.text = l.title.ifBlank { l.topic }
            val snippet = l.board.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            holder.snippet.text = snippet
            holder.snippet.visibility = if (snippet.isBlank()) View.GONE else View.VISIBLE
            holder.time.text = HistoryAdapter.formatTimestamp(l.updatedAt)
            holder.itemView.setOnClickListener { onOpen(l) }
            holder.delete.setOnClickListener { onDelete(l) }
        }

        override fun getItemCount(): Int = rows.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.lhRowTitle)
            val snippet: TextView = v.findViewById(R.id.lhRowSnippet)
            val time: TextView = v.findViewById(R.id.lhRowTime)
            val delete: ImageButton = v.findViewById(R.id.lhRowDelete)
        }
    }
}
