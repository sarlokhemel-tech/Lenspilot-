package com.hemel.lenspilot.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.R
import com.hemel.lenspilot.chat.MaxHeightScrollView
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * v122 — কীবোর্ডের ভেতরের গ্লাস-কার্ড AI চ্যাট (বাবলের গ্লাস চ্যাটের মতো)।
 * খুললে কীবোর্ডের কীগুলো চ্যাট-অ্যাপের ঘরে না গিয়ে এই প্যানেলের নিজের লেখার ঘরে ([input]) লেখে।
 * সেন্ড/এন্টারে AI-কে পাঠায়; উত্তর বাবলে আসে — "↵ বসাও" চাপলে আসল অ্যাপের ঘরে লেখা হয়, "কপি" ক্লিপবোর্ডে।
 */
class KeyboardChatPanel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val themeProvider: () -> KeyboardTheme,
    private val onInsertToField: (String) -> Unit,
    private val onClose: () -> Unit
) {
    private class Msg(val fromUser: Boolean, val text: String)

    private val messages = ArrayList<Msg>()
    private val input = StringBuilder()
    private var busy = false
    private var job: Job? = null

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    val view = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    private val scroll = MaxHeightScrollView(ctx).apply {
        maxHeightPx = dp(150)
        isVerticalScrollBarEnabled = false
    }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val inputRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val inputText = TextView(ctx).apply {
        textSize = 15f
        maxLines = 3
        minHeight = dp(38)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }
    val sendBtn = TextView(ctx).apply {
        text = "➤"
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
    }
    val closeBtn = TextView(ctx).apply {
        text = "✕"
        textSize = 15f
        gravity = Gravity.CENTER
        isClickable = true
    }

    init {
        scroll.addView(list)
        view.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        inputRow.addView(inputText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })
        inputRow.addView(closeBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })
        view.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })
        sendBtn.setOnClickListener { send() }
        closeBtn.setOnClickListener { onClose() }
        applyTheme()
        render()
    }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun open() { view.visibility = View.VISIBLE; applyTheme(); render() }
    fun close() { view.visibility = View.GONE; job?.cancel(); busy = false }

    // ---- কীবোর্ড থেকে আসা লেখা ----
    fun tail(n: Int): String = input.takeLast(n).toString()
    fun hasInput(): Boolean = input.isNotEmpty()
    fun append(s: String) { input.append(s); renderInput() }
    fun deleteLast() {
        if (input.isNotEmpty()) {
            val cp = Character.codePointBefore(input, input.length)
            input.setLength(input.length - Character.charCount(cp))
        }
        renderInput()
    }

    fun applyTheme() {
        val t = themeProvider()
        inputText.background = t.pillBg(ctx, 19)
        inputText.setTextColor(t.text)
        inputText.setHintTextColor(t.textMuted)
        sendBtn.background = t.accentBg(ctx, 19)
        sendBtn.setTextColor(t.onAccent)
        closeBtn.background = t.specialBg(ctx)
        closeBtn.setTextColor(t.text)
    }

    private fun renderInput() {
        inputText.text = if (input.isEmpty()) "" else input.toString()
        if (input.isEmpty()) inputText.hint = ctx.getString(R.string.kb_chat_hint)
        sendBtn.alpha = if (input.isBlank() || busy) 0.5f else 1f
    }

    private fun render() {
        val t = themeProvider()
        list.removeAllViews()
        if (messages.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.kb_chat_empty)
                textSize = 13f
                setTextColor(t.textMuted)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
            })
        }
        messages.forEach { m -> list.addView(bubble(m, t)) }
        if (busy) list.addView(bubble(Msg(false, "…"), t))
        renderInput()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: Msg, t: KeyboardTheme): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
            gravity = if (m.fromUser) Gravity.END else Gravity.START
        }
        val tv = TextView(ctx).apply {
            text = m.text
            textSize = 14f
            setTextColor(if (m.fromUser) t.userBubbleText else t.aiBubbleText)
            if (t.glass) { setTypeface(typeface, Typeface.BOLD); setShadowLayer(3f, 0f, 1f, 0x99000000.toInt()) }
            background = if (m.fromUser) t.bubbleUser(ctx) else t.bubbleAi(ctx)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        col.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!m.fromUser && m.text != "…") {
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(t.text)
                background = t.pillBg(ctx, 14)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isClickable = true
                setOnClickListener { onClick() }
            }
            actions.addView(chip(ctx.getString(R.string.kb_chat_insert)) { onInsertToField(m.text) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            actions.addView(chip(ctx.getString(R.string.kb_chat_copy)) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("lenspilot", m.text))
                Toast.makeText(ctx, R.string.kb_chat_copied, Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3); marginStart = dp(6) })
            col.addView(actions)
        }
        return col
    }

    /** এন্টার / ➤ — লেখাটা AI-কে পাঠায়। */
    fun send() {
        val text = input.toString().trim()
        if (text.isEmpty() || busy) return
        val hist = JSONArray()
        messages.takeLast(10).forEach { hist.put(JSONObject().put("role", if (it.fromUser) "user" else "ai").put("text", it.text)) }
        messages.add(Msg(true, text))
        input.setLength(0)
        busy = true
        render()
        val body = JSONObject().put("message", text).put("history", hist).toString()
        job?.cancel()
        job = scope.launch {
            val r = ApiClient.callAuthed(ctx, ctx.getString(R.string.space_base_url), "/api/keyboard/chat", body)
            var reply = ""
            r.onSuccess { s -> reply = runCatching { JSONObject(s).optString("reply") }.getOrDefault("") }
            r.onFailure { e ->
                reply = if (e is ApiException) runCatching { JSONObject(e.body).optString("error") }.getOrDefault("") else ""
                if (reply.isBlank()) reply = ctx.getString(R.string.kb_chat_error)
            }
            if (reply.isBlank()) reply = ctx.getString(R.string.kb_chat_error)
            messages.add(Msg(false, reply.trim()))
            busy = false
            render()
        }
    }
}
