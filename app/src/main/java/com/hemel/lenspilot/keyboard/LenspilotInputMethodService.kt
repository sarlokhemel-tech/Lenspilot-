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

    private lateinit var keysContainer: LinearLayout
    private lateinit var aiToggleButton: View
    private lateinit var aiToggleLabel: TextView
    private lateinit var kbStopButton: ImageButton
    private lateinit var kbLastReplyButton: ImageButton
    private lateinit var kbStatusText: TextView

    /** True once the user has tapped Stop for the field currently
     * focused — prevents immediately re-auto-typing into the same field
     * after they've explicitly taken over; resets on the next field. */
    private var userTookOverThisField = false

    private val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val symbolRows = listOf("1234567890", "@#\$_&-+()", "*\"':;!?/")

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        keysContainer = view.findViewById(R.id.keysContainer)
        aiToggleButton = view.findViewById(R.id.aiToggleButton)
        aiToggleLabel = view.findViewById(R.id.aiToggleLabel)
        kbStopButton = view.findViewById(R.id.kbStopButton)
        kbLastReplyButton = view.findViewById(R.id.kbLastReplyButton)
        kbStatusText = view.findViewById(R.id.kbStatusText)

        view.findViewById<ImageButton>(R.id.kbGlobeButton).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        aiToggleButton.setOnClickListener { toggleAutoTypeEnabled() }
        kbStopButton.setOnClickListener { userTakesOver() }
        kbLastReplyButton.setOnClickListener { typeLastAiReply() }

        buildKeyRows()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Reset any leftover state from a previous field so a stale
        // in-flight request never types into the wrong place.
        cancelAiActivity()
        userTookOverThisField = false

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

    private fun buildKeyRows() {
        keysContainer.removeAllViews()
        val rows = if (symbolsMode) symbolRows else letterRows
        rows.forEach { row -> keysContainer.addView(buildCharRow(row)) }
        keysContainer.addView(buildSpecialRow())
        keysContainer.addView(buildBottomRow())
    }

    private fun buildCharRow(chars: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(4) }
        }
        chars.forEach { c ->
            row.addView(makeKeyView(charLabel(c), weight = 1f, special = false) {
                commitChar(c)
            })
        }
        return row
    }

    private fun buildSpecialRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(4) }
        }
        if (!symbolsMode) {
            val shiftKey = makeIconKeyView(R.drawable.ic_keyboard_shift, weight = 1.5f, special = true) {
                capsOn = !capsOn
                buildKeyRows()
            }
            if (capsOn) shiftKey.setColorFilter(getColor(R.color.brand_gradient_start))
            row.addView(shiftKey)
        } else {
            row.addView(makeKeyView("ABC", weight = 1.5f, special = true) {}.apply {
                // handled by the bottom-row toggle button too; keep this
                // slot visually balanced with a no-op spacer-ish key.
                setOnClickListener { symbolsMode = false; buildKeyRows() }
            })
        }
        (if (symbolsMode) symbolRows else letterRows)[if (symbolsMode) 1 else 2].forEach { c ->
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
        row.addView(makeKeyView(if (symbolsMode) "ABC" else "?123", weight = 1.5f, special = true) {
            symbolsMode = !symbolsMode
            buildKeyRows()
        })
        row.addView(makeKeyView(",", weight = 1f, special = false) { commitChar(',') })
        row.addView(makeKeyView(getString(R.string.keyboard_space_key), weight = 4f, special = false) {
            currentInputConnection?.commitText(" ", 1)
        })
        row.addView(makeKeyView(".", weight = 1f, special = false) { commitChar('.') })
        row.addView(makeKeyView(getString(R.string.keyboard_enter_key), weight = 1.5f, special = true) {
            submitEditorAction()
        })
        return row
    }

    private fun charLabel(c: Char): String = if (capsOn) c.uppercaseChar().toString() else c.toString()

    private fun commitChar(c: Char) {
        val out = if (capsOn) c.uppercaseChar() else c
        currentInputConnection?.commitText(out.toString(), 1)
    }

    private fun deleteOneChar() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
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
    }

    private fun makeKeyView(label: String, weight: Float, special: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        kbStopButton.visibility = View.GONE
        setStatus(null)
    }

    private fun setStatus(text: String?) {
        kbStatusText.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        kbStatusText.text = text.orEmpty()
    }
}
