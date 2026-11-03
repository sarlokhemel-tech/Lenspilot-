package com.hemel.lenspilot.multiagent

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * v94: মাল্টি-এজেন্ট উত্তরের সোর্স-কার্ডে চাপলে সাইটটা অ্যাপের ভেতরেই (WebView-তে) খোলে — বাইরের ব্রাউজারে
 * লিংক-পাঠানো নয়, Perplexity-র মতো সরাসরি ওয়েবসাইট দেখা। ওপরে: ← বন্ধ, সাইট/শিরোনাম, ⤴ বাইরের ব্রাউজারে খুলুন।
 * শুধু http/https; অন্য স্কিম (intent:, file: ইত্যাদি) ব্লক। ওয়েব-পেজের ভেতরে ঘোরা (লিংকে চাপা) এখানেই হয়,
 * ব্যাক চাপলে আগে পেজ-ইতিহাসে পিছোয়, তারপর বন্ধ।
 */
class SourceViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_source_url"
        const val EXTRA_TITLE = "extra_source_title"
        const val EXTRA_SITE = "extra_source_site"
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            Toast.makeText(this, "Invalid link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val site = intent.getStringExtra(EXTRA_SITE).orEmpty()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // শীর্ষ বার
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0F172A"))
            contentDescription = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(this).apply {
            text = title.ifBlank { site }
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }
        titles.addView(titleView)
        titles.addView(TextView(this).apply {
            text = site.ifBlank { Uri.parse(url).host.orEmpty() }
            textSize = 11.5f
            maxLines = 1
            setTextColor(Color.parseColor("#64748B"))
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "⤴"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2563EB"))
            contentDescription = "Open in browser"
            setOnClickListener {
                val cur = webView?.url ?: url
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cur))) }
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val web = WebView(this)
        webView = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return !(u.startsWith("https://") || u.startsWith("http://"))   // অন্য স্কিম ব্লক
            }

            override fun onPageFinished(view: WebView, u: String?) {
                progress.visibility = View.GONE
                if (title.isBlank()) view.title?.takeIf { it.isNotBlank() }?.let { titleView.text = it }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        root.addView(FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl(url)
    }

    override fun onDestroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
