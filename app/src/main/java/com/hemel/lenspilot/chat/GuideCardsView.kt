package com.hemel.lenspilot.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hemel.lenspilot.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * আলোচনা মোডের "স্ক্রিন গাইড" কার্ড রেন্ডারার (GPT-স্টাইল)। সার্ভারের result.guide (app.py →
 * _workflow_plan_discussion) পড়ে একটা LinearLayout-এর ভেতরে কার্ড বানায়:
 *
 *  • steps  — শিরোনাম + নম্বরসহ ধাপ। ধাপের বাঁ পাশে মার্ক-করা ছোট ছবি (লাল বৃত্ত + তীর + নম্বর,
 *             [MarkedImageView]) থাকলে চাপলে ফুলস্ক্রিন (সব মার্ক, জুম)। কমান্ড থাকলে ধূসর কোড-বক্স + "কপি করো"।
 *  • choice — প্রশ্ন + রেডিও অপশন + "এগিয়ে যাই"; চাপলে [onChoice] (ক্লায়েন্ট সেটা ইউজারের মেসেজ হিসেবে
 *             পাঠায়, উত্তর AI-র কাছে ফেরে)। উত্তর দেওয়া হয়ে গেলে কার্ড লক হয়ে নির্বাচনটা দেখায়।
 *
 * ছবি: guide.images-এর base64 ("r1"… সার্ভারের রেফারেন্স ছবি; "user" = ইউজারের নিজের স্ক্রিনশটের ছোট কপি,
 * MainActivity বসায়)। কোনো ধাপে ছবি/মার্ক না থাকলে ওটা শুধু লেখা — ভুল ছবি কখনো বানানো হয় না।
 */
object GuideCardsView {

    private val bitmapCache = LruCache<String, Bitmap>(12)
    /** রিসাইকেল/রিবাইন্ডে অনির্বাচিত রেডিও-পছন্দ হারিয়ে না যেতে (key = গাইডের হ্যাশ + কার্ড নম্বর)। */
    private val pendingSelection = HashMap<String, Int>()

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    private fun dpi(ctx: Context, v: Float) = dp(ctx, v).toInt()

    private fun JSONObject.str(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").trim().ifEmpty { null }
    }

    private fun decodeB64(b64: String): Bitmap? {
        val key = "${b64.length}:${b64.hashCode()}"
        bitmapCache.get(key)?.let { return it }
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmapCache.put(key, bmp)
        return bmp
    }

    private fun parseBox(arr: JSONArray?): IntArray? {
        if (arr == null || arr.length() != 4) return null
        return IntArray(4) { arr.optInt(it, -1) }.takeIf { b -> b.all { it in 0..1000 } && b[2] > b[0] && b[3] > b[1] }
    }

    // ------------------------------------------------------------------ render

    fun render(container: LinearLayout, guide: JSONObject, onChoice: (cardIndex: Int, option: String) -> Unit) {
        container.removeAllViews()
        val ctx = container.context
        val cards = guide.optJSONArray("cards") ?: return
        val images = guide.optJSONObject("images") ?: JSONObject()
        val stateKey = guide.toString().hashCode().toString()
        for (ci in 0 until cards.length()) {
            val c = cards.optJSONObject(ci) ?: continue
            when (c.optString("type")) {
                "steps" -> container.addView(buildStepsCard(ctx, cards, c, images))
                "choice" -> container.addView(buildChoiceCard(ctx, c, ci, stateKey, onChoice))
                "info" -> container.addView(buildInfoCard(ctx, c))
            }
        }
    }

    // ------------------------------------------------------------------ info card (v54: ChatGPT-স্টাইল আইটেম-তালিকা)

    private val infoIcons = mapOf(
        "bolt" to "⚡", "brain" to "🧠", "link" to "🔗", "rocket" to "🚀", "shield" to "🛡️", "globe" to "🌍",
        "star" to "⭐", "idea" to "💡", "gear" to "⚙️", "chart" to "📈", "people" to "👥", "book" to "📘",
        "heart" to "❤️", "lock" to "🔒", "search" to "🔍", "check" to "✅", "warning" to "⚠️", "money" to "💰",
        "clock" to "⏱️", "tool" to "🛠️"
    )

    /** heading + প্রতি আইটেমে: বাঁয়ে গোল-কোণা আইকন-টাইল, ডানে বোল্ড শিরোনাম, ধূসর সাব-লাইন, বুলেট। ছবি আনা হয় না — আইকন সার্ভারের নির্দিষ্ট তালিকা থেকে। */
    private fun buildInfoCard(ctx: Context, card: JSONObject): View {
        val items = card.optJSONArray("items") ?: return View(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(ctx, 12f) }
        }
        card.str("heading")?.let { h ->
            col.addView(TextView(ctx).apply {
                text = h
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            })
        }
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val title = it.str("title") ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dpi(ctx, 14f) }
            }
            row.addView(TextView(ctx).apply {
                text = infoIcons[it.optString("icon")] ?: "💡"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.parseColor("#F1F2F4")); cornerRadius = dp(ctx, 14f) }
                layoutParams = LinearLayout.LayoutParams(dpi(ctx, 52f), dpi(ctx, 52f)).apply { marginEnd = dpi(ctx, 12f) }
            })
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            body.addView(TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(ctx, 2f), 1f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            })
            it.str("sub")?.let { sub ->
                body.addView(TextView(ctx).apply {
                    text = sub
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_light))
                    setPadding(0, dpi(ctx, 2f), 0, 0)
                })
            }
            val pts = it.optJSONArray("points")
            if (pts != null) for (p in 0 until pts.length()) {
                val t = pts.optString(p, "").trim().ifEmpty { null } ?: continue
                body.addView(TextView(ctx).apply {
                    text = "•  $t"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setLineSpacing(dp(ctx, 2f), 1f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
                    setPadding(0, dpi(ctx, 5f), 0, 0)
                })
            }
            row.addView(body)
            col.addView(row)
        }
        return col
    }

    // ------------------------------------------------------------------ steps card

    private fun buildStepsCard(ctx: Context, allCards: JSONArray, card: JSONObject, images: JSONObject): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(ctx, 10f) }
        }
        card.str("title")?.let { title ->
            col.addView(TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            })
        }
        val steps = card.optJSONArray("steps") ?: JSONArray()

        // রেফারেন্স (ইন্টারনেটের) ছবি থাকলে একবার সততার নোট
        var hasRef = false
        for (i in 0 until steps.length()) {
            val id = steps.optJSONObject(i)?.str("image_id")
            if (id != null && id != "user" && parseBox(steps.optJSONObject(i)?.optJSONArray("mark")) != null) hasRef = true
        }
        if (hasRef) {
            col.addView(TextView(ctx).apply {
                text = "The images are examples from the internet and may look slightly different on your phone."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_light))
                setPadding(0, dpi(ctx, 2f), 0, 0)
            })
        }

        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            val n = s.optInt("n", i + 1)
            val stepText = s.str("text") ?: continue
            val stepBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dpi(ctx, 14f) }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            // ছবি (থাকলে)
            val imageId = s.str("image_id")
            val box = parseBox(s.optJSONArray("mark"))
            val b64 = imageId?.let { images.optJSONObject(it)?.str("b64") }
            val bmp = b64?.let { decodeB64(it) }
            if (bmp != null && box != null && imageId != null) {
                val thumb = MarkedImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dpi(ctx, 104f), dpi(ctx, 104f)).apply { marginEnd = dpi(ctx, 12f) }
                    setCornerRadiusDp(14f)
                    setContent(bmp, listOf(MarkedImageView.Mark(n, box)), MarkedImageView.Mode.FOCUS)
                    contentDescription = "Marked image, step $n"
                    setOnClickListener {
                        val marks = collectMarks(allCards, imageId)
                        val src = images.optJSONObject(imageId)?.str("src")
                        val caption = if (imageId == "user") "Your screenshot. See the red marks."
                        else "Example image" + (src?.let { " ($it)" } ?: "") + " (may look slightly different on your phone)"
                        showFullscreen(ctx, bmp, marks, "$n. $stepText\n$caption")
                    }
                }
                row.addView(thumb)
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                this.text = "$n. $stepText"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(ctx, 3f), 1f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            })
            s.str("detail")?.let { d ->
                textCol.addView(TextView(ctx).apply {
                    this.text = d
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(dp(ctx, 3f), 1f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_light))
                    setPadding(0, dpi(ctx, 4f), 0, 0)
                })
            }
            row.addView(textCol)
            stepBox.addView(row)

            s.str("code")?.let { code -> stepBox.addView(buildCodeBox(ctx, code)) }
            col.addView(stepBox)
        }
        return col
    }

    private fun collectMarks(allCards: JSONArray, imageId: String): List<MarkedImageView.Mark> {
        val out = ArrayList<MarkedImageView.Mark>()
        for (ci in 0 until allCards.length()) {
            val c = allCards.optJSONObject(ci) ?: continue
            if (c.optString("type") != "steps") continue
            val steps = c.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                if (s.str("image_id") != imageId) continue
                val box = parseBox(s.optJSONArray("mark")) ?: continue
                out.add(MarkedImageView.Mark(s.optInt("n", i + 1), box))
            }
        }
        return out
    }

    private fun buildCodeBox(ctx: Context, code: String): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(ctx, 10f) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(ctx, 16f)
                setStroke(dpi(ctx, 1f), ContextCompat.getColor(ctx, R.color.border_light))
            }
            setPadding(dpi(ctx, 12f), dpi(ctx, 12f), dpi(ctx, 12f), dpi(ctx, 12f))
        }
        wrap.addView(TextView(ctx).apply {
            text = code
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            setTextIsSelectable(true)
        })
        wrap.addView(TextView(ctx).apply {
            text = "Copy"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(ctx, 44f))
                .apply { topMargin = dpi(ctx, 10f) }
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(ctx, 22f)
                setStroke(dpi(ctx, 1f), ContextCompat.getColor(ctx, R.color.text_muted_light))
            }
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_copy, 0)
            compoundDrawablePadding = dpi(ctx, 8f)
            setOnClickListener { copyToClipboard(ctx, code) }
        })
        return wrap
    }

    // ------------------------------------------------------------------ choice card

    private fun buildChoiceCard(
        ctx: Context, card: JSONObject, cardIndex: Int, stateKey: String,
        onChoice: (cardIndex: Int, option: String) -> Unit
    ): View {
        val question = card.str("question") ?: return View(ctx)
        val optsArr = card.optJSONArray("options") ?: return View(ctx)
        val options = (0 until optsArr.length()).mapNotNull { optsArr.optString(it, "").trim().ifEmpty { null } }
        if (options.size < 2) return View(ctx)
        val answered = card.str("selected")
        val pendKey = "$stateKey:$cardIndex"

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(ctx, 14f) }
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(ctx, 22f)
                setStroke(dpi(ctx, 1f), ContextCompat.getColor(ctx, R.color.border_light))
            }
            setPadding(dpi(ctx, 16f), dpi(ctx, 16f), dpi(ctx, 16f), dpi(ctx, 16f))
        }
        wrap.addView(TextView(ctx).apply {
            text = question
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(ctx, 3f), 1f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
        })

        val dark = Color.parseColor("#111827")
        val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dpi(ctx, 6f), 0, dpi(ctx, 6f))
        }
        val radios = ArrayList<RadioButton>()
        options.forEachIndexed { idx, opt ->
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = opt
                tag = idx
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_light))
                buttonTintList = ColorStateList.valueOf(dark)
                setLineSpacing(dp(ctx, 2f), 1f)
                setPadding(dpi(ctx, 8f), dpi(ctx, 10f), dpi(ctx, 4f), dpi(ctx, 10f))
                layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            group.addView(rb)
            radios.add(rb)
        }
        wrap.addView(group)

        val button = TextView(ctx).apply {
            text = "Continue"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(ctx, 50f))
                .apply { topMargin = dpi(ctx, 6f) }
            background = GradientDrawable().apply { setColor(dark); cornerRadius = dp(ctx, 25f) }
        }

        if (answered != null) {
            // উত্তর দেওয়া হয়ে গেছে — লক
            radios.forEach { rb ->
                rb.isEnabled = false
                if (rb.text.toString() == answered) rb.isChecked = true
            }
            button.text = "✓ Reply sent"
            button.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_light))
            button.background = GradientDrawable().apply { setColor(Color.parseColor("#F1F5F9")); cornerRadius = dp(ctx, 25f) }
            button.isEnabled = false
            wrap.alpha = 0.92f
        } else {
            fun refreshButton() {
                val on = group.checkedRadioButtonId != -1
                button.isEnabled = on
                button.alpha = if (on) 1f else 0.38f
            }
            pendingSelection[pendKey]?.let { idx -> radios.getOrNull(idx)?.isChecked = true }
            refreshButton()
            group.setOnCheckedChangeListener { g, checkedId ->
                val rb = g.findViewById<RadioButton>(checkedId)
                (rb?.tag as? Int)?.let { pendingSelection[pendKey] = it }
                refreshButton()
            }
            button.setOnClickListener {
                val rb = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return@setOnClickListener
                button.isEnabled = false // ডাবল-ট্যাপে দু'বার না যায়
                pendingSelection.remove(pendKey)
                onChoice(cardIndex, rb.text.toString())
            }
        }
        wrap.addView(button)
        return wrap
    }

    // ------------------------------------------------------------------ fullscreen

    private fun showFullscreen(ctx: Context, bmp: Bitmap, marks: List<MarkedImageView.Mark>, caption: String) {
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.parseColor("#0B0F14")) }
        root.addView(MarkedImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setContent(bmp, marks, MarkedImageView.Mode.FIT)
        })
        root.addView(TextView(ctx).apply {
            text = caption
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 3f), 1f)
            setPadding(dpi(ctx, 16f), dpi(ctx, 12f), dpi(ctx, 16f), dpi(ctx, 20f))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        })
        root.addView(TextView(ctx).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(150, 40, 40, 40)) }
            layoutParams = FrameLayout.LayoutParams(dpi(ctx, 44f), dpi(ctx, 44f), Gravity.TOP or Gravity.END)
                .apply { topMargin = dpi(ctx, 36f); marginEnd = dpi(ctx, 16f) }
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.show()
    }

    // ------------------------------------------------------------------ helpers

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Lenspilot", text))
        Toast.makeText(ctx, ctx.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
    }

    /** AI-কে পরের মেসেজে প্রসঙ্গ দিতে — আগের কার্ডে কী দেখানো হয়েছিল তার ছোট সারাংশ
     * (সার্ভার প্রতি-টার্নে ~৭০০ অক্ষর কাটে, তাই সংক্ষিপ্ত রাখা)। */
    fun summarize(guideJson: String): String {
        val g = runCatching { JSONObject(guideJson) }.getOrNull() ?: return ""
        val cards = g.optJSONArray("cards") ?: return ""
        val sb = StringBuilder("[Guide card")
        for (ci in 0 until cards.length()) {
            val c = cards.optJSONObject(ci) ?: continue
            when (c.optString("type")) {
                "steps" -> {
                    val steps = c.optJSONArray("steps") ?: continue
                    sb.append(" | Steps: ")
                    for (i in 0 until steps.length()) {
                        val s = steps.optJSONObject(i) ?: continue
                        if (i > 0) sb.append("; ")
                        sb.append(s.optInt("n", i + 1)).append(") ").append(s.str("text")?.take(70) ?: "")
                    }
                }
                "info" -> {
                    val its = c.optJSONArray("items") ?: continue
                    sb.append(" | Items: ").append((0 until its.length()).mapNotNull { its.optJSONObject(it)?.str("title")?.take(40) }.joinToString("; "))
                }
                "choice" -> {
                    val opts = c.optJSONArray("options")
                    sb.append(" | Question: ").append(c.str("question")?.take(80) ?: "").append(" Options: ")
                    if (opts != null) sb.append((0 until opts.length()).joinToString(" / ") { opts.optString(it).take(40) })
                }
            }
        }
        sb.append("]")
        return sb.toString().take(520)
    }
}
