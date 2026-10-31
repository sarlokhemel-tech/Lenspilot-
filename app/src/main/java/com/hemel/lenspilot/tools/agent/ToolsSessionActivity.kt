package com.hemel.lenspilot.tools.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hemel.lenspilot.R
import com.hemel.lenspilot.chat.GlassChat
import com.hemel.lenspilot.tools.SessionState
import com.hemel.lenspilot.tools.StorageActivity
import com.hemel.lenspilot.tools.ToolsAsync
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.browser.BrowserWorkspaces
import com.hemel.lenspilot.tools.shell.ShellBus
import kotlinx.coroutines.launch

/**
 * লাইভ কাজের স্ক্রিন: উপরে Browser | Shell | Storage (সক্রিয়টা নিজে থেকে বদলায়), স্ট্যাটাস-চিপ, বিরতি ও থামান।
 * ভাসমান আইকন → কাচের কার্ডের চ্যাটবক্স (GlassChat): ইউজারের লেখা পরের decide-এ user_note হয়ে যায়;
 * ELORA প্রশ্ন করলে চ্যাটবক্স নিজে খোলে ও অপশন-বাটন দেখায়। Shell-ট্যাবে AI-র কমান্ড লাইভ টাইপ হয়, ইনপুট লক।
 */
class ToolsSessionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
    }

    private lateinit var sid: String
    private lateinit var tabs: List<TextView>
    private lateinit var chip: TextView
    private lateinit var stepLine: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var content: FrameLayout
    private lateinit var browserPane: LinearLayout
    private lateinit var browserBody: TextView
    private lateinit var storagePane: LinearLayout
    private lateinit var storageBody: TextView
    private lateinit var shellPane: ShellPane
    private lateinit var fab: TextView
    private lateinit var fabBadge: View
    private lateinit var overlay: FrameLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var optionRow: LinearLayout
    private lateinit var chatInput: EditText

    private val names = listOf("browser", "shell", "storage")
    private var currentTab = "shell"
    private var lastFollowed = ""
    private var lastChatCount = -1
    private var lastQuestion: String? = null
    private var paused = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id == null || !ToolsPaths.isValidId(id)) {
            Toast.makeText(this, R.string.tools_live_bad_session, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sid = id
        buildUi()
        shellPane = ShellPane(this, ShellBus.session(applicationContext, sid))
        content.addView(shellPane.root, 0)
        selectTab("shell", user = false)
        // প্রসেস মরার পর অসমাপ্ত কাজ থাকলে এখান থেকেও ফেরে
        ToolsAgents.ensureRunning(applicationContext, sid)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ToolsAgents.uiFlow(applicationContext, sid).collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()      // নোটিফিকেশন থেকে অন্য সেশনে এলে
    }

    override fun onStart() {
        super.onStart()
        if (!::sid.isInitialized) return
        ToolsAgents.viewerEnter(sid)
        if (currentTab == "shell") shellPane.show()
    }

    override fun onStop() {
        if (::shellPane.isInitialized) shellPane.hide()
        if (::sid.isInitialized) ToolsAgents.viewerExit(sid)
        super.onStop()
    }

    // ------------------------------------------------------------------------------------ UI গঠন
    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // টপ বার
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_tools_back)
            setBackgroundResource(0)
            contentDescription = getString(R.string.tools_back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tabLabels = listOf(R.string.tools_opt_browser, R.string.tools_opt_shell, R.string.tools_opt_storage)
        val tv = ArrayList<TextView>()
        for ((i, res) in tabLabels.withIndex()) {
            val t = TextView(this).apply {
                text = getString(res)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { selectTab(names[i], user = true) }
            }
            tv.add(t)
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
        tabs = tv
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chip = TextView(this).apply {
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        bar.addView(chip)
        pauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener {
                if (paused) ToolsAgents.resume(applicationContext, sid) else ToolsAgents.pause(applicationContext, sid)
            }
        }
        bar.addView(pauseBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(TextView(this).apply {
            text = "⏹"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            setOnClickListener { ToolsAgents.stop(applicationContext, sid) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        stepLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(16), 0, dp(16), dp(6))
            maxLines = 2
        }
        col.addView(stepLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        // ব্রাউজার-ট্যাব
        browserBody = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#334155")) }
        browserPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(browserBody)
            addView(Button(this@ToolsSessionActivity).apply {
                text = getString(R.string.tools_live_open_browser)
                isAllCaps = false
                setOnClickListener { BrowserWorkspaces.launch(this@ToolsSessionActivity, sid) }
            })
        }
        // স্টোরেজ-ট্যাব
        storageBody = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#334155")) }
        storagePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(Button(this@ToolsSessionActivity).apply {
                text = getString(R.string.tools_live_open_storage)
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@ToolsSessionActivity, StorageActivity::class.java).putExtra(StorageActivity.EXTRA_SESSION_ID, sid))
                }
            })
            addView(ScrollView(this@ToolsSessionActivity).apply { addView(storageBody) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        content.addView(browserPane)
        content.addView(storagePane)
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ভাসমান কন্ট্রোল আইকন
        fab = TextView(this).apply {
            text = "💬"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC0F172A")) }
            elevation = dp(6).toFloat()
            setOnClickListener { setChatOpen(true) }
        }
        rootFrame.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(16); bottomMargin = dp(88)
        })
        fabBadge = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EF4444")) }
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        rootFrame.addView(fabBadge, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(18); bottomMargin = dp(88 + 42)
        })

        buildOverlay()
        rootFrame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)
    }

    /** কাচের চ্যাটবক্স: পেছনটা হালকা অন্ধকার, কার্ডগুলো ভাসে (GlassChat-এর কার্ড হুবহু)। */
    private fun buildOverlay() {
        overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = getString(R.string.elora_name)
            GlassChat.whiteBold(this, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            GlassChat.whiteBold(this, 20f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { setChatOpen(false) }
        })
        box.addView(head)
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        box.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        optionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        box.addView(optionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val inRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chatInput = EditText(this).apply {
            hint = getString(R.string.tools_live_chat_hint)
            setHintTextColor(Color.parseColor("#B3FFFFFF"))
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_live_glass_input)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false }
        }
        inRow.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inRow.addView(TextView(this).apply {
            text = "➤"
            GlassChat.whiteBold(this, 22f)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            setOnClickListener { sendChat() }
        })
        box.addView(inRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        overlay.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun setChatOpen(open: Boolean) {
        overlay.visibility = if (open) View.VISIBLE else View.GONE
        fab.visibility = if (open) View.GONE else View.VISIBLE
        if (open) {
            fabBadge.visibility = View.GONE
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(chatInput.windowToken, 0)
        }
    }

    private fun sendChat() {
        val t = chatInput.text.toString().trim()
        if (t.isEmpty()) return
        chatInput.setText("")
        ToolsAgents.send(applicationContext, sid, t)
    }

    // ------------------------------------------------------------------------------------ ট্যাব
    private fun selectTab(name: String, user: Boolean) {
        currentTab = name
        for ((i, t) in tabs.withIndex()) {
            val on = names[i] == name
            t.setTextColor(if (on) Color.WHITE else Color.parseColor("#334155"))
            t.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (on) Color.parseColor("#0F172A") else Color.parseColor("#EEF2F7"))
            }
        }
        browserPane.visibility = if (name == "browser") View.VISIBLE else View.GONE
        storagePane.visibility = if (name == "storage") View.VISIBLE else View.GONE
        shellPane.root.visibility = if (name == "shell") View.VISIBLE else View.GONE
        if (name == "shell") shellPane.show() else shellPane.hide()
        if (name == "storage") refreshStorage()
    }

    private fun refreshStorage() {
        val app = applicationContext
        ToolsAsync.io {
            val lines = com.hemel.lenspilot.tools.agent.StorageAdapter.peek(app, sid)
            ToolsAsync.ui {
                if (isFinishing || isDestroyed) return@ui
                storageBody.text = if (lines.isEmpty()) getString(R.string.tools_live_storage_empty) else lines.joinToString("\n")
            }
        }
    }

    // ------------------------------------------------------------------------------------ রেন্ডার
    private fun render(ui: AgentUi) {
        paused = ui.paused
        pauseBtn.text = if (ui.paused) "▶" else "⏸"
        val color = Color.parseColor(stateColor(ui.state))
        chip.text = getString(stateLabel(ui.state))
        chip.setTextColor(color)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)))
        }
        stepLine.text = listOf(ui.stepLabel, ui.note).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        browserBody.text = (if (ui.workspace == "browser") ui.stepLabel + "\n\n" else "") + ui.lastResult.ifEmpty { getString(R.string.tools_live_browser_idle) }

        // সক্রিয় ওয়ার্কস্পেসে নিজে থেকে যাওয়া (ধাপ বদলালে একবার)
        val key = ui.workspace + "|" + ui.stepIndex
        if (ui.workspace in names && key != lastFollowed) {
            lastFollowed = key
            if (ui.workspace != currentTab) selectTab(ui.workspace, user = false)
            else if (ui.workspace == "storage") refreshStorage()
        } else if (currentTab == "storage" && ui.lastResult.isNotEmpty()) refreshStorage()

        renderChat(ui)
    }

    private fun renderChat(ui: AgentUi) {
        val qText = ui.question?.text
        if (ui.chat.size != lastChatCount || qText != lastQuestion) {
            val grew = lastChatCount >= 0 && ui.chat.size > lastChatCount
            lastChatCount = ui.chat.size
            chatList.removeAllViews()
            for (m in ui.chat) {
                chatList.addView(if (m.from == "user") GlassChat.userCard(this, m.text, null) else GlassChat.aiCard(this, m.text, m.ts, null))
            }
            optionRow.removeAllViews()
            val q = ui.question
            if (q != null) {
                for (o in q.options) {
                    optionRow.addView(Button(this).apply {
                        text = o
                        isAllCaps = false
                        setOnClickListener { ToolsAgents.send(applicationContext, sid, o) }
                    })
                }
            }
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            if (qText != null && qText != lastQuestion) setChatOpen(true)         // প্রশ্ন এলে চ্যাটবক্স নিজে খোলে
            else if (grew && overlay.visibility != View.VISIBLE) fabBadge.visibility = View.VISIBLE
            lastQuestion = qText
        }
    }

    private fun stateLabel(st: SessionState): Int = when (st) {
        SessionState.IDLE -> R.string.tools_state_idle
        SessionState.RUNNING -> R.string.tools_state_running
        SessionState.WAITING -> R.string.tools_state_waiting
        SessionState.NEEDS_YOU -> R.string.tools_state_needs_you
        SessionState.DONE -> R.string.tools_state_done
        SessionState.FAILED -> R.string.tools_state_failed
    }

    private fun stateColor(st: SessionState): String = when (st) {
        SessionState.IDLE -> "#64748B"
        SessionState.RUNNING -> "#2563EB"
        SessionState.WAITING -> "#F59E0B"
        SessionState.NEEDS_YOU -> "#8B5CF6"
        SessionState.DONE -> "#10B981"
        SessionState.FAILED -> "#EF4444"
    }
}
