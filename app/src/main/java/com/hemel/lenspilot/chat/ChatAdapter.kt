package com.hemel.lenspilot.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R

private const val TYPE_USER = 0
private const val TYPE_AI = 1
private const val TYPE_WORKFLOW = 2
private const val TYPE_LESSON = 3

/**
 * Simple three-view-type chat list. [onRunWorkflow] fires when the user
 * taps "Run" on a workflow card, passing the item's position so the caller
 * can update that specific card's status text as the run progresses (see
 * [setWorkflowStatus]). [onRunLesson] fires when the user taps "Run" on a
 * class card (see [LessonPreview]) — the caller should launch
 * LearningModeActivity with that topic. [onRegenerate] fires when the
 * user taps the retry/regenerate icon under an AI reply. [onRetryUserMessage]
 * fires when the user taps the retry icon under their own sent message
 * (resends it, dropping whatever reply followed).
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onRunWorkflow: (position: Int, workflow: WorkflowPreview) -> Unit,
    private val onRunLesson: (position: Int, lesson: LessonPreview) -> Unit = { _, _ -> },
    private val onReport: (position: Int, message: ChatMessage) -> Unit = { _, _ -> },
    private val onRegenerate: (position: Int, message: ChatMessage) -> Unit = { _, _ -> },
    private val onRetryUserMessage: (position: Int, message: ChatMessage) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val runningStatus = mutableMapOf<Int, String>()

    override fun getItemViewType(position: Int): Int {
        val m = messages[position]
        return when {
            m.workflow != null -> TYPE_WORKFLOW
            m.lesson != null -> TYPE_LESSON
            m.role == "user" -> TYPE_USER
            else -> TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
            TYPE_WORKFLOW -> WorkflowVH(inflater.inflate(R.layout.item_workflow_card, parent, false))
            TYPE_LESSON -> LessonVH(inflater.inflate(R.layout.item_lesson_card, parent, false))
            else -> AiVH(inflater.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = messages[position]
        when (holder) {
            is UserVH -> {
                holder.text.text = m.text
                if (m.imageBase64 != null) {
                    val bytes = runCatching { Base64.decode(m.imageBase64, Base64.NO_WRAP) }.getOrNull()
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        holder.image.setImageBitmap(bmp)
                        holder.image.visibility = View.VISIBLE
                    } else {
                        holder.image.visibility = View.GONE
                    }
                } else {
                    holder.image.visibility = View.GONE
                }
                holder.retry.setOnClickListener { onRetryUserMessage(holder.bindingAdapterPosition, m) }
            }
            is AiVH -> {
                holder.text.text = m.text
                // No point offering to act on an empty/still-streaming bubble.
                val actionable = m.text.isNotBlank() && m.text != "…" && m.text != holder.itemView.context.getString(R.string.ai_responding_placeholder)
                holder.copy.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.retry.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.report.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.copy.setOnClickListener { copyToClipboard(holder.itemView.context, m.text) }
                holder.retry.setOnClickListener { onRegenerate(holder.bindingAdapterPosition, m) }
                holder.report.setOnClickListener { onReport(holder.bindingAdapterPosition, m) }
            }
            is WorkflowVH -> {
                val wf = m.workflow ?: return
                holder.title.text = wf.title
                holder.steps.text = wf.steps.joinToString("\n") { "${it.stepNumber}. ${it.goal}" }
                val status = runningStatus[position]
                if (m.autoRun) {
                    // Auto-started from the voice-first ball — runWorkflow()
                    // already fired the moment this card was created, so
                    // there's no "Run" left to tap. Leaving the button up
                    // looked exactly like nothing had happened, and a tap
                    // on it would just start the same workflow again.
                    holder.runButton.visibility = View.GONE
                    holder.status.visibility = View.VISIBLE
                    holder.status.text = status ?: "স্বয়ংক্রিয়ভাবে চলছে…"
                } else {
                    holder.runButton.visibility = View.VISIBLE
                    holder.runButton.isEnabled = status == null
                    if (status != null) {
                        holder.status.visibility = View.VISIBLE
                        holder.status.text = status
                    } else {
                        holder.status.visibility = View.GONE
                    }
                }
                holder.runButton.setOnClickListener { onRunWorkflow(holder.bindingAdapterPosition, wf) }
            }
            is LessonVH -> {
                val lesson = m.lesson ?: return
                holder.topic.text = lesson.topic
                holder.runButton.setOnClickListener { onRunLesson(holder.bindingAdapterPosition, lesson) }
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    /** Called by the host Activity while a workflow is executing, so the
     * card shows live progress ("২/৪ ধাপ চলছে…") without rebuilding the
     * whole list. */
    fun setWorkflowStatus(position: Int, status: String?) {
        if (position < 0 || position >= messages.size) return
        if (status == null) runningStatus.remove(position) else runningStatus[position] = status
        notifyItemChanged(position)
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Lenspilot", text))
        Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        val image: ImageView = v.findViewById(R.id.messageImage)
        val retry: ImageView = v.findViewById(R.id.retryUserButton)
    }

    class AiVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        val copy: ImageView = v.findViewById(R.id.copyButton)
        val retry: ImageView = v.findViewById(R.id.retryButton)
        val report: ImageView = v.findViewById(R.id.reportButton)
    }

    class WorkflowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.workflowTitle)
        val steps: TextView = v.findViewById(R.id.workflowStepsText)
        val status: TextView = v.findViewById(R.id.workflowStatusText)
        val runButton: android.widget.Button = v.findViewById(R.id.workflowRunButton)
    }

    class LessonVH(v: View) : RecyclerView.ViewHolder(v) {
        val topic: TextView = v.findViewById(R.id.lessonTopic)
        val runButton: android.widget.Button = v.findViewById(R.id.lessonRunButton)
    }
}
