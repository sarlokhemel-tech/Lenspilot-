package com.hemel.lenspilot.multiagent

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * মাল্টি-এজেন্ট মোডের "Resources" পেজ — উত্তরের নিচের Resources বাটন থেকে খোলে
 * (MainActivity.openMultiAgentResources)।
 *
 * দেখায়:
 *  - ইউজারের প্রশ্ন + কোন মডেল চূড়ান্ত উত্তরটা লিখেছে (ফলব্যাক হলে সেটাও)
 *  - প্রতিটা এজেন্টের (Agent A = Gemini, Agent B = Groq GPT-OSS) আলাদা কার্ড:
 *      • সার্চের ধরন (Google Search / Web search / Wikipedia ফলব্যাক / সার্চ ছাড়া) ও সময়
 *      • কোন কোন কোয়েরিতে সার্চ করেছে
 *      • কোন কোন ওয়েবসাইট থেকে তথ্য নিয়েছে (ট্যাপ করলে ব্রাউজারে খোলে)
 *      • এজেন্টের নিজের পুরো উত্তর (ইউজার যা পেয়েছে সেই চূড়ান্ত উত্তর নয়)
 *
 * ডেটা সার্ভারের done.result["multi_agent"] থেকে আসে, MainActivity সেটা JSON স্ট্রিং
 * হিসেবে EXTRA_JSON-এ পাঠায় (সাথে "question")। কোনো নেটওয়ার্ক কল নেই।
 */
class MultiAgentResourcesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JSON = "extra_multi_agent_json"
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_agent_resources)
        findViewById<ImageButton>(R.id.maBackButton).setOnClickListener { finish() }
        content = findViewById(R.id.maContent)

        val root = runCatching { JSONObject(intent.getStringExtra(EXTRA_JSON).orEmpty()) }.getOrNull()
        val agents = root?.optJSONArray("agents")
        if (root == null || agents == null || agents.length() == 0) {
            addBody(getString(R.string.ma_no_data), muted = true)
            return
        }

        val question = root.optString("question", "").trim()
        if (question.isNotEmpty()) {
            addLabel(getString(R.string.ma_question_label), topMargin = 4)
            addBody(question)
        }

        val synth = prettyModel(root.optString("synth_model", ""))
        if (synth.isNotEmpty()) {
            val resId = if (root.optBoolean("synth_fallback", false)) R.string.ma_final_by_fallback else R.string.ma_final_by
            addBody(getString(resId, synth), muted = true, topMargin = 8)
        }

        for (i in 0 until agents.length()) {
            val a = agents.optJSONObject(i) ?: continue
            addAgentCard(a)
        }
    }

    // ---------------------------------------------------------------- cards

    private fun addAgentCard(a: JSONObject) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }

        val ok = a.optBoolean("ok", false)

        // ---- শিরোনাম: "Agent A" + অবস্থা-চিপ
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = a.optString("label", "Agent")
            setTextColor(color(R.color.text_primary_light))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = getString(if (ok) R.string.ma_status_ok else R.string.ma_status_failed)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(if (ok) R.color.confirm_success else R.color.danger_destructive))
            setBackgroundResource(R.drawable.bg_source_chip)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        card.addView(header)

        // ---- মডেল · সার্চের ধরন · সময়
        val seconds = a.optDouble("seconds", 0.0)
        val meta = listOf(
            prettyModel(a.optString("model", "")),
            modeLabel(a.optString("search_mode", "")),
            if (seconds > 0) String.format("%.1fs", seconds) else ""
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
        card.addView(smallText(meta, topMargin = 4))

        // ---- ব্যর্থ হলে কারণ
        val err = a.optString("error", "")
        if (!ok && err.isNotBlank()) {
            card.addView(smallText(err, topMargin = 8, colorRes = R.color.danger_destructive))
        }

        // ---- সার্চ কোয়েরি
        val queries = strings(a.optJSONArray("queries"))
        if (queries.isNotEmpty()) {
            card.addView(sectionLabel(getString(R.string.ma_queries_label)))
            for (q in queries) card.addView(bodyText("🔎  $q", topMargin = 3))
        }

        // ---- ওয়েবসাইট/সূত্র (ট্যাপ করলে খোলে)
        card.addView(sectionLabel(getString(R.string.ma_sources_label)))
        val sources = a.optJSONArray("sources")
        if (sources == null || sources.length() == 0) {
            card.addView(smallText(getString(R.string.ma_no_sources), topMargin = 3))
        } else {
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                card.addView(sourceRow(s))
            }
        }

        // ---- এই এজেন্টের নিজের উত্তর
        val answer = a.optString("answer", "").trim()
        if (answer.isNotEmpty()) {
            card.addView(sectionLabel(getString(R.string.ma_answer_label)))
            card.addView(bodyText(answer, topMargin = 4).apply { setTextIsSelectable(true) })
        }

        content.addView(card)
    }

    private fun sourceRow(s: JSONObject): View {
        val url = s.optString("url", "")
        val site = s.optString("site", "")
        val title = s.optString("title", "").ifBlank { site }
        val snippet = s.optString("snippet", "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            if (url.startsWith("http")) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openUrl(url) }
            }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(if (url.startsWith("http")) R.color.primary_action else R.color.text_primary_light))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (site.isNotBlank() && !title.equals(site, ignoreCase = true)) {
            box.addView(smallText(site, topMargin = 1))
        }
        if (snippet.isNotBlank()) {
            box.addView(smallText(snippet, topMargin = 2).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        return box
    }

    // -------------------------------------------------------------- helpers

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun prettyModel(m: String): String = when {
        m.isBlank() -> ""
        m.startsWith("gemini-") -> "Gemini " + m.removePrefix("gemini-").split("-")
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        m.contains("gpt-oss-120b") -> "Groq GPT-OSS 120B"
        m.contains("gpt-oss-20b") -> "Groq GPT-OSS 20B"
        else -> m
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "google_search" -> getString(R.string.ma_mode_google_search)
        "browser_search" -> getString(R.string.ma_mode_browser_search)
        "wikipedia" -> getString(R.string.ma_mode_wikipedia)
        "none" -> getString(R.string.ma_mode_none)
        else -> ""
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun smallText(t: String, topMargin: Int = 0, colorRes: Int = R.color.text_secondary_light) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(color(colorRes))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }
    }

    private fun bodyText(t: String, topMargin: Int = 0) = TextView(this).apply {
        text = t
        textSize = 14f
        setLineSpacing(0f, 1.15f)
        setTextColor(color(R.color.text_primary_light))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 11f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.text_muted_light))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun addLabel(t: String, topMargin: Int = 0) {
        content.addView(sectionLabel(t).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(topMargin) })
    }

    private fun addBody(t: String, muted: Boolean = false, topMargin: Int = 4) {
        content.addView(bodyText(t, topMargin).apply {
            if (muted) {
                setTextColor(color(R.color.text_secondary_light))
                textSize = 13f
            }
        })
    }
}
