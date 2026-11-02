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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.ComposerOptions
import com.hemel.lenspilot.ModePickerSheet
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.chat.MaxHeightScrollView
import com.hemel.lenspilot.chat.GlassChat
import com.hemel.lenspilot.chat.GlassMain
import com.hemel.lenspilot.chat.HistoryStore

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
    private var sidebar: GlassMain.Sidebar? = null
    private var host: FrameLayout? = null
    /** v87: সাইডবার থেকে বাছা পুরনো কথোপকথন (শুধু দেখা)। null = চলতি লাইভ চ্যাট। */
    private var viewing: List<LiveMsg>? = null
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
        val inflated = layoutInflater.inflate(R.layout.dialog_browser_live_chat, null)
        val frame = FrameLayout(context).apply {
            addView(inflated, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        host = frame
        setContentView(frame)
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
        // v87: টপ বার = মেইন চ্যাটের গ্লাস রূপ — সাইডবার | ········ | 🔊 (উত্তর পড়ে শোনানো) | ✕ (👁 নেই)
        speakerButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_speaker_white)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bg_live_glass_circle)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Read the AI's reply aloud"
        }
        sidebar = GlassMain.Sidebar(context, HistoryStore.SCOPE_BROWSER, onPick = { viewSession(it) }, onNew = { newChat() })
        val top = GlassMain.topBar(
            context, showScreenToggle = false, screenOn = false,
            onSidebar = { host?.let { sidebar?.toggle(it) } },
            onScreenToggle = {},
            onClose = { dismiss() },
            middle = speakerButton
        )
        findViewById<FrameLayout>(R.id.liveChatTopHolder).addView(top.root)
        statusChip = findViewById(R.id.liveChatStatusChip)
        previewRow = findViewById(R.id.liveChatImagePreviewRow)
        previewImage = findViewById(R.id.liveChatImagePreview)

        // v90: মডেল চিপ (AI Agent প্যানেলের মতো) — তালিকা কম্পোজারের ওপরে ভাসে; বাছাই পরের কাজ থেকে চলে
        modelChip = findViewById(R.id.liveChatModelChip)
        pickBox = findViewById(R.id.liveChatPick)
        refreshModelChip()
        modelChip.setOnClickListener { toggleModelPicker() }
        input.setOnFocusChangeListener { _, has -> if (has) closePicker() }

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
            controller.messages.add(LiveMsg(false, "Hello! Tell me what to do, by typing, sending an image or speaking. I will keep working in the browser and ask you here if I need anything."))
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
        sidebar?.dismissNow()
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
            Toast.makeText(activity, "Please wait. The reply is on its way…", Toast.LENGTH_SHORT).show()
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

    private lateinit var modelChip: TextView
    private lateinit var pickBox: FrameLayout
    private var pickOpen = false

    private fun refreshModelChip() {
        modelChip.text = ComposerOptions.systemLabel(Prefs.selectedSystem(context)) + " ▾"
    }

    private fun closePicker() {
        pickBox.removeAllViews(); pickBox.visibility = View.GONE; pickOpen = false
    }

    private fun toggleModelPicker() {
        if (pickOpen) { closePicker(); return }
        val cur = Prefs.selectedSystem(context)
        pickBox.removeAllViews()
        val sheet = ModePickerSheet.buildSheet(context, "Model", ComposerOptions.systems, cur, floating = true) { key ->
            closePicker()
            if (key != cur) {
                Prefs.setSelectedSystem(context, key)
                refreshModelChip()
                Toast.makeText(activity, "Model: " + ComposerOptions.systemLabel(key) + " — used from the next task", Toast.LENGTH_SHORT).show()
            }
        }
        val sc = MaxHeightScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            maxHeightPx = context.resources.displayMetrics.heightPixels * 45 / 100
            addView(sheet)
        }
        pickBox.addView(sc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        pickBox.visibility = View.VISIBLE
        pickOpen = true
        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        scrollToEnd()
    }

    private fun updateSpeakerUi() {
        speakerButton.alpha = if (controller.speakReplies) 1f else 0.5f
    }

    private fun renderNew() {
        if (viewing != null) return      // পুরনো কথোপকথন দেখার সময় লাইভ বার্তা ঢোকে না — "নতুন চ্যাট" চাপলে ফেরে
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

    // ব্রাউজার-AI একটা চলমান কাজ করে — একই নির্দেশ আবার পাঠালে কাজ দুবার/নতুন করে শুরু হতে পারে, তাই এখানে ↻ রিপিট নেই (কপি ও শোনা আছে)।
    private fun bubble(m: LiveMsg): View =
        if (m.fromUser) GlassChat.userCard(activity, m.text, m.imageBase64)
        else GlassChat.aiCard(activity, m.text, m.ts, ticker, m.imageBase64)

    /** সাইডবার থেকে পুরনো ব্রাউজার-কথোপকথন দেখা (চলতি কাজে কোনো প্রভাব নেই)। */
    private fun viewSession(sess: HistoryStore.Session) {
        removeTyping()
        val msgs = sess.messages.filter { it.text.isNotBlank() }.map { LiveMsg(it.role == "user", it.text) }
        viewing = msgs
        list.removeAllViews()
        msgs.forEach { list.addView(bubble(it)) }
        scrollToEnd()
    }

    /** "নতুন চ্যাট" / ফেরা — চলতি লাইভ চ্যাট আবার দেখানো। */
    private fun newChat() { backToLive() }

    private fun backToLive() {
        if (viewing == null) return
        viewing = null
        list.removeAllViews()
        rendered = 0
        renderNew()
    }

    private fun addTyping() {
        val (row, tv) = GlassChat.typingCard(activity)
        typingView = row
        list.addView(row)
        // তিন-বিন্দু অ্যানিমেশন
        typingAnim = ValueAnimator.ofInt(0, 4).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tv.text = "Typing" + ".".repeat(it.animatedValue as Int % 4) }
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
            Toast.makeText(activity, "Voice recognition is not available on this device", Toast.LENGTH_SHORT).show()
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
            com.hemel.lenspilot.audio.VoiceLang.applyTo(this)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    private fun setListeningUi(on: Boolean) {
        listening = on
        micButton.setBackgroundResource(if (on) R.drawable.bg_live_glass_circle_active else R.drawable.bg_live_glass_circle)
    }

    private fun stopVoice() {
        try { recognizer?.cancel() } catch (e: Exception) { /* ignore */ }
        try { recognizer?.destroy() } catch (e: Exception) { /* ignore */ }
        recognizer = null
        if (::micButton.isInitialized) setListeningUi(false)
    }
}
