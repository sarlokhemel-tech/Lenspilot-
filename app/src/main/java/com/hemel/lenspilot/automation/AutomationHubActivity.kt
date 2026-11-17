package com.hemel.lenspilot.automation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "AI Automation of the Real World" মোড — চ্যাটবট নয়।
 *
 * v37:
 *  • সার্চ করলে তবেই এজেন্ট আসে (সার্চ ফাঁকা = সার্ভারে কিছুই চাওয়া হয় না, কিছু দেখানোও হয় না)।
 *  • ডাউনলোড করলে এজেন্ট "আমার এজেন্ট"-এ চলে যায়, আর সার্চ ফলাফলে আসে না।
 *  • প্রতিটা এজেন্ট ফাইভার-গিগের মতো কার্ড: অ্যাডমিন প্যানেল থেকে দেওয়া কভার-থাম্বনেইল, ক্যাটাগরি, শিরোনাম, বিবরণ।
 *  • প্রিমিয়াম হালকা থিম (লোগোর নীল/সাদা), আগের "হ্যাকার" লুক নয়।
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
        AutoUi.chrome(this)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AutoUi.BG)
            fitsSystemWindows = true
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8), AutoUi.dp(ctx, 16), 0)
        }

        // ---------- হেডার ----------
        val header = AutoUi.row(ctx)
        val back = TextView(ctx).apply {
            text = "←"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(AutoUi.INK)
            background = AutoUi.rounded(ctx, AutoUi.SURFACE, 20, AutoUi.STROKE)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(AutoUi.dp(ctx, 40), AutoUi.dp(ctx, 40)))
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AutoUi.dp(ctx, 12), 0, 0, 0)
        }
        titleBox.addView(AutoUi.text(ctx, "AI Automation", 20f, AutoUi.INK, true))
        titleBox.addView(AutoUi.text(ctx, "of the Real World", 12.5f, AutoUi.MUTED))
        header.addView(titleBox)
        root.addView(header)
        root.addView(AutoUi.gap(ctx, 12))

        // ---------- সার্চ ----------
        searchInput = EditText(ctx).apply {
            hint = "Search agents…"
            setHintTextColor(AutoUi.MUTED)
            setTextColor(AutoUi.TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            background = AutoUi.rounded(ctx, AutoUi.SURFACE, 28, AutoUi.STROKE)
            elevation = AutoUi.dp(ctx, 2).toFloat()
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_blue, 0, 0, 0)
            compoundDrawablePadding = AutoUi.dp(ctx, 10)
            setPadding(AutoUi.dp(ctx, 18), AutoUi.dp(ctx, 14), AutoUi.dp(ctx, 18), AutoUi.dp(ctx, 14))
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
            setPadding(AutoUi.dp(ctx, 6), AutoUi.dp(ctx, 8), 0, AutoUi.dp(ctx, 2))
        }
        root.addView(status)

        // ---------- স্ক্রল: হিরো + তালিকা ----------
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, AutoUi.dp(ctx, 28))
        }
        content.addView(heroCard())
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh("")
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) refresh(currentQuery)
    }

    private fun heroCard(): View {
        val ctx = this
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AutoUi.gradient(ctx, 24, AutoUi.INK, AutoUi.ACCENT)
            elevation = AutoUi.dp(ctx, 3).toFloat()
            setPadding(AutoUi.dp(ctx, 20), AutoUi.dp(ctx, 18), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = AutoUi.dp(ctx, 14) }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(AutoUi.text(ctx, "Real-world machines, controlled by AI", 18f, Color.WHITE, true))
        texts.addView(AutoUi.text(ctx,
            "Search an agent → download it → connect to the ESP32 and run it with AI.", 13f, 0xCCFFFFFF.toInt()).apply {
            setPadding(0, AutoUi.dp(ctx, 6), 0, 0)
        })
        hero.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val iconWrap = FrameLayout(ctx).apply {
            background = AutoUi.rounded(ctx, 0x26FFFFFF, 30)
        }
        iconWrap.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_automation_world)
            setColorFilter(Color.WHITE)
            setPadding(AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 12))
        }, FrameLayout.LayoutParams(AutoUi.dp(ctx, 60), AutoUi.dp(ctx, 60)))
        hero.addView(iconWrap, LinearLayout.LayoutParams(AutoUi.dp(ctx, 60), AutoUi.dp(ctx, 60)).apply {
            marginStart = AutoUi.dp(ctx, 12)
        })
        return hero
    }

    private fun refresh(query: String) {
        currentQuery = query
        val seq = ++searchSeq
        val installed = AgentStore.installed(this)
        if (query.isBlank()) {
            // সার্চ না করলে সার্ভারে কিছু চাওয়া হয় না, সার্চ-ফলাফলও নেই — শুধু ডাউনলোড-করা এজেন্ট
            status.text = ""
            render(installed, emptyList(), searching = false, filteredOut = 0, failed = false)
            return
        }
        status.text = "Searching…"
        lifecycleScope.launch {
            val res = AutomationApi.search(this@AutomationHubActivity, query)
            if (seq != searchSeq) return@launch // নতুন সার্চ শুরু হয়ে গেছে
            val inst = AgentStore.installed(this@AutomationHubActivity)
            val results = res.getOrNull()
            if (results == null) {
                status.text = "Search failed: ${res.exceptionOrNull()?.message ?: "Network"}"
                render(inst, emptyList(), searching = true, filteredOut = 0, failed = true)
            } else {
                status.text = ""
                val ids = inst.map { it.optString("id") }.toSet()
                val fresh = results.filter { it.optString("id") !in ids }
                render(inst, fresh, searching = true, filteredOut = results.size - fresh.size, failed = false)
            }
        }
    }

    private fun sectionTitle(s: String): TextView {
        val ctx = this
        return AutoUi.text(ctx, s, 15f, AutoUi.INK, true).apply {
            setPadding(AutoUi.dp(ctx, 4), AutoUi.dp(ctx, 20), 0, AutoUi.dp(ctx, 2))
        }
    }

    private fun hintCard(title: String, body: String): View {
        val ctx = this
        val c = AutoUi.card(ctx)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(AutoUi.text(ctx, title, 15f, AutoUi.INK, true).apply { gravity = Gravity.CENTER })
        c.addView(AutoUi.text(ctx, body, 13f, AutoUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, AutoUi.dp(ctx, 6), 0, 0)
        })
        return c
    }

    private fun render(
        installed: List<JSONObject>, fresh: List<JSONObject>,
        searching: Boolean, filteredOut: Int, failed: Boolean
    ) {
        listBox.removeAllViews()
        if (!searching) {
            if (installed.isNotEmpty()) {
                listBox.addView(sectionTitle("My agents"))
                installed.forEach { listBox.addView(agentCard(it, true)) }
            } else {
                listBox.addView(hintCard("No agents yet",
                    "Type in the search box above. Agents appear only after you search."))
            }
            return
        }
        if (fresh.isNotEmpty()) {
            listBox.addView(sectionTitle("Search results"))
            fresh.forEach { listBox.addView(agentCard(it, false)) }
        } else if (!failed) {
            listBox.addView(hintCard("Nothing found",
                if (filteredOut > 0) "The item you searched for is already downloaded and is in “My agents” (clear the search to see it)."
                else "Try different keywords."))
        }
    }

    // ------------------------------------------------------------------
    // ফাইভার-গিগ ধাঁচের কার্ড
    // ------------------------------------------------------------------

    private fun agentCard(agent: JSONObject, isInstalled: Boolean): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoUi.rounded(ctx, AutoUi.SURFACE, 22, AutoUi.STROKE)
            elevation = AutoUi.dp(ctx, 3).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = AutoUi.dp(ctx, 14) }
        }

        // কভার (১৬:৯) — ছবি আসার আগে/না থাকলে গ্রেডিয়েন্ট + আইকন
        val coverH = ((resources.displayMetrics.widthPixels - AutoUi.dp(ctx, 32)) * 9) / 16
        val coverBox = FrameLayout(ctx).apply {
            background = AutoUi.gradient(ctx, 0, AutoUi.INK, AutoUi.ACCENT_2)
        }
        val fallbackIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_automation_world)
            setColorFilter(0x66FFFFFF)
        }
        coverBox.addView(fallbackIcon, FrameLayout.LayoutParams(AutoUi.dp(ctx, 72), AutoUi.dp(ctx, 72), Gravity.CENTER))
        val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverBox.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val category = agent.optString("category")
        if (category.isNotBlank()) {
            coverBox.addView(AutoUi.chip(ctx, category, AutoUi.SURFACE, AutoUi.ACCENT), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
            ).apply { setMargins(AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 12), 0, 0) })
        }
        if (isInstalled) {
            coverBox.addView(AutoUi.chip(ctx, "✓ Downloaded", 0xFFDCFCE7.toInt(), 0xFF15803D.toInt()), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { setMargins(0, AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 12), 0) })
        }
        AutoUi.roundTop(coverBox, 22)
        card.addView(coverBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverH))

        val thumbUrl = agent.optString("thumb_url")
        if (thumbUrl.isNotBlank()) {
            lifecycleScope.launch {
                val bmp = ThumbLoader.load(ctx, thumbUrl)
                if (bmp != null) {
                    cover.setImageBitmap(bmp)
                    fallbackIcon.visibility = View.GONE
                }
            }
        }

        // বডি
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 14), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 16))
        }
        body.addView(AutoUi.text(ctx, agent.optString("title"), 17f, AutoUi.INK, true))
        val desc = agent.optString("description")
        if (desc.isNotEmpty()) body.addView(AutoUi.text(ctx, desc, 13.5f, AutoUi.MUTED).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            setPadding(0, AutoUi.dp(ctx, 6), 0, 0)
        })

        val chips = AutoUi.row(ctx).apply { setPadding(0, AutoUi.dp(ctx, 12), 0, AutoUi.dp(ctx, 14)) }
        chips.addView(AutoUi.chip(ctx, "ESP32"))
        chips.addView(AutoUi.chip(ctx, "v${agent.optInt("version", 1)}", 0xFFF1F5F9.toInt(), AutoUi.MUTED).apply { spaced() })
        val tr = agent.optJSONArray("transports")
        for (i in 0 until (tr?.length() ?: 0)) {
            val label = when (tr!!.optString(i)) {
                "wifi" -> "WiFi"
                "bluetooth" -> "Bluetooth"
                "otg" -> "OTG"
                else -> continue
            }
            chips.addView(AutoUi.chip(ctx, label, 0xFFF1F5F9.toInt(), AutoUi.MUTED).apply { spaced() })
        }
        val chipScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        body.addView(chipScroll)

        if (isInstalled) {
            val row = AutoUi.row(ctx)
            row.addView(AutoUi.button(ctx, "Open") {
                startActivity(Intent(ctx, AgentActivity::class.java).putExtra(AgentActivity.EXTRA_ID, agent.optString("id")))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(AutoUi.button(ctx, "Delete", filled = false, color = AutoUi.DANGER) {
                AlertDialog.Builder(ctx)
                    .setTitle("Delete this agent?")
                    .setMessage("“${agent.optString("title")}” will be removed from this phone. You can search and download it again if needed.")
                    .setPositiveButton("Delete") { _, _ ->
                        AgentStore.remove(ctx, agent.optString("id"))
                        refresh(currentQuery)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = AutoUi.dp(ctx, 10)
            })
            body.addView(row)
        } else {
            lateinit var btn: Button
            btn = AutoUi.button(ctx, "⬇  Download") {
                btn.isEnabled = false
                btn.alpha = 0.7f
                btn.text = "Downloading…"
                lifecycleScope.launch {
                    val r = AutomationApi.download(ctx, agent) { msg -> btn.text = msg }
                    if (r.isSuccess) {
                        Toast.makeText(ctx, "Downloaded. Added to “My agents”", Toast.LENGTH_SHORT).show()
                        // ডাউনলোড হলে সার্চ ফাঁকা: এজেন্ট আর সার্চে আসে না, "আমার এজেন্ট"-এ দেখায়
                        searchInput.setText("")
                        refresh("")
                    } else {
                        Toast.makeText(ctx, "Download failed: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.alpha = 1f
                        btn.text = "⬇  Download"
                    }
                }
            }
            body.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(body)
        return card
    }

    private fun View.spaced() {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = AutoUi.dp(this@AutomationHubActivity, 6) }
    }
}
