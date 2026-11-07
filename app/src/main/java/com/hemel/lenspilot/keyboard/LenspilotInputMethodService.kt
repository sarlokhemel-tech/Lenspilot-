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
        toolIcons = view.findViewById(R.id.toolIcons)
        suggestionBar = view.findViewById(R.id.suggestionBar)
        kbRootView = view
        kbSuggestion1 = view.findViewById(R.id.kbSuggestion1)
        kbSuggestion2 = view.findViewById(R.id.kbSuggestion2)
        kbSuggestion3 = view.findViewById(R.id.kbSuggestion3)
        kbMainButton.setOnClickListener { showToolsMenu(it) }
        kbChatButton.setOnClickListener { toggleChatPanel() }
        kbThemeButton.setOnClickListener { showThemeMenu(it) }
        // v122: গ্লাস-কার্ড AI চ্যাট প্যানেল — কীগুলোর ঠিক ওপরে বসে
        chatPanel = KeyboardChatPanel(this, serviceScope, { th() }, { text -> insertIntoField(text) }, { closeChatPanel() })
        (keysContainer.parent as? LinearLayout)?.let { root ->
            root.addView(chatPanel!!.view, root.indexOfChild(keysContainer))
        }
        kbTranslateButton.setOnClickListener { toggleTranslateMode() }
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
        tbSpecs.add(TbSpec(kbMainButton, "tb_main", 34, 34, 16f))
        tbSpecs.add(TbSpec(kbGlobeButton, "tb_globe", 34, 34, 0f))
        tbSpecs.add(TbSpec(kbBanglaToggleButton, "tb_lang", 34, 34, 12f))
        tbSpecs.add(TbSpec(aiToggleButton, "tb_ai", 44, 26, 11f))
        tbSpecs.add(TbSpec(kbTranslateButton, "tb_translate", 34, 34, 13f))
        tbSpecs.add(TbSpec(kbChatButton, "tb_chat", 34, 34, 15f))
        tbSpecs.add(TbSpec(kbThemeButton, "tb_theme", 34, 34, 15f))
        tbSpecs.add(TbSpec(kbLastReplyButton, "tb_last", 34, 34, 0f))
        tbSpecs.add(TbSpec(kbStopButton, "tb_stop", 34, 34, 0f))
        chatPanel?.let {
            tbSpecs.add(TbSpec(it.sendBtn, "chat_send", 38, 38, 16f))
            tbSpecs.add(TbSpec(it.closeBtn, "chat_close", 38, 38, 15f))
        }
        applyToolbarCustom()
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> reportKeyboardTop(v); resizeOverlay?.invalidate() }
        TranslateGlassOverlay.listener = { updateTranslateButtonUi() }
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
        cancelAiActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
        TranslateGlassOverlay.listener = null
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
        listOf<View>(kbMainButton, kbGlobeButton, kbBanglaToggleButton, kbLastReplyButton, kbStopButton, kbChatButton, kbThemeButton)
            .forEach { it.background = t.specialBg(this) }
        listOf(kbMainButton, kbBanglaToggleButton, kbChatButton, kbThemeButton).forEach { it.setTextColor(t.text) }
        listOf(kbGlobeButton, kbLastReplyButton, kbStopButton).forEach { it.imageTintList = ColorStateList.valueOf(t.text) }
        suggestionBar.background = t.pillBg(this, 18)
        listOf(kbSuggestion1, kbSuggestion2, kbSuggestion3).forEach { it.setTextColor(t.text) }
        kbStatusText.setTextColor(t.textMuted)
        updateAutoTypeToggleUi()
        updateTranslateButtonUi()
        chatPanel?.applyTheme()
        buildKeyRows()
    }

    private fun showThemeMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val cur = KeyboardTheme.current(this).id
        KeyboardTheme.ALL.forEachIndexed { i, t ->
            menu.menu.add(0, 100 + i, i, (if (t.id == cur) "✓ " else "    ") + t.label)
        }
        menu.setOnMenuItemClickListener { item ->
            val t = KeyboardTheme.ALL.getOrNull(item.itemId - 100)
            if (t != null) {
                Prefs.setKeyboardTheme(this, t.id)
                applyTheme()
            }
            true
        }
        menu.show()
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
            keysContainer.addView(buildCharRow(banglaRows[0], vowelRow = true))
            banglaRows.subList(1, banglaRows.size - 1).forEach { row ->
                keysContainer.addView(buildCharRow(row))
            }
            keysContainer.addView(buildBanglaLastRow())
            keysContainer.addView(buildBottomRow())
            updateSuggestions()
            return
        }
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
        chars.forEach { c ->
            // Gboard-এর মতো: ব্যঞ্জনের পরে ওপরের সারি কারে বদলে যায় (ত তা তি তী তু তূ তে তৈ তো তৌ) — চাপলে শুধু কার বসে
            val label = if (vowelRow && shownMatraBase.isNotEmpty()) shownMatraBase + (banglaMatraMap[c] ?: "") else charLabel(c)
            row.addView(makeKeyView(label, weight = 1f, special = false) {
                if (vowelRow) commitBanglaVowel(c) else commitChar(c)
            })
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
        if (!banglaMode || symbolsMode || !::keysContainer.isInitialized || keysContainer.childCount < 6) return
        val base = lastConsonantCluster()
        if (base == shownMatraBase) return
        shownMatraBase = base
        keysContainer.removeViewAt(0)
        keysContainer.addView(buildCharRow(banglaRows[0], vowelRow = true), 0)
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
                    capsOn = !capsOn
                    buildKeyRows()
                }
                if (capsOn) shiftKey.imageTintList = ColorStateList.valueOf(th().accent)
                row.addView(shiftKey)
            }
        }
        currentRows().last().forEach { c ->
            row.addView(makeKeyView(charLabel(c), weight = 1f, special = false) { commitChar(c) })
        }
        row.addView(makeIconKeyView(R.drawable.ic_keyboard_backspace, weight = 1.5f, special = true) {
            deleteOneChar()
        }.apply { attachRepeatOnHold { deleteOneChar() } })
        fitRow(row)
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, keyH()
            ).apply { topMargin = dp(4) }
        }
        row.addView(makeKeyView(if (symbolsMode) (if (banglaMode) "বাংলা" else "ABC") else "?123", weight = 1.2f, special = true) {
            symbolsMode = !symbolsMode
            buildKeyRows()
        })
        row.addView(makeKeyView("‹", weight = 0.7f, special = true) {
            moveCursor(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        })
        row.addView(makeKeyView(
            if (symbolsMode) getString(R.string.keyboard_space_key) else (if (banglaMode) "বাংলা" else "English"),
            weight = 2.6f, special = false
        ) {
            emit(" ")
            clearSuggestions()
        })
        row.addView(makeKeyView("›", weight = 0.7f, special = true) {
            moveCursor(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        })
        row.addView(makeKeyView(getString(R.string.keyboard_translate_key), weight = 1.1f, special = true) {
            translateCurrentField()
        })
        row.addView(makeKeyView(getString(R.string.keyboard_enter_key), weight = 1.3f, special = true) {
            submitEditorAction()
        })
        fitRow(row)
        return row
    }

    private fun charLabel(c: Char): String = if (capsOn) c.uppercaseChar().toString() else c.toString()

    private fun commitChar(c: Char) {
        val out = if (capsOn) c.uppercaseChar() else c
        emit(out.toString())
        updateSuggestions()
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
        if (!::toolIcons.isInitialized) return
        refreshBanglaDynamicRows()
        val typing = !customizeMode && fieldHasTypedText()
        toolIcons.visibility = if (typing) View.GONE else View.VISIBLE
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
        suggestionBar.visibility = if (typing && matches.isNotEmpty()) View.VISIBLE else View.GONE
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
            tag = KeyTag(id, weight, 16f)
            applyKeyCustom(this)
            isClickable = true
            isFocusable = false
            if (customizeMode) attachSelector(this, id) else setOnClickListener { onClick() }
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
            if (customizeMode) attachSelector(this, id) else setOnClickListener { onClick() }
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
                val p = ((7 * d) / k.t).toInt().coerceIn((1 * d).toInt(), (16 * d).toInt())
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
        val repeatRunnable = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 45)
            }
        }
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeating = true
                    handler.postDelayed(repeatRunnable, 350)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (repeating) handler.removeCallbacks(repeatRunnable)
                    repeating = false
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

    /** ⊞ মেইন আইকন — সব সেটিং এক জায়গায় (flat মেনু; প্রতিটা চাপে কাজ হয়ে মেনু বন্ধ, আবার খুলে অন্যটা)। */
    private fun showToolsMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val scale = Prefs.keyboardSizeScale(this)
        val sizeLabel = when {
            scale <= 0.9f -> getString(R.string.keyboard_size_small)
            scale >= 1.1f -> getString(R.string.keyboard_size_large)
            else -> getString(R.string.keyboard_size_medium)
        }
        var order = 0
        if (customizeMode) menu.menu.add(0, 8, order++, getString(R.string.kb_menu_resize_done))
        menu.menu.add(0, 1, order++, getString(if (Prefs.keyboardAutoTypeEnabled(this)) R.string.kb_menu_ai_on else R.string.kb_menu_ai_off))
        menu.menu.add(0, 2, order++, getString(if (banglaMode) R.string.kb_menu_layout_bn else R.string.kb_menu_layout_en))
        menu.menu.add(0, 3, order++, getString(if (TranslateGlassOverlay.isShowing) R.string.kb_menu_translate_on else R.string.kb_menu_translate_off))
        menu.menu.add(0, 4, order++, getString(R.string.kb_menu_my_lang, Prefs.translateMyLang(this)))
        menu.menu.add(0, 5, order++, getString(R.string.kb_menu_out_lang, Prefs.translateTargetLang(this)))
        menu.menu.add(0, 6, order++, sizeLabel)
        menu.menu.add(0, 7, order++, getString(R.string.kb_menu_last_reply))
        menu.menu.add(0, 10, order++, getString(if (chatOpen()) R.string.kb_menu_chat_close else R.string.kb_menu_chat_open))
        menu.menu.add(0, 11, order++, getString(R.string.kb_menu_theme, KeyboardTheme.current(this).label))
        if (!customizeMode) menu.menu.add(0, 8, order++, getString(R.string.kb_menu_resize_start))
        menu.menu.add(0, 9, order++, getString(R.string.kb_menu_resize_reset))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleAutoTypeEnabled()
                2 -> toggleBanglaMode()
                3 -> toggleTranslateMode()
                4 -> cycleMyLang()
                5 -> cycleTranslateLanguage()
                6 -> cycleKeyboardSize()
                7 -> typeLastAiReply()
                10 -> toggleChatPanel()
                11 -> showThemeMenu(anchor)
                8 -> setCustomizeMode(!customizeMode)
                9 -> {
                    KeyCustomizer.reset(this)
                    Prefs.resetKeyboardFrame(this)
                    applyKeyboardFrame()
                    applyToolbarCustom()
                    buildKeyRows()
                    Toast.makeText(this, R.string.kb_sizes_reset_toast, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        menu.show()
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
            if (!TranslateGlassOverlay.show(this)) {
                Toast.makeText(this, R.string.tg_need_accessibility, Toast.LENGTH_LONG).show()
            }
        }
        updateTranslateButtonUi()
    }

    private fun cycleMyLang() {
        val idx = translateLanguages.indexOf(Prefs.translateMyLang(this))
        val next = translateLanguages[(idx + 1).mod(translateLanguages.size)]
        Prefs.setTranslateMyLang(this, next)
        Toast.makeText(this, next, Toast.LENGTH_SHORT).show()
    }

    private fun cycleKeyboardSize() {
        val current = Prefs.keyboardSizeScale(this)
        val (next, label) = when {
            current <= 0.9f -> 1.0f to R.string.keyboard_size_medium
            current >= 1.1f -> 0.85f to R.string.keyboard_size_small
            else -> 1.15f to R.string.keyboard_size_large
        }
        Prefs.setKeyboardSizeScale(this, next)
        buildKeyRows()
        Toast.makeText(this, getString(label).substringBefore(" ("), Toast.LENGTH_SHORT).show()
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
