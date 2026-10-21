package com.hemel.lenspilot.browser

import android.content.Context
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** লাইভ চ্যাটের একটা বার্তা। [imageBase64] শুধু ইউজার-পাঠানো ছবির জন্য। */
class LiveMsg(val fromUser: Boolean, val text: String, val imageBase64: String? = null, val ts: Long = System.currentTimeMillis())

/** AiBrowserActivity যা যা দেয় — কন্ট্রোলার শুধু এই ইন্টারফেস দিয়ে ব্রাউজারের অবস্থা পড়ে ও কাজ চালায়। */
interface LiveChatHost {
    val context: Context
    /** এই মুহূর্তের রান-অবস্থা (goal, running, paused, awaiting_user, finished, status, url, title, recent, system)। */
    fun runSnapshot(): JSONObject
    /** AI যে কাজ ঠিক করেছে (start_task | add_instruction | answer_agent | pause | resume | stop) তা বাস্তবায়ন। */
    fun applyChatAction(action: String, task: String, imageBase64: String?)
}

/**
 * লাইভ-চ্যাটের অবস্থা ও নেটওয়ার্ক। চ্যাট বন্ধ/খোলায় কথোপকথন হারায় না — [messages] এখানেই থাকে।
 * কোন action নেওয়া হবে তা সার্ভারের AI অর্থ বুঝে ঠিক করে (/api/browser/live-chat); এখানে কোনো শব্দ-মিলানো নেই।
 */
class LiveChatController(private val host: LiveChatHost, private val scope: CoroutineScope) {

    interface Listener {
        fun onMessagesChanged()
        fun onBusyChanged(busy: Boolean)
        fun onStatusChanged(text: String)
        /** AI-র নতুন উত্তর (জোরে পড়ার জন্য)। */
        fun onAiReply(text: String)
    }

    val messages = ArrayList<LiveMsg>()
    var listener: Listener? = null
    var busy = false
        private set
    var lastStatus = ""
        private set
    var speakReplies = false

    /** চ্যাট বন্ধ থাকা অবস্থায় আসা AI-র বার্তার সংখ্যা — 💬 আইকনের ব্যাজ। চ্যাট খুললে [markRead]। */
    var unread = 0
        private set
    var onUnreadChanged: ((Int) -> Unit)? = null

    fun markRead() {
        if (unread != 0) { unread = 0; onUnreadChanged?.invoke(0) }
    }

    private fun countIfClosed() {
        if (listener == null) { unread++; onUnreadChanged?.invoke(unread) }
    }

    private fun history(): JSONArray {
        val arr = JSONArray()
        messages.takeLast(12).forEach { m ->
            arr.put(JSONObject().put("role", if (m.fromUser) "user" else "ai").put("text", m.text))
        }
        return arr
    }

    /** এজেন্ট নিজে থেকে কিছু বললে (প্রশ্ন/ক্যাপচা/শেষ) — চ্যাটে AI-র বার্তা হিসেবে যোগ হয়। */
    fun postAgent(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (messages.lastOrNull()?.let { !it.fromUser && it.text == t } == true) return   // একই বার্তা দুবার নয়
        messages.add(LiveMsg(false, t))
        countIfClosed()
        listener?.onMessagesChanged()
        listener?.onAiReply(t)
    }

    fun onStatus(text: String) {
        lastStatus = text
        listener?.onStatusChanged(text)
    }

    fun send(text: String, imageBase64: String?) {
        val t = text.trim()
        if ((t.isEmpty() && imageBase64 == null) || busy) return
        val hist = history()                       // এই বার্তার আগের কথোপকথন
        messages.add(LiveMsg(true, t, imageBase64))
        listener?.onMessagesChanged()
        setBusy(true)
        val body = JSONObject().apply {
            put("message", t)
            if (imageBase64 != null) put("image_base64", imageBase64)
            put("history", hist)
            put("run", host.runSnapshot())
        }
        scope.launch {
            val ctx = host.context
            val base = ctx.getString(R.string.space_base_url)
            val result = ApiClient.callAuthed(ctx, base, "/api/browser/live-chat", body.toString())
            result.onSuccess { s -> handleReply(s, imageBase64) }
                .onFailure { e -> handleFailure(e) }
            setBusy(false)
        }
    }

    private fun handleReply(raw: String, sentImage: String?) {
        val j = try { JSONObject(raw) } catch (e: Exception) { null }
        if (j == null) {
            postAgent("উত্তরটা পড়তে পারিনি — আরেকবার বলবেন?")
            return
        }
        val reply = j.optString("reply").ifBlank { "ঠিক আছে।" }
        val action = j.optString("action", "none")
        val task = j.optString("task")
        messages.add(LiveMsg(false, reply))
        countIfClosed()
        listener?.onMessagesChanged()
        listener?.onAiReply(reply)
        if (action != "none") {
            // ছবি শুধু তখনই কাজের সাথে যায় যখন AI সেটা কাজে লাগানোর action বেছেছে
            val img = if (action == "start_task" || action == "add_instruction" || action == "answer_agent") sentImage else null
            host.applyChatAction(action, task, img)
        }
    }

    private fun handleFailure(e: Throwable) {
        val msg = if (e is ApiException) {
            try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" }
        } else ""
        postAgent(msg.ifBlank { "নেটওয়ার্ক সমস্যা হয়েছে — একটু পরে আবার চেষ্টা করুন।" }.take(160))
    }

    private fun setBusy(b: Boolean) {
        busy = b
        listener?.onBusyChanged(b)
    }
}
