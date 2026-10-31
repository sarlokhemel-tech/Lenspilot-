package com.hemel.lenspilot.tools.perm

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.hemel.lenspilot.browser.VaultUi
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.security.SecretVault
import com.hemel.lenspilot.tools.runner.RunnerEvent
import com.hemel.lenspilot.tools.runner.RunnerKind
import com.hemel.lenspilot.tools.runner.RunnerSelfTest
import com.hemel.lenspilot.tools.runner.ScheduleFired
import com.hemel.lenspilot.tools.runner.ToolsEvents
import kotlinx.coroutines.launch

/**
 * Tools-এর ছোট নিরাপত্তা স্ক্রিন (টপ বারের তালা আইকন থেকে):
 *  • ভল্টের শিরোনাম (মান কখনো নয়) + প্রতিটির মুছুন বাটন + নতুন যোগ;
 *  • টেস্ট নোটিফিকেশন (দরকারে POST_NOTIFICATIONS চায়);
 *  • সাইন-ইন-করা ব্রাউজার সেশন ব্যবহারের সম্মতি-সুইচ।
 * UI প্রোগ্রাম্যাটিক — আলাদা লেআউট-রিসোর্স নেই।
 */
class ToolsSecurityActivity : AppCompatActivity() {

    private lateinit var listBox: LinearLayout
    private val ink = Color.parseColor("#0F172A")
    private val soft = Color.parseColor("#64748B")

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendTest()
        else Toast.makeText(this, "নোটিফিকেশনের অনুমতি দেওয়া হয়নি — অনুমোদনের অনুরোধ ব্যাকগ্রাউন্ডে দেখাতে পারব না।", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(ink)
            setPadding(dp(12), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "নিরাপত্তা"
            textSize = 20f
            setTextColor(ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(bar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(heading("ভল্ট"))
        body.addView(note("এখানে শুধু শিরোনাম দেখা যায় — মান কখনো দেখানো হয় না। মান এনক্রিপ্ট করা, শুধু এই ফোনে থাকে; AI কেবল শিরোনাম ও ফিল্ডের নাম জানে।"))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listBox)
        body.addView(Button(this).apply {
            text = "ভল্টে যোগ করুন"
            isAllCaps = false
            setOnClickListener { VaultUi.showAdd(this@ToolsSecurityActivity, null) { render() } }
        })

        body.addView(heading("অনুমোদনের নোটিফিকেশন"))
        body.addView(note("ঝুঁকিপূর্ণ কাজের (যেমন push, মোছা, গোপন তথ্য নেওয়া) আগে অনুমোদন চাওয়া হয়। অ্যাপ বন্ধ থাকলে নোটিফিকেশনে আসে। সময় শেষ হলে অস্বীকার ধরা হয়।"))
        body.addView(Button(this).apply {
            text = "টেস্ট নোটিফিকেশন পাঠান"
            isAllCaps = false
            setOnClickListener { onTestClicked() }
        })

        body.addView(heading("ডিবাগ: Runner ও Scheduler"))
        body.addView(note("AI ছাড়াই ফোনে যাচাই। নোটিফিকেশন দেখতে আগে উপরের টেস্ট নোটিফিকেশন চালিয়ে অনুমতি দিন।"))
        body.addView(Button(this).apply {
            text = "টেস্ট watcher (২০ সেকেন্ড পর ফাইল)"
            isAllCaps = false
            setOnClickListener {
                val err = RunnerSelfTest.startWatcherTest(this@ToolsSecurityActivity)
                Toast.makeText(
                    this@ToolsSecurityActivity,
                    err ?: "watcher চালু — ২০ সেকেন্ড পর ফাইল তৈরি হবে, ধরা পড়লে জানানো হবে",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        body.addView(Button(this).apply {
            text = "টেস্ট schedule (১ মিনিট পর নোটিফিকেশন)"
            isAllCaps = false
            setOnClickListener {
                val r = RunnerSelfTest.startScheduleTest(this@ToolsSecurityActivity)
                val msg = when {
                    r == "EXACT" -> "নির্ধারিত (সঠিক অ্যালার্ম) — ১ মিনিট পর নোটিফিকেশন আসবে"
                    r == "WORK" -> "নির্ধারিত (WorkManager ফলব্যাক, কিছুটা দেরি হতে পারে) — ১ মিনিট পর"
                    else -> "ব্যর্থ: " + r.removePrefix("ERR:")
                }
                Toast.makeText(this@ToolsSecurityActivity, msg, Toast.LENGTH_LONG).show()
            }
        })

        body.addView(heading("সাইন-ইন করা ব্রাউজার সেশন"))
        body.addView(note("চালু থাকলে ELORA ব্রাউজারে আপনার আগে থেকে সাইন-ইন করা সেশন ব্যবহার করতে পারবে। ক্যাপচা, 2FA বা ভেরিফিকেশন কখনো এড়ানো হয় না — সেখানে থেমে আপনাকে জানায়। ডিফল্ট: বন্ধ।"))
        body.addView(SwitchCompat(this).apply {
            text = "সাইন-ইন করা সেশন ব্যবহারে সম্মতি"
            setTextColor(ink)
            isChecked = ToolsSecurityPrefs.signedInSessionConsent(this@ToolsSecurityActivity)
            setOnCheckedChangeListener { _, on -> ToolsSecurityPrefs.setSignedInSessionConsent(this@ToolsSecurityActivity, on) }
            setPadding(0, dp(8), 0, dp(8))
        })

        setContentView(root)
        render()

        // ডিবাগ-পরীক্ষার ফল স্ক্রিনে খোলা থাকলে টোস্টে
        lifecycleScope.launch {
            ToolsEvents.flow.collect { e ->
                if (e is RunnerEvent && e.stepId == RunnerSelfTest.TEST_STEP) {
                    val ok = e.kind == RunnerKind.DONE
                    Toast.makeText(this@ToolsSecurityActivity, (if (ok) "✓ watcher ফাইল ধরেছে: " else "✗ watcher: ") + e.evidence, Toast.LENGTH_LONG).show()
                } else if (e is ScheduleFired && e.note.startsWith(RunnerSelfTest.TEST_NOTE)) {
                    Toast.makeText(this@ToolsSecurityActivity, "✓ নির্ধারিত সময় এসেছে", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) render()
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(ink)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(soft)
        setPadding(0, 0, 0, dp(8))
    }

    private fun render() {
        listBox.removeAllViews()
        val items = SecretVault.list(this)
        if (items.isEmpty()) {
            listBox.addView(note("ভল্ট খালি।"))
            return
        }
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = item.title; textSize = 15f; setTextColor(ink) })
            col.addView(TextView(this).apply {
                text = item.fields.joinToString(", ")
                textSize = 12f
                setTextColor(soft)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "মুছুন"
                isAllCaps = false
                setOnClickListener { confirmDelete(item.title) }
            })
            listBox.addView(row)
        }
    }

    private fun confirmDelete(title: String) {
        AlertDialog.Builder(this)
            .setTitle("মুছবেন?")
            .setMessage("\"$title\"-এর সব সংরক্ষিত মান এই ফোন থেকে মুছে যাবে।")
            .setPositiveButton("মুছুন") { _, _ ->
                SecretVault.deleteTitle(this, title)
                render()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun onTestClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        sendTest()
    }

    private fun sendTest() {
        val ok = PermissionGate.sendTestNotification(this)
        Toast.makeText(
            this,
            if (ok) "টেস্ট নোটিফিকেশন পাঠানো হয়েছে" else "নোটিফিকেশন বন্ধ — ফোনের সেটিংসে অ্যাপের নোটিফিকেশন চালু করুন",
            Toast.LENGTH_LONG
        ).show()
    }
}
