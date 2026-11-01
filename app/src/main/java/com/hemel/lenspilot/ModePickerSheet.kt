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

    fun show(ctx: Context, title: String, items: List<Item>, selectedKey: String?, onPick: (String) -> Unit): Dialog {
        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadii = floatArrayOf(
                    dp(ctx, 24).toFloat(), dp(ctx, 24).toFloat(),
                    dp(ctx, 24).toFloat(), dp(ctx, 24).toFloat(), 0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 18))
        }

        // ছোট হ্যান্ডেল
        sheet.addView(View(ctx).apply {
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

            row.setOnClickListener { _ ->
                dlg.dismiss()
                onPick(item.key)
            }
            list.addView(row)
        }
        sheet.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        "multi" -> "Agents"
        else -> "Screen Guide"
    }

    /** [cur] = বর্তমান মোড; লার্নিংয়ে থাকলে Discussion/Browser/Agents/Tools/Automation আসে না (আগের নিয়ম)। */
    fun modes(cur: String): List<ModePickerSheet.Item> {
        val items = mutableListOf<ModePickerSheet.Item>()
        if (cur != "learning") items.add(ModePickerSheet.Item("discussion", "💬", "Discussion", "Simple mode — talk it through, no workflow cards"))
        items.add(ModePickerSheet.Item("chat", "📱", "Screen Guide", "Ask anything, get guidance and workflows"))
        if (cur != "learning") {
            items.add(ModePickerSheet.Item("browser", "🌐", "Automation Browser", "AI works in the in-app browser for you"))
            items.add(ModePickerSheet.Item("multi", "🤝", "Agents", "Several AIs answer together"))
        }
        items.add(ModePickerSheet.Item("learning", "🎓", "Learning", "Lessons, pictures and classes"))
        if (cur != "learning") {
            items.add(ModePickerSheet.Item("tools", "🧰", "Tools", "Open the tools workspace"))
            items.add(ModePickerSheet.Item("automation", "🌍", "Automation", "AI automation of the real world"))
        }
        return items
    }
}
