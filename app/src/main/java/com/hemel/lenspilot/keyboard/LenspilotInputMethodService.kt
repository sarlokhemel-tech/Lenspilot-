package com.hemel.lenspilot.keyboard

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import com.hemel.lenspilot.workflow.WorkflowContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private lateinit var kbToolsButton: TextView
    private lateinit var suggestionBar: LinearLayout
    private lateinit var kbSuggestionMenuButton: TextView
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
        "সহড়ঢ়য়ৎঋ\u0981\u09CD"   // স হ ড় ঢ় য় ৎ ঋ ঁ ্  (9 keys, backspace appended after)
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
    private val banglaConsonantChars =
        "কখগঘঙচছজঝঞটঠডঢণতথদধনপফবভমযরলশষসহড়ঢ়য়ৎ"

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        keysContainer = view.findViewById(R.id.keysContainer)
        aiToggleButton = view.findViewById(R.id.aiToggleButton)
        aiToggleLabel = view.findViewById(R.id.aiToggleLabel)
        kbStopButton = view.findViewById(R.id.kbStopButton)
        kbLastReplyButton = view.findViewById(R.id.kbLastReplyButton)
        kbStatusText = view.findViewById(R.id.kbStatusText)
        kbBanglaToggleButton = view.findViewById(R.id.kbBanglaToggleButton)
        kbToolsButton = view.findViewById(R.id.kbToolsButton)
        suggestionBar = view.findViewById(R.id.suggestionBar)
        kbSuggestionMenuButton = view.findViewById(R.id.kbSuggestionMenuButton)
        kbSuggestion1 = view.findViewById(R.id.kbSuggestion1)
        kbSuggestion2 = view.findViewById(R.id.kbSuggestion2)
        kbSuggestion3 = view.findViewById(R.id.kbSuggestion3)
        kbSuggestionMenuButton.setOnClickListener { showToolsMenu(it) }
        kbSuggestion1.setOnClickListener { applySuggestion(kbSuggestion1.text.toString()) }
        kbSuggestion2.setOnClickListener { applySuggestion(kbSuggestion2.text.toString()) }
        kbSuggestion3.setOnClickListener { applySuggestion(kbSuggestion3.text.toString()) }

        view.findViewById<ImageButton>(R.id.kbGlobeButton).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        aiToggleButton.setOnClickListener { toggleAutoTypeEnabled() }
        kbStopButton.setOnClickListener { userTakesOver() }
        kbLastReplyButton.setOnClickListener { typeLastAiReply() }

        banglaMode = Prefs.keyboardBanglaMode(this)
        updateBanglaToggleUi()
        kbBanglaToggleButton.setOnClickListener { toggleBanglaMode() }
        kbToolsButton.setOnClickListener { showToolsMenu(it) }

        buildKeyRows()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Reset any leftover state from a previous field so a stale
        // in-flight request never types into the wrong place.
        cancelAiActivity()
        userTookOverThisField = false
        clearSuggestions()

        val guarded = fieldIsGuarded(info)
        aiToggleButton.isEnabled = !guarded
        aiToggleButton.alpha = if (guarded) 0.4f else 1f
        kbLastReplyButton.isEnabled = !guarded
        kbLastReplyButton.alpha = if (guarded) 0.4f else 1f
        updateAutoTypeToggleUi()

        if (guarded) {
            setStatus(null)
            return
        }
        maybeAutoType(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelAiActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun buildKeyRows() {
        keysContainer.removeAllViews()
        if (banglaMode && !symbolsMode) {
            // Unified single-page Bangla layout — 4 plain rows + 1 row
            // that also carries the backspace key (matches the reference
            // keyboard's 5x10 grid, no shift/page-toggle key anywhere).
            // Row 0 (independent vowels) is the only one wired to the
            // smart matra conversion (see commitBanglaVowel).
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
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(4) }
        }
        chars.forEach { c ->
            row.addView(makeKeyView(charLabel(c), weight = 1f, special = false) {
                if (vowelRow) commitBanglaVowel(c) else commitChar(c)
            })
        }
        return row
    }

    /** Bangla's row 5 — same key-row visuals as buildCharRow, but the
     * backspace key is appended right after the last character (matches
     * the reference keyboard putting backspace at the end of this exact
     * row instead of a dedicated 6th row). */
    private fun buildBanglaLastRow(): LinearLayout {
        val row = buildCharRow(banglaRows.last())
        row.addView(makeIconKeyView(R.drawable.ic_keyboard_backspace, weight = 1.5f, special = true) {
            deleteOneChar()
        }.apply { attachRepeatOnHold { deleteOneChar() } })
        return row
    }

    private fun buildSpecialRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
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
                if (capsOn) shiftKey.setColorFilter(getColor(R.color.brand_gradient_start))
                row.addView(shiftKey)
            }
        }
        currentRows().last().forEach { c ->
            row.addView(makeKeyView(charLabel(c), weight = 1f, special = false) { commitChar(c) })
        }
        row.addView(makeIconKeyView(R.drawable.ic_keyboard_backspace, weight = 1.5f, special = true) {
            deleteOneChar()
        }.apply { attachRepeatOnHold { deleteOneChar() } })
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
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
            currentInputConnection?.commitText(" ", 1)
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
        return row
    }

    private fun charLabel(c: Char): String = if (capsOn) c.uppercaseChar().toString() else c.toString()

    private fun commitChar(c: Char) {
        val out = if (capsOn) c.uppercaseChar() else c
        currentInputConnection?.commitText(out.toString(), 1)
        updateSuggestions()
    }

    /** Row-1 vowel keys in Bangla mode — commits the dependent matra
     * (কার) sign instead of the independent letter whenever the previous
     * character is a consonant, exactly like the reference keyboard's
     * phonetic behaviour (ক + ই → কি, not কই). অ is the inherent vowel:
     * right after a bare consonant nothing needs to be typed at all. */
    private fun commitBanglaVowel(vowel: Char) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString()?.lastOrNull()
        val afterConsonant = before != null && before in banglaConsonantChars
        if (vowel == 'অ') {
            if (!afterConsonant) ic.commitText("অ", 1)
            updateSuggestions()
            return
        }
        val matra = banglaMatraMap[vowel]
        if (matra != null && afterConsonant) {
            ic.commitText(matra, 1)
        } else {
            ic.commitText(vowel.toString(), 1)
        }
        updateSuggestions()
    }

    private fun deleteOneChar() {
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
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val idx = before.indexOfLast { it == ' ' || it == '\n' }
        return before.substring(idx + 1)
    }

    private fun updateSuggestions() {
        if (!banglaMode || symbolsMode) {
            suggestionBar.visibility = View.GONE
            return
        }
        val prefix = currentWordBeforeCursor()
        if (prefix.isBlank()) {
            suggestionBar.visibility = View.GONE
            return
        }
        val matches = CommonBanglaWords.WORDS.filter { it.startsWith(prefix) && it != prefix }.take(3)
        if (matches.isEmpty()) {
            suggestionBar.visibility = View.GONE
            return
        }
        val slots = listOf(kbSuggestion1, kbSuggestion2, kbSuggestion3)
        slots.forEachIndexed { i, view ->
            view.text = matches.getOrNull(i).orEmpty()
            view.visibility = if (i < matches.size) View.VISIBLE else View.GONE
        }
        suggestionBar.visibility = View.VISIBLE
    }

    private fun clearSuggestions() {
        suggestionBar.visibility = View.GONE
    }

    /** Replaces the in-progress word (text after the last space/newline)
     * with the tapped suggestion, then adds a trailing space. */
    private fun applySuggestion(word: String) {
        if (word.isBlank()) return
        val ic = currentInputConnection ?: return
        val prefix = currentWordBeforeCursor()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText("$word ", 1)
        clearSuggestions()
    }

    private fun submitEditorAction() {
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
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun makeKeyView(label: String, weight: Float, special: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = sizeScaleTextSize(16f)
            setTextColor(ContextCompat.getColor(this@LenspilotInputMethodService, R.color.text_primary_light))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            setBackgroundResource(if (special) R.drawable.bg_keyboard_key_special else R.drawable.bg_keyboard_key)
            isClickable = true
            isFocusable = false
            setOnClickListener { onClick() }
        }
    }

    private fun makeIconKeyView(iconRes: Int, weight: Float, special: Boolean, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            setBackgroundResource(if (special) R.drawable.bg_keyboard_key_special else R.drawable.bg_keyboard_key)
            setOnClickListener { onClick() }
        }
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

    /** ⚙ button — a flat action menu (no nested submenus) covering the
     * three settings from the update request: key/row size, the
     * translate feature's on/off switch, and its target language. Each
     * tap performs its action immediately and closes the menu; reopen
     * the menu to change something else. */
    private fun showToolsMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val scale = Prefs.keyboardSizeScale(this)
        val sizeLabel = when {
            scale <= 0.9f -> getString(R.string.keyboard_size_small)
            scale >= 1.1f -> getString(R.string.keyboard_size_large)
            else -> getString(R.string.keyboard_size_medium)
        }
        menu.menu.add(0, 1, 0, sizeLabel)
        menu.menu.add(0, 2, 1,
            if (Prefs.translateEnabled(this)) getString(R.string.keyboard_translate_on)
            else getString(R.string.keyboard_translate_off)
        )
        menu.menu.add(0, 3, 2,
            getString(R.string.keyboard_translate_lang_prefix, Prefs.translateTargetLang(this))
        )
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> cycleKeyboardSize()
                2 -> toggleTranslateEnabled()
                3 -> cycleTranslateLanguage()
            }
            true
        }
        menu.show()
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
        aiToggleLabel.setText(if (on) R.string.keyboard_auto_type_on else R.string.keyboard_auto_type_off)
        aiToggleButton.setBackgroundResource(
            if (on) R.drawable.bg_button_primary else R.drawable.bg_keyboard_key_special
        )
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
        typeGeneratedText(last, autoSubmit = false)
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
        if (!WorkflowContext.active || WorkflowContext.goal.isBlank()) return
        generateAndType(info, clarifyAnswerVersion = null)
    }

    private fun generateAndType(info: EditorInfo?, clarifyAnswerVersion: Int?) {
        val baseUrl = getString(R.string.space_base_url)
        val fieldHint = info?.hintText?.toString().orEmpty()
        val appPackage = info?.packageName.orEmpty()
        val workflowGoal = WorkflowContext.goal

        setStatus(getString(R.string.keyboard_ai_thinking))
        kbStopButton.visibility = View.VISIBLE

        val body = JSONObject().apply {
            if (fieldHint.isNotBlank()) put("field_hint", fieldHint)
            if (appPackage.isNotBlank()) put("app_package", appPackage)
            if (workflowGoal.isNotBlank()) put("workflow_goal", workflowGoal)
        }.toString()

        var clarifyQuestion: String? = null
        var finalText: String? = null
        generateJob = serviceScope.launch {
            val result = ApiClient.streamAuthed(this@LenspilotInputMethodService, baseUrl, "/api/keyboard/generate", body) { event ->
                when (event.optString("type")) {
                    "done" -> finalText = event.optString("text")
                    "clarify" -> clarifyQuestion = event.optString("question")
                    "error" -> Log.w("LenspilotKeyboard", "generate error: ${event.optString("error")}")
                }
            }
            if (result.isFailure) {
                kbStopButton.visibility = View.GONE
                setStatus(null)
                Toast.makeText(this@LenspilotInputMethodService, R.string.keyboard_ai_error_generic, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val question = clarifyQuestion
            val text = finalText?.trim()
            when {
                !question.isNullOrBlank() -> handleClarifyQuestion(question)
                !text.isNullOrBlank() -> typeGeneratedText(text, autoSubmit = isSearchAction(info))
                else -> {
                    kbStopButton.visibility = View.GONE
                    setStatus(null)
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
                    if (!userTookOverThisField) {
                        setStatus(getString(R.string.keyboard_ai_thinking))
                        generateAndType(currentInputEditorInfo, clarifyAnswerVersion = WorkflowContext.version)
                    }
                    return@launch
                }
            }
        }
    }

    private fun typeGeneratedText(text: String, autoSubmit: Boolean) {
        val ic: InputConnection = currentInputConnection ?: run {
            kbStopButton.visibility = View.GONE
            setStatus(null)
            return
        }
        Prefs.setLastAiReply(this, text)
        typingJob?.cancel()
        typingJob = KeyboardTypingAnimator.start(serviceScope, ic, text) {
            kbStopButton.visibility = View.GONE
            setStatus(null)
            if (autoSubmit) submitEditorAction()
        }
    }

    private fun cancelAiActivity() {
        generateJob?.cancel()
        typingJob?.cancel()
        clarifyWatchJob?.cancel()
        translateJob?.cancel()
        kbStopButton.visibility = View.GONE
        setStatus(null)
    }

    private fun setStatus(text: String?) {
        kbStatusText.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        kbStatusText.text = text.orEmpty()
    }
}
