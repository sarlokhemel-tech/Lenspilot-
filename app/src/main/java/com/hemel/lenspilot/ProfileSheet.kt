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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v59: প্রোফাইল আইকনে চাপলে Gemini-র অ্যাকাউন্ট-প্যানেলের মতো সাজানো পূর্ণ-স্ক্রিন প্রোফাইল।
 * ওপরে ইমেইল + ✕, মাঝে নামের অক্ষরের গোল অবতার, "Hi, নাম!", Manage account বাটন, Switch account সারি,
 * টোকেন কার্ড; তার নিচে গ্রুপ করা সব অপশন (ইমোজি আইকন + নাম + ›)।
 */
object ProfileSheet {

    class Item(val emoji: String, val label: String, val sub: String? = null, val danger: Boolean = false, val onClick: () -> Unit)
    class Group(val title: String?, val items: List<Item>)

    private const val BG = 0xFFF0F4F9.toInt()
    private const val TEXT = 0xFF1F1F1F.toInt()
    private const val MUTED = 0xFF5F6368.toInt()
    private const val BLUE = 0xFF0B57D0.toInt()
    private val AVATAR_COLORS = intArrayOf(0xFF689F38.toInt(), 0xFF1E6BFF.toInt(), 0xFFD84315.toInt(), 0xFF6A1B9A.toInt(), 0xFF00897B.toInt(), 0xFFC2185B.toInt())

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun pill(c: Context, fill: Int, stroke: Int? = null, radiusDp: Int = 28) = GradientDrawable().apply {
        cornerRadius = dp(c, radiusDp).toFloat(); setColor(fill)
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    private fun tv(c: Context, text: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun show(
        ctx: Context,
        email: String,
        displayName: String?,
        tokensLine: String,
        groups: List<Group>,
        onManageAccount: () -> Unit,
        onSwitchAccount: () -> Unit,
        onTokens: () -> Unit
    ): Dialog {
        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val first = (displayName?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: email.substringBefore("@")).replaceFirstChar { it.uppercase() }
        val initial = first.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val avatarColor = AVATAR_COLORS[(email.hashCode().and(0x7fffffff)) % AVATAR_COLORS.size]

        val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 34), dp(ctx, 16), dp(ctx, 28))
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- ইমেইল + ✕
        val top = FrameLayout(ctx)
        top.addView(tv(ctx, email, 17f, TEXT).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(ctx, 40); marginEnd = dp(ctx, 40); gravity = Gravity.CENTER_VERTICAL
            })
        top.addView(tv(ctx, "✕", 22f, TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dlg.dismiss() }
        }, FrameLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- অবতার + Hi, নাম!
        col.addView(TextView(ctx).apply {
            text = initial; textSize = 52f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(avatarColor) }
        }, LinearLayout.LayoutParams(dp(ctx, 112), dp(ctx, 112)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(ctx, 18) })
        col.addView(tv(ctx, "Hi, $first!", 28f, TEXT).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 14) })

        // ---- Manage your Google Account
        col.addView(tv(ctx, "Manage your Google Account", 16f, BLUE, true).apply {
            gravity = Gravity.CENTER
            background = pill(ctx, Color.TRANSPARENT, 0xFF747775.toInt(), 28)
            setPadding(dp(ctx, 24), dp(ctx, 14), dp(ctx, 24), dp(ctx, 14))
            setOnClickListener { dlg.dismiss(); onManageAccount() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(ctx, 16)
        })

        // ---- Switch account
        val sw = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(ctx, Color.WHITE, null, 32)
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            setOnClickListener { dlg.dismiss(); onSwitchAccount() }
        }
        sw.addView(tv(ctx, "Switch account", 17f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sw.addView(TextView(ctx).apply {
            text = initial; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(avatarColor) }
        }, LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38)).apply { marginEnd = dp(ctx, 10) })
        sw.addView(tv(ctx, "⌄", 22f, TEXT).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFEEF1F6.toInt()) }
        }, LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38)))
        col.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 22) })

        // ---- টোকেন কার্ড
        val tok = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(ctx, Color.WHITE, null, 32)
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
            setOnClickListener { dlg.dismiss(); onTokens() }
        }
        tok.addView(tv(ctx, "🪙", 22f, TEXT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 14) })
        val tokCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tokCol.addView(tv(ctx, "Tokens", 16f, TEXT))
        tokCol.addView(tv(ctx, tokensLine, 13f, MUTED))
        tok.addView(tokCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tok.addView(tv(ctx, "Get more ›", 14f, BLUE, true))
        col.addView(tok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })

        // ---- অপশনের গ্রুপগুলো
        groups.forEach { g ->
            if (!g.title.isNullOrBlank()) {
                col.addView(tv(ctx, g.title, 15f, MUTED).apply { setPadding(dp(ctx, 8), 0, 0, 0) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(ctx, 24); bottomMargin = dp(ctx, 8)
                    })
            }
            val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            g.items.forEachIndexed { i, item ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 16), dp(ctx, 18))
                    // গ্রুপের ওপরের কোণ গোল, নিচের কোণ গোল, মাঝেরগুলো প্রায় সোজা (স্ক্রিনশটের মতো)
                    val big = dp(ctx, 28).toFloat(); val small = dp(ctx, 5).toFloat()
                    val tc = if (i == 0) big else small
                    val bc = if (i == g.items.lastIndex) big else small
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadii = floatArrayOf(tc, tc, tc, tc, bc, bc, bc, bc)
                    }
                    isClickable = true; isFocusable = true
                    setOnClickListener { dlg.dismiss(); item.onClick() }
                }
                row.addView(tv(ctx, item.emoji, 22f, TEXT).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(ctx, 34), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 14) })
                val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                t.addView(tv(ctx, item.label, 17f, if (item.danger) 0xFFB3261E.toInt() else TEXT))
                if (!item.sub.isNullOrBlank()) t.addView(tv(ctx, item.sub, 12.5f, MUTED))
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(tv(ctx, "›", 22f, MUTED))
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(ctx, 2)
                })
            }
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(BG))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
        return dlg
    }
}
