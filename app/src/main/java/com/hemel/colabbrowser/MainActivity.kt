package com.hemel.colabbrowser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences
    private var desktopMode = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val START_URL = "https://colab.research.google.com/"

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results: Array<Uri>? = when {
                    data?.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
                callback.onReceiveValue(results)
            } else {
                callback.onReceiveValue(null)
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("colab_browser_prefs", MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop_mode", false)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        setupWebView()
        webView.addJavascriptInterface(DownloadBridge(this), "AndroidDownloader")
        webView.loadUrl(START_URL)

        btnSettings.setOnClickListener { showSettingsMenu(it) }

        ensureNotificationPermission()
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (desktopMode) settings.userAgentString = desktopUA

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = (params?.createIntent()?.apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }) ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:")) {
                // blob: লিংক থেকে DownloadManager সরাসরি নামাতে পারে না —
                // তাই JS দিয়ে blob-এর আসল ডেটা বের করে Android-এ পাঠানো হচ্ছে
                downloadBlobUrl(url, fileName)
            } else {
                downloadFile(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    private fun downloadBlobUrl(blobUrl: String, fileName: String) {
        Toast.makeText(this, "ডাউনলোড প্রস্তুত হচ্ছে...", Toast.LENGTH_SHORT).show()
        val safeUrl = blobUrl.replace("\"", "\\\"")
        val safeName = fileName.replace("\"", "\\\"")
        val js = """
            (function() {
                fetch("$safeUrl")
                    .then(function(res) { return res.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidDownloader.saveBase64File(base64, "$safeName");
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(err) { AndroidDownloader.reportError(err.toString()); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class DownloadBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                activity.runOnUiThread {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val resolver = activity.contentResolver
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                                values.clear()
                                values.put(MediaStore.Downloads.IS_PENDING, 0)
                                resolver.update(uri, values, null, null)
                            }
                        } else {
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadsDir.exists()) downloadsDir.mkdirs()
                            val file = File(downloadsDir, fileName)
                            FileOutputStream(file).use { it.write(bytes) }
                        }
                        Toast.makeText(activity, "✅ ডাউনলোড সম্পন্ন: $fileName (Downloads ফোল্ডারে)", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "ডাউনলোড ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "ডাউনলোড ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, "ডাউনলোড ব্যর্থ: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val cookie = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("cookie", cookie)
            request.addRequestHeader("User-Agent", userAgent)
            request.setMimeType(mimeType)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "ডাউনলোড শুরু হয়েছে: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ডাউনলোড ব্যর্থ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        webView.settings.userAgentString = if (enabled) desktopUA else null
        prefs.edit().putBoolean("desktop_mode", enabled).apply()
        webView.reload()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (e: Exception) { }
        } else {
            Toast.makeText(this, "ইতিমধ্যে battery optimization বন্ধ আছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showSettingsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (desktopMode) "✅ Desktop Mode (ON) — চাপুন OFF করতে" else "🖥 Desktop Mode (OFF) — চাপুন ON করতে")
        popup.menu.add(0, 2, 1, "🔄 Reload")
        popup.menu.add(0, 3, 2, "🔋 Battery অপ্টিমাইজেশন বন্ধ করুন")
        popup.menu.add(0, 4, 3, "♿ Accessibility সেটিংস খুলুন")
        popup.menu.add(0, 5, 4, "⬅️ পিছনে যান")
        popup.menu.add(0, 6, 5, "❌ Exit / সেশন সম্পূর্ণ বন্ধ করুন")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { setDesktopMode(!desktopMode); true }
                2 -> { webView.reload(); true }
                3 -> { requestIgnoreBatteryOptimizations(); true }
                4 -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); true }
                5 -> { if (webView.canGoBack()) webView.goBack(); true }
                6 -> { exitApp(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun exitApp() {
        stopService(Intent(this, KeepAliveService::class.java))
        finishAndRemoveTask()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }
}
