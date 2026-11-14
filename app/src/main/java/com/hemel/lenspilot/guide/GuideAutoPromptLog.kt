package com.hemel.lenspilot.guide

import com.hemel.lenspilot.browser.AutoPromptChat
import org.json.JSONObject

/**
 * v156 — the screen guide's "Auto prompt chat" (same idea as the automation browser's v147 chat).
 *
 * The guide loop sends a prompt to the AI by itself on every trigger / screen change. This class RECORDS each of those in the
 * background (whether or not the chat window is open) and, when the user opens the "Auto prompt" view in the guide chat, replays
 * them into an [AutoPromptChat]: what was sent (⏳ → ✅ / ❌, screen data, memory) on the right, the AI's message and action
 * code on the left.
 *
 * It only RECORDS what the server returned (the exact prompt text, the raw reply). It decides nothing and changes no behaviour of
 * the guide: no action is taken here — a click is still only ever shown as a highlight.
 */
class GuideAutoPromptLog {

    private class Step(val no: Int, val headline: String, val localData: String) {
        var delivered = false
        var hadImage = false
        var prompt = ""
        var message = ""
        var memory = ""
        var screen = ""
        var failed: String? = null
        var replyMessage: String? = null      // null = no AI answer yet
        var actionCode = ""
    }

    private val steps = ArrayList<Step>()
    private var live: AutoPromptChat? = null

    // ---------------------------------------------------------------- recording (called by GuideLoopV3, main thread)

    fun clear() {
        steps.clear()
        live?.clear()
    }

    /** The request is about to go. [localData] = what the app itself knows it is sending, until the server's exact text arrives. */
    fun sending(no: Int, headline: String, localData: String) {
        val st = Step(no, headline, localData)
        steps.add(st)
        while (steps.size > MAX_STEPS) steps.removeAt(0)
        live?.sending(no, headline, localData)
    }

    /** The server answered (so the prompt certainly went). [r] = the whole result; its "chat" field carries the exact prompt/reply. */
    fun answered(no: Int, r: JSONObject) {
        val st = steps.firstOrNull { it.no == no } ?: return
        val chat = r.optJSONObject("chat")
        st.delivered = true
        st.hadImage = chat?.optBoolean("image", false) ?: false
        st.prompt = chat?.optString("prompt").orEmpty()
        st.message = chat?.optString("message").orEmpty()
        st.memory = chat?.optString("memory").orEmpty()
        st.screen = chat?.optString("screen").orEmpty()
        val rawReply = chat?.optString("reply").orEmpty()
        val note = r.optString("note", "")
        val say = r.optString("say", "")
        st.replyMessage = AutoPromptChat.messageOf(rawReply, note, null).ifBlank { say }.ifBlank { "(no message)" }
        st.actionCode = AutoPromptChat.actionCodeOf(rawReply, emptyList())
            .ifBlank { r.optJSONObject("do")?.toString().orEmpty() }
        live?.let { render(it, st, replayed = false) }
    }

    /** The request did not go / the server returned an error. */
    fun failed(no: Int, reason: String) {
        val st = steps.firstOrNull { it.no == no } ?: return
        st.failed = reason.take(120)
        live?.failed(no, st.failed!!)
    }

    // ---------------------------------------------------------------- viewing

    /** Show everything recorded so far in [chat]; later records appear live until [detach]. */
    fun attach(chat: AutoPromptChat) {
        live = null
        chat.clear()
        steps.forEach { st ->
            chat.sending(st.no, st.headline, st.localData)
            render(chat, st, replayed = true)
        }
        live = chat
    }

    fun detach() { live = null }

    val isEmpty: Boolean get() = steps.isEmpty()

    private fun render(chat: AutoPromptChat, st: Step, replayed: Boolean) {
        st.failed?.let { chat.failed(st.no, it) }
        if (!st.delivered) return
        chat.delivered(st.no, st.prompt, st.hadImage)
        if (st.message.isNotBlank() || st.memory.isNotBlank() || st.screen.isNotBlank()) chat.parts(st.no, st.message, st.memory, st.screen)
        st.replyMessage?.let { chat.reply(st.no, it, st.actionCode) }
    }

    private companion object {
        const val MAX_STEPS = 30
    }
}
