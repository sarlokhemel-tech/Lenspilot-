package com.hemel.lenspilot

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v73: Claude-স্টাইল নিচ থেকে ওঠা তালিকা — মডেল (সিস্টেম) আর মোড বাছাইয়ের জন্য।
 * প্রতিটা সারিতে: ইমোজি-আইকন, নাম, ছোট বিবরণ, আর বর্তমানটার ডানে ✓।
 */
object ModePickerSheet {

    class Item(val key: String, val emoji: String, val label: String, val sub: String? = null)

    private const val BG = 0xFFFFFFFF.toInt()
    private const val TEXT = 0xFF1F1F1F.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()
    private const val SELECTED_BG = 0xFFF3F0EA.toInt()
    private const val CHECK = 0xFF2563EB.toInt()

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /**
     * v81: তালিকার ভিউটাই আলাদা — মেইন চ্যাটের Dialog ([show]) আর বাবল-চ্যাটের ওভারলে (যেখানে Dialog খোলা যায় না) হুবহু এই এক ভিউ ব্যবহার করে।
     * [floating] = true হলে চারদিকে গোল-কোণ (বাবলে কম্পোজারের ওপর ভাসে), হ্যান্ডেল থাকে না।
     */
    fun buildSheet(ctx: Context, title: String, items: List<Item>, selectedKey: String?, floating: Boolean, onRowClick: (String) -> Unit): View {
        val r = dp(ctx, 24).toFloat()
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadii = if (floating) floatArrayOf(r, r, r, r, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                if (floating) setStroke(dp(ctx, 1), 0xFFE2E8F0.toInt())
            }
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, if (floating) 10 else 18))
            isClickable = true   // ওভারলেতে ফাঁকা জায়গায় ছুঁলে নিচের ভিউতে না যায়
        }

        // ছোট হ্যান্ডেল
        if (!floating) sheet.addView(View(ctx).apply {
            background = GradientDrawable().apply { setColor(0xFFD0D0D0.toInt()); cornerRadius = dp(ctx, 3).toFloat() }
        }, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(ctx, 10)
        })

        sheet.addView(TextView(ctx).apply {
            text = title; textSize = 13f; setTextColor(MUTED)
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 8))
        })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) {
            val selected = item.key == selectedKey
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                if (selected) background = GradientDrawable().apply {
                    setColor(SELECTED_BG); cornerRadius = dp(ctx, 16).toFloat()
                }
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(ctx).apply {
                text = item.emoji; textSize = 20f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = item.label; textSize = 16f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD)
            })
            if (!item.sub.isNullOrBlank()) col.addView(TextView(ctx).apply {
                text = item.sub; textSize = 13f; setTextColor(MUTED)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 8)
            })

            if (selected) row.addView(TextView(ctx).apply {
                text = "✓"; textSize = 18f; setTextColor(CHECK); setTypeface(typeface, Typeface.BOLD)
            })

            row.setOnClickListener { onRowClick(item.key) }
            list.addView(row)
        }
        sheet.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sheet
    }

    fun show(ctx: Context, title: String, items: List<Item>, selectedKey: String?, onPick: (String) -> Unit): Dialog {
        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = buildSheet(ctx, title, items, selectedKey, floating = false) { key ->
            dlg.dismiss()
            onPick(key)
        }

        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.35f)
        }
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
        return dlg
    }
}


/**
 * v79: মডেল ও মোডের তালিকা এক জায়গায় — মেইন চ্যাটের কম্পোজার আর বাবলের গ্লাস চ্যাট দুটোই এটা ব্যবহার করে,
 * তাই দুই জায়গায় নাম/ক্রম/বিবরণ সবসময় হুবহু এক।
 */
object ComposerOptions {
    /** v81: "+" মেনু — মেইন চ্যাট আর বাবল-চ্যাটে একই তিনটি (আগে ভিডিও ছিল না)। */
    val attachItems: List<ModePickerSheet.Item> = listOf(
        ModePickerSheet.Item("image", "🖼️", "Photo", "From gallery"),
        ModePickerSheet.Item("video", "🎬", "Video", "From gallery — the AI looks at frames from it"),
        ModePickerSheet.Item("file", "📎", "File", "PDF, Word, Excel, PowerPoint, text, code…")
    )

    val systems: List<ModePickerSheet.Item> = listOf(
        ModePickerSheet.Item("super_lite", "⚡", "Super Lite", "Plans the whole task once, then runs it"),
        ModePickerSheet.Item("super_1_2", "✨", "Super 1.2", "Default — re-checks every screen"),
        ModePickerSheet.Item("ela_1st", "🧭", "ELA 1st"),
        ModePickerSheet.Item("ela_4n", "🔎", "ELA 4N")
    )

    fun systemLabel(key: String): String = when (key) {
        "super_lite" -> "Lite"
        "ela_1st" -> "ELA 1st"
        "ela_4n" -> "ELA 4N"
        else -> "Super 1.2"
    }

    fun modeLabel(key: String): String = when (key) {
        "learning" -> "Learning"
        "browser" -> "Auto Browser"
        "discussion" -> "Discussion"
        "multi" -> "Multi Search"   // v91: চিপে ছোট নাম — তালিকায় পুরো নাম "Multi Agent Search"
        else -> "Screen Guide"
    }

    /** v82: সব মোড সব সময় তালিকায় — লার্নিংয়ে থাকলেও Discussion/Browser/Agents/Tools/Automation বাছা যায় ([cur] শুধু পরিচিতির জন্য)। */
    @Suppress("UNUSED_PARAMETER")
    fun modes(cur: String): List<ModePickerSheet.Item> = listOf(
        ModePickerSheet.Item("discussion", "💬", "Discussion", "Simple mode — talk it through, no workflow cards"),
        ModePickerSheet.Item("chat", "📱", "Screen Guide", "Ask anything, get guidance and workflows"),
        ModePickerSheet.Item("browser", "🌐", "Automation Browser", "AI works in the in-app browser for you"),
        ModePickerSheet.Item("multi", "🤝", "Multi Agent Search", "Several AIs search the web and answer together"),
        ModePickerSheet.Item("learning", "🎓", "Learning", "Lessons, pictures and classes"),
        // v130: "Automation — AI automation of the real world" মোড থেকে বাদ; এখন সাইড-বারে "RealWorld Automator" লিংক।
        ModePickerSheet.Item("tools", "🧰", "Tools", "Open the tools workspace")
    )
}
