package com.hemel.lenspilot.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
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
import org.json.JSONObject

/** Small dp->px helper, used only for the ELA 4N full-width/no-bubble
 * restyle in AiVH's bind (see there) since that's set programmatically
 * rather than fixed in item_message_ai.xml. */
private fun dpToPx(view: View, dp: Int): Int = (dp * view.resources.displayMetrics.density).toInt()

private const val TYPE_USER = 0
private const val TYPE_AI = 1
private const val TYPE_WORKFLOW = 2
private const val TYPE_LESSON = 3
private const val TYPE_ELA_RUN = 4
private const val TYPE_MA_RESOURCES = 5
private const val TYPE_FOLLOWUPS = 6

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
    private val onAddRunInfo: (position: Int, workflow: WorkflowPreview) -> Unit = { _, _ -> },
    /** Multi-agent মোড: fires when the user taps the small "Resources" button
     * under a multi-agent reply (see item_ma_resources_button.xml) — the caller
     * opens MultiAgentResourcesActivity with that message's multiAgentJson. */
    private val onOpenResources: (position: Int, message: ChatMessage) -> Unit = { _, _ -> },
    /** আলোচনা মোডের স্ক্রিন-গাইড choice কার্ডে "এগিয়ে যাই" চাপলে — [cardIndex] = guide.cards-এর index,
     * [option] = বাছা অপশনের লেখা। MainActivity কার্ডটা "উত্তর দেওয়া" হিসেবে লক করে আর option-টা ইউজারের
     * মেসেজ হিসেবে পাঠায়। */
    private val onGuideChoice: (position: Int, cardIndex: Int, option: String) -> Unit = { _, _, _ -> },
    /** v74: AI মেসেজের নিচের ছোট স্পিকার আইকন — ওই মেসেজ TTS দিয়ে পড়ে শোনায় (Gemini-র মতো)। */
    private val onSpeak: (position: Int, message: ChatMessage) -> Unit = { _, _ -> },
    /** v79: "পরের প্রশ্ন" সারিতে চাপলে — প্রশ্নটা নতুন মেসেজ হিসেবে পাঠানো হয়। */
    private val onFollowup: (question: String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** v28/v49: AI ব্রাউজার মোডে true (নাম পুরনো, এখন হালকা থিম) — AI-র উত্তর সাদা বর্ডার-বাবলে, গাঢ় লেখা।
     * MainActivity.applyBrowserModeUi সেট করে ও notifyDataSetChanged() ডাকে। */
    var darkChatTheme: Boolean = false

    /** v50: লার্নিং মোডের চ্যাট পেজে true — মেসেজ Claude-স্টাইলে: ইউজার = হালকা ধূসর-উষ্ণ গোল বাবল,
     * AI = বাবল ছাড়া, পুরো প্রস্থে, সেরিফ ফন্টে গাঢ় লেখা। MainActivity.applyLearningModeUi সেট করে। */
    var claudeStyle: Boolean = false

    private val runningStatus = mutableMapOf<Int, String>()
    /** Positions that currently have one-time run-data attached (set via
     * [setRunDataAttached]) — purely cosmetic, lights up elaRunDataDot. */
    private val runDataAttached = mutableSetOf<Int>()

    override fun getItemViewType(position: Int): Int {
        val m = messages[position]
        return when {
            // v79: ফলো-আপ প্রশ্নের আলাদা আইটেম (ফাঁকা লেখা + followups)
            m.role == "ai" && !m.followups.isNullOrEmpty() && m.text.isBlank() && m.workflow == null &&
                m.multiAgentJson == null && m.lesson == null -> TYPE_FOLLOWUPS
            // Multi-agent: the trailing blank-text item that carries the Resources
            // button INSTEAD of ELA's Run button (see MainActivity.applyWorkflowPlanResult).
            m.role == "ai" && m.multiAgentJson != null && m.text.isBlank() && m.workflow == null -> TYPE_MA_RESOURCES
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
            TYPE_MA_RESOURCES -> MaResourcesVH(inflater.inflate(R.layout.item_ma_resources_button, parent, false))
            TYPE_FOLLOWUPS -> FollowupsVH(inflater.inflate(R.layout.item_followups, parent, false))
            else -> AiVH(inflater.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = messages[position]
        when (holder) {
            is UserVH -> {
                holder.text.text = m.text
                // v49: ডার্ক থিম বাদ — ইউজার-বাবল সবসময় নীল
                if (claudeStyle) {
                    holder.text.setBackgroundResource(R.drawable.bg_bubble_user_claude)
                    holder.text.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.claude_text))
                    holder.text.textSize = 16f
                } else {
                    holder.text.setBackgroundResource(R.drawable.bg_bubble_user)
                    holder.text.setTextColor(0xFFFFFFFF.toInt())
                    holder.text.textSize = 15f
                }
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
                // ELA 4N "Perplexity-style" live status (see
                // ChatMessage.statusStage's doc comment): while a stage is
                // active, this reply hasn't started generating real text
                // yet — show the animated statusRow instead of the bubble,
                // and hold off on copy/retry/report (nothing to act on).
                if (m.statusStage != null) {
                    holder.statusRow.visibility = View.VISIBLE
                    holder.statusText.text = m.statusStage
                    holder.text.visibility = View.GONE
                    holder.copy.visibility = View.GONE
                    holder.speak.visibility = View.GONE
                    holder.retry.visibility = View.GONE
                    holder.report.visibility = View.GONE
                } else {
                    holder.statusRow.visibility = View.GONE
                    holder.text.visibility = View.VISIBLE
                }

                // FEATURE ("রিপ্লাই মেসেজ আকারে নয়, Perplexity-র মতো পুরো
                // স্ক্রিনে হতে হবে"): ELA 4N replies used to sit in the
                // same rounded chat bubble (bg_bubble_ai, wrap_content,
                // 60dp end margin) as every other reply, which is exactly
                // the "just a message" look that was asked to go away.
                // For richFormatted (ELA 4N) only, the bubble is dropped —
                // full width, no background, like Perplexity's answer
                // page — while every other system's bubble (ela_1st,
                // super_1_2, super_lite) is untouched. Layout params are
                // reset explicitly both ways since RecyclerView recycles
                // this same view between the two styles.
                val lp = holder.text.layoutParams as ViewGroup.MarginLayoutParams
                if (m.richFormatted) {
                    holder.text.background = null
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.marginEnd = dpToPx(holder.itemView, 8)
                    holder.text.setPadding(dpToPx(holder.itemView, 4), dpToPx(holder.itemView, 4),
                        dpToPx(holder.itemView, 4), dpToPx(holder.itemView, 4))
                } else {
                    holder.text.setBackgroundResource(
                        if (darkChatTheme) R.drawable.bg_auto_bubble_ai else R.drawable.bg_bubble_ai
                    )
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.marginEnd = dpToPx(holder.itemView, 60)
                    holder.text.setPadding(dpToPx(holder.itemView, 16), dpToPx(holder.itemView, 10),
                        dpToPx(holder.itemView, 16), dpToPx(holder.itemView, 10))
                }
                holder.text.layoutParams = lp
                // v49: AI Browser মোডও এখন হালকা থিম (darkChatTheme ফ্ল্যাগের মানে এখন "ব্রাউজার-থিম") — লেখা সবসময় গাঢ় রং
                holder.text.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.text_primary_light)
                )

                if (claudeStyle) {
                    // Claude-স্টাইল: বাবল নেই, পুরো প্রস্থ, সেরিফ, একটু বড় লাইন-স্পেস
                    val ctx = holder.itemView.context
                    holder.text.background = null
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.marginEnd = dpToPx(holder.itemView, 8)
                    holder.text.setPadding(dpToPx(holder.itemView, 4), dpToPx(holder.itemView, 4),
                        dpToPx(holder.itemView, 4), dpToPx(holder.itemView, 4))
                    holder.text.layoutParams = lp
                    holder.text.typeface = Typeface.SERIF
                    holder.text.textSize = 16.5f
                    holder.text.setLineSpacing(dpToPx(holder.itemView, 4).toFloat(), 1f)
                    holder.text.setTextColor(ContextCompat.getColor(ctx, R.color.claude_text))
                } else {
                    holder.text.typeface = Typeface.DEFAULT
                    holder.text.textSize = 15f
                    holder.text.setLineSpacing(0f, 1f)
                }
                holder.text.text = if (m.richFormatted || claudeStyle) {
                    val d = holder.itemView.resources.displayMetrics
                    com.hemel.lenspilot.learning.BoardMarkdown.render(
                        m.text, holder.text.paint, d.widthPixels - (40 * d.density).toInt(), d.density, light = true
                    )
                } else m.text
                // v95: কোড কার্ড (কপি বাটন) / কলআউট বক্স — উত্তরে ``` বা ::: থাকলে লেখা-অংশ ও ব্লক ক্রম মেনে blocksBox-এ আঁকা হয়
                if (m.statusStage == null && RichBlocks.hasBlocks(m.text)) {
                    val dd = holder.itemView.resources.displayMetrics
                    val useMd = m.richFormatted || claudeStyle
                    RichBlocks.build(holder.blocksBox, RichBlocks.parse(m.text), holder.text) { t ->
                        if (useMd) com.hemel.lenspilot.learning.BoardMarkdown.render(
                            t, holder.text.paint, dd.widthPixels - (40 * dd.density).toInt(), dd.density, light = true
                        ) else t
                    }
                    holder.blocksBox.visibility = View.VISIBLE
                    holder.text.visibility = View.GONE
                } else {
                    holder.blocksBox.visibility = View.GONE
                }
                // No point offering to act on an empty/still-streaming bubble.
                val actionable = m.statusStage == null && m.text.isNotBlank() && m.text != "…" && m.text != holder.itemView.context.getString(R.string.ai_responding_placeholder)
                holder.copy.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.speak.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.retry.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.report.visibility = if (actionable) View.VISIBLE else View.GONE
                holder.copy.setOnClickListener { copyToClipboard(holder.itemView.context, m.text) }
                holder.speak.setOnClickListener { onSpeak(holder.bindingAdapterPosition, m) }
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
                val cards = m.sourceCards
                if (!cards.isNullOrEmpty() && m.statusStage == null) {
                    // v94: Perplexity-স্টাইল সোর্স-কার্ড — আইকন + সাইট + শিরোনাম; চাপলে সাইট অ্যাপের ভেতরেই খোলে
                    bindSourceCards(holder, cards)
                } else if (!sources.isNullOrEmpty()) {
                    val ctx = holder.itemView.context
                    holder.sourcesRow.removeAllViews()
                    val hPad = (10 * ctx.resources.displayMetrics.density).toInt()
                    val vPad = (4 * ctx.resources.displayMetrics.density).toInt()
                    val marginEnd = (6 * ctx.resources.displayMetrics.density).toInt()
                    val srcUrls = m.sourceUrls
                    for ((srcIdx, src) in sources.withIndex()) {
                        val srcUrl = srcUrls?.getOrNull(srcIdx)?.takeIf { it.startsWith("http") }
                        val chip = TextView(ctx).apply {
                            text = if (srcUrl != null) "↗ $src" else src
                            if (srcUrl != null) {
                                // v90: লিংকসহ সোর্স — চাপলে আসল পেজ ব্রাউজারে খোলে
                                setOnClickListener {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(srcUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }
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

                bindImages(holder, m)

                // আলোচনা মোড — স্ক্রিন গাইড কার্ড (ধাপ/মার্ক-করা ছবি/অপশন)। guideJson না থাকলে সব GONE,
                // বাকি সব উত্তর আগের মতোই। স্ট্যাটাস-স্পিনার শুধু ছবি-খোঁজার সময়।
                val guideObj = if (m.statusStage == null) m.guideJson?.let { runCatching { JSONObject(it) }.getOrNull() } else null
                if (guideObj != null) {
                    holder.guideContainer.visibility = View.VISIBLE
                    GuideCardsView.render(holder.guideContainer, guideObj) { cardIndex, option ->
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) onGuideChoice(pos, cardIndex, option)
                    }
                    // শুধু কার্ড, লেখা নেই — খালি বাবল দেখানো নয়
                    if (m.text.isBlank()) holder.text.visibility = View.GONE
                } else {
                    holder.guideContainer.removeAllViews()
                    holder.guideContainer.visibility = View.GONE
                }
                val gStatus = m.guideStatus
                if (gStatus != null && m.statusStage == null && guideObj == null) {
                    holder.guideStatusRow.visibility = View.VISIBLE
                    holder.guideStatusText.text = gStatus
                } else {
                    holder.guideStatusRow.visibility = View.GONE
                }
            }
            is FollowupsVH -> bindFollowups(holder, m)
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
                    holder.status.text = status ?: "Running automatically…"
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
                    holder.status.text = status ?: "Running automatically…"
                } else {
                    holder.button.visibility = View.VISIBLE
                    holder.button.isEnabled = status == null
                    holder.status.visibility = if (status != null) View.VISIBLE else View.GONE
                    if (status != null) holder.status.text = status
                }
                holder.button.setOnClickListener { onRunWorkflow(holder.bindingAdapterPosition, wf) }
                holder.dataButton.setOnClickListener { onAddRunInfo(holder.bindingAdapterPosition, wf) }
                holder.dataDot.visibility = if (position in runDataAttached) View.VISIBLE else View.GONE

                // ELA 4N only: new "view sources" icon in this same row —
                // m.sources is only ever attached here for ela_4n (see
                // MainActivity's addMessage(... sources = sourcesList ...)
                // right after this workflow item is created), so this is
                // naturally GONE for every ela_1st reply.
                val sources = m.sources
                if (wf.system == "ela_4n" && !sources.isNullOrEmpty()) {
                    holder.sourcesButton.visibility = View.VISIBLE
                    holder.sourcesButton.setOnClickListener {
                        val ctx = holder.itemView.context
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle(ctx.getString(R.string.sources_dialog_title))
                            .setItems(sources.toTypedArray(), null)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    holder.sourcesButton.visibility = View.GONE
                }
            }
            is MaResourcesVH -> {
                holder.button.setOnClickListener { onOpenResources(holder.bindingAdapterPosition, m) }
            }
            is LessonVH -> {
                val lesson = m.lesson ?: return
                holder.topic.text = lesson.topic
                holder.label.text = lesson.label.ifBlank { "Class" }
                holder.desc.text = lesson.desc
                holder.desc.visibility = if (lesson.desc.isBlank()) View.GONE else View.VISIBLE
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

    class FollowupsVH(v: View) : RecyclerView.ViewHolder(v) {
        val box: LinearLayout = v.findViewById(R.id.followupsBox)
    }

    /** v79: Perplexity-র মতো "↳ প্রশ্ন" সারি, মাঝে সরু দাগ। */
    private fun bindFollowups(holder: FollowupsVH, m: ChatMessage) {
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val box = holder.box
        box.removeAllViews()
        val list = m.followups.orEmpty()
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        list.forEachIndexed { i, q ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt())
                isClickable = true
                isFocusable = true
                setBackgroundResource(tv.resourceId)
                contentDescription = q
            }
            row.addView(TextView(ctx).apply {
                text = "↳"
                textSize = 18f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_light))
                setPadding(0, 0, (12 * d).toInt(), 0)
            })
            row.addView(TextView(ctx).apply {
                text = q
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { onFollowup(q) }
            box.addView(row)
            if (i < list.lastIndex) {
                box.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.border_light)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, d.toInt())))
            }
        }
    }

    /** v94: সোর্স-কার্ডের সারি। কার্ড = সাদা গোল-কোণা বাক্স: ওপরে ছোট আইকন + সাইটের নাম, নিচে পেজের শিরোনাম (২ লাইন)।
     * আইকন না থাকলে সাইটের নামের প্রথম অক্ষর গোল রঙিন বৃত্তে। চাপলে [com.hemel.lenspilot.multiagent.SourceViewerActivity]। */
    private fun bindSourceCards(holder: AiVH, cards: List<SourceCard>) {
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        holder.sourcesRow.removeAllViews()
        for (card in cards) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_source_card)
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams((176 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (8 * d).toInt() }
                if (card.url.startsWith("http")) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        runCatching {
                            ctx.startActivity(
                                Intent(ctx, com.hemel.lenspilot.multiagent.SourceViewerActivity::class.java)
                                    .putExtra(com.hemel.lenspilot.multiagent.SourceViewerActivity.EXTRA_URL, card.url)
                                    .putExtra(com.hemel.lenspilot.multiagent.SourceViewerActivity.EXTRA_TITLE, card.title.ifBlank { card.site })
                                    .putExtra(com.hemel.lenspilot.multiagent.SourceViewerActivity.EXTRA_SITE, card.site)
                            )
                        }
                    }
                }
            }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val iconSize = (18 * d).toInt()
            val bmp = if (card.icon.isNotBlank()) ChatImageCache.get(card.icon) else null
            if (bmp != null) {
                head.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(iconSize, iconSize))
            } else {
                head.addView(TextView(ctx).apply {
                    text = card.site.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF6B7A99.toInt())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                        }
                    }
                }, LinearLayout.LayoutParams(iconSize, iconSize))
            }
            head.addView(TextView(ctx).apply {
                text = card.site
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_light))
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * d).toInt() })
            box.addView(head)
            box.addView(TextView(ctx).apply {
                text = card.title.ifBlank { card.site }
                textSize = 12.5f
                maxLines = 2
                minLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (5 * d).toInt() })
            holder.sourcesRow.addView(box)
        }
        holder.sourcesScroll.visibility = View.VISIBLE
    }

    /** v79: মাল্টি-এজেন্ট সার্চের ছবির সারি — গোল-কোণা থাম্বনেইল, নিচে সাইটের নাম; চাপলে উৎস-পেজ খোলে। */
    private fun bindImages(holder: AiVH, m: ChatMessage) {
        val imgs = m.images
        if (imgs.isNullOrEmpty() || m.statusStage != null) {
            holder.imagesRow.removeAllViews()
            holder.imagesScroll.visibility = View.GONE
            return
        }
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        holder.imagesRow.removeAllViews()
        for (img in imgs) {
            val bmp = ChatImageCache.get(img.b64) ?: continue
            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((150 * d).toInt(), (104 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
                setBackgroundColor(0xFFE9E7E1.toInt())
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 12 * d)
                    }
                }
            }
            frame.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (img.src.isNotBlank()) {
                frame.addView(TextView(ctx).apply {
                    text = img.src
                    textSize = 10f
                    maxLines = 1
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0x80000000.toInt())
                    setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            if (img.page.startsWith("http")) {
                frame.setOnClickListener {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(img.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            holder.imagesRow.addView(frame)
        }
        holder.imagesScroll.visibility = if (holder.imagesRow.childCount > 0) View.VISIBLE else View.GONE
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
        val speak: ImageView = v.findViewById(R.id.speakMsgButton)
        val retry: ImageView = v.findViewById(R.id.retryButton)
        val report: ImageView = v.findViewById(R.id.reportButton)
        val sourcesScroll: HorizontalScrollView = v.findViewById(R.id.sourcesScroll)
        val sourcesRow: LinearLayout = v.findViewById(R.id.sourcesRow)
        val imagesScroll: HorizontalScrollView = v.findViewById(R.id.imagesScroll)
        val imagesRow: LinearLayout = v.findViewById(R.id.imagesRow)
        val statusRow: LinearLayout = v.findViewById(R.id.statusRow)
        val statusText: TextView = v.findViewById(R.id.statusText)
        val guideStatusRow: LinearLayout = v.findViewById(R.id.guideStatusRow)
        val guideStatusText: TextView = v.findViewById(R.id.guideStatusText)
        val guideContainer: LinearLayout = v.findViewById(R.id.guideContainer)
        val blocksBox: LinearLayout = v.findViewById(R.id.blocksBox)
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
        val label: TextView = v.findViewById(R.id.lessonLabel)
        val topic: TextView = v.findViewById(R.id.lessonTopic)
        val desc: TextView = v.findViewById(R.id.lessonDesc)
        val runButton: android.widget.Button = v.findViewById(R.id.lessonRunButton)
    }

    class MaResourcesVH(v: View) : RecyclerView.ViewHolder(v) {
        val button: View = v.findViewById(R.id.maResourcesButton)
    }

    class ElaRunVH(v: View) : RecyclerView.ViewHolder(v) {
        val button: android.widget.ImageButton = v.findViewById(R.id.elaRunButton)
        val sourcesButton: android.widget.ImageButton = v.findViewById(R.id.elaSourcesButton)
        val status: TextView = v.findViewById(R.id.elaRunStatusText)
        val dataButton: android.widget.ImageButton = v.findViewById(R.id.elaRunDataButton)
        val dataDot: View = v.findViewById(R.id.elaRunDataDot)
    }
}


/** v79: base64 থাম্বনেইল একবারই ডিকোড — স্ক্রল করে ফিরলে আবার ডিকোড হয় না। */
internal object ChatImageCache {
    private val cache = LruCache<String, Bitmap>(32)
    fun get(b64: String): Bitmap? {
        val key = b64.length.toString() + ":" + b64.hashCode()
        cache.get(key)?.let { return it }
        val bmp = runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
