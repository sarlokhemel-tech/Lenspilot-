package com.hemel.lenspilot.browser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v147 — "Auto prompt chat" (replaces the old thinking log in the automation browser).
 *
 * Right side  : the auto prompt the app sent for each step. It shows clearly whether it went:
 *               ⏳ sending… → ✅ sent (the AI answered) → ❌ did not go.
 *               A button opens exactly the screen data that was sent.
 * Left side   : the AI's message. Under it a button opens the action code, separated from the message.
 *
 * Everything here is arranged by code. The LLM only gives text (message + commands);
 * it is never asked to format, label or lay anything out.
 */
class AutoPromptChat(
    private val context: Context,
    private val list: LinearLayout,
    private val scroll: ScrollView
) {
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private class Turn(val step: Int) {
        var sentRow: View? = null
        var sentStatus: TextView? = null
        var sentButton: TextView? = null
        var screenData: String = ""
        var sentBubble: LinearLayout? = null
        var message: String = ""
        var memory: String = ""
        var screen: String = ""
        var views: MutableList<View> = mutableListOf()
    }

    private val turns = ArrayList<Turn>()
    private val byStep = HashMap<Int, Turn>()

    // -------------------------------------------------------------------- public API (called by the agent host)

    /** The request is about to be sent. [screenData] = what the app itself knows it is sending (shown until the server's exact text arrives). */
    fun sending(step: Int, headline: String, screenData: String) {
        val t = Turn(step)
        t.screenData = screenData
        byStep[step] = t
        turns.add(t)
        val bubble = bubble(right = true)
        val title = text("📤 Auto prompt · #$step", bold = true, color = INK_ON_USER)
        val head = text(headline, color = INK_ON_USER, size = 12f)
        val status = text("⏳ Sending…", bold = true, color = INK_ON_USER)
        val btn = chip("📄 Screen data") { showScreenData(t) }
        bubble.addView(title); bubble.addView(head); bubble.addView(status); bubble.addView(btn)
        t.sentStatus = status; t.sentButton = btn; t.sentBubble = bubble
        val row = row(bubble, right = true)
        t.sentRow = row
        t.views.add(row)
        list.addView(row)
        trim(); toBottom()
    }

    /** The server answered, so the prompt certainly went. [prompt] is the exact text the model received. */
    fun delivered(step: Int, prompt: String, hadImage: Boolean) {
        val t = byStep[step] ?: return
        if (prompt.isNotBlank()) t.screenData = prompt
        t.sentStatus?.text = if (hadImage) "✅ Sent (with screenshot)" else "✅ Sent"
        toBottom()
    }

    /**
     * v150: the prompt that went, in readable parts: the message is shown in full right in the bubble; the memory (earlier steps) and
     * the screen data (element list) stay behind buttons. The system prompt is never part of any of these.
     */
    fun parts(step: Int, message: String, memory: String, screen: String) {
        val t = byStep[step] ?: return
        t.message = message; t.memory = memory; t.screen = screen
        val bubble = t.sentBubble ?: return
        if (message.isNotBlank()) {
            val tv = text(message, color = INK_ON_USER, size = 12.5f).apply { setTextIsSelectable(true) }
            bubble.addView(tv, minOf(3, bubble.childCount))
        }
        if (memory.isNotBlank()) {
            bubble.addView(chip("📒 Memory") { showText("Memory sent · #${t.step}", t.memory) })
        }
        toBottom()
    }

    /** The request did not go (network / token). */
    fun failed(step: Int, reason: String) {
        val t = byStep[step] ?: return
        t.sentStatus?.text = "❌ Did not go · $reason"
        toBottom()
    }

    /** The AI's answer: its message on the left, its action code behind a button (separate). */
    fun reply(step: Int, message: String, actionCode: String) {
        val t = byStep[step] ?: Turn(step).also { byStep[step] = it; turns.add(it) }
        val bubble = bubble(right = false)
        bubble.addView(text("🤖 AI output", bold = true, color = INK_ON_AI, size = 12f))
        bubble.addView(text(message.ifBlank { "(no message)" }, color = INK_ON_AI))
        if (actionCode.isNotBlank()) {
            val code = text(actionCode, color = Color.parseColor("#1F1E1D"), size = 12f).apply {
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#E9E2D0"))
                setPadding(px(8), px(6), px(8), px(6))
                visibility = View.GONE
                setTextIsSelectable(true)
            }
            val btn = chip("⚙ Action code") {
                val open = code.visibility != View.VISIBLE
                code.visibility = if (open) View.VISIBLE else View.GONE
                toBottom()
            }
            bubble.addView(btn); bubble.addView(code)
        } else {
            bubble.addView(text("No action", color = MUTED, size = 11f))
        }
        val row = row(bubble, right = false)
        t.views.add(row)
        list.addView(row)
        trim(); toBottom()
    }

    fun clear() {
        list.removeAllViews()
        turns.clear(); byStep.clear()
    }

    fun isEmpty() = turns.isEmpty()

    // -------------------------------------------------------------------- pure formatting helpers

    companion object {
        private val INK_ON_USER = Color.parseColor("#1F1E1D")
        private val INK_ON_AI = Color.parseColor("#1F1E1D")
        private val MUTED = Color.parseColor("#6B6862")
        private const val MAX_TURNS = 30

        private val FENCE = Regex("```[ \\t]*([A-Za-z_-]*)[ \\t]*\\r?\\n(.*?)(?:```|\\z)", RegexOption.DOT_MATCHES_ALL)

        /** The command lines from the model's raw reply (fenced block, or '>>' lines), else the parsed actions as lines. */
        fun actionCodeOf(reply: String, actions: List<AgentAction>): String {
            val text = reply.replace("\r\n", "\n")
            val fences = FENCE.findAll(text).toList()
            val chosen = fences.filter { it.groupValues[1].lowercase() in setOf("action", "actions", "act", "cmd") }.ifEmpty { fences }
            val fromFence = chosen.joinToString("\n") { it.groupValues[2].trim('\n') }.trim()
            if (fromFence.isNotBlank()) return fromFence
            val arrows = text.lines().filter { it.trimStart().startsWith(">>") }.joinToString("\n") { it.trimStart().removePrefix(">>").trim() }
            if (arrows.isNotBlank()) return arrows
            return actions.joinToString("\n") { it.json.toString() }
        }

        /** The model's chat sentence: the raw reply without its code block; falls back to the parsed note. */
        fun messageOf(reply: String, note: String, blocked: String?): String {
            val stripped = FENCE.replace(reply.replace("\r\n", "\n"), "")
                .lines().filterNot { it.trimStart().startsWith(">>") }.joinToString(" ")
                .replace("`", "").replace("**", "").trim().replace(Regex("\\s+"), " ")
            return when {
                stripped.isNotBlank() && !stripped.startsWith("{") -> stripped
                note.isNotBlank() -> note
                !blocked.isNullOrBlank() -> blocked
                else -> ""
            }
        }

        /** What the app itself can show of the screen while the exact server text is not back yet. */
        fun localScreenData(obs: Observation): String {
            val sb = StringBuilder()
            sb.append("URL: ").append(obs.url).append('\n').append("Title: ").append(obs.title).append('\n')
            sb.append("Elements shown: ").append(obs.shown).append('\n')
            val els = obs.raw.optJSONArray("elements")
            if (els != null) for (i in 0 until minOf(els.length(), 200)) sb.append(els.opt(i).toString().take(160)).append('\n')
            return sb.toString().take(30000)
        }
    }

    // -------------------------------------------------------------------- view building

    private fun showText(title: String, body: String) {
        val tv = TextView(context).apply {
            text = body.ifBlank { "(nothing recorded)" }
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(16), px(10), px(16), px(10))
        }
        AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply { addView(tv) }).setPositiveButton("Close", null).show()
    }

    private fun showScreenData(t: Turn) {
        val body = (if (t.screen.isNotBlank()) t.screen else t.screenData).ifBlank { "(nothing recorded)" }
        val tv = TextView(context).apply {
            text = body
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(16), px(10), px(16), px(10))
        }
        val sv = ScrollView(context).apply { addView(tv) }
        AlertDialog.Builder(context)
            .setTitle("Screen data sent · #${t.step}")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("screen data", body))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun bubble(right: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(8), px(12), px(8))
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            setColor(Color.parseColor(if (right) "#E6D9B8" else "#F7F2E6"))
            setStroke(px(1), Color.parseColor("#D9D1BC"))
        }
    }

    private fun row(bubble: View, right: Boolean): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(6) }
            gravity = if (right) Gravity.END else Gravity.START
        }
        wrap.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bubble.minimumWidth = px(120)
        return wrap
    }

    private fun text(s: String, bold: Boolean = false, color: Int = Color.BLACK, size: Float = 13f) = TextView(context).apply {
        text = s
        textSize = size
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt() - px(24)   // keeps a bubble from filling the whole row
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(px(2).toFloat(), 1f)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#1F1E1D"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(px(10), px(5), px(10), px(5))
        background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
            setStroke(px(1), Color.parseColor("#C9B98A"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(6) }
        setOnClickListener { onClick() }
    }

    private fun trim() {
        while (turns.size > MAX_TURNS) {
            val old = turns.removeAt(0)
            byStep.remove(old.step)
            old.views.forEach { list.removeView(it) }
        }
    }

    private fun toBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
