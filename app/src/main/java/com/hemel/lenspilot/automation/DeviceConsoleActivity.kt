package com.hemel.lenspilot.automation

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * ESP32-র কন্ট্রোল পেজ (Access Point-এর পেজ) অ্যাপের ভেতরেই — WebView শুধু http://127.0.0.1:<port> খোলে
 * (LocalProxyServer), যেটা অ্যাপ-লকসহ ESP32-তে পৌঁছে দেয় (WiFi / ব্লুটুথ / OTG — যেটা দিয়েই কানেক্ট)।
 * বাইরের কোনো লিংকে যেতে দেয় না। এই স্ক্রিন বন্ধ হলে সংযোগও বন্ধ (DeviceHub.disconnect)।
 *
 * 🤖 বাটন: AI-কন্ট্রোল প্যানেল — লক্ষ্য দিলে সার্ভারের AI ESP32-কে চালায় ([AiController])।
 * পেজের নিজের JS-ও চালাতে পারে: `LPAI.run("লক্ষ্য")`, `LPAI.stop()`, `LPAI.isRunning()`।
 */
class DeviceConsoleActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var ai: AiController? = null

    private lateinit var panel: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView
    private var pendingAsk: CancellableContinuation<String>? = null

    private val host = object : AiController.Host {
        override fun log(line: String) {
            if (line.isBlank()) return
            runOnUiThread {
                logView.append(line + "\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        override fun onRunning(running: Boolean) {
            runOnUiThread {
                sendBtn.text = if (running) "↩" else "▶"
                stopBtn.alpha = if (running) 1f else 0.35f
                if (!running) {
                    pendingAsk = null
                    input.hint = "Describe what the AI should do…"
                }
            }
        }

        override suspend fun confirm(text: String): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val dlg = AlertDialog.Builder(this@DeviceConsoleActivity)
                    .setTitle("Permission required")
                    .setMessage(text)
                    .setCancelable(false)
                    .setPositiveButton("Run") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .show()
                cont.invokeOnCancellation { runOnUiThread { runCatching { dlg.dismiss() } } }
            }
        }

        override suspend fun ask(question: String): String = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                pendingAsk = cont
                input.hint = "Type your answer to the AI's question…"
                input.requestFocus()
                cont.invokeOnCancellation { pendingAsk = null }
            }
        }
    }

    /** পেজের JS থেকে: LPAI.run("লক্ষ্য") ইত্যাদি। JS থ্রেড থেকে আসে, তাই মেইন থ্রেডে নিই। */
    inner class AiBridge {
        @JavascriptInterface fun run(goal: String) { runOnUiThread { startAi(goal) } }
        @JavascriptInterface fun stop() { runOnUiThread { ai?.stop() } }
        @JavascriptInterface fun isRunning(): Boolean = ai?.running == true
    }

    private fun startAi(goal: String) {
        if (goal.isBlank()) return
        val conn = DeviceHub.current ?: return
        panel.visibility = View.VISIBLE
        val c = ai ?: AiController(applicationContext, conn.agentId, conn.session, host).also { ai = it }
        if (c.running) { host.log("The AI is already running. Tap ■ first."); return }
        logView.text = ""
        host.log("🎯 $goal")
        c.start(goal)
    }

    private fun onSendClicked() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        val ask = pendingAsk
        if (ask != null) {
            pendingAsk = null
            input.hint = "Describe what the AI should do…"
            if (ask.isActive) ask.resume(t)
        } else {
            startAi(t)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conn = DeviceHub.current
        if (conn == null) { finish(); return }
        val ctx = this
        AutoUi.chrome(this)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AutoUi.BG)
            fitsSystemWindows = true
        }
        val bar = AutoUi.row(ctx).apply {
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8))
        }
        bar.addView(AutoUi.text(ctx, "✕", 22f).apply {
            setPadding(0, 0, AutoUi.dp(ctx, 14), 0)
            setOnClickListener { finish() }
        })
        bar.addView(AutoUi.text(ctx, "ESP32 control  •  ${conn.transport.label}", 16f, AutoUi.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val aiEnabled = AgentStore.find(ctx, conn.agentId)?.optBoolean("ai_enabled", true) ?: true
        val aiToggle = AutoUi.text(ctx, "🤖", 22f).apply {
            setPadding(AutoUi.dp(ctx, 14), 0, 0, 0)
            visibility = if (aiEnabled) View.VISIBLE else View.GONE
        }
        bar.addView(aiToggle)
        root.addView(bar)

        val port = conn.proxy.port
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    val local = u.host == "127.0.0.1" && u.port == port
                    return !local // বাইরের লিংক ব্লক
                }
            }
            if (aiEnabled) addJavascriptInterface(AiBridge(), "LPAI")
        }
        web = wv
        root.addView(wv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- AI প্যানেল (নিচে, শুরুতে লুকানো) ----
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoUi.rounded(ctx, AutoUi.SURFACE, 14)
            setPadding(AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 10), AutoUi.dp(ctx, 12), AutoUi.dp(ctx, 10))
            visibility = View.GONE
        }
        logView = AutoUi.text(ctx, "", 12f, AutoUi.MUTED)
        logScroll = ScrollView(ctx).apply { addView(logView) }
        panel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, AutoUi.dp(ctx, 150)))
        val inRow = AutoUi.row(ctx)
        input = EditText(ctx).apply {
            hint = "Describe what the AI should do…"
            setHintTextColor(AutoUi.MUTED)
            setTextColor(AutoUi.TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
        }
        sendBtn = AutoUi.text(ctx, "▶", 24f, AutoUi.ACCENT, true).apply {
            setPadding(AutoUi.dp(ctx, 14), 0, AutoUi.dp(ctx, 8), 0)
            setOnClickListener { onSendClicked() }
        }
        stopBtn = AutoUi.text(ctx, "■", 24f, AutoUi.DANGER, true).apply {
            setPadding(AutoUi.dp(ctx, 8), 0, 0, 0)
            alpha = 0.35f
            setOnClickListener { ai?.stop() }
        }
        inRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inRow.addView(sendBtn)
        inRow.addView(stopBtn)
        panel.addView(inRow)
        root.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val m = AutoUi.dp(ctx, 8)
            setMargins(m, m, m, m)
        })
        aiToggle.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
        wv.loadUrl(conn.entryUrl)
    }

    override fun onDestroy() {
        ai?.stop()          // চলন্ত AI-কে থামাও (ডিভাইস safe_stop ঘোষণা করলে সেটা পাঠানো হবে)
        web?.destroy()
        web = null
        if (isFinishing) {
            // safe_stop পাঠানোর জন্য সংযোগ একটু ধরে রেখে তারপর বন্ধ
            val c = ai
            if (c != null && c.running) {
                Thread {
                    var n = 0
                    while (c.running && n++ < 40) Thread.sleep(100)
                    c.release()
                    DeviceHub.disconnect()
                }.start()
            } else {
                c?.release()
                DeviceHub.disconnect()
            }
        }
        super.onDestroy()
    }
}
