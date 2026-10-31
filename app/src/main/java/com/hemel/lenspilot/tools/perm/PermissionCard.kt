package com.hemel.lenspilot.tools.perm

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.hemel.lenspilot.R
import com.hemel.lenspilot.chat.GlassChat

/**
 * অনুমোদনের গ্লাস কার্ড (অ্যাপ ফোরগ্রাউন্ডে থাকলে)। চেহারা GlassChat-এর কার্ড ([R.drawable.bg_live_glass_card])
 * ও সাদা বোল্ড লেখা পুনর্ব্যবহার করে। বাটন প্রোগ্রাম্যাটিক শেপে — আলাদা রিসোর্স লাগে না।
 * টাইমআউট/বন্ধ করা [PermissionGate] নিজে করে (dialog.dismiss())।
 */
object PermissionCard {

    fun show(
        a: Activity,
        title: String,
        detail: String,
        risk: Risk,
        deadlineWall: Long,
        onDecision: (approve: Boolean, remember: Boolean) -> Unit
    ): Dialog {
        fun dp(v: Int) = GlassChat.dp(a, v)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(false)

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_live_glass_card)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }

        val head = TextView(a).apply {
            text = a.getString(R.string.elora_name) + " · অনুমতি দরকার"
            GlassChat.whiteBold(this, 12f)
            alpha = 0.85f
        }
        card.addView(head)

        val riskTv = TextView(a).apply {
            text = if (risk == Risk.HIGH) "উচ্চ ঝুঁকি — প্রতিবার অনুমোদন লাগবে" else "মাঝারি ঝুঁকি"
            textSize = 12f
            setTextColor(if (risk == Risk.HIGH) Color.parseColor("#FF8A80") else Color.parseColor("#FFD180"))
            setShadowLayer(4f, 0f, 1.5f, 0xB3000000.toInt())
        }
        card.addView(riskTv, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val titleTv = TextView(a).apply {
            text = title
            GlassChat.whiteBold(this, 17f)
        }
        card.addView(titleTv, lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(8) })

        val detailTv = TextView(a).apply {
            text = detail
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setShadowLayer(4f, 0f, 1.5f, 0xB3000000.toInt())
            setTextIsSelectable(false)
        }
        card.addView(detailTv, lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(6) })

        var remember: CheckBox? = null
        if (risk == Risk.MEDIUM) {
            remember = CheckBox(a).apply {
                text = "এই সেশনে আর জিজ্ঞেস করবেন না"
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            card.addView(remember, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        val timer = TextView(a).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            alpha = 0.75f
        }
        card.addView(timer, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun pill(label: String, fill: Int, onClick: () -> Unit): TextView = TextView(a).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(fill) }
            setOnClickListener { onClick() }
        }
        val deny = pill("অস্বীকার", Color.parseColor("#66FFFFFF")) {
            d.dismiss(); onDecision(false, false)
        }
        val ok = pill("অনুমোদন", Color.parseColor("#2E7D32")) {
            val rem = remember?.isChecked == true
            d.dismiss(); onDecision(true, rem)
        }
        row.addView(deny, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        card.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(12) })

        d.setContentView(card)

        // কাউন্টডাউন — প্রতি সেকেন্ডে; ডায়ালগ বন্ধ হলে থামে
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val left = ((deadlineWall - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                timer.text = "সময় বাকি: ${left}s — সময় শেষ হলে অস্বীকার ধরা হবে"
                h.postDelayed(this, 1000L)
            }
        }
        d.setOnDismissListener { h.removeCallbacks(tick) }
        tick.run()

        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.55f)
            // অনুমোদন-কার্ড স্ক্রিনশটে ধরা পড়ার দরকার নেই
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.show()
        return d
    }

    private fun lp(w: Int) = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
}
