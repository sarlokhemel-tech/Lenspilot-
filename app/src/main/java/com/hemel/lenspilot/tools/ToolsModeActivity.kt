package com.hemel.lenspilot.tools

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.R
import com.hemel.lenspilot.tools.agent.PlanController
import com.hemel.lenspilot.tools.agent.PlanResult
import com.hemel.lenspilot.tools.agent.ToolsAgentPrefs
import com.hemel.lenspilot.tools.agent.ToolsAgents
import com.hemel.lenspilot.tools.agent.ToolsApi
import com.hemel.lenspilot.tools.agent.ToolsSessionActivity
import java.util.Calendar

/**
 * Tools মোড — ELORA হোম (MainActivity.openToolsMode থেকে খোলে)।
 *
 * ডেমো FFmpeg স্ক্রিন সরানো হয়েছে। এখন: সাদা ব্যাকগ্রাউন্ডে চ্যাট-স্টাইল হোম —
 * উপরে ELORA, শুভেচ্ছা, তিনটি অপশন-আইকন (ব্রাউজার/শেল/স্টোরেজ), নিচে ইনপুট।
 * প্রথম মেসেজ পাঠালে ELORA ও শুভেচ্ছা মিলিয়ে যায়, আইকনগুলো উপরের স্থায়ী বারে উঠে যায়।
 *
 * এআই এখনো যুক্ত নয় (পরের ধাপ): মেসেজ থেকে একটা WorkSession তৈরি হয়, বাবলে দেখায়,
 * আর স্ট্যাটাস লাইনে জানায় যে পরিকল্পনা পরের ধাপে আসছে। পুরো স্পেক: lp/TOOLS_MODE_SPEC.md।
 */
class ToolsModeActivity : AppCompatActivity() {

    private class OptionItem(val root: LinearLayout, val circle: FrameLayout, val icon: ImageView, val label: TextView)

    private lateinit var home: View
    private lateinit var eloraTitle: View
    private lateinit var greeting: TextView
    private lateinit var optionRow: LinearLayout
    private lateinit var slot: FrameLayout
    private lateinit var chat: View
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: EditText

    private val optionItems = ArrayList<OptionItem>()
    private var currentSession: WorkSession? = null
    private var chatStarted = false
    private var animating = false
    private var planning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_mode)

        home = findViewById(R.id.toolsHome)
        eloraTitle = findViewById(R.id.toolsEloraTitle)
        greeting = findViewById(R.id.toolsGreeting)
        optionRow = findViewById(R.id.toolsOptionRow)
        slot = findViewById(R.id.toolsSlot)
        chat = findViewById(R.id.toolsChat)
        chatScroll = findViewById(R.id.toolsChatScroll)
        chatList = findViewById(R.id.toolsChatList)
        status = findViewById(R.id.toolsStatus)
        input = findViewById(R.id.toolsInput)

        greeting.text = getString(R.string.tools_greeting_format, greetingWord())

        findViewById<ImageButton>(R.id.toolsClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.toolsSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.toolsSecurity).setOnClickListener {
            startActivity(Intent(this, com.hemel.lenspilot.tools.perm.ToolsSecurityActivity::class.java))
        }
        findViewById<ImageButton>(R.id.toolsSend).setOnClickListener { onSend() }

        addOption(R.drawable.ic_tools_browser, R.string.tools_opt_browser) { openBrowser() }
        addOption(R.drawable.ic_tools_shell, R.string.tools_opt_shell) { openShell() }
        addOption(R.drawable.ic_tools_storage, R.string.tools_opt_storage) { openStorage() }
    }

    // ---------- শুভেচ্ছা ----------

    private fun greetingWord(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val res = when {
            h < 5 -> R.string.tools_greeting_night
            h < 12 -> R.string.tools_greeting_morning
            h < 15 -> R.string.tools_greeting_noon
            h < 18 -> R.string.tools_greeting_afternoon
            h < 21 -> R.string.tools_greeting_evening
            else -> R.string.tools_greeting_night
        }
        return getString(res)
    }

    // ---------- অপশন-আইকন ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addOption(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
        val label = TextView(this).apply {
            text = getString(labelRes)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ToolsModeActivity, R.color.text_secondary_light))
            setPadding(0, dp(6), 0, 0)
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = getString(labelRes)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        }
        val circle = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@ToolsModeActivity, R.drawable.bg_tools_option_circle)
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            addView(icon)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(circle)
            addView(label)
        }
        optionRow.addView(root)
        optionItems.add(OptionItem(root, circle, icon, label))
    }

    /** উপরের বারে ঢোকার পর ছোট রূপ: লেবেল নেই, বৃত্ত 40dp। */
    private fun applyCompact() {
        for (o in optionItems) {
            o.label.visibility = View.GONE
            o.circle.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            o.icon.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            o.root.layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun soon() {
        Toast.makeText(this, R.string.tools_soon, Toast.LENGTH_SHORT).show()
    }

    private fun openShell() {
        val i = Intent(this, com.hemel.lenspilot.tools.shell.ShellActivity::class.java)
        currentSession?.let { i.putExtra(com.hemel.lenspilot.tools.shell.ShellActivity.EXTRA_SESSION_ID, it.id) }
        startActivity(i)
    }

    /** ব্রাউজার: সেশনের নিজস্ব ডাউনলোড-ফোল্ডার লাগে, তাই সেশন না থাকলে ফাঁকা সেশন বানিয়ে খোলে (প্রথম বার্তা পরে এতেই বসে)। */
    private fun openBrowser() {
        val s = currentSession
        if (s != null) {
            com.hemel.lenspilot.tools.browser.BrowserWorkspaces.launch(this, s.id)
            return
        }
        val app = applicationContext
        val defTitle = getString(R.string.tools_session_default_title)
        ToolsAsync.io {
            try {
                val created = SessionStore.create(app, "", defTitle)
                ToolsAsync.ui {
                    if (isFinishing || isDestroyed) return@ui
                    currentSession = created
                    com.hemel.lenspilot.tools.browser.BrowserWorkspaces.launch(this, created.id)
                }
            } catch (e: Exception) {
                ToolsAsync.ui {
                    if (isFinishing || isDestroyed) return@ui
                    Toast.makeText(
                        this,
                        getString(R.string.tools_session_create_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openStorage() {
        val i = Intent(this, StorageActivity::class.java)
        currentSession?.let { i.putExtra(StorageActivity.EXTRA_SESSION_ID, it.id) }
        startActivity(i)
    }

    // ---------- মেসেজ পাঠানো ----------

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || animating) return
        if (planning) {
            Toast.makeText(this, R.string.tools_plan_busy, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        addUserBubble(text)

        if (!chatStarted) {
            chatStarted = true
            moveOptionsToTopBar()
            createSession(text)
        } else {
            val s = currentSession
            if (s != null) {
                // সেশন ৭: কাজ চলছে/শেষ হলে বার্তা এজেন্টের কাছে (user_note); পরিকল্পনা এখনো না হলে নতুন লক্ষ্য ধরে আবার পরিকল্পনা
                if (ToolsAgents.isAlive(s.id) || com.hemel.lenspilot.tools.agent.AgentStateStore.exists(applicationContext, s.id)) {
                    ToolsAgents.send(applicationContext, s.id, text)
                    openLive(s.id)
                } else {
                    val app = applicationContext
                    ToolsAsync.io {
                        val s2 = SessionStore.update(app, s.id) { it.copy(goal = text) } ?: s
                        ToolsAsync.ui { if (!isFinishing && !isDestroyed) { currentSession = s2; startPlanning(s2, text) } }
                    }
                }
            } else {
                // আগের বার সেশন তৈরি ব্যর্থ হয়েছিল — এই বার্তাকেই লক্ষ্য ধরে আবার চেষ্টা
                createSession(text)
            }
        }
    }

    private fun createSession(goal: String) {
        val app = applicationContext
        val defTitle = getString(R.string.tools_session_default_title)
        val note = getString(R.string.tools_note_saved)
        val existing = currentSession      // ব্রাউজার-আইকন আগেই সেশন বানিয়ে থাকলে সেটাই ব্যবহার (ডুপ্লিকেট নয়)
        ToolsAsync.io {
            try {
                val s = if (existing != null) {
                    val g = goal.trim()
                    SessionStore.update(app, existing.id) {
                        it.copy(goal = g, title = g.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()?.take(48) ?: it.title)
                    } ?: existing
                } else SessionStore.create(app, goal, defTitle)
                val s2 = SessionStore.update(app, s.id) { it.copy(lastNote = note) } ?: s
                ToolsAsync.ui {
                    if (isFinishing || isDestroyed) return@ui
                    currentSession = s2
                    startPlanning(s2, s2.goal.ifEmpty { goal })
                }
            } catch (e: Exception) {
                ToolsAsync.ui {
                    if (isFinishing || isDestroyed) return@ui
                    Toast.makeText(
                        this,
                        getString(R.string.tools_session_create_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showThinking(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun openLive(sessionId: String) {
        startActivity(Intent(this, ToolsSessionActivity::class.java).putExtra(ToolsSessionActivity.EXTRA_SESSION_ID, sessionId))
    }

    // ---------- সেশন ৭: প্রথম বার্তা = লক্ষ্য → /api/tools/plan → সারাংশ-কার্ড → Start ----------

    private fun stageText(stage: String): Int = when (stage) {
        "constraints" -> R.string.tools_plan_stage_constraints
        "criteria" -> R.string.tools_plan_stage_criteria
        "candidates" -> R.string.tools_plan_stage_candidates
        "research" -> R.string.tools_plan_stage_research
        "scoring" -> R.string.tools_plan_stage_scoring
        "critic" -> R.string.tools_plan_stage_critic
        else -> R.string.tools_plan_stage_final
    }

    private fun startPlanning(s: WorkSession, goal: String) {
        planning = true
        showThinking(getString(R.string.tools_plan_stage_constraints))
        PlanController.plan(
            this, s.id, goal,
            onStage = { stage, st ->
                if (!isFinishing && !isDestroyed && st != "done") showThinking(getString(stageText(stage)))
            },
            onResult = { r ->
                planning = false
                if (!isFinishing && !isDestroyed) {
                    val plan = r.getOrNull()
                    if (plan == null) {
                        val err = r.exceptionOrNull()
                        showThinking(getString(R.string.tools_plan_failed, if (err != null) ToolsApi.friendlyError(this, err) else ""))
                    } else {
                        status.visibility = View.GONE
                        if (ToolsAgentPrefs.autoStart(this)) startAgent(s, goal, plan) else addPlanCard(s, goal, plan)
                    }
                }
            }
        )
    }

    private fun startAgent(s: WorkSession, goal: String, plan: PlanResult) {
        ToolsAgents.start(applicationContext, s.id, goal, plan)
        PlanController.clear(s.id)
        Toast.makeText(this, R.string.tools_plan_started, Toast.LENGTH_SHORT).show()
        openLive(s.id)
    }

    /** পরিকল্পনার সারাংশ-কার্ড: ধাপ-সংখ্যা, আনুমানিক সময়/খরচ, সতর্কতা, Start বাটন ও auto-start সেটিং। */
    private fun addPlanCard(s: WorkSession, goal: String, plan: PlanResult) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@ToolsModeActivity, R.drawable.bg_tools_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun line(t: String, size: Float, bold: Boolean = false, color: Int = ContextCompat.getColor(this, R.color.text_primary_light)) {
            card.addView(TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
        }
        line(getString(R.string.tools_plan_card_title) + if (plan.pipelineName.isNotEmpty()) " — " + plan.pipelineName else "", 15f, true)
        if (plan.summary.isNotEmpty()) line(plan.summary, 13f)
        val meta = ArrayList<String>()
        meta.add(getString(R.string.tools_plan_card_steps, plan.steps.size))
        if (plan.timeText.isNotEmpty()) meta.add(getString(R.string.tools_plan_card_time, plan.timeText))
        if (plan.costText.isNotEmpty()) meta.add(getString(R.string.tools_plan_card_cost, plan.costText))
        line(meta.joinToString(" · "), 13f, false, ContextCompat.getColor(this, R.color.text_secondary_light))
        if (plan.warnings.isNotEmpty()) line(getString(R.string.tools_plan_warnings, plan.warnings.filter { it.isNotBlank() }.take(2).joinToString("; ")), 12f, false, android.graphics.Color.parseColor("#B45309"))
        val auto = android.widget.CheckBox(this).apply {
            text = getString(R.string.tools_plan_autostart)
            isChecked = ToolsAgentPrefs.autoStart(this@ToolsModeActivity)
            setOnCheckedChangeListener { _, on -> ToolsAgentPrefs.setAutoStart(this@ToolsModeActivity, on) }
        }
        card.addView(auto)
        card.addView(android.widget.Button(this).apply {
            text = getString(R.string.tools_plan_start)
            isAllCaps = false
            setOnClickListener {
                isEnabled = false
                startAgent(s, goal, plan)
            }
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        chatList.addView(card, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addUserBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@ToolsModeActivity, android.R.color.white))
            background = ContextCompat.getDrawable(this@ToolsModeActivity, R.drawable.bg_tools_bubble_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.bottomMargin = dp(10)
        chatList.addView(bubble, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- হোম → চ্যাট রূপান্তর ----------

    /**
     * ELORA ও শুভেচ্ছা মিলিয়ে যায়; আইকন-সারি উপরের বারের slot-এর দিকে সরে ছোট হয়, শেষে slot-এ বসে।
     * স্কেলের pivot মাঝের বৃত্তের কেন্দ্রে রাখা হয়েছে, যাতে গন্তব্য হিসাব ঠিক মেলে।
     */
    private fun moveOptionsToTopBar() {
        animating = true
        val ref = optionItems[1].circle
        val refLoc = IntArray(2)
        val rowLoc = IntArray(2)
        val slotLoc = IntArray(2)
        ref.getLocationInWindow(refLoc)
        optionRow.getLocationInWindow(rowLoc)
        slot.getLocationInWindow(slotLoc)

        val refCx = refLoc[0] + ref.width / 2f
        val refCy = refLoc[1] + ref.height / 2f
        val dx = (slotLoc[0] + slot.width / 2f) - refCx
        val dy = (slotLoc[1] + slot.height / 2f) - refCy

        optionRow.pivotX = refCx - rowLoc[0]
        optionRow.pivotY = refCy - rowLoc[1]

        eloraTitle.animate().alpha(0f).setDuration(260).start()
        greeting.animate().alpha(0f).setDuration(260).start()
        for (o in optionItems) o.label.animate().alpha(0f).setDuration(200).start()

        optionRow.animate()
            .translationX(dx)
            .translationY(dy)
            .scaleX(40f / 60f)
            .scaleY(40f / 60f)
            .setDuration(460)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finishMove() }
            .start()
    }

    private fun finishMove() {
        (optionRow.parent as? ViewGroup)?.removeView(optionRow)
        optionRow.translationX = 0f
        optionRow.translationY = 0f
        optionRow.scaleX = 1f
        optionRow.scaleY = 1f
        applyCompact()
        slot.addView(
            optionRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        home.visibility = View.GONE
        chat.alpha = 0f
        chat.visibility = View.VISIBLE
        chat.animate().alpha(1f).setDuration(220).start()
        animating = false
    }
}
