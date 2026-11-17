package com.hemel.lenspilot.tools.agent

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hemel.lenspilot.R
import com.hemel.lenspilot.tools.shell.GitHubLoginDialog
import com.hemel.lenspilot.tools.shell.ShellLine
import com.hemel.lenspilot.tools.shell.ShellSession

/**
 * লাইভ স্ক্রিনের Shell-ট্যাব: কালো টার্মিনাল। AI-র কমান্ড [ShellSession]-এর টাইপিং-অ্যানিমেশনে অক্ষর-অক্ষর করে ইনপুট-বক্সে
 * টাইপ হয় এবং AI চালানোর সময় ইনপুট লক থাকে। ShellSession-এ একটাই লিসেনার — [show]/[hide] দিয়ে বসে/খোলে।
 */
class ShellPane(private val act: AppCompatActivity, private val session: ShellSession) : ShellSession.Listener {

    private companion object {
        const val MAX_CHARS = 120_000
        const val KEEP_CHARS = 90_000
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private val scroll = ScrollView(act)
    private val output = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Color.parseColor("#D4D4D4"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private val progress = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Color.parseColor("#8BE9FD"))
        setPadding(dp(10), 0, dp(10), dp(4))
        visibility = View.GONE
    }
    private val banner = TextView(act).apply {
        text = act.getString(R.string.tools_shell_ai_banner)
        textSize = 12f
        setTextColor(Color.parseColor("#FFD866"))
        setBackgroundColor(Color.parseColor("#1F1F1F"))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = View.GONE
    }
    private val prompt = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Color.parseColor("#5AF78E"))
        setPadding(dp(10), 0, dp(6), 0)
    }
    private val input = EditText(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#777777"))
        setBackgroundColor(Color.TRANSPARENT)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO
    }

    val root: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        scroll.addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(prompt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private var shown = false

    init {
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEND || enter) {
                runCurrent()
                true
            } else false
        }
    }

    /** ট্যাব দেখানোর সময়: লিসেনার বসানো + পুরনো স্ক্রলব্যাক দেখানো। */
    fun show() {
        if (shown) return
        shown = true
        output.text = ""
        val snap = session.attach(this)
        appendSpanned(snap.lines, true)
        prompt.text = snap.prompt
        onProgress(snap.progress)
        if (snap.typing != null) onTyping(snap.typing)
        onState(snap.running, snap.aiWorking)
    }

    fun hide() {
        if (!shown) return
        shown = false
        session.detach(this)
    }

    private fun runCurrent() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (!session.runUser(t)) {
            Toast.makeText(act, R.string.shell_busy, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
    }

    // ------------------------------------------------------------ Listener (main থ্রেড)
    override fun onLines(lines: List<ShellLine>) = appendSpanned(lines, false)

    override fun onClear() { output.text = "" }

    override fun onProgress(text: String?) {
        if (text.isNullOrEmpty()) progress.visibility = View.GONE
        else { progress.text = text; progress.visibility = View.VISIBLE }
    }

    override fun onPrompt(prompt: String) { this.prompt.text = prompt }

    override fun onTyping(text: String?) {
        if (text == null) input.setText("")
        else { input.setText(text); input.setSelection(text.length) }
    }

    override fun onState(running: Boolean, aiWorking: Boolean) {
        val locked = running || aiWorking
        input.isEnabled = !locked
        banner.visibility = if (aiWorking) View.VISIBLE else View.GONE
        input.hint = act.getString(
            when {
                aiWorking -> R.string.shell_hint_ai
                running -> R.string.shell_hint_busy
                else -> R.string.shell_hint
            }
        )
    }

    override fun onLoginRequested() { GitHubLoginDialog.show(act) }

    // ------------------------------------------------------------ রেন্ডার
    private fun colorOf(kind: Int): Int = when (kind) {
        ShellLine.ERR -> Color.parseColor("#FF6B6B")
        ShellLine.PROMPT -> Color.parseColor("#5AF78E")
        ShellLine.INFO -> Color.parseColor("#8BE9FD")
        else -> Color.parseColor("#D4D4D4")
    }

    private fun appendSpanned(lines: List<ShellLine>, forceScroll: Boolean) {
        if (lines.isEmpty()) return
        val sb = SpannableStringBuilder()
        for (l in lines) {
            val start = sb.length
            sb.append(l.text).append('\n')
            sb.setSpan(ForegroundColorSpan(colorOf(l.kind)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        output.append(sb)
        val ed = output.text as? Editable
        if (ed != null && ed.length > MAX_CHARS) {
            var cut = ed.length - KEEP_CHARS
            val nl = ed.indexOf("\n", cut)
            if (nl >= 0) cut = nl + 1
            ed.delete(0, cut)
        }
        if (forceScroll || true) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
