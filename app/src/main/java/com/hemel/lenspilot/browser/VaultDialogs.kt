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

    private val COMMON_LABELS = listOf("Email", "Phone number", "Username", "Password")

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
            text = "\"$title\" needs \"$label\". Enter or paste it here. It is stored encrypted on this phone only, and the AI cannot read it. You will not be asked again."
            textSize = 13f
        })
        val input = field(a, label, secret)
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 10) })
        if (secret) {
            box.addView(CheckBox(a).apply {
                text = "Show"
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
            .setTitle("Secret — $title")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save", null)          // নিচে নিজে হ্যান্ডেল — খালি থাকলে ডায়ালগ বন্ধ হবে না
            .setNegativeButton("I'll type it myself") { _, _ -> finish(false) }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text?.toString().orEmpty()
                if (v.isBlank()) { input.error = "This cannot be left empty"; return@setOnClickListener }
                if (SecretVault.put(a, title, label, v)) { input.setText(""); d.dismiss(); finish(true) }
                else Toast.makeText(a, "Could not save (is the vault full?)", Toast.LENGTH_LONG).show()
            }
        }
        secure(d); d.show()
    }

    // ---------------------------------------------------------------- একাধিক বিকল্প: ইউজার বাছে
    fun askChoice(a: Activity, message: String, options: List<String>, onResult: (String?) -> Unit) {
        var answered = false
        fun finish(v: String?) { if (!answered) { answered = true; onResult(v) } }
        AlertDialog.Builder(a)
            .setTitle("Which should I do?")
            .setMessage(message)
            .setItems(options.toTypedArray()) { _, i -> finish(options[i]) }
            .setNegativeButton("I'll do it myself") { _, _ -> finish(null) }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------- ম্যানেজার
    fun showManager(a: Activity) {
        val items = SecretVault.list(a)
        val labels = items.map { it.title + "  —  " + it.fields.joinToString(", ") }.toTypedArray()
        val b = AlertDialog.Builder(a)
            .setTitle("Secret vault (${items.size})")
            .setNeutralButton("Add new") { _, _ -> showAdd(a, null) { showManager(a) } }
            .setPositiveButton("Close", null)
        if (items.isEmpty()) b.setMessage("Nothing here yet. If you store an email, phone number or password under a site or account name, the AI browser can fill it into login forms itself, but it can never see the values.")
        else b.setItems(labels) { _, i -> showTitle(a, items[i].title) }
        b.show()
    }

    private fun showTitle(a: Activity, title: String) {
        val fields = SecretVault.list(a).firstOrNull { it.title == title }?.fields ?: emptyList()
        val rows = (fields.map { "$it  ●●●●" } + listOf("＋ Add field", "Delete this title")).toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(rows) { _, i ->
                when {
                    i < fields.size -> fieldMenu(a, title, fields[i])
                    i == fields.size -> showAdd(a, title) { showTitle(a, title) }
                    else -> AlertDialog.Builder(a).setMessage("All information for \"$title\" will be deleted.")
                        .setPositiveButton("Delete") { _, _ -> SecretVault.deleteTitle(a, title); showManager(a) }
                        .setNegativeButton("No") { _, _ -> showTitle(a, title) }.show()
                }
            }
            .setPositiveButton("Back") { _, _ -> showManager(a) }
            .show()
    }

    private fun fieldMenu(a: Activity, title: String, label: String) {
        AlertDialog.Builder(a)
            .setTitle("$title — $label")
            .setItems(arrayOf("Enter a new value", "Delete this field")) { _, i ->
                if (i == 0) showAdd(a, title, label) { showTitle(a, title) }
                else { SecretVault.deleteField(a, title, label); showTitle(a, title) }
            }
            .setPositiveButton("Back") { _, _ -> showTitle(a, title) }
            .show()
    }

    /** শিরোনাম + ফিল্ডের নাম + মান। [presetTitle]/[presetLabel] থাকলে ওগুলো আগে থেকে বসানো। */
    fun showAdd(a: Activity, presetTitle: String?, presetLabel: String? = null, onDone: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        }
        val titleIn = field(a, "Site or account name", false).apply { setText(presetTitle.orEmpty()); isEnabled = presetTitle == null }
        val labelIn = field(a, "Field name", false).apply { setText(presetLabel.orEmpty()); isEnabled = presetLabel == null }
        val valueIn = field(a, "Value", true)
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
            text = "The value is encrypted and stays on this phone only. The AI sees only the title and the field name."
            textSize = 12f; visibility = View.VISIBLE
            setPadding(0, dp(a, 8), 0, 0)
        })
        val d = AlertDialog.Builder(a)
            .setTitle("Add to vault")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = titleIn.text.toString().trim(); val l = labelIn.text.toString().trim(); val v = valueIn.text.toString()
                when {
                    t.isEmpty() -> titleIn.error = "Enter a title"
                    l.isEmpty() -> labelIn.error = "Enter a field name"
                    v.isEmpty() -> valueIn.error = "Enter a value"
                    else -> if (SecretVault.put(a, t, l, v)) { valueIn.setText(""); d.dismiss(); onDone() }
                            else Toast.makeText(a, "Could not save (limit reached?)", Toast.LENGTH_LONG).show()
                }
            }
        }
        secure(d); d.show()
    }
}
