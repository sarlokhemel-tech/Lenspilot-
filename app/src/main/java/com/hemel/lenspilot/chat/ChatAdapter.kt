package com.hemel.lenspilot.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R

private const val TYPE_USER = 0
private const val TYPE_AI = 1
private const val TYPE_WORKFLOW = 2
private const val TYPE_LESSON = 3
private const val TYPE_ELA_RUN = 4

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
    private val onRetryUserMessage: (position: Int, message: ChatMessage) -> Unit = { _, _ -> },
    /** Fires when the user taps the small "এই কাজের জন্য শুধু" icon next
     * to ANY Run button — the ELA 1st/4N per-reply button (see
     * item_ela_run_button.xml) or a regular workflow card's Run button
     * (item_workflow_card.xml) — the caller opens a one-time (never
     * persisted) data dialog scoped to just that one card's next Run. */
    private val onAddRunInfo: (position: Int, workflow: WorkflowPreview) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val runningStatus = mutableMapOf<Int, String>()
    /** Positions that currently have one-time run-data attached (set via
     * [setRunDataAttached]) — purely cosmetic, lights up elaRunDataDot. */
    private val runDataAttached = mutableSetOf<Int>()

    override fun getItemViewType(position: Int): Int {
        val m = messages[position]
        return when {
            m.workflow?.system == "ela_1st" || m.workflow?.system == "ela_4n" -> TYPE_ELA_RUN
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
            TYPE_ELA_RUN -> ElaRunVH(inflater.inflate(R.layout.item_ela_run_button, parent, false))
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
                holder.copy.setOnClickListener { copyToClipboard(holder.itemView.context, m.text) }
                // Long-press the bubble itself also copies, same as most
                // chat apps — the small copy icon can be fiddly to hit.
                holder.text.setOnLongClickListener {
                    copyToClipboard(holder.itemView.context, m.text)
                    true
                }
            }
            is AiVH -> {
                // ELA 4N only ("Perplexity-style" reply — see ChatMessage.
                // richFormatted's doc comment): render the small **bold**/
                // "- " bullet markdown subset the backend prompt is allowed
                // to use. Every other system's m.richFormatted is false, so
                // this stays exactly the plain m.text assignment it always
                // was for them.
                holder.text.text = if (m.richFormatted) renderLightMarkdown(m.text) else m.text
                // No point offering to act on an empty/still-streaming bubble.
                val actionable = m.text.isNotBlank() && m.text != "…" && m.text != holder.itemView.context.getString(R.string.ai_responding_placeholder)
                holder.copy.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.retry.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.report.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.copy.setOnClickListener { copyToClipboard(holder.itemView.context, m.text) }
                holder.retry.setOnClickListener { onRegenerate(holder.bindingAdapterPosition, m) }
                holder.report.setOnClickListener { onReport(holder.bindingAdapterPosition, m) }
                holder.text.setOnLongClickListener {
                    if (actionable) copyToClipboard(holder.itemView.context, m.text)
                    actionable
                }

                // ELA 4N only: small "which sites" chip row under the reply.
                // Empty/null for every other system (see ChatMessage.sources'
                // doc comment) — sourcesScroll then just stays GONE, same as
                // before this feature existed.
                val sources = m.sources
                if (!sources.isNullOrEmpty()) {
                    val ctx = holder.itemView.context
                    holder.sourcesRow.removeAllViews()
                    val hPad = (10 * ctx.resources.displayMetrics.density).toInt()
                    val vPad = (4 * ctx.resources.displayMetrics.density).toInt()
                    val marginEnd = (6 * ctx.resources.displayMetrics.density).toInt()
                    for (src in sources) {
                        val chip = TextView(ctx).apply {
                            text = src
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_light))
                            setBackgroundResource(R.drawable.bg_source_chip)
                            setPadding(hPad, vPad, hPad, vPad)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { this.marginEnd = marginEnd }
                        }
                        holder.sourcesRow.addView(chip)
                    }
                    holder.sourcesScroll.visibility = View.VISIBLE
                } else {
                    holder.sourcesScroll.visibility = View.GONE
                }
            }
            is WorkflowVH -> {
                val wf = m.workflow ?: return
                holder.title.visibility = View.VISIBLE
                holder.steps.visibility = View.VISIBLE
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
                holder.dataButton.setOnClickListener { onAddRunInfo(holder.bindingAdapterPosition, wf) }
                holder.dataDot.visibility = if (position in runDataAttached) View.VISIBLE else View.GONE
            }
            is ElaRunVH -> {
                // ELA 1st: every single reply gets this small arrow button,
                // always — no title/step card, just the icon (see
                // item_ela_run_button.xml). Reuses the same
                // onRunWorkflow/WorkflowPreview plumbing as the regular
                // workflow card above, just a different, much smaller view.
                val wf = m.workflow ?: return
                val status = runningStatus[position]
                if (m.autoRun) {
                    holder.button.visibility = View.GONE
                    holder.status.visibility = View.VISIBLE
                    holder.status.text = status ?: "স্বয়ংক্রিয়ভাবে চলছে…"
                } else {
                    holder.button.visibility = View.VISIBLE
                    holder.button.isEnabled = status == null
                    holder.status.visibility = if (status != null) View.VISIBLE else View.GONE
                    if (status != null) holder.status.text = status
                }
                holder.button.setOnClickListener { onRunWorkflow(holder.bindingAdapterPosition, wf) }
                holder.dataButton.setOnClickListener { onAddRunInfo(holder.bindingAdapterPosition, wf) }
                holder.dataDot.visibility = if (position in runDataAttached) View.VISIBLE else View.GONE
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

    /** Called by MainActivity right after the one-time data dialog is
     * saved/cleared for [position], so that reply's elaRunDataDot
     * reflects whether the next Run tap will actually carry extra data. */
    fun setRunDataAttached(position: Int, attached: Boolean) {
        if (position < 0 || position >= messages.size) return
        if (attached) runDataAttached.add(position) else runDataAttached.remove(position)
        notifyItemChanged(position)
    }

    /** Renders the small markdown subset ELA4N_CHAT_PROMPT is instructed to
     * use (app.py) — "**bold**" spans and "- "/"* " bullet lines — into a
     * Spannable, with no external markdown library. Only ever called for
     * richFormatted (ELA 4N) bubbles; every other bubble still just sets
     * holder.text.text = m.text directly, unaffected by this function. */
    private fun renderLightMarkdown(raw: String): CharSequence {
        val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
        val builder = SpannableStringBuilder()
        val lines = raw.split("\n")
        lines.forEachIndexed { idx, rawLine ->
            val trimmed = rawLine.trimStart()
            val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ")
            val line = if (isBullet) "• " + trimmed.removePrefix("- ").removePrefix("* ") else rawLine
            var cursor = 0
            val matches = boldRegex.findAll(line).toList()
            if (matches.isEmpty()) {
                builder.append(line)
            } else {
                for (match in matches) {
                    builder.append(line.substring(cursor, match.range.first))
                    val boldStart = builder.length
                    builder.append(match.groupValues[1])
                    builder.setSpan(StyleSpan(Typeface.BOLD), boldStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cursor = match.range.last + 1
                }
                if (cursor < line.length) builder.append(line.substring(cursor))
            }
            if (idx != lines.lastIndex) builder.append("\n")
        }
        return builder
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
        val copy: ImageView = v.findViewById(R.id.copyUserButton)
    }

    class AiVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        val copy: ImageView = v.findViewById(R.id.copyButton)
        val retry: ImageView = v.findViewById(R.id.retryButton)
        val report: ImageView = v.findViewById(R.id.reportButton)
        val sourcesScroll: HorizontalScrollView = v.findViewById(R.id.sourcesScroll)
        val sourcesRow: LinearLayout = v.findViewById(R.id.sourcesRow)
    }

    class WorkflowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.workflowTitle)
        val steps: TextView = v.findViewById(R.id.workflowStepsText)
        val status: TextView = v.findViewById(R.id.workflowStatusText)
        val runButton: android.widget.ImageButton = v.findViewById(R.id.workflowRunButton)
        val dataButton: android.widget.ImageButton = v.findViewById(R.id.workflowRunDataButton)
        val dataDot: View = v.findViewById(R.id.workflowRunDataDot)
    }

    class LessonVH(v: View) : RecyclerView.ViewHolder(v) {
        val topic: TextView = v.findViewById(R.id.lessonTopic)
        val runButton: android.widget.Button = v.findViewById(R.id.lessonRunButton)
    }

    class ElaRunVH(v: View) : RecyclerView.ViewHolder(v) {
        val button: android.widget.ImageButton = v.findViewById(R.id.elaRunButton)
        val status: TextView = v.findViewById(R.id.elaRunStatusText)
        val dataButton: android.widget.ImageButton = v.findViewById(R.id.elaRunDataButton)
        val dataDot: View = v.findViewById(R.id.elaRunDataDot)
    }
}
