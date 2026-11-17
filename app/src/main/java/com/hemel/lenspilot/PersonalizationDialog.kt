package com.hemel.lenspilot

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * v78: "Personalization" পূর্ণ-স্ক্রিন — স্মৃতি চালু/বন্ধ, AI যা শিখেছে তার তালিকা (ট্যাপ করে এডিট / পিন / মোছা),
 * নিজে যোগ করা, আর সব মুছে ফেলা। ProfileSheet-এর সাথে একই রঙ-ধাঁচ; কোনো XML বা Manifest লাগে না।
 */
object PersonalizationDialog {

    private const val BG = 0xFFF0F4F9.toInt()
    private const val CARD = 0xFFFFFFFF.toInt()
    private const val TEXT = 0xFF1F1F1F.toInt()
    private const val MUTED = 0xFF5F6368.toInt()
    private const val BLUE = 0xFF0B57D0.toInt()
    private const val RED = 0xFFB3261E.toInt()

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun round(c: Context, fill: Int, r: Int = 16) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(c, r).toFloat()
    }

    private fun label(c: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun show(ctx: Context): Dialog {
        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(ctx, 16), dp(ctx, 30), dp(ctx, 16), dp(ctx, 16))
        }

        // ---- ওপরের সারি: ✕ + শিরোনাম
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(ctx, "✕", 20f, TEXT).apply {
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            setOnClickListener { dlg.dismiss() }
        })
        top.addView(label(ctx, "Personalization", 20f, TEXT, true))
        root.addView(top)

        // ---- চালু/বন্ধ কার্ড
        val toggleCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(ctx, CARD)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(ctx, "Learn from my chats", 16f, TEXT, true))
        col.addView(label(ctx, "Likes, dislikes and wishes from every mode. Kept on this phone.", 12f, MUTED))
        toggleCard.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = PersonalMemory.enabled(ctx)
            setOnCheckedChangeListener { _, on -> PersonalMemory.setEnabled(ctx, on) }
        }
        toggleCard.addView(sw)
        root.addView(toggleCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 14)
        })

        // ---- তালিকা (এখানে স্ক্রল দরকার — স্মৃতি যত বাড়ে)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(ctx, 12) })

        lateinit var refresh: () -> Unit

        fun editor(title: String, initial: String, pinned: Boolean?, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onPin: (() -> Unit)?) {
            val input = EditText(ctx).apply {
                setText(initial)
                setSelection(text.length)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                maxLines = 5
                hint = "One short line"
            }
            val wrap = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
                addView(input)
            }
            val b = AlertDialog.Builder(ctx).setTitle(title).setView(wrap)
                .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()); refresh() }
            if (onDelete != null) b.setNegativeButton("Delete") { _, _ -> onDelete(); refresh() }
            else b.setNegativeButton("Cancel", null)
            if (onPin != null) b.setNeutralButton(if (pinned == true) "Unpin" else "📌 Pin") { _, _ -> onPin(); refresh() }
            b.show()
        }

        refresh = {
            list.removeAllViews()
            val items = PersonalMemory.items(ctx).sortedWith(compareByDescending<PersonalMemory.Item> { it.pinned }.thenByDescending { it.ts })
            if (items.isEmpty()) {
                list.addView(label(ctx, "Nothing remembered yet. It fills in as you chat — or add something yourself.", 14f, MUTED).apply {
                    setPadding(dp(ctx, 6), dp(ctx, 20), dp(ctx, 6), dp(ctx, 20)); gravity = Gravity.CENTER
                })
            }
            for (it in items) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = round(ctx, CARD, 14)
                    setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                    isClickable = true; isFocusable = true
                    setOnClickListener { _ ->
                        editor("Edit", it.text, it.pinned,
                            onSave = { t -> PersonalMemory.update(ctx, it.id, text = t) },
                            onDelete = { PersonalMemory.delete(ctx, it.id) },
                            onPin = { PersonalMemory.update(ctx, it.id, pinned = !it.pinned) })
                    }
                }
                row.addView(label(ctx, PersonalMemory.emoji(it.cat), 18f, TEXT).apply { setPadding(0, 0, dp(ctx, 12), 0) })
                row.addView(label(ctx, it.text, 14f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (it.pinned) row.addView(label(ctx, "📌", 14f, TEXT).apply { setPadding(dp(ctx, 8), 0, 0, 0) })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(ctx, 8)
                })
            }
        }
        refresh()

        // ---- নিচের সারি: যোগ করুন / সব মুছুন
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val add = label(ctx, "＋ Add", 15f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = round(ctx, BLUE, 24)
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
            setOnClickListener {
                editor("Remember this", "", null, onSave = { t -> PersonalMemory.add(ctx, t, "about", pinned = true) }, onDelete = null, onPin = null)
            }
        }
        val clear = label(ctx, "Clear all", 15f, RED, true).apply {
            gravity = Gravity.CENTER
            background = round(ctx, CARD, 24)
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
            setOnClickListener {
                if (PersonalMemory.items(ctx).isEmpty()) return@setOnClickListener
                AlertDialog.Builder(ctx).setTitle("Clear everything the AI remembered?")
                    .setMessage("This can't be undone.")
                    .setPositiveButton("Clear") { _, _ -> PersonalMemory.clearAll(ctx); refresh() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        bottom.addView(add)
        bottom.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(ctx, 12)
        })
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 8)
        })

        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
        return dlg
    }
}
