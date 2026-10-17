package com.hemel.lenspilot.automation

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "AI Automation of the Real World" মোড — চ্যাটবট নয়, টাইপিং-বার বা কন্ট্রোল সুইচ নেই। ওপরে শুধু সার্চ:
 * অটোমেশন এজেন্ট খুঁজে ডাউনলোড করা যায় (এখন একটাই: "Make cake with Ai অটোমেশন")। ডাউনলোড হলে এজেন্ট
 * "আমার এজেন্ট"-এ আসে; খুললে AgentActivity — মেশিন ESP32, কানেক্ট বাটন, ফ্রেমওয়ার্ক।
 */
class AutomationHubActivity : AppCompatActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var currentQuery = ""
    private var searchSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AutoUi.BG)
            fitsSystemWindows = true
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8))
        }

        val header = AutoUi.row(ctx)
        header.addView(AutoUi.text(ctx, "←", 24f).apply {
            setPadding(0, 0, AutoUi.dp(ctx, 14), 0)
            setOnClickListener { finish() }
        })
        header.addView(AutoUi.text(ctx, "AI Automation of the Real World", 18f, AutoUi.TEXT, true))
        root.addView(header)
        root.addView(AutoUi.gap(ctx, 12))

        searchInput = EditText(ctx).apply {
            hint = "🔍  অটোমেশন এজেন্ট খুঁজুন…"
            setHintTextColor(AutoUi.MUTED)
            setTextColor(AutoUi.TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            background = AutoUi.rounded(ctx, AutoUi.SURFACE, 14, 0xFF243041.toInt())
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 12))
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                val r = Runnable { refresh(q) }
                pending = r
                handler.postDelayed(r, 450)
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pending?.let { handler.removeCallbacks(it) }
                refresh(searchInput.text.toString().trim())
                true
            } else false
        }
        root.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = AutoUi.text(ctx, "", 13f, AutoUi.MUTED).apply {
            setPadding(AutoUi.dp(ctx, 4), AutoUi.dp(ctx, 10), 0, AutoUi.dp(ctx, 4))
        }
        root.addView(status)

        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh("")
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) refresh(currentQuery)
    }

    private fun refresh(query: String) {
        currentQuery = query
        val seq = ++searchSeq
        status.text = "খুঁজছি…"
        lifecycleScope.launch {
            val res = AutomationApi.search(this@AutomationHubActivity, query)
            if (seq != searchSeq) return@launch // নতুন সার্চ শুরু হয়ে গেছে
            val installed = AgentStore.installed(this@AutomationHubActivity)
            val results = res.getOrNull()
            if (results == null) {
                status.text = "সার্চ ব্যর্থ: ${res.exceptionOrNull()?.message ?: "নেটওয়ার্ক"}"
                render(installed, emptyList())
            } else {
                status.text = if (results.isEmpty() && installed.isEmpty()) "কিছু পাওয়া যায়নি" else ""
                render(installed, results)
            }
        }
    }

    private fun render(installed: List<JSONObject>, results: List<JSONObject>) {
        listBox.removeAllViews()
        val ctx = this
        val installedIds = installed.map { it.optString("id") }.toSet()
        if (installed.isNotEmpty()) {
            listBox.addView(AutoUi.text(ctx, "আমার এজেন্ট", 14f, AutoUi.ACCENT, true).apply {
                setPadding(AutoUi.dp(ctx, 4), AutoUi.dp(ctx, 8), 0, 0)
            })
            installed.forEach { listBox.addView(agentCard(it, true)) }
        }
        val fresh = results.filter { it.optString("id") !in installedIds }
        if (fresh.isNotEmpty()) {
            listBox.addView(AutoUi.text(ctx, "সার্চ ফলাফল", 14f, AutoUi.ACCENT, true).apply {
                setPadding(AutoUi.dp(ctx, 4), AutoUi.dp(ctx, 16), 0, 0)
            })
            fresh.forEach { listBox.addView(agentCard(it, false)) }
        }
    }

    private fun agentCard(agent: JSONObject, isInstalled: Boolean): LinearLayout {
        val ctx = this
        val card = AutoUi.card(ctx)
        card.addView(AutoUi.text(ctx, agent.optString("title"), 16f, AutoUi.TEXT, true))
        val desc = agent.optString("description")
        if (desc.isNotEmpty()) card.addView(AutoUi.text(ctx, desc, 13f, AutoUi.MUTED).apply {
            setPadding(0, AutoUi.dp(ctx, 4), 0, 0)
        })
        val nParts = agent.optJSONObject("flash")?.optJSONArray("parts")?.length() ?: 0
        card.addView(AutoUi.text(ctx,
            "মেশিন: ESP32  •  ফ্রেমওয়ার্ক v${agent.optInt("version", 1)}  •  $nParts ফাইল", 12f, AutoUi.MUTED).apply {
            setPadding(0, AutoUi.dp(ctx, 6), 0, AutoUi.dp(ctx, 10))
        })
        val row = AutoUi.row(ctx)
        if (isInstalled) {
            row.addView(AutoUi.button(ctx, "খুলুন") {
                startActivity(Intent(ctx, AgentActivity::class.java).putExtra(AgentActivity.EXTRA_ID, agent.optString("id")))
            })
            row.addView(AutoUi.button(ctx, "মুছুন", filled = false, color = AutoUi.DANGER) {
                AgentStore.remove(ctx, agent.optString("id"))
                refresh(currentQuery)
            }.apply { (layoutParams as? LinearLayout.LayoutParams) })
        } else {
            lateinit var btn: Button
            btn = AutoUi.button(ctx, "⬇ ডাউনলোড") {
                btn.isEnabled = false
                btn.text = "ডাউনলোড হচ্ছে…"
                lifecycleScope.launch {
                    val r = AutomationApi.download(ctx, agent) { msg -> btn.text = msg }
                    if (r.isSuccess) {
                        Toast.makeText(ctx, "ডাউনলোড হয়েছে", Toast.LENGTH_SHORT).show()
                        refresh(currentQuery)
                    } else {
                        Toast.makeText(ctx, "ডাউনলোড ব্যর্থ: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.text = "⬇ ডাউনলোড"
                    }
                }
            }
            row.addView(btn)
        }
        card.addView(row)
        return card
    }
}
