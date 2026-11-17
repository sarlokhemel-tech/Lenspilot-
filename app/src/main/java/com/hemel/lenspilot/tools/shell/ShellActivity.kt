package com.hemel.lenspilot.tools.shell

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hemel.lenspilot.R
import com.hemel.lenspilot.tools.ToolsPaths

/**
 * শেল স্ক্রিন: কালো, মনোস্পেস, প্রম্পট, স্ক্রলব্যাক, ইনপুট + ইতিহাস ▲▼ + Stop।
 * সব কাজ [ShellSession]-এর মাধ্যমে; AI চালালে ইনপুট লক ও "ELORA কাজ করছে" দেখায়।
 * Intent extra: [EXTRA_SESSION_ID] (না দিলে গ্লোবাল workspace)।
 */
class ShellActivity : AppCompatActivity(), ShellSession.Listener {

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        private const val MAX_CHARS = 120_000
        private const val KEEP_CHARS = 90_000
    }

    private lateinit var session: ShellSession
    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var progress: TextView
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private lateinit var banner: View
    private lateinit var stopBtn: View
    private lateinit var upBtn: View
    private lateinit var downBtn: View

    private var histIndex = 0
    private var draft = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sid = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sid != null && !ToolsPaths.isValidId(sid)) {
            Toast.makeText(this, R.string.shell_bad_session, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_shell)
        session = ShellBus.session(applicationContext, sid)

        scroll = findViewById(R.id.shellScroll)
        output = findViewById(R.id.shellOutput)
        progress = findViewById(R.id.shellProgress)
        prompt = findViewById(R.id.shellPrompt)
        input = findViewById(R.id.shellInput)
        banner = findViewById(R.id.shellAiBanner)
        stopBtn = findViewById(R.id.shellStop)
        upBtn = findViewById(R.id.shellUp)
        downBtn = findViewById(R.id.shellDown)

        findViewById<View>(R.id.shellClose).setOnClickListener { finish() }
        findViewById<View>(R.id.shellGithub).setOnClickListener { GitHubLoginDialog.show(this) }
        stopBtn.setOnClickListener { session.stop() }
        upBtn.setOnClickListener { historyStep(-1) }
        downBtn.setOnClickListener { historyStep(1) }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEND || enter) {
                runCurrent()
                true
            } else {
                false
            }
        }

        val snap = session.attach(this)
        if (snap.lines.isEmpty()) {
            appendSpanned(listOf(ShellLine(getString(R.string.shell_welcome), ShellLine.INFO)), true)
        } else {
            appendSpanned(snap.lines, true)
        }
        prompt.text = snap.prompt
        onProgress(snap.progress)
        if (snap.typing != null) onTyping(snap.typing)
        onState(snap.running, snap.aiWorking)
        histIndex = session.historySnapshot().size
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.detach(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------ ইনপুট
    private fun runCurrent() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!session.runUser(text)) {
            Toast.makeText(this, R.string.shell_busy, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        draft = ""
        histIndex = session.historySnapshot().size
    }

    private fun historyStep(delta: Int) {
        if (!input.isEnabled) return
        val h = session.historySnapshot()
        if (h.isEmpty()) return
        if (histIndex > h.size) histIndex = h.size
        if (histIndex == h.size) draft = input.text.toString()
        val next = (histIndex + delta).coerceIn(0, h.size)
        histIndex = next
        val t = if (next == h.size) draft else h[next]
        input.setText(t)
        input.setSelection(t.length)
    }

    // ------------------------------------------------------------ Listener (main থ্রেড)
    override fun onLines(lines: List<ShellLine>) = appendSpanned(lines, false)

    override fun onClear() {
        output.text = ""
    }

    override fun onProgress(text: String?) {
        if (text.isNullOrEmpty()) {
            progress.visibility = View.GONE
        } else {
            progress.text = text
            progress.visibility = View.VISIBLE
        }
    }

    override fun onPrompt(prompt: String) {
        this.prompt.text = prompt
    }

    override fun onTyping(text: String?) {
        if (text == null) {
            input.setText("")
        } else {
            input.setText(text)
            input.setSelection(text.length)
        }
    }

    override fun onState(running: Boolean, aiWorking: Boolean) {
        val locked = running || aiWorking
        input.isEnabled = !locked
        upBtn.alpha = if (locked) 0.4f else 1f
        downBtn.alpha = if (locked) 0.4f else 1f
        stopBtn.alpha = if (locked) 1f else 0.4f
        banner.visibility = if (aiWorking) View.VISIBLE else View.GONE
        input.hint = getString(
            when {
                aiWorking -> R.string.shell_hint_ai
                running -> R.string.shell_hint_busy
                else -> R.string.shell_hint
            }
        )
        if (!locked) input.requestFocus()
    }

    override fun onLoginRequested() {
        GitHubLoginDialog.show(this)
    }

    // ------------------------------------------------------------ রেন্ডার
    private fun colorOf(kind: Int): Int = when (kind) {
        ShellLine.ERR -> Color.parseColor("#FF6B6B")
        ShellLine.PROMPT -> Color.parseColor("#5AF78E")
        ShellLine.INFO -> Color.parseColor("#8BE9FD")
        else -> Color.parseColor("#D4D4D4")
    }

    private fun appendSpanned(lines: List<ShellLine>, forceScroll: Boolean) {
        if (lines.isEmpty()) return
        val child = scroll.getChildAt(0)
        val nearBottom = child == null ||
            (child.bottom - (scroll.height + scroll.scrollY)) < (80 * resources.displayMetrics.density)
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
        if (forceScroll || nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
