package com.hemel.lenspilot.browser

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.security.SecretVault

/**
 * গোপন ভল্টের নেটিভ ডায়ালগ। এখানে লেখা/পেস্ট করা মান সরাসরি Keystore-এনক্রিপ্টেড ভল্টে যায় —
 * AI, সার্ভার, লগ বা চ্যাট-ইতিহাসে কখনো নয়। ডায়ালগ FLAG_SECURE (স্ক্রিনশট/রেকর্ডিং-এ দেখা যায় না)।
 */
object VaultUi {

    private val COMMON_LABELS = listOf("ইমেইল", "ফোন নম্বর", "ইউজারনেম", "পাসওয়ার্ড")

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun looksSecret(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("পাস") || l.contains("pass") || l.contains("pin") || l.contains("পিন")
    }

    private fun secure(d: AlertDialog) { d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }

    private fun field(a: Activity, hint: String, secret: Boolean): EditText = EditText(a).apply {
        this.hint = hint
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        // কীবোর্ডের পরামর্শ/স্বয়ংক্রিয় সংশোধনে গোপন মান শেখানো আটকাতে
        if (secret) imeOptions = imeOptions or 0x01000000      // IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    // ---------------------------------------------------------------- AI চাইলে: আলাদা সুরক্ষিত বক্স
    /** onResult(true) = ভল্টে সংরক্ষিত; false = ইউজার "নিজেই লিখব" বা বাতিল। */
    fun askSecret(a: Activity, title: String, label: String, kind: String, onResult: (Boolean) -> Unit) {
        val secret = kind == "password" || looksSecret(label)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        }
        box.addView(TextView(a).apply {
            text = "\"$title\" এর \"$label\" লাগবে। এখানে লিখুন বা পেস্ট করুন — এটা শুধু এই ফোনে এনক্রিপ্ট হয়ে থাকবে, AI এটা পড়তে পারবে না। পরের বার আর জিজ্ঞেস করা হবে না।"
            textSize = 13f
        })
        val input = field(a, label, secret)
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 10) })
        if (secret) {
            box.addView(CheckBox(a).apply {
                text = "দেখাও"
                setOnCheckedChangeListener { _, on ->
                    val sel = input.selectionEnd
                    input.inputType = if (on) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                      else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    input.setSelection(sel.coerceAtLeast(0))
                }
            })
        }
        var answered = false
        fun finish(ok: Boolean) { if (!answered) { answered = true; onResult(ok) } }
        val d = AlertDialog.Builder(a)
            .setTitle("গোপন তথ্য — $title")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("সংরক্ষণ করো", null)          // নিচে নিজে হ্যান্ডেল — খালি থাকলে ডায়ালগ বন্ধ হবে না
            .setNegativeButton("আমি নিজেই লিখব") { _, _ -> finish(false) }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text?.toString().orEmpty()
                if (v.isBlank()) { input.error = "খালি রাখা যাবে না"; return@setOnClickListener }
                if (SecretVault.put(a, title, label, v)) { input.setText(""); d.dismiss(); finish(true) }
                else Toast.makeText(a, "সংরক্ষণ করা গেল না (ভল্ট ভরে গেছে?)", Toast.LENGTH_LONG).show()
            }
        }
        secure(d); d.show()
    }

    // ---------------------------------------------------------------- একাধিক বিকল্প: ইউজার বাছে
    fun askChoice(a: Activity, message: String, options: List<String>, onResult: (String?) -> Unit) {
        var answered = false
        fun finish(v: String?) { if (!answered) { answered = true; onResult(v) } }
        AlertDialog.Builder(a)
            .setTitle("কোনটা করব?")
            .setMessage(message)
            .setItems(options.toTypedArray()) { _, i -> finish(options[i]) }
            .setNegativeButton("আমি নিজেই করছি") { _, _ -> finish(null) }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------- ম্যানেজার
    fun showManager(a: Activity) {
        val items = SecretVault.list(a)
        val labels = items.map { it.title + "  —  " + it.fields.joinToString(", ") }.toTypedArray()
        val b = AlertDialog.Builder(a)
            .setTitle("গোপন ভল্ট (${items.size})")
            .setNeutralButton("নতুন যোগ করো") { _, _ -> showAdd(a, null) { showManager(a) } }
            .setPositiveButton("বন্ধ", null)
        if (items.isEmpty()) b.setMessage("এখনো কিছু নেই। \"Facebook\"-এর মতো শিরোনামে ইমেইল/ফোন/পাসওয়ার্ড রাখলে AI ব্রাউজার লগইন ফর্মে নিজেই বসাবে — কিন্তু মানগুলো কখনো দেখতে পাবে না।")
        else b.setItems(labels) { _, i -> showTitle(a, items[i].title) }
        b.show()
    }

    private fun showTitle(a: Activity, title: String) {
        val fields = SecretVault.list(a).firstOrNull { it.title == title }?.fields ?: emptyList()
        val rows = (fields.map { "$it  ●●●●" } + listOf("＋ ফিল্ড যোগ করো", "শিরোনামটা মুছো")).toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(rows) { _, i ->
                when {
                    i < fields.size -> fieldMenu(a, title, fields[i])
                    i == fields.size -> showAdd(a, title) { showTitle(a, title) }
                    else -> AlertDialog.Builder(a).setMessage("\"$title\" এর সব তথ্য মুছে যাবে।")
                        .setPositiveButton("মুছো") { _, _ -> SecretVault.deleteTitle(a, title); showManager(a) }
                        .setNegativeButton("না") { _, _ -> showTitle(a, title) }.show()
                }
            }
            .setPositiveButton("পেছনে") { _, _ -> showManager(a) }
            .show()
    }

    private fun fieldMenu(a: Activity, title: String, label: String) {
        AlertDialog.Builder(a)
            .setTitle("$title — $label")
            .setItems(arrayOf("নতুন মান দাও", "এই ফিল্ড মুছো")) { _, i ->
                if (i == 0) showAdd(a, title, label) { showTitle(a, title) }
                else { SecretVault.deleteField(a, title, label); showTitle(a, title) }
            }
            .setPositiveButton("পেছনে") { _, _ -> showTitle(a, title) }
            .show()
    }

    /** শিরোনাম + ফিল্ডের নাম + মান। [presetTitle]/[presetLabel] থাকলে ওগুলো আগে থেকে বসানো। */
    fun showAdd(a: Activity, presetTitle: String?, presetLabel: String? = null, onDone: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        }
        val titleIn = field(a, "শিরোনাম (যেমন: Facebook)", false).apply { setText(presetTitle.orEmpty()); isEnabled = presetTitle == null }
        val labelIn = field(a, "ফিল্ডের নাম (যেমন: পাসওয়ার্ড)", false).apply { setText(presetLabel.orEmpty()); isEnabled = presetLabel == null }
        val valueIn = field(a, "মান", true)
        box.addView(titleIn)
        box.addView(labelIn)
        if (presetLabel == null) {
            val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
            for (c in COMMON_LABELS) {
                chips.addView(TextView(a).apply {
                    text = c; textSize = 12f
                    setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6))
                    setOnClickListener {
                        labelIn.setText(c)
                        valueIn.inputType = if (looksSecret(c)) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                    }
                })
            }
            box.addView(chips)
        }
        box.addView(valueIn)
        box.addView(TextView(a).apply {
            text = "মানটা এনক্রিপ্ট হয়ে শুধু এই ফোনে থাকবে। AI শুধু শিরোনাম ও ফিল্ডের নাম দেখে।"
            textSize = 12f; visibility = View.VISIBLE
            setPadding(0, dp(a, 8), 0, 0)
        })
        val d = AlertDialog.Builder(a)
            .setTitle("ভল্টে যোগ করো")
            .setView(box)
            .setPositiveButton("সংরক্ষণ", null)
            .setNegativeButton("বাতিল", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = titleIn.text.toString().trim(); val l = labelIn.text.toString().trim(); val v = valueIn.text.toString()
                when {
                    t.isEmpty() -> titleIn.error = "শিরোনাম দাও"
                    l.isEmpty() -> labelIn.error = "ফিল্ডের নাম দাও"
                    v.isEmpty() -> valueIn.error = "মান দাও"
                    else -> if (SecretVault.put(a, t, l, v)) { valueIn.setText(""); d.dismiss(); onDone() }
                            else Toast.makeText(a, "সংরক্ষণ করা গেল না (সীমা শেষ?)", Toast.LENGTH_LONG).show()
                }
            }
        }
        secure(d); d.show()
    }
}
