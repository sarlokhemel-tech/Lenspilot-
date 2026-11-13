package com.hemel.lenspilot.keyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.ScaleGestureDetector
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.content.res.ColorStateList
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.security.SensitiveFieldGuard
import com.hemel.lenspilot.workflow.ContextWindowStore
import com.hemel.lenspilot.workflow.WorkflowContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * TIER-INDEPENDENT input method — this runs whether or not the user ever
 * turns on LenspilotAccessibilityService. It shows up in Android Settings
 * under "On-screen keyboard" exactly like Gboard/SwiftKey (see
 * res/xml/method.xml), and the user has to explicitly enable + select it,
 * same as installing any keyboard app — that OS-level step is what keeps
 * this permission-free from Lenspilot's side.
 *
 * Two independent things live in this one view:
 *   1. A plain QWERTY keyboard (buildKeyRows) — works with zero AI/network
 *      involvement, exactly like any keyboard.
 *   2. AI AUTO-TYPING — fully automatic, not a manual "type a prompt"
 *      panel. As soon as a field is focused (onStartInputView), if the
 *      user has AI auto-type turned on (Prefs.keyboardAutoTypeEnabled)
 *      and there's an active workflow ([WorkflowContext]) giving context
 *      for what's needed, the keyboard asks the backend what to type and
 *      commits it via [KeyboardTypingAnimator] — the user never types a
 *      prompt into the keyboard itself. They can tap the AI pill to pause
 *      it and type manually, or tap Stop mid-generation to take over
 *      immediately. If the AI isn't sure what to type, the backend sends
 *      back a clarifying question instead of field text (see the
 *      "clarify" event below); the keyboard shows that question and
 *      tells the user to answer via the overlay control bar's OWN mic
 *      button — there is deliberately no second/duplicate mic here.
 *      Skipped entirely for password fields and known banking/finance
 *      apps (SensitiveFieldGuard), and for any field when there's no
 *      active workflow to draw context from — with nothing to go on,
 *      this stays a completely plain keyboard and the user just types.
 */
class LenspilotInputMethodService : InputMethodService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var generateJob: Job? = null
    private var typingJob: Job? = null
    private var clarifyWatchJob: Job? = null

    private var capsOn = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var symbolsMode = false

    /** v37: প্রতিটা ইনপুট-শুরুতে বাড়ে — দেরিতে আসা পুরনো AI-উত্তর ভুল ঘরে বসা ঠেকাতে। */
    private var fieldToken = 0
    private var currentFieldKey = ""
    /** যে ঘরে AI একবার লিখে ফেলেছে — কিবোর্ড রিস্টার্ট/ঘোরানোয় (restarting) একই ঘরে দ্বিতীয়বার লেখা হয় না। */
    private var lastTypedFieldKey = ""
    private var clarifyAttempts = 0

    /** True = keyboard shows the traditional Bangla layout instead of
     * QWERTY (persisted, see Prefs.keyboardBanglaMode). Bangla mode is a
     * single unified page now (vowels + consonants together, matching
     * the reference keyboard) — no separate কার/ব্যঞ্জন sub-page anymore. */
    private var banglaMode = false
    private var translateJob: Job? = null

    private lateinit var keysContainer: LinearLayout
    private lateinit var aiToggleButton: View
    private lateinit var aiToggleLabel: TextView
    private lateinit var kbStopButton: ImageButton
    private lateinit var kbLastReplyButton: ImageButton
    private lateinit var kbStatusText: TextView
    private lateinit var kbBanglaToggleButton: TextView
    private lateinit var kbMainButton: TextView
    private lateinit var kbTranslateButton: TextView
    private lateinit var kbGlobeButton: ImageButton
    private lateinit var kbChatButton: TextView
    private lateinit var kbThemeButton: TextView
    private lateinit var kbSettingsButton: TextView
    private lateinit var kbMicButton: ImageButton
    // v127: Gboard-এর মতো আরও বাটন + কীর জায়গায় বসা প্যানেল (ইমোজি / ক্লিপবোর্ড / টেক্সট-এডিট)
    private lateinit var kbClipButton: TextView
    private lateinit var kbEmojiButton: TextView
    private lateinit var kbEditButton: TextView
    private lateinit var kbOneHandButton: TextView
    private lateinit var toolScroll: android.widget.HorizontalScrollView
    private var panels: KeyboardPanels? = null
    private var panelHost: FrameLayout? = null
    private var panelKind: String? = null
    private var clipListenerOn = false
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            // পাসওয়ার্ড-ম্যানেজার ইত্যাদির "সংবেদনশীল" কপি ইতিহাসে রাখি না
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return@OnPrimaryClipChangedListener
            val txt = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
            ClipHistory.add(this, txt)
        } catch (e: Exception) { /* ক্লিপবোর্ড পড়া গেল না — বাদ */ }
    }
    /** v125: ⊞ চাপলে (লেখার সময়ও) আইকনের সারি খোলে/বন্ধ হয় — Gboard-এর মতো। */
    private var toolsExpanded = false
    private var inlineMenu: android.widget.ScrollView? = null
    private var chatPanel: KeyboardChatPanel? = null
    private var kbTheme: KeyboardTheme? = null
    private fun th(): KeyboardTheme = kbTheme ?: KeyboardTheme.current(this).also { kbTheme = it }
    private lateinit var toolIcons: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private var kbRootView: View? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    /** v120: true = "বাটন/লেখা টেনে ছোট-বড়" এডিট মোড — বাটন চাপলে টাইপ হয় না, টানলে মাপ বদলায়। */
    private var customizeMode = false
    private var frameWrapper: FrameLayout? = null
    private var resizeOverlay: KeyboardResizeOverlay? = null
    private var selectedId: String? = null
    private var selStart = floatArrayOf(1f, 1f, 1f)
    private var selStartPx = intArrayOf(0, 0)
    private var fStartKeysH = 1
    private var fStartScale = 1f
    private var fStartLeft = 0
    private var fStartW = 1
    private var fStartLift = 0f
    private lateinit var kbSuggestion1: TextView
    private lateinit var kbSuggestion2: TextView
    private lateinit var kbSuggestion3: TextView

    /** True once the user has tapped Stop for the field currently
     * focused — prevents immediately re-auto-typing into the same field
     * after they've explicitly taken over; resets on the next field. */
    private var userTookOverThisField = false

    private val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    // "," and "." moved here (off the bottom row, to match the reference
    // keyboard's bottom row exactly) — still one tap away via ?123.
    private val symbolRows = listOf("1234567890", "@#\$_&-+()", "*\"':;!?/,.")

    // Unified Bangla layout — one page, 5 rows x 10 keys, matching the
    // reference keyboard exactly (row 1 = independent vowels minus ঋ,
    // rows 2-4 = consonants, row 5 = the remaining consonants + ঋ +
    // chandrabindu/hasant marks, backspace appended after). No more
    // separate কার/ব্যঞ্জন sub-page — SMART TYPING below handles matra
    // conversion automatically instead, exactly like the reference.
    private val banglaRow1Vowels = "অআইঈউঊএঐওঔ"
    private val banglaRows = listOf(
        banglaRow1Vowels,
        "কখগঘঙচছজঝঞ",
        "টঠডঢণতথদধন",
        "পফবভমযরলশষ",
        "সহ\u09DC\u09DD\u09DF\u09CE"   // (শেষ সারি এখন buildBanglaLastRow-এ নিজে বানানো)
    )

    // Independent vowel -> dependent matra (কার) sign. Typed automatically
    // instead of the independent form whenever the previous character is
    // a consonant — exactly the phonetic-keyboard behaviour in the
    // reference video (তাই "ক"+"ই" চাপলে "কই" না হয়ে "কি" হয়)। অ has no
    // visible sign (it's the inherent vowel) so it's handled separately.
    private val banglaMatraMap = mapOf(
        'আ' to "া", 'ই' to "ি", 'ঈ' to "ী", 'উ' to "ু", 'ঊ' to "ূ",
        'ঋ' to "ৃ", 'এ' to "ে", 'ঐ' to "ৈ", 'ও' to "ো", 'ঔ' to "ৌ"
    )
    // ড় ঢ় য় এখন একটাই কোডপয়েন্ট (U+09DC/09DD/09DF) — আগে ড+়-এর মতো ভেঙে দুটো কী হয়ে যেত। ় (নুক্তা) আর ৎ-ও ব্যঞ্জনের মতো ধরা হয়।
    private val banglaConsonantChars =
        "কখগঘঙচছজঝঞটঠডঢণতথদধনপফবভমযরলশষসহ" + "\u09DC\u09DD\u09DF\u09BC\u09CE"

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        keysContainer = view.findViewById(R.id.keysContainer)
        aiToggleButton = view.findViewById(R.id.aiToggleButton)
        aiToggleLabel = view.findViewById(R.id.aiToggleLabel)
        kbStopButton = view.findViewById(R.id.kbStopButton)
        kbLastReplyButton = view.findViewById(R.id.kbLastReplyButton)
        kbStatusText = view.findViewById(R.id.kbStatusText)
        kbBanglaToggleButton = view.findViewById(R.id.kbBanglaToggleButton)
        kbMainButton = view.findViewById(R.id.kbMainButton)
        kbTranslateButton = view.findViewById(R.id.kbTranslateButton)
        kbGlobeButton = view.findViewById(R.id.kbGlobeButton)
        kbChatButton = view.findViewById(R.id.kbChatButton)
        kbThemeButton = view.findViewById(R.id.kbThemeButton)
        kbSettingsButton = view.findViewById(R.id.kbSettingsButton)
        kbMicButton = view.findViewById(R.id.kbMicButton)
        kbClipButton = view.findViewById(R.id.kbClipButton)
        kbEmojiButton = view.findViewById(R.id.kbEmojiButton)
        kbEditButton = view.findViewById(R.id.kbEditButton)
        kbOneHandButton = view.findViewById(R.id.kbOneHandButton)
        toolScroll = view.findViewById(R.id.toolScroll)
        toolIcons = view.findViewById(R.id.toolIcons)
        suggestionBar = view.findViewById(R.id.suggestionBar)
        kbRootView = view
        kbSuggestion1 = view.findViewById(R.id.kbSuggestion1)
        kbSuggestion2 = view.findViewById(R.id.kbSuggestion2)
        kbSuggestion3 = view.findViewById(R.id.kbSuggestion3)
        // v125: ⊞ = আইকনের সারি খোলা/বন্ধ (লেখার সময়ও কাজ করে); ⚙ = সব সেটিং; 🎨 = থিম; মাইক = ভয়েস টাইপ।
        // মেনুগুলো আর পপআপ-উইন্ডো নয় — কীবোর্ডের ভেতরেই বসে (IME-তে PopupMenu অনেক ফোনে খোলে না)।
        kbMainButton.setOnClickListener { if (inlineMenu?.visibility == View.VISIBLE) hideInlineMenu() else showToolsMenu() }
        kbSettingsButton.setOnClickListener { showToolsMenu() }
        kbMicButton.setOnClickListener { toggleMic() }
        kbChatButton.setOnClickListener { toggleChatPanel() }
        kbThemeButton.setOnClickListener { showThemeMenu() }
        // v122: গ্লাস-কার্ড AI চ্যাট প্যানেল — কীগুলোর ঠিক ওপরে বসে
        chatPanel = KeyboardChatPanel(this, serviceScope, { th() }, { text -> insertIntoField(text) }, { closeChatPanel() })
        (keysContainer.parent as? LinearLayout)?.let { root ->
            root.addView(chatPanel!!.view, root.indexOfChild(keysContainer))
            // v125: কীবোর্ডের ভেতরের মেনু-প্যানেল (পপআপ নয়)
            val sv = android.widget.ScrollView(this).apply { visibility = View.GONE; isFillViewport = false }
            root.addView(sv, root.indexOfChild(keysContainer), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt())
            })
            inlineMenu = sv
            // v127: ইমোজি/ক্লিপবোর্ড/এডিট প্যানেল — খুললে কীর জায়গা নেয়
            val ph = FrameLayout(this).apply { visibility = View.GONE }
            root.addView(ph, root.indexOfChild(keysContainer), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt(), 0)
            })
            panelHost = ph
        }
        panels = KeyboardPanels(this, { th() }, { str -> emit(str); updateSuggestions() }, { deleteOneChar() }, { currentInputConnection }, { closePanel() })
        if (!clipListenerOn) {
            runCatching {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).addPrimaryClipChangedListener(clipListener)
                clipListenerOn = true
            }
        }
        kbTranslateButton.setOnClickListener { toggleTranslateMode() }
        // ⊞ লং-প্রেস = সরাসরি টেনে ছোট-বড় করার দাগ (হ্যান্ডেল) চালু
        kbMainButton.setOnLongClickListener { setCustomizeMode(true); true }
        kbClipButton.setOnClickListener { openPanel("clip") }
        kbEmojiButton.setOnClickListener { openPanel("emoji") }
        kbEditButton.setOnClickListener { openPanel("edit") }
        kbOneHandButton.setOnClickListener { cycleOneHand() }
        kbSuggestion1.setOnClickListener { applySuggestion(kbSuggestion1.text.toString()) }
        kbSuggestion2.setOnClickListener { applySuggestion(kbSuggestion2.text.toString()) }
        kbSuggestion3.setOnClickListener { applySuggestion(kbSuggestion3.text.toString()) }

        kbGlobeButton.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        aiToggleButton.setOnClickListener { toggleAutoTypeEnabled() }
        kbStopButton.setOnClickListener { userTakesOver() }
        kbLastReplyButton.setOnClickListener { typeLastAiReply() }

        banglaMode = Prefs.keyboardBanglaMode(this)
        updateBanglaToggleUi()
        kbBanglaToggleButton.setOnClickListener { toggleBanglaMode() }

        // v120: টুলবারের প্রতিটা বাটনের নিজস্ব মাপ (ইউজারের টেনে-ঠিক-করা) + কীবোর্ডের মাথা কোথায় তা গ্লাস কার্ডকে জানানো
        tbSpecs.clear()
        tbSpecs.add(TbSpec(kbMainButton, "tb_main", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbGlobeButton, "tb_globe", 42, 42, 0f))
        tbSpecs.add(TbSpec(kbBanglaToggleButton, "tb_lang", 46, 42, 16f))
        tbSpecs.add(TbSpec(aiToggleButton, "tb_ai", 56, 34, 15f))
        tbSpecs.add(TbSpec(kbTranslateButton, "tb_translate", 42, 42, 17f))
        tbSpecs.add(TbSpec(kbClipButton, "tb_clip", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbEmojiButton, "tb_emoji", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbEditButton, "tb_edit", 42, 42, 22f))
        tbSpecs.add(TbSpec(kbOneHandButton, "tb_onehand", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbChatButton, "tb_chat", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbThemeButton, "tb_theme", 42, 42, 20f))
        tbSpecs.add(TbSpec(kbLastReplyButton, "tb_last", 42, 42, 0f))
        tbSpecs.add(TbSpec(kbSettingsButton, "tb_settings", 42, 42, 22f))
        tbSpecs.add(TbSpec(kbMicButton, "tb_mic", 42, 42, 0f))
        tbSpecs.add(TbSpec(kbStopButton, "tb_stop", 42, 42, 0f))
        chatPanel?.let {
            tbSpecs.add(TbSpec(it.sendBtn, "chat_send", 38, 38, 16f))
            tbSpecs.add(TbSpec(it.closeBtn, "chat_close", 38, 38, 15f))
        }
        // v127: আগের (ছোট) টেনে-ঠিক-করা বাটন-মাপ একবার রিসেট — নতুন বড় ডিফল্ট যেন সত্যিই দেখা যায়
        if (!Prefs.keyboardV127Done(this)) {
            KeyCustomizer.reset(this)
            Prefs.setKeyboardSizeScale(this, 1f)
            Prefs.setKeyboardV127Done(this, true)
        }
        applyToolbarCustom()
        // v129: যত বাটন ধরে শুধু ততগুলোই দেখায় (কিছু কাটা পড়ে না); বাকিগুলো ⊞ গ্রিডে
        toolScroll.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ ->
            if (r - l != or2 - ol) toolScroll.post { fitToolbar() }
        }
        toolScroll.post { fitToolbar() }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> reportKeyboardTop(v); resizeOverlay?.invalidate() }
        TranslateGlassOverlay.listener = { updateTranslateButtonUi() }
        TranslateGlassOverlay.onSend = { sendTranslatedDraft() }
        TranslateGlassOverlay.onShowKeyboard = { runCatching { requestShowSelf(0) } }
        updateTranslateButtonUi()

        kbTheme = KeyboardTheme.current(this)
        applyTheme()

        // v124: Gboard-এর মতো — কীবোর্ড একটা র‍্যাপারে বসে (প্রস্থ/অবস্থান/ওঠানো এখানে), তার ওপরে সাইজ-এডিট ওভারলে
        val wrapper = FrameLayout(this)
        wrapper.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val overlay = KeyboardResizeOverlay(this, resizeHost)
        overlay.visibility = View.GONE
        wrapper.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frameWrapper = wrapper
        resizeOverlay = overlay
        customizeMode = false
        applyKeyboardFrame()
        return wrapper
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val key = fieldKey(info)
        // v120: অনুবাদ-গ্লাস কার্ডের নিজের লেখার ঘর — এখানে AI অটো-টাইপ নয়
        val ownOverlayField = TranslateGlassOverlay.isShowing && info?.packageName == packageName
        val sameField = restarting && key == currentFieldKey
        val inFlight = sameField && (generateJob?.isActive == true || typingJob?.isActive == true)
        if (!inFlight) {
            // Reset any leftover state from a previous field so a stale
            // in-flight request never types into the wrong place.
            cancelAiActivity()
            fieldToken++
        }
        if (!sameField) {
            userTookOverThisField = false
            clarifyAttempts = 0
        }
        currentFieldKey = key
        clearSuggestions()

        val guarded = fieldIsGuarded(info)
        aiToggleButton.isEnabled = !guarded
        aiToggleButton.alpha = if (guarded) 0.4f else 1f
        kbLastReplyButton.isEnabled = !guarded
        kbLastReplyButton.alpha = if (guarded) 0.4f else 1f
        kbMicButton.isEnabled = !guarded
        kbMicButton.alpha = if (guarded) 0.4f else 1f
        updateAutoTypeToggleUi()
        updateTranslateButtonUi()
        updateToolbar()
        if (ownOverlayField) {
            setStatus(null)
            return
        }

        if (guarded) {
            setStatus(null)
            return
        }
        if (inFlight) return          // একই ঘরের রিস্টার্ট — চলমান লেখা/ভাবা চলতে দাও
        if (sameField && key == lastTypedFieldKey) return   // এই ঘরে AI আগেই লিখেছে
        maybeAutoType(info)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        TranslateGlassOverlay.onKeyboardTop(Int.MAX_VALUE)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        kbRootView?.let { v -> v.post { reportKeyboardTop(v) } }
    }

    private fun reportKeyboardTop(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        TranslateGlassOverlay.onKeyboardTop(loc[1])
    }

    /** কার্সার/লেখা বদলালেই (অ্যাপ নিজে ঘর খালি করলেও) টুলবার-অবস্থা ঠিক করে। */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateToolbar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopMic()
        hideInlineMenu()
        closePanel()
        cancelAiActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
        TranslateGlassOverlay.listener = null
        TranslateGlassOverlay.onSend = null
        TranslateGlassOverlay.onShowKeyboard = null
        if (clipListenerOn) {
            runCatching { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).removePrimaryClipChangedListener(clipListener) }
            clipListenerOn = false
        }
        stopMic()
        serviceJob.cancel()
    }

    // ------------------------------------------------------------------
    // Plain keyboard — letters/symbols/backspace/space/enter/shift.
    // No network, no AI, works exactly like any other keyboard app.
    // ------------------------------------------------------------------

    /** Which row-set is currently showing — Bangla (consonants or
     * vowels/কার page), symbols, or plain QWERTY letters. */
    private fun currentRows(): List<String> = when {
        symbolsMode -> symbolRows
        banglaMode -> banglaRows
        else -> letterRows
    }

    // ------------------------------------------------------------------
    // v122: থিম + গ্লাস AI চ্যাট প্যানেলের রাউটিং
    // ------------------------------------------------------------------

    private fun chatOpen(): Boolean = chatPanel?.isOpen == true

    /** কীবোর্ডের লেখা কোথায় যাবে: চ্যাট প্যানেল খোলা থাকলে প্যানেলের ঘরে, নইলে অ্যাপের ঘরে। */
    private fun emit(s: String) {
        val p = chatPanel
        if (p != null && p.isOpen) p.append(s) else currentInputConnection?.commitText(s, 1)
    }

    private fun toggleChatPanel() {
        if (chatOpen()) closeChatPanel() else {
            closePanel()
            chatPanel?.open()
            updateToolbar()
        }
    }

    private fun closeChatPanel() {
        chatPanel?.close()
        updateToolbar()
    }

    /** প্যানেলের উত্তর আসল অ্যাপের ঘরে বসায় (চ্যাট খোলা থাকলেও অ্যাপের InputConnection-এ লেখে)। */
    private fun insertIntoField(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            Toast.makeText(this, R.string.kb_chat_error, Toast.LENGTH_SHORT).show()
            return
        }
        ic.commitText(text, 1)
        Prefs.setLastAiReply(this, text)
    }

    private fun applyTheme() {
        val t = KeyboardTheme.current(this)
        kbTheme = t
        kbRootView?.background = t.rootBg(this)
        listOf<View>(kbMainButton, kbGlobeButton, kbBanglaToggleButton, kbLastReplyButton, kbStopButton, kbChatButton, kbThemeButton, kbSettingsButton, kbMicButton, kbClipButton, kbEmojiButton, kbEditButton, kbOneHandButton)
            .forEach { it.background = t.specialBg(this) }
        listOf(kbMainButton, kbBanglaToggleButton, kbChatButton, kbThemeButton, kbSettingsButton, kbClipButton, kbEmojiButton, kbEditButton, kbOneHandButton).forEach { it.setTextColor(t.text) }
        listOf(kbGlobeButton, kbLastReplyButton, kbStopButton, kbMicButton).forEach { it.imageTintList = ColorStateList.valueOf(t.text) }
        suggestionBar.background = t.pillBg(this, 18)
        listOf(kbSuggestion1, kbSuggestion2, kbSuggestion3).forEach { it.setTextColor(t.text) }
        kbStatusText.setTextColor(t.textMuted)
        updateAutoTypeToggleUi()
        updateTranslateButtonUi()
        chatPanel?.applyTheme()
        closePanel()
        buildKeyRows()
    }

    private fun showThemeMenu() {
        val cur = KeyboardTheme.current(this).id
        showInlineMenu(KeyboardTheme.ALL.map { t ->
            MenuEntry((if (t.id == cur) "✓ " else "    ") + t.label, false) {
                Prefs.setKeyboardTheme(this, t.id)
                applyTheme()
            }
        })
    }

    // ------------------------------------------------------------------
    // v125: কীবোর্ডের ভেতরের মেনু (পপআপ-উইন্ডো নয়, তাই সব ফোনে খোলে)
    // ------------------------------------------------------------------

    private class MenuEntry(val label: String, val reopen: Boolean, val action: () -> Unit)

    private fun hideInlineMenu() { inlineMenu?.visibility = View.GONE }

    private fun showInlineMenu(entries: List<MenuEntry>, reopen: (() -> Unit)? = null) {
        val sv = inlineMenu ?: return
        val t = th()
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = t.pillBg(this@LenspilotInputMethodService, 14)
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        }
        fun addRow(label: String, bold: Boolean, onTap: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 14.5f
                setTextColor(t.text)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (44 * d).toInt()
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                isClickable = true
                isFocusable = false
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addRow(getString(R.string.kb_menu_close), true) { hideInlineMenu() }
        for (e in entries) addRow(e.label, false) {
            hideInlineMenu()
            e.action()
            if (e.reopen) reopen?.invoke()
        }
        sv.removeAllViews()
        sv.addView(col)
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        sv.visibility = View.VISIBLE
        sv.post {
            if (sv.height > maxH) sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }
    }

    private fun buildKeyRows() {
        keysContainer.removeAllViews()
        if (banglaMode && !symbolsMode) {
            // Unified single-page Bangla layout — 4 plain rows + 1 row
            // that also carries the backspace key (matches the reference
            // keyboard's 5x10 grid, no shift/page-toggle key anywhere).
            // Row 0 (independent vowels) is the only one wired to the
            // smart matra conversion (see commitBanglaVowel).
            shownMatraBase = lastConsonantCluster()
            if (Prefs.keyboardNumberRow(this)) keysContainer.addView(buildCharRow(numberRowChars()))
            keysContainer.addView(buildCharRow(banglaRows[0], vowelRow = true))
            banglaRows.subList(1, banglaRows.size - 1).forEach { row ->
                keysContainer.addView(buildCharRow(row))
            }
            keysContainer.addView(buildBanglaLastRow())
            keysContainer.addView(buildBottomRow())
            updateSuggestions()
            return
        }
        if (Prefs.keyboardNumberRow(this) && !symbolsMode) keysContainer.addView(buildCharRow(numberRowChars()))
        val rows = currentRows()
        // The LAST row is rendered inside buildSpecialRow, merged with
        // the shift/page-toggle key and backspace — same as any normal
        // QWERTY keyboard's bottom letter row.
        rows.dropLast(1).forEach { row -> keysContainer.addView(buildCharRow(row)) }
        keysContainer.addView(buildSpecialRow())
        keysContainer.addView(buildBottomRow())
        updateSuggestions()
    }

    private fun buildCharRow(chars: String, vowelRow: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, keyH()
            ).apply { topMargin = dp(4) }
        }
        chars.forEachIndexed { idx, c ->
            // Gboard-এর মতো: ব্যঞ্জনের পরে ওপরের সারি কারে বদলে যায় (ত তা তি তী তু তূ তে তৈ তো তৌ) — চাপলে শুধু কার বসে
            val label = if (vowelRow && shownMatraBase.isNotEmpty()) shownMatraBase + (banglaMatraMap[c] ?: "") else charLabel(c)
            val k = makeKeyView(label, weight = 1f, special = false) {
                if (vowelRow) commitBanglaVowel(c) else commitChar(c)
            }
            attachKeyGestures(k, label, altsFor(c, idx, vowelRow), null) { typeStr(it) }
            row.addView(k)
        }
        fitRow(row)
        return row
    }

    /** Bangla's row 5 (Gboard-এর মতো): স হ ড় ঢ় য় ৎ  ্য  ্ (হসন্ত)  ⋯ (ঁ ং ঃ ঋ ্র র্ ক্ষ জ্ঞ)  ⌫
     * যুক্তবর্ণ = ব্যঞ্জন + ্ + ব্যঞ্জন (যেমন ক ্ ত → ক্ত); ্য ও ্র (য-ফলা/র-ফলা) আর র্ (রেফ) এক চাপেই। */
    private fun buildBanglaLastRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, keyH()
            ).apply { topMargin = dp(4) }
        }
        listOf("স", "হ", "\u09DC", "\u09DD", "\u09DF", "\u09CE").forEach { str ->
            row.addView(makeKeyView(str, weight = 1f, special = false) { typeStr(str) })
        }
        row.addView(makeKeyView("\u09CD\u09AF", weight = 1f, special = true) { typeStr("\u09CD\u09AF") })
        row.addView(makeKeyView("\u09CD", weight = 1f, special = true) { typeStr("\u09CD") })
        lateinit var marksKey: View
        marksKey = makeKeyView("\u22EF", weight = 1f, special = true) { showMarksPopup(marksKey) }
        row.addView(marksKey)
        row.addView(makeIconKeyView(R.drawable.ic_keyboard_backspace, weight = 1.5f, special = true) {
            deleteOneChar()
        }.apply { attachRepeatOnHold { deleteOneChar() } })
        fitRow(row)
        return row
    }

    private fun typeStr(str: String) {
        emit(str)
        updateSuggestions()
    }

    /** কীর আগের ব্যঞ্জন (নুক্তাসহ) — না থাকলে "" । ওপরের সারির কার-লেবেল এটা দিয়ে বানানো হয়। */
    private fun lastConsonantCluster(): String {
        val before = if (chatOpen()) chatPanel!!.tail(2)
            else currentInputConnection?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val last = before.lastOrNull() ?: return ""
        if (last == '\u09BC') return if (before.length >= 2) before.takeLast(2) else ""
        return if (last in banglaConsonantChars) last.toString() else ""
    }

    private var shownMatraBase = ""

    /** ব্যঞ্জন লেখা/মোছা হলে ওপরের স্বরবর্ণ সারি আবার বানায় (স্বর ↔ কার)। */
    private fun refreshBanglaDynamicRows() {
        val off = if (Prefs.keyboardNumberRow(this)) 1 else 0
        if (!banglaMode || symbolsMode || !::keysContainer.isInitialized || keysContainer.childCount < 6 + off) return
        val base = lastConsonantCluster()
        if (base == shownMatraBase) return
        shownMatraBase = base
        keysContainer.removeViewAt(off)
        keysContainer.addView(buildCharRow(banglaRows[0], vowelRow = true), off)
    }

    /** ⋯ চাপলে ছোট পপআপ: ঁ ং ঃ ঋ(/ৃ) ্র র্ ক্ষ জ্ঞ। */
    private fun showMarksPopup(anchor: View) {
        val t = th()
        val after = lastConsonantCluster().isNotEmpty()
        val opts = listOf("\u0981", "\u0982", "\u0983", if (after) "\u09C3" else "\u098B", "\u09CD\u09B0", "\u09B0\u09CD", "\u0995\u09CD\u09B7", "\u099C\u09CD\u099E")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = t.pillBg(this@LenspilotInputMethodService, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        var pw: android.widget.PopupWindow? = null
        opts.forEach { o ->
            box.addView(TextView(this).apply {
                text = if (o == "\u0981" || o == "\u0982" || o == "\u0983" || o == "\u09C3") "\u25CC" + o else o
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(t.text)
                background = t.keyBg(this@LenspilotInputMethodService)
                minWidth = dp(44)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                }
                setOnClickListener {
                    if (o == "\u098B" || o == "\u09C3") commitBanglaVowel('\u098B') else typeStr(o)
                    pw?.dismiss()
                }
            })
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width - box.measuredWidth).coerceIn(0, maxOf(0, sw - box.measuredWidth))
        val y = loc[1] - box.measuredHeight - dp(4)
        pw = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = false
            isClippingEnabled = false
            elevation = 8 * resources.displayMetrics.density
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun buildSpecialRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, keyH()
            ).apply { topMargin = dp(4) }
        }
        when {
            symbolsMode -> {
                row.addView(makeKeyView(if (banglaMode) "বাংলা" else "ABC", weight = 1.5f, special = true) {
                    symbolsMode = false
                    buildKeyRows()
                })
            }
            else -> {
                val shiftKey = makeIconKeyView(R.drawable.ic_keyboard_shift, weight = 1.5f, special = true) {
                    // Gboard-এর মতো: এক চাপ = পরের একটা বড় হাতের অক্ষর; দ্রুত দুই চাপ = Caps Lock
                    val now = android.os.SystemClock.uptimeMillis()
                    if (capsLock) { capsLock = false; capsOn = false }
                    else if (capsOn && now - lastShiftTap < 400) capsLock = true
                    else capsOn = !capsOn
                    lastShiftTap = now
                    buildKeyRows()
                }
                if (capsOn) shiftKey.imageTintList = ColorStateList.valueOf(th().accent)
                if (capsLock) shiftKey.background = th().accentBg(this, 17)
                row.addView(shiftKey)
            }
        }
        currentRows().last().forEachIndexed { idx, c ->
            val k = makeKeyView(charLabel(c), weight = 1f, special = false) { commitChar(c) }
            attachKeyGestures(k, charLabel(c), altsFor(c, idx, false), null) { typeStr(it) }
            row.addView(k)
        }
        row.addView(makeIconKeyView(R.drawable.ic_keyboard_backspace, weight = 1.5f, special = true) {
            deleteOneChar()
        }.apply { attachRepeatOnHold { deleteOneChar() } })
        fitRow(row)
        return row
    }

    /** Gboard-এর মতো নিচের সারি: ?123 · , · 😊 · স্পেস · . · Enter  (কমা লং-প্রেস = টুল-পপআপ, স্পেস টানলে কার্সার, স্পেস লং-প্রেস = ভাষা বদল, Enter লং-প্রেস = এক-হাত) */
    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, keyH()
            ).apply { topMargin = dp(4) }
        }
        row.addView(makeKeyView(if (symbolsMode) (if (banglaMode) "বাংলা" else "ABC") else "?123", weight = 1.3f, special = true) {
            symbolsMode = !symbolsMode
            buildKeyRows()
        })
        lateinit var commaKey: TextView
        commaKey = makeKeyView(",", weight = 0.8f, special = true) { typeStr(",") }
        attachKeyGestures(commaKey, ",", emptyList(), { showQuickTools(commaKey) }) { }
        row.addView(commaKey)
        row.addView(makeKeyView("😊", weight = 0.9f, special = true) { openPanel("emoji") })
        val space = makeKeyView(
            if (symbolsMode) getString(R.string.keyboard_space_key) else (if (banglaMode) "বাংলা" else "English"),
            weight = 3.2f, special = false
        ) {
            emit(" ")
            clearSuggestions()
            maybeAutoCap()
        }
        attachSpaceGestures(space)
        row.addView(space)
        val dot = makeKeyView(".", weight = 0.8f, special = true) { typeStr("."); maybeAutoCap() }
        attachKeyGestures(dot, ".", listOf("!", "?", ":", ";", "'", "\"", "-", "(", ")", "/", "@", "…"), null) { typeStr(it) }
        row.addView(dot)
        val enter = makeKeyView(getString(R.string.keyboard_enter_key), weight = 1.3f, special = true) {
            submitEditorAction()
        }
        enter.setOnLongClickListener { cycleOneHand(); true }
        row.addView(enter)
        fitRow(row)
        return row
    }

    // ------------------------------------------------------------------
    // v128: Gboard-এর মতো কী-জেশ্চার — লং-প্রেসে পপআপ (সংখ্যা/অ্যাকসেন্ট/চিহ্ন), চাপলে ছোট প্রিভিউ,
    //        স্পেস টানলে কার্সার, ব্যাকস্পেস বাঁয়ে টানলে শব্দ-শব্দ মোছা, অটো-ক্যাপিটাল
    // ------------------------------------------------------------------

    private val engAlts = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3 è é ê ë ē ė ę", 'r' to "4 ř", 't' to "5 ť", 'y' to "6 ý ÿ",
        'u' to "7 ù ú û ü ū", 'i' to "8 ì í î ï ī", 'o' to "9 ò ó ô ö õ ø œ ō", 'p' to "0",
        'a' to "à á â ä æ ã å ā", 's' to "ß ś š", 'd' to "ď", 'l' to "ł", 'z' to "ž ź ż", 'c' to "ç ć č", 'n' to "ñ ń",
        '1' to "¹ ½ ⅓ ¼", '2' to "² ⅔", '3' to "³ ¾", '4' to "⁴", '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹", '0' to "⁰ ° ∅",
        '-' to "_ – — •", '$' to "€ £ ¥ ₹ ¢", '&' to "§", '?' to "¿", '!' to "¡"
    )
    private val bnAlts = mapOf(
        'ড' to "ড়", 'ঢ' to "ঢ়", 'য' to "য়", 'ত' to "ৎ", 'ক' to "ক্ষ", 'জ' to "জ্ঞ", 'হ' to "ঃ", 'ন' to "ণ ঞ ং", 'স' to "ষ শ"
    )

    /** কী-তে লং-প্রেসে যা যা আসবে (প্রথমটা সবচেয়ে কাছের)। */
    private fun altsFor(c: Char, index: Int, vowelRow: Boolean): List<String> {
        val raw: List<String> = when {
            banglaMode && !symbolsMode -> {
                val digit = if (vowelRow) listOf("১২৩৪৫৬৭৮৯০".getOrNull(index)?.toString()) else emptyList()
                val extra = (bnAlts[c] ?: "").split(" ")
                (digit + extra).filterNotNull()
            }
            else -> (engAlts[c.lowercaseChar()] ?: "").split(" ")
        }
        return raw.filter { it.isNotBlank() }.map { if (capsOn && it != "ß") it.uppercase() else it }
    }

    private fun dismissQuiet(pw: android.widget.PopupWindow?) { try { pw?.dismiss() } catch (e: Exception) { } }

    /**
     * [alts] থাকলে লং-প্রেসে পপআপ; আঙুল না তুলে পাশে টেনে বাছা যায়, তুললেই বসে যায়।
     * [longAction] থাকলে লং-প্রেসে সেটা চলে (যেমন কমা → টুল-পপআপ)। [preview] ফাঁকা হলে কী-র লেবেলই প্রিভিউ।
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachKeyGestures(
        v: View, label: String, alts: List<String>, longAction: (() -> Unit)?, onPick: (String) -> Unit
    ) {
        if (customizeMode) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var preview: android.widget.PopupWindow? = null
        var altPw: android.widget.PopupWindow? = null
        var altViews: List<TextView> = emptyList()
        var longDone = false
        var picked = 0
        val t = th()
        fun clearPreview() { dismissQuiet(preview); preview = null }
        fun clearAll() { clearPreview(); dismissQuiet(altPw); altPw = null; altViews = emptyList() }
        fun highlight(i: Int) {
            picked = i
            altViews.forEachIndexed { j, tv -> tv.background = if (j == i) t.accentBg(this, 10) else t.keyBg(this); tv.setTextColor(if (j == i) t.onAccent else t.text) }
        }
        val showLong = Runnable {
            longDone = true
            clearPreview()
            if (longAction != null) { longAction(); return@Runnable }
            if (alts.isEmpty()) { longDone = false; return@Runnable }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = t.pillBg(this@LenspilotInputMethodService, 14)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val tvs = ArrayList<TextView>()
            alts.forEach { a ->
                val tv = TextView(this).apply {
                    text = a; textSize = 22f; gravity = Gravity.CENTER; setTextColor(t.text)
                    background = t.keyBg(this@LenspilotInputMethodService)
                    minWidth = dp(40)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginStart = dp(2); marginEnd = dp(2) }
                    setPadding(dp(6), 0, dp(6), 0)
                }
                tvs.add(tv); box.addView(tv)
            }
            altViews = tvs
            box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val sw = resources.displayMetrics.widthPixels
            val x = loc[0].coerceIn(0, maxOf(0, sw - box.measuredWidth))
            val y = loc[1] - box.measuredHeight - dp(6)
            altPw = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                isTouchable = false; isFocusable = false; isClippingEnabled = false
                elevation = 8 * resources.displayMetrics.density
                try { showAtLocation(v, Gravity.NO_GRAVITY, x, y) } catch (e: Exception) { }
            }
            highlight(0)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longDone = false
                    view.isPressed = true
                    // চাপলে কী-র ওপরে বড় প্রিভিউ (একটা-দুটো অক্ষরের কী-তে)
                    if (label.length <= 2 && label.isNotBlank() && longAction == null) {
                        val tv = TextView(this).apply {
                            text = label; textSize = 30f; gravity = Gravity.CENTER; setTextColor(t.text)
                            background = t.pillBg(this@LenspilotInputMethodService, 12)
                            minWidth = view.width + dp(10); minHeight = view.height + dp(14)
                        }
                        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                        val loc = IntArray(2); view.getLocationOnScreen(loc)
                        val x = (loc[0] + view.width / 2 - tv.measuredWidth / 2).coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - tv.measuredWidth))
                        val y = loc[1] - tv.measuredHeight - dp(2)
                        preview = android.widget.PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            isTouchable = false; isFocusable = false; isClippingEnabled = false
                            elevation = 6 * resources.displayMetrics.density
                            try { showAtLocation(view, Gravity.NO_GRAVITY, x, y) } catch (ex: Exception) { }
                        }
                    }
                    if (alts.isNotEmpty() || longAction != null) h.postDelayed(showLong, 380)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (altViews.isNotEmpty()) {
                        var best = picked; var bestD = Float.MAX_VALUE
                        altViews.forEachIndexed { i, tv ->
                            val l = IntArray(2); tv.getLocationOnScreen(l)
                            val dd = Math.abs(e.rawX - (l[0] + tv.width / 2f))
                            if (dd < bestD) { bestD = dd; best = i }
                        }
                        if (best != picked) highlight(best)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(showLong)
                    view.isPressed = false
                    val had = altViews.isNotEmpty()
                    val pick = picked
                    clearAll()
                    if (had) { haptic(view); onPick(alts[pick]) }
                    else if (!longDone) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(showLong); view.isPressed = false; clearAll(); true }
                else -> true
            }
        }
    }

    /** স্পেস: ডান-বাঁয়ে টানলে কার্সার সরে; লং-প্রেস = বাংলা↔English; ছোট ট্যাপ = স্পেস। */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachSpaceGestures(v: View) {
        if (customizeMode) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var startX = 0f; var lastStep = 0; var moved = false; var longDone = false
        val stepPx = dp(16)
        val longRun = Runnable {
            if (!moved) { longDone = true; v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); toggleBanglaMode() }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; lastStep = 0; moved = false; longDone = false; view.isPressed = true; h.postDelayed(longRun, 500) }
                MotionEvent.ACTION_MOVE -> {
                    val steps = ((e.rawX - startX) / stepPx).toInt()
                    if (steps != lastStep) {
                        moved = true; h.removeCallbacks(longRun)
                        val code = if (steps > lastStep) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
                        repeat(Math.abs(steps - lastStep)) { moveCursor(code) }
                        lastStep = steps
                    }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(longRun); view.isPressed = false
                    if (!moved && !longDone) view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(longRun); view.isPressed = false }
            }
            true
        }
    }

    /** কমা লং-প্রেস: দ্রুত টুল (Gboard-এ যেখানে সেটিংস/এক-হাত আসে)। */
    private fun showQuickTools(anchor: View) {
        val t = th()
        val opts = listOf(
            "⚙" to { showToolsMenu() },
            "🖐" to { cycleOneHand() },
            "T↔" to { translateCurrentField() },
            "🌐" to { runCatching { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() } }
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = t.pillBg(this@LenspilotInputMethodService, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        var pw: android.widget.PopupWindow? = null
        opts.forEach { (lab, act) ->
            box.addView(TextView(this).apply {
                text = lab; textSize = 20f; gravity = Gravity.CENTER; setTextColor(t.text)
                background = t.keyBg(this@LenspilotInputMethodService)
                minWidth = dp(48); isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginStart = dp(2); marginEnd = dp(2) }
                setOnClickListener { dismissQuiet(pw); act() }
            })
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val x = loc[0].coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - box.measuredWidth))
        pw = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true; isFocusable = false; isClippingEnabled = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            elevation = 8 * resources.displayMetrics.density
            try { showAtLocation(anchor, Gravity.NO_GRAVITY, x, loc[1] - box.measuredHeight - dp(6)) } catch (e: Exception) { }
        }
    }

    /** ইংরেজিতে বাক্যের শুরুতে অটো-ক্যাপিটাল (Gboard-এর মতো)। */
    private fun maybeAutoCap() {
        if (banglaMode || symbolsMode || capsOn || chatOpen()) return
        val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: return
        if (before.isEmpty() || before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")) {
            capsOn = true
            buildKeyRows()
        }
    }

    private fun numberRowChars(): String = if (banglaMode) "১২৩৪৫৬৭৮৯০" else "1234567890"

    private fun charLabel(c: Char): String = if (capsOn) c.uppercaseChar().toString() else c.toString()

    private fun commitChar(c: Char) {
        val out = if (capsOn) c.uppercaseChar() else c
        emit(out.toString())
        if (capsOn && !capsLock && c.isLetter()) { capsOn = false; buildKeyRows() } else updateSuggestions()
    }

    /** Row-1 vowel keys in Bangla mode — commits the dependent matra
     * (কার) sign instead of the independent letter whenever the previous
     * character is a consonant, exactly like the reference keyboard's
     * phonetic behaviour (ক + ই → কি, not কই). অ is the inherent vowel:
     * right after a bare consonant nothing needs to be typed at all. */
    private fun commitBanglaVowel(vowel: Char) {
        val before = if (chatOpen()) chatPanel!!.tail(1).lastOrNull()
            else (currentInputConnection ?: return).getTextBeforeCursor(1, 0)?.toString()?.lastOrNull()
        val afterConsonant = before != null && before in banglaConsonantChars
        if (vowel == 'অ') {
            if (!afterConsonant) emit("অ")
            updateSuggestions()
            return
        }
        val matra = banglaMatraMap[vowel]
        if (matra != null && afterConsonant) {
            emit(matra)
        } else {
            emit(vowel.toString())
        }
        updateSuggestions()
    }

    /** কার্সারের আগের একটা শব্দ মোছে (ব্যাকস্পেস বাঁয়ে টানলে)। */
    private fun deleteWordBack() {
        if (chatOpen()) { deleteOneChar(); return }
        val c = currentInputConnection ?: return
        val before = c.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        c.deleteSurroundingText(before.length - i, 0)
        updateSuggestions()
    }

    private fun deleteOneChar() {
        if (chatOpen()) { chatPanel!!.deleteLast(); updateSuggestions(); return }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        updateSuggestions()
    }

    // ------------------------------------------------------------------
    // Word-suggestion bar (Bangla mode only) — prefix match against a
    // small bundled common-word list. NOTE: this is a starter dictionary
    // (a few hundred high-frequency words), not a full corpus — good
    // enough to show the bar working exactly like the reference, but
    // swap in a bigger word-frequency list later (as a JSON/text asset)
    // for real-world coverage. See CommonBanglaWords.WORDS.
    // ------------------------------------------------------------------

    private fun currentWordBeforeCursor(): String {
        val before = if (chatOpen()) chatPanel!!.tail(40)
            else (currentInputConnection ?: return "").getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val idx = before.indexOfLast { it == ' ' || it == '\n' }
        return before.substring(idx + 1)
    }

    /** ঘরে (কার্সারের আগে/পরে) কিছু লেখা আছে কিনা। */
    private fun fieldHasTypedText(): Boolean {
        if (chatOpen()) return chatPanel!!.hasInput()
        val ic = currentInputConnection ?: return false
        return !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    /** v120: ঘর খালি = ছোট আইকনের সারি; লেখা শুরু = আইকন সব সরে যায়, থাকে শুধু বাঁ কোণের মেইন আইকন
     * (আর বাংলা শব্দ-সাজেশন থাকলে সেগুলো)। এডিট মোডে টুলবার সবসময় দেখা যায়। */
    private fun updateToolbar() {
        if (!::toolScroll.isInitialized) return
        refreshBanglaDynamicRows()
        if (TranslateGlassOverlay.isShowing) TranslateGlassOverlay.setDraft(currentFieldText())
        val typing = !customizeMode && fieldHasTypedText()
        toolScroll.visibility = if (typing && !toolsExpanded) View.GONE else View.VISIBLE
        if (toolScroll.visibility == View.VISIBLE) toolScroll.post { fitToolbar() }
        var matches: List<String> = emptyList()
        if (typing && banglaMode && !symbolsMode) {
            val prefix = currentWordBeforeCursor()
            if (prefix.isNotBlank()) {
                matches = CommonBanglaWords.WORDS.filter { it.startsWith(prefix) && it != prefix }.take(3)
            }
        }
        val slots = listOf(kbSuggestion1, kbSuggestion2, kbSuggestion3)
        slots.forEachIndexed { i, view ->
            view.text = matches.getOrNull(i).orEmpty()
            view.visibility = if (i < matches.size) View.VISIBLE else View.GONE
        }
        suggestionBar.visibility = if (typing && !toolsExpanded && matches.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSuggestions() = updateToolbar()

    private fun clearSuggestions() = updateToolbar()

    /** Replaces the in-progress word (text after the last space/newline)
     * with the tapped suggestion, then adds a trailing space. */
    private fun applySuggestion(word: String) {
        if (word.isBlank()) return
        val ic = currentInputConnection ?: if (chatOpen()) null else return
        val prefix = currentWordBeforeCursor()
        if (chatOpen()) {
            repeat(prefix.length) { chatPanel!!.deleteLast() }
            chatPanel!!.append("$word ")
            clearSuggestions()
            return
        }
        if (prefix.isNotEmpty()) ic?.deleteSurroundingText(prefix.length, 0)
        ic?.commitText("$word ", 1)
        clearSuggestions()
    }

    private fun submitEditorAction() {
        if (chatOpen()) { chatPanel!!.send(); return }
        if (TranslateGlassOverlay.isShowing) { sendTranslatedDraft(); return }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        clearSuggestions()
    }

    /** Cursor-move keys (‹ ›) on the bottom row — a proper key press needs
     * both DOWN and UP, otherwise some apps' text fields never register
     * the move. */
    private fun moveCursor(keyCode: Int) {
        if (chatOpen()) return
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** বাটনের পরিচয় + ডিফল্ট মাপ (view.tag-এ) — KeyCustomizer-এর গুণক এর ওপর বসে। */
    private class KeyTag(val id: String, val baseWeight: Float, val baseTextSp: Float)

    private fun makeKeyView(label: String, weight: Float, special: Boolean, onClick: () -> Unit): TextView {
        val id = "k_" + label.lowercase()
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(th().text)
            layoutParams = LinearLayout.LayoutParams(0, keyH(), weight).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            background = if (special) th().specialBg(this@LenspilotInputMethodService) else th().keyBg(this@LenspilotInputMethodService)
            // v127: এক-দুই অক্ষরের কী বড় লেখায় (আগে সবই ১৬sp ছিল, ছোট দেখাত)
            tag = KeyTag(id, weight, if (label.length <= 2) 21f else 16f)
            applyKeyCustom(this)
            isClickable = true
            isFocusable = false
            if (customizeMode) attachSelector(this, id) else setOnClickListener { haptic(this); onClick() }
        }
    }

    private fun makeIconKeyView(iconRes: Int, weight: Float, special: Boolean, onClick: () -> Unit): ImageButton {
        val id = "i_" + runCatching { resources.getResourceEntryName(iconRes) }.getOrDefault(iconRes.toString())
        return ImageButton(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(0, keyH(), weight).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            background = if (special) th().specialBg(this@LenspilotInputMethodService) else th().keyBg(this@LenspilotInputMethodService)
            imageTintList = ColorStateList.valueOf(th().text)
            tag = KeyTag(id, weight, 0f)
            applyKeyCustom(this)
            if (customizeMode) attachSelector(this, id) else setOnClickListener { haptic(this); onClick() }
        }
    }

    /** KeyCustomizer-এর গুণক অনুযায়ী প্রস্থ (weight), উচ্চতা ও লেখার সাইজ বসায়। */
    private fun applyKeyCustom(v: View) {
        val t = v.tag as? KeyTag ?: return
        val k = KeyCustomizer.get(this, t.id)
        val lp = v.layoutParams as LinearLayout.LayoutParams
        lp.weight = t.baseWeight * k.w
        lp.height = (keyH() * k.h).toInt().coerceAtLeast(dp(14))
        v.layoutParams = lp
        if (v is TextView) {
            v.textSize = sizeScaleTextSize(t.baseTextSp) * k.t
        } else {
            val p = (dp(10) / k.t).toInt().coerceIn(dp(1), dp(22))
            v.setPadding(p, p, p, p)
        }
    }

    /** সারির উচ্চতা = তার সবচেয়ে উঁচু বাটনের উচ্চতা; ছোট বাটন মাঝ বরাবর বসে। */
    private fun fitRow(row: LinearLayout) {
        row.gravity = Gravity.CENTER_VERTICAL
        var h = 0
        for (i in 0 until row.childCount) {
            val lp = row.getChildAt(i).layoutParams as? LinearLayout.LayoutParams ?: continue
            h = maxOf(h, lp.height)
        }
        val lp = row.layoutParams as LinearLayout.LayoutParams
        lp.height = if (h > 0) h else keyH()
        row.layoutParams = lp
    }

    // ------------------------------------------------------------------
    // v120: বাটন + বাটনের লেখা টেনে ঠিক-মাপে ছোট-বড় করা
    //   এক আঙুল টানা  = বাটনের প্রস্থ (ডানে-বামে) ও উচ্চতা (ওপরে-নিচে) আঙুলের সঙ্গে সঙ্গে বদলায়
    //   দুই আঙুলে চিমটি = ওই বাটনের লেখা/আইকনের সাইজ
    // মাপ KeyCustomizer-এ সেভ হয়, কীবোর্ড বন্ধ-খুললেও থাকে।
    // ------------------------------------------------------------------

    private class TbSpec(val view: View, val id: String, val wDp: Int, val hDp: Int, val textSp: Float)
    private val tbSpecs = mutableListOf<TbSpec>()

    private fun applyToolbarCustom() {
        tbSpecs.forEach { applyTb(it) }
    }

    private fun applyTb(sp: TbSpec) {
        val k = KeyCustomizer.get(this, sp.id)
        val d = resources.displayMetrics.density
        val lp = sp.view.layoutParams ?: return
        lp.width = (sp.wDp * d * k.w).toInt().coerceAtLeast((12 * d).toInt())
        lp.height = (sp.hDp * d * k.h).toInt().coerceAtLeast((12 * d).toInt())
        sp.view.layoutParams = lp
        when {
            sp.view === aiToggleButton -> aiToggleLabel.textSize = sp.textSp * k.t
            sp.view is TextView -> sp.view.textSize = sp.textSp * k.t
            sp.view is ImageButton -> {
                val p = ((9 * d) / k.t).toInt().coerceIn((1 * d).toInt(), (16 * d).toInt())
                sp.view.setPadding(p, p, p, p)
            }
        }
    }

    // ------------------------------------------------------------------
    // v124: Gboard-এর মতো দৃশ্যমান হ্যান্ডেল (দেখো KeyboardResizeOverlay)
    //   বাটনে চাপো = সিলেক্ট (টিল বর্ডার + ডানে/নিচে দাগ + বাঁ-ওপরে "A")
    //   ডানের দাগ = প্রস্থ, নিচের দাগ = উচ্চতা, A টানা = লেখার সাইজ; দুই আঙুলে চিমটিও চলে
    //   কীবোর্ডের চারপাশের দাগ = পুরো কীবোর্ডের উচ্চতা/প্রস্থ, মাঝের ✥ = সরানো
    // ------------------------------------------------------------------

    private fun keyH(): Int =
        (44f * resources.displayMetrics.density * Prefs.keyboardSizeScale(this) * Prefs.keyboardHeightScale(this)).toInt()

    private fun applyKeyboardFrame() {
        val v = kbRootView ?: return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        val sw = resources.displayMetrics.widthPixels
        val frac = Prefs.keyboardWidthFrac(this).coerceIn(0.5f, 1f)
        val w = (sw * frac).toInt()
        lp.width = if (frac >= 0.999f) ViewGroup.LayoutParams.MATCH_PARENT else w
        lp.leftMargin = if (frac >= 0.999f) 0 else (sw * Prefs.keyboardLeftFrac(this)).toInt().coerceIn(0, sw - w)
        lp.bottomMargin = (Prefs.keyboardLiftDp(this) * resources.displayMetrics.density).toInt()
        lp.gravity = Gravity.BOTTOM or Gravity.START
        v.layoutParams = lp
    }

    private fun findResizable(id: String): View? {
        tbSpecs.firstOrNull { it.id == id }?.let { return it.view }
        if (!::keysContainer.isInitialized) return null
        for (r in 0 until keysContainer.childCount) {
            val row = keysContainer.getChildAt(r) as? ViewGroup ?: continue
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                if ((v.tag as? KeyTag)?.id == id) return v
            }
        }
        return null
    }

    private fun applySel(id: String) {
        val sp = tbSpecs.firstOrNull { it.id == id }
        if (sp != null) { applyTb(sp); return }
        val v = findResizable(id) ?: return
        applyKeyCustom(v)
        (v.parent as? LinearLayout)?.let { fitRow(it) }
    }

    /** এডিট মোডে বাটনের টাচ: চাপলে সিলেক্ট, দুই আঙুলে চিমটি = লেখার সাইজ। টাইপ হয় না। */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachSelector(v: View, id: String) {
        val ctx = this
        val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val k = KeyCustomizer.get(ctx, id)
                k.t = (k.t * d.scaleFactor).coerceIn(0.5f, 3f)
                applySel(id)
                v.post { resizeOverlay?.refresh() }
                return true
            }
        })
        v.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectedId = id
                    resizeOverlay?.refresh()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    KeyCustomizer.save(ctx)
                    resizeOverlay?.refresh()
                }
            }
            true
        }
    }

    private fun screenRect(v: View?): Rect? {
        if (v == null || v.width <= 0 || v.height <= 0 || !v.isShown) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private val resizeHost = object : KeyboardResizeOverlay.Host {
        private val svc get() = this@LenspilotInputMethodService

        override fun selectedBounds(): Rect? {
            if (!svc.customizeMode) return null
            val id = svc.selectedId ?: return null
            return svc.screenRect(svc.findResizable(id))
        }

        override fun frameBounds(): Rect? = if (svc.customizeMode) svc.screenRect(svc.kbRootView) else null

        override fun buttonBegin() {
            val id = svc.selectedId ?: return
            val v = svc.findResizable(id) ?: return
            val k = KeyCustomizer.get(svc, id)
            svc.selStart = floatArrayOf(k.w, k.h, k.t)
            svc.selStartPx = intArrayOf(v.width, v.height)
        }

        override fun buttonApply(kind: Int, dx: Float, dy: Float) {
            val id = svc.selectedId ?: return
            val v = svc.findResizable(id) ?: return
            val k = KeyCustomizer.get(svc, id)
            val px = svc.selStartPx
            val st = svc.selStart
            when (kind) {
                0 -> if (px[0] > 0) {
                    val tag = v.tag as? KeyTag
                    val row = v.parent as? LinearLayout
                    if (tag != null && row != null) {
                        // কীর প্রস্থ weight দিয়ে চলে — আঙুলের ঠিক জায়গায় ডান কিনারা রাখতে weight হিসাব করি
                        var others = 0f
                        var margins = 0
                        for (i in 0 until row.childCount) {
                            val c = row.getChildAt(i)
                            val lp = c.layoutParams as LinearLayout.LayoutParams
                            margins += lp.marginStart + lp.marginEnd
                            if (c !== v) others += lp.weight
                        }
                        val avail = (row.width - row.paddingLeft - row.paddingRight - margins).toFloat()
                        if (avail > 0f) {
                            val target = (px[0] + dx).coerceIn(svc.dp(14).toFloat(), avail * 0.7f)
                            val w = target * others / (avail - target)
                            k.w = (w / tag.baseWeight).coerceIn(0.2f, 6f)
                        }
                    } else {
                        k.w = (st[0] * (px[0] + dx) / px[0]).coerceIn(0.3f, 4f)
                    }
                }
                1 -> if (px[1] > 0) {
                    k.h = (st[1] * (px[1] + dy) / px[1]).coerceIn(0.4f, 2.5f)
                }
                2 -> {
                    val f = Math.exp((-dy / (90f * svc.resources.displayMetrics.density)).toDouble()).toFloat()
                    k.t = (st[2] * f).coerceIn(0.5f, 3f)
                }
            }
            svc.applySel(id)
        }

        override fun buttonEnd() {
            KeyCustomizer.save(svc)
            val id = svc.selectedId
            val isToolbar = id != null && svc.tbSpecs.any { it.id == id }
            if (!isToolbar) svc.uiHandler.post { svc.buildKeyRows(); svc.resizeOverlay?.refresh() }
        }

        override fun frameBegin() {
            val kb = svc.kbRootView
            svc.fStartKeysH = maxOf(1, svc.keysContainer.height)
            svc.fStartScale = Prefs.keyboardHeightScale(svc)
            svc.fStartLeft = kb?.left ?: 0
            svc.fStartW = maxOf(1, kb?.width ?: 1)
            svc.fStartLift = Prefs.keyboardLiftDp(svc)
        }

        private fun setFrame(left: Int, w: Int) {
            val sw = svc.resources.displayMetrics.widthPixels.toFloat()
            Prefs.setKeyboardWidthFrac(svc, w / sw)
            Prefs.setKeyboardLeftFrac(svc, left / sw)
            svc.applyKeyboardFrame()
        }

        override fun frameApply(kind: Int, dx: Float, dy: Float) {
            val sw = svc.resources.displayMetrics.widthPixels
            val minW = (sw * 0.5f).toInt()
            when (kind) {
                0 -> {
                    val sc = (svc.fStartScale * (svc.fStartKeysH - dy) / svc.fStartKeysH).coerceIn(0.6f, 1.9f)
                    Prefs.setKeyboardHeightScale(svc, sc)
                    svc.buildKeyRows()
                }
                1 -> {
                    val right = svc.fStartLeft + svc.fStartW
                    val nl = (svc.fStartLeft + dx).toInt().coerceIn(0, maxOf(0, right - minW))
                    setFrame(nl, right - nl)
                }
                2 -> {
                    val nw = (svc.fStartW + dx).toInt().coerceIn(minW, maxOf(minW, sw - svc.fStartLeft))
                    setFrame(svc.fStartLeft, nw)
                }
                3 -> {
                    val nl = (svc.fStartLeft + dx).toInt().coerceIn(0, maxOf(0, sw - svc.fStartW))
                    Prefs.setKeyboardLeftFrac(svc, nl.toFloat() / sw)
                    Prefs.setKeyboardLiftDp(svc, (svc.fStartLift - dy / svc.resources.displayMetrics.density).coerceIn(0f, 160f))
                    svc.applyKeyboardFrame()
                }
            }
        }

        override fun frameEnd() {
            svc.uiHandler.post { svc.resizeOverlay?.refresh() }
        }

        override fun onReset() {
            KeyCustomizer.reset(svc)
            Prefs.resetKeyboardFrame(svc)
            svc.applyKeyboardFrame()
            svc.applyToolbarCustom()
            svc.buildKeyRows()
            svc.uiHandler.post { svc.resizeOverlay?.refresh() }
            Toast.makeText(svc, R.string.kb_sizes_reset_toast, Toast.LENGTH_SHORT).show()
        }

        override fun onDone() {
            svc.setCustomizeMode(false)
        }
    }

    private fun setCustomizeMode(on: Boolean) {
        customizeMode = on
        selectedId = null
        for (sp in tbSpecs) {
            if (on) attachSelector(sp.view, sp.id) else sp.view.setOnTouchListener(null)
        }
        resizeOverlay?.visibility = if (on) View.VISIBLE else View.GONE
        // এডিটের সময় কীবোর্ড একটু ঝাপসা (Gboard-এর মতো) — হ্যান্ডেল ফুটে ওঠে
        kbRootView?.alpha = if (on) 0.6f else 1f
        applyToolbarCustom()
        setStatus(if (on) getString(R.string.kb_resize_hint) else null)
        updateToolbar()
        buildKeyRows()
        resizeOverlay?.let { o -> o.post { o.refresh() } }
    }

    /** Holds a key repeating its action while pressed (backspace). */
    private fun View.attachRepeatOnHold(action: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var repeating = false
        var swiped = false
        var startX = 0f
        var doneWords = 0
        val repeatRunnable = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 45)
            }
        }
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeating = true; swiped = false; startX = event.rawX; doneWords = 0
                    handler.postDelayed(repeatRunnable, 350)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gboard-এর মতো: বাঁয়ে টানলে এক-এক শব্দ করে মোছে
                    val dx = startX - event.rawX
                    if (dx > dp(28)) {
                        swiped = true
                        handler.removeCallbacks(repeatRunnable)
                        val want = (dx / dp(34)).toInt()
                        while (doneWords < want) { deleteWordBack(); doneWords++ }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (repeating) handler.removeCallbacks(repeatRunnable)
                    repeating = false
                    if (swiped) { swiped = false; return@setOnTouchListener true }
                }
            }
            false // let the normal click listener still fire for a plain tap
        }
    }

    /** Every row height, key margin, and icon padding is defined in
     * dp() calls, so scaling this one function by the user's chosen
     * size (see Prefs.keyboardSizeScale / the ⚙ tools menu) resizes
     * the whole keyboard uniformly — this is the fix for "buttons are
     * too small". Key text size is scaled separately in makeKeyView. */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density * Prefs.keyboardSizeScale(this)).toInt()

    private fun sizeScaleTextSize(base: Float): Float = base * Prefs.keyboardSizeScale(this)

    // ------------------------------------------------------------------
    // Bangla layout toggle, resize, and translate — all persisted via
    // Prefs so the choice sticks across app focus changes.
    // ------------------------------------------------------------------

    private fun updateBanglaToggleUi() {
        kbBanglaToggleButton.text = if (banglaMode) "EN" else "বাং"
    }

    private fun toggleBanglaMode() {
        banglaMode = !banglaMode
        symbolsMode = false
        Prefs.setKeyboardBanglaMode(this, banglaMode)
        updateBanglaToggleUi()
        buildKeyRows()
    }

    private class Tile(val icon: String, val label: String, val active: Boolean, val keepOpen: Boolean, val action: () -> Unit)

    /** টুলবারে যা ধরে না সেগুলো এখানেও আছে — ⊞ চাপলে সব অপশন গ্রিডে (৪ কলাম, স্ক্রল করা যায়, কিছু কাটা পড়ে না)। */
    private fun showToolsMenu() {
        val tiles = ArrayList<Tile>()
        fun add(icon: String, label: String, active: Boolean = false, keep: Boolean = false, action: () -> Unit) {
            tiles.add(Tile(icon, label, active, keep, action))
        }
        if (customizeMode) add("✔", "Done", true) { setCustomizeMode(false) }
        add("🤖", "AI typing", Prefs.keyboardAutoTypeEnabled(this), true) { toggleAutoTypeEnabled() }
        add("🌐", if (banglaMode) "বাংলা" else "English", false, false) { toggleBanglaMode() }
        add("文A", "Screen translate", TranslateGlassOverlay.isShowing) { toggleTranslateMode() }
        add("T↔", "Translate text") { translateCurrentField() }
        add("🗣", "I read: " + Prefs.translateMyLang(this), false, true) { cycleMyLang() }
        add("➡", "Out: " + Prefs.translateTargetLang(this), false, true) { cycleTranslateLanguage() }
        add("😊", "Emoji", panelKind == "emoji") { openPanel("emoji") }
        add("📋", "Clipboard", panelKind == "clip") { openPanel("clip") }
        add("✎", "Edit text", panelKind == "edit") { openPanel("edit") }
        add("🔢", "Number row", Prefs.keyboardNumberRow(this), true) {
            Prefs.setKeyboardNumberRow(this, !Prefs.keyboardNumberRow(this)); buildKeyRows()
        }
        add("🖐", "One-hand: " + Prefs.keyboardOneHand(this), Prefs.keyboardOneHand(this) != "off", true) { cycleOneHand() }
        add("📳", "Vibration", Prefs.keyboardHaptic(this), true) { Prefs.setKeyboardHaptic(this, !Prefs.keyboardHaptic(this)) }
        add("↩", "Last AI reply") { typeLastAiReply() }
        add("💬", "AI chat", chatOpen()) { toggleChatPanel() }
        add("🎨", "Theme: " + KeyboardTheme.current(this).label) { showThemeMenu() }
        if (!customizeMode) add("⤢", "Resize (drag)") { setCustomizeMode(true) }
        add("♻", "Reset sizes") {
            KeyCustomizer.reset(this)
            Prefs.resetKeyboardFrame(this)
            Prefs.setKeyboardOneHand(this, "off")
            applyKeyboardFrame()
            applyToolbarCustom()
            buildKeyRows()
            toolScroll.post { fitToolbar() }
            Toast.makeText(this, R.string.kb_sizes_reset_toast, Toast.LENGTH_SHORT).show()
        }
        add("⌨", "Switch keyboard") {
            runCatching { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }
        }
        showInlineGrid(tiles) { showToolsMenu() }
    }

    private fun showInlineGrid(tiles: List<Tile>, reopen: () -> Unit) {
        val sv = inlineMenu ?: return
        val t = th()
        val d = resources.displayMetrics.density
        val me = this
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = t.pillBg(me, 14)
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.kb_menu_close)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(t.text)
            gravity = Gravity.CENTER
            minHeight = (40 * d).toInt()
            isClickable = true
            isFocusable = false
            setOnClickListener { hideInlineMenu() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cols = 4
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until cols) {
                val tile = tiles.getOrNull(i + j)
                if (tile == null) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); continue }
                val fg = if (tile.active) t.onAccent else t.text
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = if (tile.active) t.accentBg(me, 12) else t.keyBg(me)
                    isClickable = true
                    isFocusable = false
                    setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
                    addView(TextView(me).apply { text = tile.icon; textSize = 24f; gravity = Gravity.CENTER; setTextColor(fg) })
                    addView(TextView(me).apply {
                        text = tile.label; textSize = 12.5f; maxLines = 2; gravity = Gravity.CENTER
                        ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(fg)
                    })
                    setOnClickListener {
                        hideInlineMenu()
                        tile.action()
                        if (tile.keepOpen) reopen()
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, (68 * d).toInt(), 1f).apply {
                    setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
                })
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            i += cols
        }
        sv.removeAllViews()
        sv.addView(col)
        val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        sv.visibility = View.VISIBLE
        sv.post {
            if (sv.height > maxH) sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }
    }

    /** টুলবারের স্ট্রিপে যতগুলো বাটন ধরে ততগুলো দেখায়, বাকিগুলো লুকায় (সবই ⊞ গ্রিডে আছে)। */
    private fun fitToolbar() {
        if (!::toolScroll.isInitialized || toolScroll.visibility != View.VISIBLE) return
        val avail = toolScroll.width
        if (avail <= 0) return
        val order = listOf<View>(
            kbGlobeButton, kbBanglaToggleButton, aiToggleButton, kbTranslateButton, kbClipButton, kbEmojiButton,
            kbEditButton, kbOneHandButton, kbChatButton, kbThemeButton, kbSettingsButton, kbLastReplyButton
        )
        var used = 0
        for (v in order) {
            val lp = v.layoutParams as? LinearLayout.LayoutParams
            val w = (lp?.width ?: v.width) + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
            if (used + w <= avail) { v.visibility = View.VISIBLE; used += w } else v.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // v127: Gboard-এর মতো প্যানেল (ইমোজি / ক্লিপবোর্ড / টেক্সট-এডিট), এক-হাতের মোড, কী-কাঁপুনি
    // ------------------------------------------------------------------

    private fun haptic(v: View) {
        if (Prefs.keyboardHaptic(this)) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun openPanel(kind: String) {
        if (panelKind == kind) { closePanel(); return }
        val host = panelHost ?: return
        val p = panels ?: return
        hideInlineMenu()
        if (chatOpen()) closeChatPanel()
        val h = maxOf(keysContainer.height, keyH() * 4)
        host.removeAllViews()
        host.addView(when (kind) {
            "emoji" -> p.emojiView()
            "clip" -> p.clipboardView()
            else -> p.editView()
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.layoutParams = host.layoutParams.apply { height = h }
        host.visibility = View.VISIBLE
        keysContainer.visibility = View.GONE
        panelKind = kind
        updatePanelButtons()
    }

    private fun closePanel() {
        panelKind = null
        panelHost?.let { it.visibility = View.GONE; it.removeAllViews() }
        if (::keysContainer.isInitialized) keysContainer.visibility = View.VISIBLE
        updatePanelButtons()
    }

    private fun updatePanelButtons() {
        if (!::kbClipButton.isInitialized) return
        val t = th()
        listOf(kbClipButton to "clip", kbEmojiButton to "emoji", kbEditButton to "edit").forEach { (b, k) ->
            val on = panelKind == k
            b.background = if (on) t.accentBg(this, 17) else t.specialBg(this)
            b.setTextColor(if (on) t.onAccent else t.text)
        }
        val oh = Prefs.keyboardOneHand(this) != "off"
        kbOneHandButton.background = if (oh) t.accentBg(this, 17) else t.specialBg(this)
        kbOneHandButton.setTextColor(if (oh) t.onAccent else t.text)
    }

    /** এক-হাতের মোড: বন্ধ → বাঁ → ডান → বন্ধ (কীবোর্ড ৮০% চওড়া হয়ে এক পাশে সরে যায়)। */
    private fun cycleOneHand() {
        val next = when (Prefs.keyboardOneHand(this)) { "off" -> "left"; "left" -> "right"; else -> "off" }
        Prefs.setKeyboardOneHand(this, next)
        when (next) {
            "left" -> { Prefs.setKeyboardWidthFrac(this, 0.8f); Prefs.setKeyboardLeftFrac(this, 0f) }
            "right" -> { Prefs.setKeyboardWidthFrac(this, 0.8f); Prefs.setKeyboardLeftFrac(this, 0.2f) }
            else -> { Prefs.setKeyboardWidthFrac(this, 1f); Prefs.setKeyboardLeftFrac(this, 0f) }
        }
        applyKeyboardFrame()
        updatePanelButtons()
        Toast.makeText(this, when (next) {
            "left" -> R.string.kb_menu_onehand_left
            "right" -> R.string.kb_menu_onehand_right
            else -> R.string.kb_menu_onehand_off
        }, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // v125: Gboard-এর মতো ডান কোণের মাইক — ভয়েস টাইপ (Android SpeechRecognizer)
    // ------------------------------------------------------------------

    private var speech: android.speech.SpeechRecognizer? = null
    private var micOn = false

    private fun updateMicUi() {
        if (!::kbMicButton.isInitialized) return
        val t = th()
        kbMicButton.background = if (micOn) t.accentBg(this, 17) else t.specialBg(this)
        kbMicButton.imageTintList = ColorStateList.valueOf(if (micOn) t.onAccent else t.text)
    }

    private fun toggleMic() {
        if (micOn) { stopMic(); return }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.kb_mic_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.kb_mic_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val sr = try { android.speech.SpeechRecognizer.createSpeechRecognizer(this) } catch (e: Exception) { null }
        if (sr == null) { Toast.makeText(this, R.string.kb_mic_unavailable, Toast.LENGTH_LONG).show(); return }
        speech = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { setStatus(getString(R.string.kb_mic_listening)) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stopMic()
                if (!text.isNullOrBlank()) {
                    emit("$text ")
                    updateSuggestions()
                }
            }
            override fun onError(error: Int) { stopMic() }
        })
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, if (banglaMode) "bn-BD" else "en-US")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        micOn = true
        updateMicUi()
        try { sr.startListening(i) } catch (e: Exception) { stopMic() }
    }

    private fun stopMic() {
        micOn = false
        runCatching { speech?.destroy() }
        speech = null
        if (::kbStatusText.isInitialized) setStatus(null)
        updateMicUi()
    }

    // ------------------------------------------------------------------
    // v120: অনুবাদ-মোড — গ্লাস কার্ড (দেখো TranslateGlassOverlay)
    // ------------------------------------------------------------------

    private fun updateTranslateButtonUi() {
        if (!::kbTranslateButton.isInitialized) return
        val on = TranslateGlassOverlay.isShowing
        kbTranslateButton.background = if (on) th().accentBg(this, 17) else th().specialBg(this)
        kbTranslateButton.setTextColor(if (on) th().onAccent else th().text)
    }

    private fun toggleTranslateMode() {
        if (TranslateGlassOverlay.isShowing) {
            TranslateGlassOverlay.hide()
        } else {
            Prefs.setTranslateEnabled(this, true)
            when (TranslateGlassOverlay.show(this)) {
                TranslateGlassOverlay.NEED_OVERLAY ->
                    Toast.makeText(this, R.string.tg_need_overlay, Toast.LENGTH_LONG).show()
                TranslateGlassOverlay.NEED_CAPTURE ->
                    Toast.makeText(this, R.string.tg_need_capture, Toast.LENGTH_LONG).show()
            }
        }
        updateTranslateButtonUi()
    }

    // ------------------------------------------------------------------
    // v125: অনুবাদ মোডে লেখা — আসল চ্যাট-বক্সের লেখা অনুবাদ করে সেই বক্সেই বসানো (Accessibility ছাড়া, InputConnection দিয়ে)
    // ------------------------------------------------------------------

    private var sendingTranslation = false

    private fun currentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        return (ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()) + (ic.getTextAfterCursor(4000, 0)?.toString().orEmpty())
    }

    private fun sendTranslatedDraft() {
        if (sendingTranslation) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        val text = (before + after).trim()
        if (text.isEmpty()) return
        sendingTranslation = true
        TranslateGlassOverlay.setBusy(getString(R.string.tg_translating))
        serviceScope.launch {
            try {
                val tr = TranslateApi.translate(this@LenspilotInputMethodService, listOf(text), Prefs.translateTargetLang(this@LenspilotInputMethodService))
                    ?.firstOrNull()?.trim()
                if (tr.isNullOrBlank()) {
                    Toast.makeText(this@LenspilotInputMethodService, R.string.tg_send_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ic2 = currentInputConnection ?: return@launch
                TranslateGlassOverlay.rememberSent(tr, text)
                ic2.beginBatchEdit()
                ic2.deleteSurroundingText(before.length, after.length)
                ic2.commitText(tr, 1)
                ic2.endBatchEdit()
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO) {
                    ic2.performEditorAction(action)
                } else {
                    // এই অ্যাপের বক্সে Enter = নতুন লাইন, তাই নিজে চাপি না — কার্ড কিছুক্ষণ সরে যায়, ইউজার অ্যাপের সেন্ড বাটন চাপবে
                    Toast.makeText(this@LenspilotInputMethodService, R.string.tg_tap_send, Toast.LENGTH_LONG).show()
                    TranslateGlassOverlay.passThrough(7000)
                }
            } finally {
                sendingTranslation = false
                TranslateGlassOverlay.setBusy(null)
            }
        }
    }

    private fun cycleMyLang() {
        val idx = translateLanguages.indexOf(Prefs.translateMyLang(this))
        val next = translateLanguages[(idx + 1).mod(translateLanguages.size)]
        Prefs.setTranslateMyLang(this, next)
        Toast.makeText(this, next, Toast.LENGTH_SHORT).show()
    }

    private fun toggleTranslateEnabled() {
        val next = !Prefs.translateEnabled(this)
        Prefs.setTranslateEnabled(this, next)
        Toast.makeText(
            this,
            getString(if (next) R.string.keyboard_translate_on else R.string.keyboard_translate_off)
                .substringBefore(" ("),
            Toast.LENGTH_SHORT
        ).show()
    }

    private val translateLanguages = listOf("বাংলা", "English", "हिन्दी", "اردو", "العربية")

    private fun cycleTranslateLanguage() {
        val idx = translateLanguages.indexOf(Prefs.translateTargetLang(this))
        val next = translateLanguages[(idx + 1).mod(translateLanguages.size)]
        Prefs.setTranslateTargetLang(this, next)
        Toast.makeText(this, next, Toast.LENGTH_SHORT).show()
    }

    /** The keyboard's "T↔" key — translates whatever's currently in the
     * focused field, in place, to Prefs.translateTargetLang. On-demand
     * (tap-to-translate), not per-keystroke, so it never fights the
     * user mid-sentence and never spends tokens on unfinished text. */
    private fun translateCurrentField() {
        if (!Prefs.translateEnabled(this)) {
            Toast.makeText(this, R.string.keyboard_translate_disabled_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        val fullText = (before + after).trim()
        if (fullText.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_translate_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val targetLang = Prefs.translateTargetLang(this)
        val baseUrl = getString(R.string.space_base_url)
        val body = JSONObject().apply {
            put("text", fullText)
            put("target_lang", targetLang)
        }.toString()

        setStatus(getString(R.string.keyboard_translate_thinking))
        var finalText: String? = null
        translateJob?.cancel()
        translateJob = serviceScope.launch {
            val result = ApiClient.streamAuthed(this@LenspilotInputMethodService, baseUrl, "/api/translate", body) { event ->
                when (event.optString("type")) {
                    "done" -> finalText = event.optString("translated_text")
                    "error" -> Log.w("LenspilotKeyboard", "translate error: ${event.optString("error")}")
                }
            }
            setStatus(null)
            if (result.isFailure || finalText.isNullOrBlank()) {
                Toast.makeText(this@LenspilotInputMethodService, R.string.keyboard_translate_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Replace the field's full contents with the translation —
            // delete everything on both sides of the cursor, then commit.
            ic.deleteSurroundingText(before.length, after.length)
            ic.commitText(finalText, 1)
        }
    }

    // ------------------------------------------------------------------
    // AI auto-typing — fully automatic, gated on an active workflow and
    // off entirely for sensitive fields/apps.
    // ------------------------------------------------------------------

    private fun fieldIsGuarded(info: EditorInfo?): Boolean {
        val pkg = info?.packageName
        return SensitiveFieldGuard.isSensitiveField(info) || SensitiveFieldGuard.isSensitivePackage(pkg)
    }

    private fun updateAutoTypeToggleUi() {
        val on = Prefs.keyboardAutoTypeEnabled(this)
        // ছোট সুইচ: চালু = নীল পিল + সাদা "AI"; বন্ধ = ধূসর পিল + কাটা "AI"
        aiToggleLabel.text = "AI"
        aiToggleLabel.setTextColor(if (on) th().onAccent else th().textMuted)
        aiToggleLabel.paintFlags = if (on) aiToggleLabel.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            else aiToggleLabel.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        aiToggleButton.background = if (on) th().accentBg(this, 13) else th().specialBg(this)
        aiToggleButton.contentDescription = getString(if (on) R.string.keyboard_auto_type_on else R.string.keyboard_auto_type_off)
    }

    private fun toggleAutoTypeEnabled() {
        if (!aiToggleButton.isEnabled) {
            val info = currentInputEditorInfo
            val msg = if (SensitiveFieldGuard.isSensitiveField(info)) {
                R.string.keyboard_ai_password_field_disabled
            } else {
                R.string.keyboard_ai_blocked_app
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        val turningOn = !Prefs.keyboardAutoTypeEnabled(this)
        Prefs.setKeyboardAutoTypeEnabled(this, turningOn)
        updateAutoTypeToggleUi()
        if (turningOn) {
            userTookOverThisField = false
            maybeAutoType(currentInputEditorInfo)
        } else {
            userTakesOver()
        }
    }

    /** Stop button, or turning the AI pill off mid-generation — cancels
     * whatever's in flight and hands the field straight back to the user,
     * with no further auto-typing for this field until it's refocused. */
    private fun userTakesOver() {
        cancelAiActivity()
        userTookOverThisField = true
        setStatus(getString(R.string.keyboard_ai_took_over))
    }

    private fun typeLastAiReply() {
        if (!kbLastReplyButton.isEnabled) return
        val last = Prefs.lastAiReply(this)
        if (last.isNullOrBlank()) {
            Toast.makeText(this, R.string.keyboard_ai_no_last_reply, Toast.LENGTH_SHORT).show()
            return
        }
        typeGeneratedText(last, autoSubmit = false, fieldDescription = "")
    }

    /** Entry point called on every field focus. Auto-types only when
     * ALL of these hold: AI is enabled, the field isn't guarded, the
     * user hasn't just tapped Stop for this exact field, and there's an
     * active workflow to draw context from — with nothing to go on this
     * is just a plain keyboard, on purpose (see class kdoc). */
    private fun maybeAutoType(info: EditorInfo?) {
        if (!Prefs.keyboardAutoTypeEnabled(this)) return
        if (fieldIsGuarded(info)) return
        if (userTookOverThisField) return
        // Lenspilot-এর নিজের ঘর (ওভারলে চ্যাট বক্স, "আমার তথ্য" ফর্ম, ব্রাউজারের ঠিকানা-বার) — এখানে AI লিখলে
        // ইউজারের নিজের মেসেজ/তথ্যের ঘরে গাইডের লক্ষ্য ঢুকে যেত
        if (info?.packageName == packageName) return
        if (!WorkflowContext.active || WorkflowContext.goal.isBlank()) return
        // ঘরে আগে থেকেই লেখা থাকলে (ইউজার নিজে লিখেছে / আগেরবার বসেছে) অটো-টাইপ নয় — নইলে "tiktoktiktok"
        if (fieldHasText()) return
        generateAndType(info, clarifyAnswerVersion = null)
    }

    private fun fieldHasText(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        return !before.isNullOrEmpty() || !after.isNullOrEmpty()
    }

    private fun fieldKey(info: EditorInfo?): String =
        "${info?.packageName}|${info?.fieldId}|${info?.fieldName}|${info?.hintText}"

    /** ঘরের ধরন — সার্ভার ফোন/ইমেইল/সংখ্যার ঘরে উপযোগী ফরম্যাটে লেখে; পাসওয়ার্ড-ধরনে কিছু লেখে না। */
    private fun inputKind(info: EditorInfo?): String {
        val t = info?.inputType ?: return "text"
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER ->
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) "password" else "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
                InputType.TYPE_TEXT_VARIATION_URI -> "url"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "name"
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "password"
                else -> "text"
            }
            else -> "text"
        }
    }

    private fun isMultiline(info: EditorInfo?): Boolean =
        ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    private fun generateAndType(info: EditorInfo?, clarifyAnswerVersion: Int?) {
        val baseUrl = getString(R.string.space_base_url)
        val fieldHint = info?.hintText?.toString().orEmpty()
        val fieldLabel = info?.label?.toString().orEmpty()
        val appPackage = info?.packageName.orEmpty()
        val workflowGoal = WorkflowContext.goal
        val token = fieldToken
        val key = currentFieldKey
        val fieldDescription = (fieldHint.ifBlank { fieldLabel }).take(60)

        setStatus(getString(R.string.keyboard_ai_thinking))
        kbStopButton.visibility = View.VISIBLE
        // গাইড এখন এই কিবোর্ডের কাজ শেষ হওয়া পর্যন্ত স্ক্রিন-বদলের অটো-কল থামিয়ে রাখবে
        WorkflowContext.setKeyboardBusy(true)

        val sid = WorkflowContext.sessionId
        val history = if (sid.isNotBlank()) ContextWindowStore.recentForPrompt(this, sid, 6) else emptyList()
        val body = JSONObject().apply {
            if (fieldHint.isNotBlank()) put("field_hint", fieldHint)
            if (fieldLabel.isNotBlank()) put("field_label", fieldLabel)
            if (appPackage.isNotBlank()) put("app_package", appPackage)
            if (workflowGoal.isNotBlank()) put("workflow_goal", workflowGoal)
            WorkflowContext.guideSay.takeIf { it.isNotBlank() }?.let { put("guide_say", it) }
            WorkflowContext.guideNote.takeIf { it.isNotBlank() }?.let { put("guide_note", it) }
            WorkflowContext.lastUserAnswer.takeIf { it.isNotBlank() }?.let { put("last_user_answer", it) }
            if (history.isNotEmpty()) put("context_history", JSONArray(history))
            put("input_kind", inputKind(info))
            put("multiline", isMultiline(info))
            put("is_search", isSearchAction(info))
            Prefs.userInfoJsonArrayOrNull(this@LenspilotInputMethodService)?.let { put("user_info", it) }
        }.toString()

        var clarifyQuestion: String? = null
        var finalText: String? = null
        generateJob?.cancel()
        generateJob = serviceScope.launch {
            val result = ApiClient.streamAuthed(this@LenspilotInputMethodService, baseUrl, "/api/keyboard/generate", body) { event ->
                when (event.optString("type")) {
                    "done" -> finalText = event.optString("text")
                    "clarify" -> clarifyQuestion = event.optString("question")
                    "error" -> Log.w("LenspilotKeyboard", "generate error: ${event.optString("error")}")
                }
            }
            // ইনপুট বদলে গেছে (অন্য ঘর/কিবোর্ড লুকানো) — পুরনো উত্তর আর বসবে না
            if (token != fieldToken) return@launch
            if (result.isFailure) {
                kbStopButton.visibility = View.GONE
                setStatus(null)
                WorkflowContext.setKeyboardBusy(false)
                Toast.makeText(this@LenspilotInputMethodService, R.string.keyboard_ai_error_generic, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val question = clarifyQuestion
            val text = finalText?.trim()
            when {
                !question.isNullOrBlank() -> handleClarifyQuestion(question)
                !text.isNullOrBlank() -> typeGeneratedText(text, autoSubmit = isSearchAction(info), fieldDescription = fieldDescription, fieldKey = key)
                else -> {
                    kbStopButton.visibility = View.GONE
                    setStatus(null)
                    WorkflowContext.setKeyboardBusy(false)
                }
            }
        }
    }

    /** IME_ACTION_SEARCH fields (e.g. Play Store's search bar) get
     * auto-submitted right after typing, matching "লিখে সার্চ করে দিবে" —
     * other actions (send/done/next on arbitrary forms) are left for the
     * user to confirm, since auto-submitting those could send something
     * before the rest of a multi-field form is ready. */
    private fun isSearchAction(info: EditorInfo?): Boolean {
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        return action == EditorInfo.IME_ACTION_SEARCH
    }

    /** AI wasn't confident enough to type anything — show the question
     * and point the user at the control bar's own mic (no second mic
     * here), then quietly watch for the user's answer (WorkflowContext
     * bumps its version the moment the control bar processes a voice
     * reply) and retry automatically once it does. */
    private fun handleClarifyQuestion(question: String) {
        kbStopButton.visibility = View.GONE
        // প্রশ্নের উত্তরের অপেক্ষায় গাইড আটকে থাকবে না — ইউজার মাইক/ট্রিগার দিয়ে উত্তর দেবে, গাইড সেটা নিজে ধরে
        WorkflowContext.setKeyboardBusy(false)
        clarifyAttempts++
        if (clarifyAttempts > 2) {
            // একই ঘরে বারবার প্রশ্ন নয় — ইউজার নিজে লিখুক
            setStatus(getString(R.string.keyboard_ai_took_over))
            return
        }
        setStatus(getString(R.string.keyboard_ai_clarify_prefix, question))

        val versionAtRequest = WorkflowContext.version
        clarifyWatchJob?.cancel()
        clarifyWatchJob = serviceScope.launch {
            // Give up after ~2 minutes of waiting rather than polling
            // forever if the user never answers or switches fields.
            val deadline = 120_000L
            var waited = 0L
            while (isActive && waited < deadline) {
                delay(1500)
                waited += 1500
                if (WorkflowContext.version != versionAtRequest) {
                    // গাইড শেষ/বন্ধ হয়ে version বদলালে আর লেখা নয়
                    if (!userTookOverThisField && WorkflowContext.active && !fieldHasText()) {
                        setStatus(getString(R.string.keyboard_ai_thinking))
                        generateAndType(currentInputEditorInfo, clarifyAnswerVersion = WorkflowContext.version)
                    }
                    return@launch
                }
            }
        }
    }

    private fun typeGeneratedText(
        text: String, autoSubmit: Boolean, fieldDescription: String = "", fieldKey: String = currentFieldKey
    ) {
        if (currentInputConnection == null) {
            kbStopButton.visibility = View.GONE
            setStatus(null)
            WorkflowContext.setKeyboardBusy(false)
            return
        }
        Prefs.setLastAiReply(this, text)
        typingJob?.cancel()
        WorkflowContext.setKeyboardBusy(true)
        typingJob = KeyboardTypingAnimator.start(serviceScope, { currentInputConnection }, text) { completed ->
            kbStopButton.visibility = View.GONE
            setStatus(null)
            WorkflowContext.setKeyboardBusy(false)
            if (completed) {
                lastTypedFieldKey = fieldKey
                if (autoSubmit) submitEditorAction()
                // গাইডকে জানানো: কী লেখা হলো (গাইড ইতিহাসে টুকে পরের ধাপ চাইবে)
                WorkflowContext.notifyKeyboardTyped(text, fieldDescription, autoSubmit)
            }
        }
    }

    private fun cancelAiActivity() {
        generateJob?.cancel()
        typingJob?.cancel()
        clarifyWatchJob?.cancel()
        translateJob?.cancel()
        kbStopButton.visibility = View.GONE
        setStatus(null)
        WorkflowContext.setKeyboardBusy(false)
    }

    private fun setStatus(text: String?) {
        kbStatusText.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        kbStatusText.text = text.orEmpty()
    }
}
