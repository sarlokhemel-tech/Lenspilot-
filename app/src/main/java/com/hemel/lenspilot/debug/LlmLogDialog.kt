package com.hemel.lenspilot.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * টেস্ট টুল — প্রোফাইল আইকন → "LLM লগ"।
 *
 * সার্ভার প্রতিটা AI কলের হুবহু system prompt + ইনপুট + আউটপুট রাখে (শুধু সুইচ চালু থাকলে)।
 * এখানে সেগুলো তালিকা আকারে দেখা, কপি করা, একটা/সব মুছে ফেলা যায়।
 * ডেটা সার্ভারে (users/{uid}/llm_logs) থাকে — অ্যাপ শুধু দেখায়।
 */
object LlmLogDialog {

    private const val SHOW_LIMIT = 30_000   // একটা ক্ষেত্রের এর বেশি স্ক্রিনে আগেই দেখানো হয় না (কপিতে সবসময় পুরোটা)

    private val timeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

    fun show(
        activity: AppCompatActivity,
        baseUrl: String,
        ensureSession: suspend () -> Result<String>
    ) {
        val ctx: Context = activity
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.text_primary_light)
        val secondary = ContextCompat.getColor(ctx, R.color.text_secondary_light)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
        }

        val headerRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(ctx).apply {
            text = "LLM লগ (টেস্ট)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val recordSwitch = Switch(ctx).apply { text = "রেকর্ড " }
        headerRow.addView(recordSwitch)
        root.addView(headerRow)

        root.addView(TextView(ctx).apply {
            text = "সুইচ চালু থাকলে এরপর থেকে প্রতিটা AI কলে মডেলের কাছে যা গেছে আর যা ফিরেছে — হুবহু — এখানে জমা হয়।"
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(4), 0, dp(8))
        })

        val btnRow = LinearLayout(ctx)
        val refreshBtn = Button(ctx).apply { text = "রিফ্রেশ"; isAllCaps = false }
        val clearBtn = Button(ctx).apply { text = "সব মুছুন"; isAllCaps = false }
        btnRow.addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        val status = TextView(ctx).apply {
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(status)

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        ))

        val dialog = AlertDialog.Builder(ctx).setView(root).setNegativeButton("বন্ধ", null).create()

        var settingSwitch = false

        fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

        lateinit var load: () -> Unit

        fun openDetail(id: String) {
            activity.lifecycleScope.launch {
                ensureSession()
                val res = ApiClient.getAuthed(ctx, baseUrl, "/api/llm-log/item?id=$id")
                val text = res.getOrNull()
                if (text == null) { toast("লগটা আনা যায়নি"); return@launch }
                val d = try { JSONObject(text) } catch (e: Exception) { toast("লগ পড়া যায়নি"); return@launch }
                showDetail(activity, d, baseUrl, ensureSession) { load() }
            }
        }

        fun render(items: org.json.JSONArray) {
            listBox.removeAllViews()
            if (items.length() == 0) {
                status.text = "কোনো লগ নেই।"
                return
            }
            status.text = "${items.length()}টি কল (নতুন আগে) — ট্যাপ করলে পুরো ইনপুট-আউটপুট দেখাবে"
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                }
                val ts = item.optLong("ts_ms")
                val time = if (ts > 0) Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFmt) else ""
                val sys = item.optString("system").ifBlank { "—" }
                row.addView(TextView(ctx).apply {
                    text = "$time · $sys · ${fnLabel(item.optString("fn"))}"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(primary)
                })
                row.addView(TextView(ctx).apply {
                    text = "${endpointLabel(item.optString("path"))} · ${item.optString("provider")}/${item.optString("model")} · " +
                        "${item.optDouble("secs", 0.0)}s · ${item.optInt("tokens")} টোকেন"
                    textSize = 11f
                    setTextColor(secondary)
                })
                val preview = item.optString("preview")
                if (preview.isNotBlank()) {
                    row.addView(TextView(ctx).apply {
                        text = preview
                        textSize = 12f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(primary)
                    })
                }
                val err = item.optString("error")
                if (err.isNotBlank()) {
                    row.addView(TextView(ctx).apply {
                        text = "ত্রুটি: $err"
                        textSize = 11f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.parseColor("#DC2626"))
                    })
                }
                val id = item.optString("id")
                row.setOnClickListener { openDetail(id) }
                listBox.addView(row)
                listBox.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#E2E8F0")) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }

        load = {
            status.text = "লোড হচ্ছে…"
            activity.lifecycleScope.launch {
                val sess = ensureSession()
                if (sess.isFailure) { if (dialog.isShowing) status.text = "লগইন সেশন পাওয়া যায়নি"; return@launch }
                val res = ApiClient.getAuthed(ctx, baseUrl, "/api/llm-log/list?limit=100")
                if (!dialog.isShowing) return@launch
                val text = res.getOrNull()
                if (text == null) { status.text = "লোড করা যায়নি — ইন্টারনেট/সার্ভার দেখুন"; return@launch }
                try {
                    val json = JSONObject(text)
                    settingSwitch = true
                    recordSwitch.isChecked = json.optBoolean("enabled", false)
                    settingSwitch = false
                    render(json.optJSONArray("items") ?: org.json.JSONArray())
                } catch (e: Exception) {
                    status.text = "উত্তর পড়া যায়নি"
                }
            }
        }

        recordSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingSwitch) return@setOnCheckedChangeListener
            activity.lifecycleScope.launch {
                ensureSession()
                val body = JSONObject().put("on", checked).toString()
                val r = ApiClient.callAuthed(ctx, baseUrl, "/api/llm-log/toggle", body)
                if (r.isSuccess) {
                    toast(if (checked) "রেকর্ড চালু — এখন থেকে কলগুলো জমা হবে" else "রেকর্ড বন্ধ")
                } else {
                    toast("সুইচ বদলানো যায়নি")
                    settingSwitch = true; recordSwitch.isChecked = !checked; settingSwitch = false
                }
            }
        }

        refreshBtn.setOnClickListener { load() }
        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("সব লগ মুছবেন?")
                .setMessage("এটা ফেরানো যাবে না।")
                .setNegativeButton("বাতিল", null)
                .setPositiveButton("মুছুন") { _, _ ->
                    activity.lifecycleScope.launch {
                        ensureSession()
                        val r = ApiClient.callAuthed(ctx, baseUrl, "/api/llm-log/delete", JSONObject().put("all", true).toString())
                        toast(if (r.isSuccess) "সব লগ মুছে গেছে" else "মোছা যায়নি")
                        load()
                    }
                }.show()
        }

        dialog.show()
        load()
    }

    private fun showDetail(
        activity: AppCompatActivity,
        d: JSONObject,
        baseUrl: String,
        ensureSession: suspend () -> Result<String>,
        onDeleted: () -> Unit
    ) {
        val ctx: Context = activity
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.text_primary_light)
        val secondary = ContextCompat.getColor(ctx, R.color.text_secondary_light)

        val sysPrompt = d.optString("system_prompt")
        val input = d.optString("input")
        val output = d.optString("output")
        val err = d.optString("error")

        fun copy(label: String, text: String) {
            try {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(ctx, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "কপি করা যায়নি (লেখা অনেক বড়)", Toast.LENGTH_SHORT).show()
            }
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }

        val ts = d.optLong("ts_ms")
        val time = if (ts > 0) Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFmt) else ""
        val extra = d.optJSONObject("extra")
        val extraText = if (extra != null && extra.length() > 0) "\nপ্যারামিটার: $extra" else ""
        box.addView(TextView(ctx).apply {
            text = "$time · সিস্টেম: ${d.optString("system").ifBlank { "—" }} · ${endpointLabel(d.optString("path"))}\n" +
                "${fnLabel(d.optString("fn"))} · ${d.optString("provider")}/${d.optString("model")} · " +
                "${d.optDouble("secs", 0.0)}s · ${d.optInt("tokens")} টোকেন$extraText"
            textSize = 12f
            setTextColor(secondary)
            setTextIsSelectable(true)
        })
        if (err.isNotBlank()) {
            box.addView(TextView(ctx).apply {
                text = "ত্রুটি: $err"
                textSize = 12f
                setTextColor(Color.parseColor("#DC2626"))
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, 0)
            })
        }

        fun section(title: String, content: String) {
            val head = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(4))
            }
            head.addView(TextView(ctx).apply {
                text = "$title  (${content.length} অক্ষর)"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(primary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(Button(ctx).apply {
                text = "কপি"; isAllCaps = false
                setOnClickListener { copy(title, content) }
            })
            box.addView(head)

            val body = TextView(ctx).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(primary)
                setTextIsSelectable(true)
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                text = if (content.isEmpty()) "(খালি)" else content.take(SHOW_LIMIT)
            }
            box.addView(body)
            if (content.length > SHOW_LIMIT) {
                box.addView(Button(ctx).apply {
                    text = "পুরোটা দেখান (আরও ${content.length - SHOW_LIMIT} অক্ষর)"
                    isAllCaps = false
                    setOnClickListener { body.text = content; visibility = View.GONE }
                })
            }
        }

        section("SYSTEM PROMPT", sysPrompt)
        section("INPUT (ইউজার/প্রম্পট অংশ)", input)
        section("OUTPUT (মডেলের উত্তর)", output)

        val scroll = ScrollView(ctx).apply { addView(box) }
        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setNegativeButton("বন্ধ", null)
            .setNeutralButton("মুছুন", null)
            .setPositiveButton("সব কপি", null)
            .create()
        dlg.show()
        // বাটনে চাপলে ডায়ালগ যেন নিজে বন্ধ না হয় (কপি) — তাই এখানে আলাদা listener
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            copy("LLM log", "=== SYSTEM PROMPT ===\n$sysPrompt\n\n=== INPUT ===\n$input\n\n=== OUTPUT ===\n$output")
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val id = d.optString("id")
            activity.lifecycleScope.launch {
                ensureSession()
                val r = ApiClient.callAuthed(ctx, baseUrl, "/api/llm-log/delete", JSONObject().put("id", id).toString())
                if (r.isSuccess) {
                    Toast.makeText(ctx, "মুছে গেছে", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onDeleted()
                } else {
                    Toast.makeText(ctx, "মোছা যায়নি", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fnLabel(fn: String): String = when (fn) {
        "stream_gemini_raw" -> "Gemini (স্ট্রিম)"
        "stream_groq_raw" -> "Groq (স্ট্রিম)"
        "stream_groq_chat_raw" -> "Groq চ্যাট (স্ট্রিম)"
        "call_gemini" -> "Gemini"
        "call_groq_chat" -> "Groq চ্যাট"
        "_supervisor_llm" -> "Supervisor/Router"
        else -> fn
    }

    private fun endpointLabel(path: String): String =
        path.removePrefix("/api/").ifBlank { "ব্যাকগ্রাউন্ড" }
}
