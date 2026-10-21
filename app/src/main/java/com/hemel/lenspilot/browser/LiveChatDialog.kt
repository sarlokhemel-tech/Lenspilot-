package com.hemel.lenspilot.browser

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.R
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.chat.GlassChat

/** AiBrowserActivity থেকে যা লাগে (ছবি বাছা, মাইক-অনুমতি)। */
interface LiveChatUi {
    fun pickImage()
    fun hasMicPermission(): Boolean
    fun askMicPermission()
}

/**
 * ফুল-স্ক্রিন গ্লাস চ্যাট। উইন্ডো স্বচ্ছ + হালকা dim (Android 12+ হলে blur) — তাই পেছনে ব্রাউজারের কাজ
 * কাচের ভেতর দিয়ে দেখা যায়। সব লেখা সাদা। উপরে ডানে × চ্যাট বন্ধ করে (কাজ চলতেই থাকে)।
 */
class LiveChatDialog(
    private val activity: Activity,
    private val controller: LiveChatController,
    private val ui: LiveChatUi
) : Dialog(activity, R.style.Theme_Lenspilot_LiveChatGlass), LiveChatController.Listener {

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var micButton: ImageButton
    private lateinit var speakerButton: ImageButton
    private lateinit var statusChip: TextView
    private lateinit var subtitle: TextView
    private lateinit var previewRow: View
    private lateinit var previewImage: ImageView

    private var pendingImage: String? = null
    private var rendered = 0
    private var typingView: View? = null
    private var typingAnim: ValueAnimator? = null
    private val ticker = GlassChat.TimeTicker()
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_browser_live_chat)
        setCanceledOnTouchOutside(false)

        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // v40: মেইন চ্যাট-পেজ একদম স্বচ্ছ — কোনো dim/blur নেই; শুধু কার্ডগুলো কাচের মতো ভাসে
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        scroll = findViewById(R.id.liveChatScroll)
        list = findViewById(R.id.liveChatList)
        input = findViewById(R.id.liveChatInput)
        micButton = findViewById(R.id.liveChatMicButton)
        speakerButton = findViewById(R.id.liveChatSpeakerButton)
        statusChip = findViewById(R.id.liveChatStatusChip)
        subtitle = findViewById(R.id.liveChatSubtitle)
        previewRow = findViewById(R.id.liveChatImagePreviewRow)
        previewImage = findViewById(R.id.liveChatImagePreview)

        findViewById<ImageButton>(R.id.liveChatCloseButton).setOnClickListener { dismiss() }
        findViewById<ImageButton>(R.id.liveChatSendButton).setOnClickListener { submit() }
        findViewById<ImageButton>(R.id.liveChatAttachButton).setOnClickListener { ui.pickImage() }
        findViewById<ImageButton>(R.id.liveChatImageRemove).setOnClickListener { setPendingImage(null) }
        micButton.setOnClickListener { toggleVoice() }
        speakerButton.setOnClickListener {
            controller.speakReplies = !controller.speakReplies
            if (!controller.speakReplies) LocalTts.stop()
            updateSpeakerUi()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        updateSpeakerUi()

        if (controller.messages.isEmpty()) {
            controller.messages.add(LiveMsg(false, "হ্যালো! কী কাজ করব বলুন — লিখে, ছবি দিয়ে বা কথা বলে। আমি ব্রাউজারে করতে থাকব, আর কিছু জানার থাকলে এখানেই জিজ্ঞেস করব।"))
        }
        renderNew()
        onStatusChanged(controller.lastStatus)
        onBusyChanged(controller.busy)
    }

    override fun onStart() {
        super.onStart()
        controller.listener = this
        controller.markRead()
        ticker.start()
    }

    override fun onStop() {
        ticker.stop()
        typingAnim?.cancel()
        stopVoice()
        LocalTts.stop()
        if (controller.listener === this) controller.listener = null
        super.onStop()
    }

    // ---------------------------------------------------------------- UI

    private fun submit() {
        val t = input.text?.toString()?.trim().orEmpty()
        val img = pendingImage
        if (t.isEmpty() && img == null) return
        if (controller.busy) {
            Toast.makeText(activity, "একটু অপেক্ষা করুন, উত্তর আসছে…", Toast.LENGTH_SHORT).show()
            return
        }
        stopVoice()
        input.setText("")
        setPendingImage(null)
        controller.send(t, img)
    }

    fun setPendingImage(base64: String?) {
        pendingImage = base64
        if (base64 == null) {
            previewRow.visibility = View.GONE
            return
        }
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        previewImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        previewRow.visibility = View.VISIBLE
    }

    private fun updateSpeakerUi() {
        speakerButton.alpha = if (controller.speakReplies) 1f else 0.5f
    }

    private fun renderNew() {
        removeTyping()
        while (rendered < controller.messages.size) {
            list.addView(bubble(controller.messages[rendered]))
            rendered++
        }
        if (controller.busy) addTyping()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun whiteText(tv: TextView, size: Float) {
        tv.setTextColor(Color.WHITE)
        tv.textSize = size
        // v28: সাদা লেখা বোল্ড + গাঢ় ছায়া, যাতে হালকা ব্যাকগ্রাউন্ডেও পড়া যায়
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setShadowLayer(4f, 0f, 1.5f, 0xB3000000.toInt())
    }

    private fun bubble(m: LiveMsg): View =
        if (m.fromUser) GlassChat.userCard(activity, m.text, m.imageBase64)
        else GlassChat.aiCard(activity, m.text, m.ts, ticker, m.imageBase64)

    private fun addTyping() {
        val (row, tv) = GlassChat.typingCard(activity)
        typingView = row
        list.addView(row)
        // তিন-বিন্দু অ্যানিমেশন
        typingAnim = ValueAnimator.ofInt(0, 4).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tv.text = "লিখছে" + ".".repeat(it.animatedValue as Int % 4) }
            start()
        }
    }

    private fun removeTyping() {
        typingAnim?.cancel()
        typingAnim = null
        typingView?.let { list.removeView(it) }
        typingView = null
    }

    // ---------------------------------------------------------------- Listener

    override fun onMessagesChanged() { if (isShowing) renderNew() }

    override fun onBusyChanged(busy: Boolean) {
        if (!isShowing) return
        removeTyping()
        if (busy) addTyping()
        scrollToEnd()
    }

    override fun onStatusChanged(text: String) {
        if (!::statusChip.isInitialized) return
        val t = text.trim()
        statusChip.text = t
        statusChip.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onAiReply(text: String) {
        if (controller.speakReplies && isShowing) LocalTts.speak(activity, text)
    }

    // ---------------------------------------------------------------- Voice

    private fun toggleVoice() {
        if (listening) { recognizer?.stopListening(); return }
        if (!ui.hasMicPermission()) { ui.askMicPermission(); return }
        startVoice()
    }

    /** অনুমতি পাওয়ার পর Activity এটাও ডাকে। */
    fun startVoice() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "এই ডিভাইসে ভয়েস রিকগনিশন পাওয়া যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        LocalTts.stop()     // AI-র কথার ওপর ইউজার বলতে গেলে AI চুপ করে
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle) {
                val t = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) input.setText(t)
            }
            override fun onResults(results: Bundle) {
                setListeningUi(false)
                val t = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) return
                input.setText(t)
                submit()            // কথা শেষ হলে নিজেই চলে যায় — লাইভ কথোপকথনের মতো
            }
            override fun onError(error: Int) { setListeningUi(false) }
        })
        setListeningUi(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    private fun setListeningUi(on: Boolean) {
        listening = on
        micButton.setBackgroundResource(if (on) R.drawable.bg_live_glass_circle_active else R.drawable.bg_live_glass_circle)
        subtitle.text = if (on) "শুনছি… বলুন" else "ব্রাউজার AI-র সাথে কথা বলুন"
    }

    private fun stopVoice() {
        try { recognizer?.cancel() } catch (e: Exception) { /* ignore */ }
        try { recognizer?.destroy() } catch (e: Exception) { /* ignore */ }
        recognizer = null
        if (::micButton.isInitialized) setListeningUi(false)
    }
}
