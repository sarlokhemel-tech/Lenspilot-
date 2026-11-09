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
        else Toast.makeText(this, "Notification permission not granted — can't show approval requests in the background.", Toast.LENGTH_LONG).show()
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
            text = "Security"
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

        body.addView(heading("Vault"))
        body.addView(note("Only titles are shown here — values are never displayed. Values are encrypted and stay on this phone only; the AI knows only titles and field names."))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listBox)
        body.addView(Button(this).apply {
            text = "Add to vault"
            isAllCaps = false
            setOnClickListener { VaultUi.showAdd(this@ToolsSecurityActivity, null) { render() } }
        })

        body.addView(heading("Approval notifications"))
        body.addView(note("Approval is requested before risky actions (e.g. push, delete, taking secrets). If the app is closed, it arrives as a notification. A timeout counts as denial."))
        body.addView(Button(this).apply {
            text = "Send test notification"
            isAllCaps = false
            setOnClickListener { onTestClicked() }
        })

        body.addView(heading("Debug: Runner and Scheduler"))
        body.addView(note("On-device check without AI. To see notifications, first run the test notification above and grant permission."))
        body.addView(Button(this).apply {
            text = "Test watcher (file after 20 seconds)"
            isAllCaps = false
            setOnClickListener {
                val err = RunnerSelfTest.startWatcherTest(this@ToolsSecurityActivity)
                Toast.makeText(
                    this@ToolsSecurityActivity,
                    err ?: "Watcher started — a file will be created after 20 seconds; you'll be notified when it's detected",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        body.addView(Button(this).apply {
            text = "Test schedule (notification after 1 minute)"
            isAllCaps = false
            setOnClickListener {
                val r = RunnerSelfTest.startScheduleTest(this@ToolsSecurityActivity)
                val msg = when {
                    r == "EXACT" -> "Scheduled (exact alarm) — notification will arrive in 1 minute"
                    r == "WORK" -> "Scheduled (WorkManager fallback, may be slightly delayed) — in 1 minute"
                    else -> "Failed: " + r.removePrefix("ERR:")
                }
                Toast.makeText(this@ToolsSecurityActivity, msg, Toast.LENGTH_LONG).show()
            }
        })

        body.addView(heading("Signed-in browser session"))
        body.addView(note("When enabled, ELORA can use your already signed-in session in the browser. CAPTCHA, 2FA or verification is never bypassed — it stops there and tells you. Default: off."))
        body.addView(SwitchCompat(this).apply {
            text = "Consent to use signed-in session"
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
                    Toast.makeText(this@ToolsSecurityActivity, (if (ok) "✓ watcher detected file: " else "✗ watcher: ") + e.evidence, Toast.LENGTH_LONG).show()
                } else if (e is ScheduleFired && e.note.startsWith(RunnerSelfTest.TEST_NOTE)) {
                    Toast.makeText(this@ToolsSecurityActivity, "✓ scheduled time reached", Toast.LENGTH_LONG).show()
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
            listBox.addView(note("Vault is empty."))
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
                text = "Delete"
                isAllCaps = false
                setOnClickListener { confirmDelete(item.title) }
            })
            listBox.addView(row)
        }
    }

    private fun confirmDelete(title: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("All saved values of \"$title\" will be deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                SecretVault.deleteTitle(this, title)
                render()
            }
            .setNegativeButton("Cancel", null)
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
            if (ok) "Test notification sent" else "Notifications off — enable the app's notifications in phone settings",
            Toast.LENGTH_LONG
        ).show()
    }
}
