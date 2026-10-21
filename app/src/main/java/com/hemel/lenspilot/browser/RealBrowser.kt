package com.hemel.lenspilot.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import com.hemel.lenspilot.R
import java.io.File

/**
 * v43 — Lenspilot AI Browser: "আসল ব্রাউজার"-এর সব সাধারণ জিনিস।
 *
 * ওপরে কন্ট্রোল বার (বিদ্যমান: বন্ধ / নতুন কমান্ড / লাইভ চ্যাট / ভল্ট / AI সুইচ), তার নিচে অ্যাড্রেস বার:
 * পেছনে/সামনে, লিংক/সার্চ বক্স (তালা-চিহ্নসহ), রিলোড/থামাও, ট্যাব-সংখ্যা, মেনু।
 * মেনুতে: নতুন ট্যাব, ট্যাব সমূহ, বুকমার্ক, হিস্ট্রি, ডাউনলোড, পেজে খোঁজো, ডেস্কটপ সাইট, শেয়ার, লিংক কপি,
 * অন্য অ্যাপে খোলো, হোম, সার্চ ইঞ্জিন বদল, ব্রাউজিং ডেটা মুছুন।
 * সাথে: ডাউনলোড (সাধারণ + blob/data), লং-প্রেস মেনু (লিংক/ছবি), ক্যামেরা/মাইক/লোকেশন অনুমতি, ফুল-স্ক্রিন ভিডিও,
 * JS alert/confirm/prompt, SSL সতর্কতা, HTTP লগইন, পপআপ→নতুন ট্যাব, এরর পেজ, রেন্ডারার-ক্র্যাশ থেকে পুনরুদ্ধার।
 *
 * AI সুইচ চালু থাকলে আগের (এজেন্ট-নিরাপদ) আচরণই থাকে: অনুমতি/ডায়ালগ স্বয়ংক্রিয়ভাবে ডিনাই/বাতিল, পপআপ একই ট্যাবে,
 * ট্যাব বদল বন্ধ — যাতে চলন্ত কাজে এজেন্টের নিচের WebView হঠাৎ বদলে না যায়।
 */
class RealBrowser(private val act: AppCompatActivity, private val host: Host) {

    interface Host {
        fun isAiOn(): Boolean
        fun configureWebView(wv: WebView)
        fun onActiveWebViewChanged(wv: WebView)
    }

    class Tab(var wv: WebView) {
        var title: String = ""
        var url: String = ""
        var loading: Boolean = false
        var desktop: Boolean = false
        var defaultUa: String = ""
    }

    private class Saved(val uri: String, val shown: String)

    companion object {
        const val HOME_URL = "https://www.google.com"
        private const val FALLBACK_CHROME = "124.0.0.0"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val container: FrameLayout = act.findViewById(R.id.browserWebContainer)
    private val urlInput: EditText = act.findViewById(R.id.browserUrlInput)
    private val urlClear: ImageButton = act.findViewById(R.id.browserUrlClearButton)
    private val secureIcon: ImageView = act.findViewById(R.id.browserSecureIcon)
    private val backBtn: ImageButton = act.findViewById(R.id.browserBackButton)
    private val forwardBtn: ImageButton = act.findViewById(R.id.browserForwardButton)
    private val reloadBtn: ImageButton = act.findViewById(R.id.browserReloadButton)
    private val tabsBtn: TextView = act.findViewById(R.id.browserTabsButton)
    private val menuBtn: ImageButton = act.findViewById(R.id.browserMenuButton)
    private val progress: ProgressBar = act.findViewById(R.id.browserProgressBar)
    private val findBar: View = act.findViewById(R.id.browserFindBar)
    private val findInput: EditText = act.findViewById(R.id.browserFindInput)
    private val findCount: TextView = act.findViewById(R.id.browserFindCount)
    private val findPrev: ImageButton = act.findViewById(R.id.browserFindPrev)
    private val findNext: ImageButton = act.findViewById(R.id.browserFindNext)
    private val findClose: ImageButton = act.findViewById(R.id.browserFindClose)

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var blobToken: String = ""
    private val bridge = BlobBridge()

    private var pendingPermAction: ((Map<String, Boolean>) -> Unit)? = null
    private val permLauncher = act.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val a = pendingPermAction
        pendingPermAction = null
        a?.invoke(res)
    }

    // ------------------------------------------------------------------
    // শুরু
    // ------------------------------------------------------------------

    fun attach(initial: WebView) {
        val t = Tab(initial)
        t.defaultUa = initial.settings.userAgentString.orEmpty()
        tabs.add(t)
        current = t
        wireUi()
        sync()
    }

    /** প্রতিটি WebView-তে (নতুন ট্যাবেও) বসে: ডাউনলোড, লং-প্রেস, blob-সেতু। */
    fun decorate(wv: WebView) {
        wv.addJavascriptInterface(bridge, "LensBlob")
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            download(url, userAgent, contentDisposition, mimeType, contentLength, wv)
        }
        wv.setOnLongClickListener { handleLongPress(wv) }
    }

    private fun wv(): WebView = current!!.wv

    private fun toast(msg: String) { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }

    private fun hideKeyboard() {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun showKeyboard(v: View) {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun wireUi() {
        backBtn.setOnClickListener { val w = wv(); if (w.canGoBack()) w.goBack() }
        forwardBtn.setOnClickListener { val w = wv(); if (w.canGoForward()) w.goForward() }
        reloadBtn.setOnClickListener {
            val t = current
            if (t != null) { if (t.loading) t.wv.stopLoading() else t.wv.reload() }
        }
        tabsBtn.setOnClickListener { showTabs() }
        menuBtn.setOnClickListener { showMenu(it) }
        urlClear.setOnClickListener { urlInput.setText(""); urlInput.requestFocus() }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) loadFromBar(urlInput.text.toString())
            go
        }
        urlInput.setOnFocusChangeListener { _, has ->
            urlClear.visibility = if (has) View.VISIBLE else View.GONE
            if (!has) syncUrlText()
        }
        findInput.doAfterTextChanged { e ->
            val q = e?.toString().orEmpty()
            if (q.isEmpty()) { wv().clearMatches(); findCount.text = "" } else wv().findAllAsync(q)
        }
        findNext.setOnClickListener { wv().findNext(true) }
        findPrev.setOnClickListener { wv().findNext(false) }
        findClose.setOnClickListener { closeFind() }
    }

    fun loadFromBar(text: String) {
        val target = BrowserUrl.resolve(text, BrowserStore.searchEngine(act))
        if (target.isEmpty()) return
        hideKeyboard()
        urlInput.clearFocus()
        wv().loadUrl(target)
    }

    // ------------------------------------------------------------------
    // UI সিঙ্ক
    // ------------------------------------------------------------------

    private fun sync() {
        val t = current ?: return
        val w = t.wv
        backBtn.alpha = if (w.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (w.canGoForward()) 1f else 0.35f
        reloadBtn.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_br_reload)
        tabsBtn.text = tabs.size.toString()
        if (!urlInput.hasFocus()) syncUrlText()
    }

    private fun syncUrlText() {
        val t = current ?: return
        val u = t.url
        urlInput.setText(if (u == "about:blank") "" else u)
        val icon = when {
            u.startsWith("https://") -> R.drawable.ic_br_lock
            u.startsWith("http://") -> R.drawable.ic_br_lock_open
            else -> R.drawable.ic_browser_ai
        }
        secureIcon.setImageResource(icon)
    }

    private fun tabFor(view: WebView?): Tab? = tabs.firstOrNull { it.wv === view }

    fun pageStarted(view: WebView?, url: String?) {
        val t = tabFor(view) ?: return
        t.loading = true
        t.url = url.orEmpty()
        if (t === current) sync()
    }

    fun pageFinished(view: WebView?, url: String?) {
        val t = tabFor(view) ?: return
        t.loading = false
        val u = url.orEmpty()
        t.url = u
        t.title = view?.title.orEmpty()
        if (u.startsWith("http://") || u.startsWith("https://")) BrowserStore.addHistory(act, u, t.title)
        if (t === current) sync()
    }

    fun urlChanged(view: WebView?, url: String?) {
        val t = tabFor(view) ?: return
        if (!url.isNullOrEmpty()) t.url = url
        if (t === current) sync()
    }

    fun titleChanged(view: WebView?, title: String?) {
        val t = tabFor(view) ?: return
        t.title = title.orEmpty()
    }

    fun progressChanged(view: WebView?, p: Int) {
        val t = tabFor(view) ?: return
        if (t !== current) return
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // ট্যাব
    // ------------------------------------------------------------------

    private fun aiLock(): Boolean {
        if (host.isAiOn()) {
            toast("AI চালু থাকলে ট্যাব বদলানো যায় না — আগে উপরের AI সুইচ বন্ধ করুন")
            return true
        }
        return false
    }

    private fun makeTab(): Tab {
        val w = WebView(act)
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        host.configureWebView(w)
        val t = Tab(w)
        t.defaultUa = w.settings.userAgentString.orEmpty()
        w.visibility = View.GONE
        container.addView(w)
        tabs.add(t)
        return t
    }

    fun newTab(url: String?): Tab? {
        if (aiLock()) return null
        val t = makeTab()
        switchTo(t)
        if (url != null) t.wv.loadUrl(url)
        return t
    }

    private fun switchTo(t: Tab) {
        if (findBar.visibility == View.VISIBLE) closeFind()
        for (x in tabs) x.wv.visibility = if (x === t) View.VISIBLE else View.GONE
        current = t
        host.onActiveWebViewChanged(t.wv)
        progress.visibility = View.GONE
        urlInput.clearFocus()
        sync()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        val wasCurrent = t === current
        tabs.removeAt(idx)
        container.removeView(t.wv)
        val dead = t.wv
        handler.post { dead.stopLoading(); dead.destroy() }
        if (tabs.isEmpty()) {
            val n = makeTab()
            switchTo(n)
            n.wv.loadUrl(HOME_URL)
        } else if (wasCurrent) {
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        } else {
            sync()
        }
    }

    /** সাইট নিজে নতুন উইন্ডো (target=_blank / window.open) চাইলে। */
    fun createWindow(resultMsg: Message?): Boolean {
        val msg = resultMsg ?: return false
        val transport = msg.obj as? WebView.WebViewTransport ?: return false
        if (host.isAiOn()) {
            // AI চালু: নতুন ট্যাব নয় — লিংকটা চলতি ট্যাবেই খোলে (আগের আচরণ)
            val temp = WebView(act)
            var done = false
            fun finish(u: String?) {
                if (done) return
                done = true
                if (!u.isNullOrBlank() && u != "about:blank") wv().loadUrl(u)
                handler.post { temp.destroy() }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    finish(request?.url?.toString())
                    return true
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    view?.stopLoading()
                    finish(url)
                }
            }
            transport.webView = temp
            msg.sendToTarget()
            return true
        }
        val t = makeTab()
        switchTo(t)
        transport.webView = t.wv
        msg.sendToTarget()
        return true
    }

    fun closeWindow(view: WebView?) {
        val t = tabFor(view) ?: return
        closeTab(t)
    }

    private fun showTabs() {
        val labels = tabs.map { t ->
            val name = if (t.title.isNotBlank()) t.title else if (t.url.isNotBlank()) t.url else "নতুন ট্যাব"
            (if (t === current) "● " else "○ ") + name + "\n" + t.url
        }
        val adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)
        val d = AlertDialog.Builder(act)
            .setTitle("ট্যাব সমূহ (${tabs.size}) — চেপে ধরলে বন্ধ")
            .setAdapter(adapter) { _, which -> if (!aiLock()) switchTo(tabs[which]) }
            .setNeutralButton("নতুন ট্যাব") { _, _ -> newTab(HOME_URL) }
            .setNegativeButton("বন্ধ", null)
            .create()
        d.setOnShowListener {
            d.listView.setOnItemLongClickListener { _, _, pos, _ ->
                if (!aiLock() && pos in tabs.indices) { closeTab(tabs[pos]); d.dismiss() }
                true
            }
        }
        d.show()
    }

    /** রেন্ডারার ক্র্যাশ করলে অ্যাপ না মরে ট্যাবটা নতুন WebView-তে ফিরিয়ে আনে। */
    fun rendererGone(view: WebView?) {
        val t = tabFor(view) ?: return
        val old = t.wv
        val url = t.url
        val vis = old.visibility
        val nw = WebView(act)
        nw.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        host.configureWebView(nw)
        nw.visibility = vis
        container.addView(nw)
        container.removeView(old)
        handler.post { old.destroy() }
        t.wv = nw
        t.loading = false
        if (t === current) host.onActiveWebViewChanged(nw)
        if (url.startsWith("http://") || url.startsWith("https://")) nw.loadUrl(url)
        toast("পেজটি থেমে গিয়েছিল — আবার লোড হচ্ছে")
        sync()
    }

    // ------------------------------------------------------------------
    // মেনু
    // ------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(act, anchor)
        val m = pm.menu
        val url = current?.url.orEmpty()
        val bookmarked = url.isNotEmpty() && BrowserStore.isBookmarked(act, url)
        m.add(0, 1, 0, "নতুন ট্যাব")
        m.add(0, 2, 1, "ট্যাব সমূহ (${tabs.size})")
        m.add(0, 3, 2, if (bookmarked) "★ বুকমার্ক সরাও" else "☆ বুকমার্কে যোগ করো")
        m.add(0, 4, 3, "বুকমার্ক তালিকা")
        m.add(0, 5, 4, "হিস্ট্রি")
        m.add(0, 6, 5, "ডাউনলোড")
        m.add(0, 7, 6, "পেজে খোঁজো")
        val desk = m.add(0, 8, 7, "ডেস্কটপ সাইট")
        desk.isCheckable = true
        desk.isChecked = current?.desktop == true
        m.add(0, 9, 8, "শেয়ার করো")
        m.add(0, 10, 9, "লিংক কপি করো")
        m.add(0, 11, 10, "অন্য অ্যাপে খোলো")
        m.add(0, 12, 11, "হোম পেজ")
        m.add(0, 13, 12, "সার্চ ইঞ্জিন: " + BrowserStore.searchEngine(act))
        m.add(0, 14, 13, "ব্রাউজিং ডেটা মুছুন")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { newTab(HOME_URL) }
                2 -> showTabs()
                3 -> toggleBookmark()
                4 -> showBookmarks()
                5 -> showHistory()
                6 -> showDownloads()
                7 -> openFind()
                8 -> toggleDesktop()
                9 -> shareCurrent()
                10 -> copyCurrent()
                11 -> openInOtherApp()
                12 -> wv().loadUrl(HOME_URL)
                13 -> chooseEngine()
                14 -> confirmClearData()
                else -> { }
            }
            true
        }
        pm.show()
    }

    private fun toggleBookmark() {
        val t = current ?: return
        val u = t.url
        if (!(u.startsWith("http://") || u.startsWith("https://"))) { toast("এই পেজ বুকমার্ক করা যায় না"); return }
        if (BrowserStore.isBookmarked(act, u)) {
            BrowserStore.removeBookmark(act, u)
            toast("বুকমার্ক সরানো হয়েছে")
        } else {
            BrowserStore.addBookmark(act, u, if (t.title.isNotBlank()) t.title else u)
            toast("বুকমার্ক হয়েছে ★")
        }
    }

    private fun showEntryList(
        title: String,
        entries: List<BrowserStore.Entry>,
        emptyMsg: String,
        onDelete: (BrowserStore.Entry) -> Unit,
        onClearAll: (() -> Unit)?
    ) {
        if (entries.isEmpty()) { toast(emptyMsg); return }
        val labels = entries.map { (if (it.title.isNotBlank()) it.title + "\n" else "") + it.url }
        val adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)
        val b = AlertDialog.Builder(act)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> wv().loadUrl(entries[which].url) }
            .setNegativeButton("বন্ধ", null)
        if (onClearAll != null) b.setNeutralButton("সব মুছুন") { _, _ -> onClearAll() }
        val d = b.create()
        d.setOnShowListener {
            d.listView.setOnItemLongClickListener { _, _, pos, _ ->
                if (pos in entries.indices) { onDelete(entries[pos]); d.dismiss(); toast("মুছে ফেলা হয়েছে") }
                true
            }
        }
        d.show()
    }

    private fun showBookmarks() {
        showEntryList("বুকমার্ক — চেপে ধরলে মুছবে", BrowserStore.bookmarks(act), "কোনো বুকমার্ক নেই",
            { BrowserStore.removeBookmark(act, it.url) }, null)
    }

    private fun showHistory() {
        showEntryList("হিস্ট্রি — চেপে ধরলে মুছবে", BrowserStore.history(act), "হিস্ট্রি খালি",
            { BrowserStore.removeHistory(act, it.url) }, { BrowserStore.clearHistory(act); toast("হিস্ট্রি মোছা হয়েছে") })
    }

    private fun toggleDesktop() {
        val t = current ?: return
        t.desktop = !t.desktop
        val s = t.wv.settings
        if (t.desktop) {
            val ver = Regex("Chrome/([\\d.]+)").find(t.defaultUa)?.groupValues?.get(1) ?: FALLBACK_CHROME
            s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            toast("ডেস্কটপ সাইট চালু")
        } else {
            s.userAgentString = t.defaultUa
            toast("মোবাইল সাইট")
        }
        t.wv.reload()
    }

    private fun shareCurrent() {
        val u = current?.url.orEmpty()
        if (u.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u)
        act.startActivity(Intent.createChooser(i, "শেয়ার করো"))
    }

    private fun copyText(label: String, text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("কপি হয়েছে")
    }

    private fun copyCurrent() {
        val u = current?.url.orEmpty()
        if (u.isNotEmpty()) copyText("url", u)
    }

    private fun openInOtherApp() {
        val u = current?.url.orEmpty()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("খোলার অ্যাপ নেই") }
    }

    private fun chooseEngine() {
        val names = BrowserUrl.ENGINES.keys.toTypedArray()
        val cur = names.indexOf(BrowserStore.searchEngine(act))
        AlertDialog.Builder(act)
            .setTitle("সার্চ ইঞ্জিন")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                BrowserStore.setSearchEngine(act, names[which])
                dlg.dismiss()
                toast("সার্চ ইঞ্জিন: " + names[which])
            }
            .setNegativeButton("বন্ধ", null)
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(act)
            .setTitle("ব্রাউজিং ডেটা মুছবেন?")
            .setMessage("সব কুকি (লগইন), ক্যাশ ও হিস্ট্রি মুছে যাবে। বুকমার্ক থাকবে।")
            .setPositiveButton("মুছুন") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                for (t in tabs) { t.wv.clearCache(true); t.wv.clearHistory() }
                BrowserStore.clearHistory(act)
                sync()
                toast("ব্রাউজিং ডেটা মোছা হয়েছে")
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ------------------------------------------------------------------
    // পেজে খোঁজা
    // ------------------------------------------------------------------

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findCount.text = ""
        findInput.requestFocus()
        showKeyboard(findInput)
        wv().setFindListener { active, total, _ ->
            findCount.text = if (total == 0) "0" else "${active + 1}/$total"
        }
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        wv().clearMatches()
        hideKeyboard()
    }

    // ------------------------------------------------------------------
    // ডাউনলোড
    // ------------------------------------------------------------------

    private fun humanSize(n: Long): String {
        if (n <= 0) return "আকার অজানা"
        val kb = n / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    }

    private fun jsQuote(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun download(url: String, userAgent: String?, contentDisposition: String?, mime: String?, length: Long, from: WebView?) {
        when {
            url.startsWith("blob:") -> downloadBlob(url, from)
            url.startsWith("data:") -> saveDataUrl(url)
            else -> {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                AlertDialog.Builder(act)
                    .setTitle("ডাউনলোড করবেন?")
                    .setMessage(name + "\n" + humanSize(length))
                    .setPositiveButton("ডাউনলোড") { _, _ -> enqueue(url, userAgent, mime, name) }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
        }
    }

    private fun enqueue(url: String, userAgent: String?, mime: String?, name: String) {
        try {
            val dm = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle(name)
            req.setDescription("Lenspilot AI Browser")
            if (!mime.isNullOrBlank()) req.setMimeType(mime)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
            val ref = current?.url
            if (!ref.isNullOrBlank()) req.addRequestHeader("Referer", ref)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val publicOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(act, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (publicOk) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            else req.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, name)
            val id = dm.enqueue(req)
            BrowserStore.addDownload(act, BrowserStore.Dl(id, name, url, System.currentTimeMillis(), "", mime.orEmpty()))
            toast("ডাউনলোড শুরু: $name")
        } catch (e: Exception) {
            toast("ডাউনলোড শুরু করা যায়নি")
        }
    }

    private fun downloadBlob(url: String, from: WebView?) {
        val w = from ?: current?.wv ?: return
        blobToken = java.util.UUID.randomUUID().toString()
        val tk = blobToken
        val js = "(function(){try{var x=new XMLHttpRequest();x.open('GET'," + jsQuote(url) + ",true);x.responseType='blob';" +
            "x.onload=function(){var r=new FileReader();r.onloadend=function(){LensBlob.save('" + tk + "',String(r.result));};r.readAsDataURL(x.response);};" +
            "x.onerror=function(){LensBlob.fail('" + tk + "');};x.send();}catch(e){LensBlob.fail('" + tk + "');}})();"
        w.evaluateJavascript(js, null)
    }

    /**
     * blob:/data: ডাউনলোডের জন্য পেজ থেকে ফাইলের বাইট পাওয়ার সেতু। যেকোনো পেজ এই অবজেক্ট দেখতে পায়, তাই ইউজার
     * ডাউনলোড চাপার সময় বানানো এক-বারের টোকেন না মিললে কিছুই সংরক্ষণ করে না।
     */
    inner class BlobBridge {
        @JavascriptInterface
        fun save(token: String?, dataUrl: String?) {
            if (token.isNullOrEmpty() || token != blobToken || dataUrl == null) return
            blobToken = ""
            act.runOnUiThread { saveDataUrl(dataUrl) }
        }

        @JavascriptInterface
        fun fail(token: String?) {
            if (token.isNullOrEmpty() || token != blobToken) return
            blobToken = ""
            act.runOnUiThread { toast("ফাইলটা নামানো যায়নি") }
        }
    }

    private fun saveDataUrl(dataUrl: String) {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) { toast("ফাইল সংরক্ষণ করা যায়নি"); return }
        val meta = dataUrl.substring(5, comma)
        val isB64 = meta.contains(";base64")
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val payload = dataUrl.substring(comma + 1)
        Thread {
            var saved: Saved? = null
            var name = ""
            try {
                val bytes = if (isB64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                name = "lenspilot_" + System.currentTimeMillis() + (if (ext.isNullOrEmpty()) "" else ".$ext")
                saved = saveBytes(name, mime, bytes)
            } catch (e: Exception) {
                saved = null
            }
            val result = saved
            val finalName = name
            act.runOnUiThread {
                if (result == null) toast("ফাইল সংরক্ষণ করা যায়নি")
                else {
                    BrowserStore.addDownload(act, BrowserStore.Dl(-1L, finalName, "", System.currentTimeMillis(), result.uri, mime))
                    toast("সংরক্ষিত: " + result.shown)
                }
            }
        }.start()
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray): Saved? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.Downloads.DISPLAY_NAME, name)
            values.put(MediaStore.Downloads.MIME_TYPE, mime)
            values.put(MediaStore.Downloads.IS_PENDING, 1)
            val resolver = act.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(uri) ?: return null
            out.use { it.write(bytes) }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
            return Saved(uri.toString(), "Downloads/$name")
        }
        val dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(bytes)
        return Saved("", f.absolutePath)
    }

    private fun dmStatus(id: Long): String {
        val dm = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query().setFilterById(id))
        if (c != null) {
            c.use {
                if (it.moveToFirst()) {
                    val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    return when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> "✔"
                        DownloadManager.STATUS_FAILED -> "✖ ব্যর্থ"
                        DownloadManager.STATUS_PAUSED -> "⏸ থেমেছে"
                        else -> "⏳ চলছে"
                    }
                }
            }
        }
        return "—"
    }

    private fun showDownloads() {
        val list = BrowserStore.downloads(act)
        val labels = list.map { d -> (if (d.id >= 0) dmStatus(d.id) else "✔") + "  " + d.name }
        val b = AlertDialog.Builder(act).setTitle("ডাউনলোড")
        if (list.isEmpty()) b.setMessage("এখনো কিছু নামানো হয়নি।")
        else b.setAdapter(ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)) { _, which -> openDownload(list[which]) }
        b.setNeutralButton("ডাউনলোড ফোল্ডার") { _, _ ->
            try { act.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (e: Exception) { toast("খোলা যায়নি") }
        }
        b.setNegativeButton("বন্ধ", null)
        b.show()
    }

    private fun openDownload(d: BrowserStore.Dl) {
        val dm = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri: Uri? = if (d.id >= 0) dm.getUriForDownloadedFile(d.id) else if (d.uri.isNotEmpty()) Uri.parse(d.uri) else null
        if (uri == null) { toast("ফাইল খোলা যাচ্ছে না (শেষ হয়নি, বা অ্যাপের নিজস্ব ফোল্ডারে আছে)"); return }
        val mime = (if (d.id >= 0) dm.getMimeTypeForDownloadedFile(d.id) else null) ?: d.mime
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, if (mime.isNullOrBlank()) "*/*" else mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { act.startActivity(i) } catch (e: Exception) { toast("এই ফাইল খোলার অ্যাপ নেই") }
    }

    // ------------------------------------------------------------------
    // লং-প্রেস মেনু (লিংক / ছবি)
    // ------------------------------------------------------------------

    private fun handleLongPress(w: WebView): Boolean {
        if (host.isAiOn()) return false
        val hit = w.hitTestResult
        val extra = hit.extra
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (extra.isNullOrEmpty()) return false
                showLinkMenu(extra, null)
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra.isNullOrEmpty()) return false
                showLinkMenu(null, extra)
                return true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msgHandler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        val link = msg.data.getString("url")
                        val src = msg.data.getString("src")
                        if (!link.isNullOrEmpty() || !src.isNullOrEmpty()) showLinkMenu(link, src)
                    }
                }
                w.requestFocusNodeHref(msgHandler.obtainMessage())
                return true
            }
            else -> return false
        }
    }

    private fun showLinkMenu(link: String?, image: String?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (!link.isNullOrEmpty()) {
            labels.add("নতুন ট্যাবে খোলো"); actions.add { newTab(link) }
            labels.add("লিংক কপি করো"); actions.add { copyText("link", link) }
            labels.add("লিংক শেয়ার করো"); actions.add {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                act.startActivity(Intent.createChooser(i, "শেয়ার করো"))
            }
        }
        if (!image.isNullOrEmpty()) {
            labels.add("ছবি ডাউনলোড করো"); actions.add { download(image, current?.defaultUa, null, null, 0L, current?.wv) }
            labels.add("ছবি নতুন ট্যাবে খোলো"); actions.add { newTab(image) }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(act)
            .setTitle(link ?: image ?: "")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ------------------------------------------------------------------
    // অনুমতি (ক্যামেরা / মাইক / লোকেশন)
    // ------------------------------------------------------------------

    private fun hostOf(origin: String?): String =
        try { Uri.parse(origin).host ?: origin.orEmpty() } catch (e: Exception) { origin.orEmpty() }

    fun handlePermissionRequest(request: PermissionRequest?) {
        val r = request ?: return
        act.runOnUiThread {
            val wanted = ArrayList<String>()
            val androidPerms = ArrayList<String>()
            val labels = ArrayList<String>()
            for (res in r.resources) {
                if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                    wanted.add(res); androidPerms.add(Manifest.permission.CAMERA); labels.add("ক্যামেরা")
                } else if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    wanted.add(res); androidPerms.add(Manifest.permission.RECORD_AUDIO); labels.add("মাইক্রোফোন")
                }
            }
            if (wanted.isEmpty()) { r.deny(); return@runOnUiThread }
            val grantList = wanted.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle("অনুমতি")
                .setMessage(hostOf(r.origin.toString()) + " আপনার " + labels.joinToString(" ও ") + " ব্যবহার করতে চায়।")
                .setPositiveButton("অনুমতি দাও") { _, _ ->
                    val missing = androidPerms.filter { ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) r.grant(grantList)
                    else {
                        pendingPermAction = { m -> if (missing.all { m[it] == true }) r.grant(grantList) else r.deny() }
                        permLauncher.launch(missing.toTypedArray())
                    }
                }
                .setNegativeButton("না") { _, _ -> r.deny() }
                .setOnCancelListener { r.deny() }
                .show()
        }
    }

    fun geolocationPrompt(origin: String?, cb: GeolocationPermissions.Callback?) {
        val c = cb ?: return
        if (host.isAiOn()) { c.invoke(origin, false, false); return }
        AlertDialog.Builder(act)
            .setTitle("লোকেশন")
            .setMessage(hostOf(origin) + " আপনার লোকেশন জানতে চায়।")
            .setPositiveButton("অনুমতি দাও") { _, _ ->
                val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                val has = perms.any { ContextCompat.checkSelfPermission(act, it) == PackageManager.PERMISSION_GRANTED }
                if (has) c.invoke(origin, true, false)
                else {
                    pendingPermAction = { m -> c.invoke(origin, m.values.any { it }, false) }
                    permLauncher.launch(perms)
                }
            }
            .setNegativeButton("না") { _, _ -> c.invoke(origin, false, false) }
            .setOnCancelListener { c.invoke(origin, false, false) }
            .show()
    }

    // ------------------------------------------------------------------
    // ফুল-স্ক্রিন ভিডিও
    // ------------------------------------------------------------------

    fun showCustomView(view: View?, cb: WebChromeClient.CustomViewCallback?) {
        if (view == null) return
        if (customView != null) { cb?.onCustomViewHidden(); return }
        customView = view
        customCallback = cb
        val decor = act.window.decorView as FrameLayout
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun hideCustomView() {
        val v = customView ?: return
        val decor = act.window.decorView as FrameLayout
        decor.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------
    // ডায়ালগ / সার্টিফিকেট / লগইন / এরর পেজ / অন্য স্কিম
    // ------------------------------------------------------------------

    fun jsAlert(message: String?, onDone: () -> Unit) {
        AlertDialog.Builder(act).setMessage(message.orEmpty())
            .setPositiveButton("ঠিক আছে") { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    fun jsPrompt(message: String?, default: String?, onResult: (String?) -> Unit) {
        val input = EditText(act)
        input.setText(default.orEmpty())
        input.setSingleLine(true)
        AlertDialog.Builder(act).setMessage(message.orEmpty()).setView(input)
            .setPositiveButton("ঠিক আছে") { _, _ -> onResult(input.text.toString()) }
            .setNegativeButton("বাতিল") { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .show()
    }

    fun sslProblem(hostName: String, onProceed: () -> Unit, onCancel: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle("নিরাপত্তা সতর্কতা")
            .setMessage(hostName + "-এর সার্টিফিকেটে সমস্যা আছে — সংযোগ নিরাপদ নাও হতে পারে। এগোবেন?")
            .setPositiveButton("তবুও এগোও") { _, _ -> onProceed() }
            .setNegativeButton("ফিরে যাও") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    fun httpAuth(hostName: String, realm: String, onLogin: (String, String) -> Unit, onCancel: () -> Unit) {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(48, 16, 48, 0)
        val user = EditText(act)
        user.hint = "ইউজারনেম"
        user.setSingleLine(true)
        val pass = EditText(act)
        pass.hint = "পাসওয়ার্ড"
        pass.setSingleLine(true)
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(user)
        box.addView(pass)
        AlertDialog.Builder(act)
            .setTitle(hostName + " — লগইন")
            .setMessage(realm)
            .setView(box)
            .setPositiveButton("লগইন") { _, _ -> onLogin(user.text.toString(), pass.text.toString()) }
            .setNegativeButton("বাতিল") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun showErrorPage(view: WebView?, failingUrl: String?, code: Int, desc: String?) {
        val w = view ?: return
        val u = failingUrl.orEmpty()
        if (u.isEmpty()) return
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/>" +
            "<style>body{font-family:sans-serif;padding:28px;color:#0F172A}h2{margin-top:48px}p{color:#64748B;word-break:break-all}" +
            "a.b{display:inline-block;margin-top:18px;padding:10px 20px;border-radius:22px;background:#14B8A6;color:#fff;text-decoration:none}</style></head>" +
            "<body><h2>পেজটি খোলা গেল না</h2><p>" + esc(u) + "</p><p>" + esc(desc.orEmpty()) + " (" + code + ")</p>" +
            "<a class='b' href='" + esc(u) + "'>আবার চেষ্টা করুন</a></body></html>"
        w.loadDataWithBaseURL(u, html, "text/html", "UTF-8", u)
    }

    /** http/https ছাড়া লিংক (tel:, mailto:, intent://, market:…) — ইউজারকে জিজ্ঞেস করে অন্য অ্যাপে খোলে। */
    fun openExternalScheme(uri: Uri) {
        val scheme = uri.scheme.orEmpty().lowercase()
        val what = when (scheme) {
            "tel" -> "ফোন"
            "mailto" -> "ইমেইল"
            "sms", "smsto" -> "মেসেজ"
            "geo" -> "ম্যাপ"
            else -> "অন্য একটি"
        }
        AlertDialog.Builder(act)
            .setMessage("এই লিংক $what অ্যাপে খুলবে। খুলবেন?")
            .setPositiveButton("খুলুন") { _, _ ->
                try {
                    val intent = if (scheme == "intent") {
                        val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        i.addCategory(Intent.CATEGORY_BROWSABLE)
                        i.component = null
                        i.selector = null
                        i
                    } else Intent(Intent.ACTION_VIEW, uri)
                    act.startActivity(intent)
                } catch (e: Exception) {
                    toast("এই লিংক খোলার অ্যাপ নেই")
                }
            }
            .setNegativeButton("না", null)
            .show()
    }

    // ------------------------------------------------------------------
    // ব্যাক-বোতাম / শেষ
    // ------------------------------------------------------------------

    /** true = ব্রাউজার নিজেই সামলেছে (অ্যাক্টিভিটি যেন বন্ধ না হয়)। */
    fun onBackPressed(): Boolean {
        if (customView != null) { hideCustomView(); return true }
        if (findBar.visibility == View.VISIBLE) { closeFind(); return true }
        if (urlInput.hasFocus()) { urlInput.clearFocus(); hideKeyboard(); return true }
        val t = current ?: return false
        if (t.wv.canGoBack()) { t.wv.goBack(); return true }
        if (tabs.size > 1 && !host.isAiOn()) { closeTab(t); return true }
        return false
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        for (t in tabs) {
            try { t.wv.stopLoading(); t.wv.destroy() } catch (e: Exception) { }
        }
        tabs.clear()
    }
}
