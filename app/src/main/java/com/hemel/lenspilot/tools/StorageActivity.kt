package com.hemel.lenspilot.tools

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Storage ওয়ার্কস্পেসের ফাইল-ম্যানেজার UI। EXTRA_SESSION_ID দিলে ওই সেশনের workspace/,
 * না দিলে (বা অবৈধ হলে) গ্লোবাল ওয়ার্কস্পেস। ব্রেডক্রাম্ব, লিস্ট, সার্চ, আপলোড (SAF),
 * লং-প্রেস মেনু (ডাউনলোড/শেয়ার/নাম বদল/মোছা/এক্সট্র্যাক্ট/ZIP) ও সহজ টেক্সট/ছবির প্রিভিউ।
 */
class StorageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        private const val PREVIEW_MAX_BYTES = 200 * 1024
        private const val PREVIEW_IMAGE_MAX_DIM = 2048
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }

    private lateinit var storage: StorageManager
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: View
    private lateinit var crumb: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var searchBar: EditText
    private lateinit var adapter: EntryAdapter

    private var currentRel = ""
    private var searchQuery = ""
    private var ignoreWatcher = false
    private var busyCount = 0
    private var refreshToken = 0
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val uploadLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        var title = getString(R.string.tools_storage_title)
        val root: File
        val session = if (ToolsPaths.isValidId(sessionId)) SessionStore.get(this, sessionId!!) else null
        if (session != null) {
            root = ToolsPaths.workspaceDir(this, session.id)
            title = session.title
        } else {
            root = ToolsPaths.globalWorkspace(this)
        }
        storage = StorageManager(root)

        findViewById<TextView>(R.id.storageTitle).text = title
        findViewById<ImageButton>(R.id.storageBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.storageUpload).setOnClickListener { uploadLauncher.launch(arrayOf("*/*")) }
        findViewById<ImageButton>(R.id.storageNewFolder).setOnClickListener { promptNewFolder() }
        findViewById<ImageButton>(R.id.storageSearchBtn).setOnClickListener { toggleSearch() }

        listView = findViewById(R.id.storageList)
        emptyView = findViewById(R.id.storageEmpty)
        progress = findViewById(R.id.storageProgress)
        crumb = findViewById(R.id.storageCrumb)
        crumbScroll = findViewById(R.id.storageCrumbScroll)
        searchBar = findViewById(R.id.storageSearchBar)

        adapter = EntryAdapter(
            onClick = { e -> if (e.isDir) openDir(e.relPath) else previewFile(e) },
            onLongClick = { e -> showMenu(e) }
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreWatcher) return
                searchQuery = s?.toString()?.trim() ?: ""
                searchRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { refresh() }
                searchRunnable = r
                handler.postDelayed(r, 300)
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchBar.visibility == View.VISIBLE -> closeSearch()
                    currentRel.isNotEmpty() -> openDir(currentRel.substringBeforeLast('/', ""))
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        buildBreadcrumb()
        refresh()
    }

    override fun onDestroy() {
        searchRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun alive(): Boolean = !isFinishing && !isDestroyed

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun errText(t: Throwable): String =
        getString(R.string.tools_err_generic, t.message ?: t.javaClass.simpleName)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setBusy(on: Boolean) {
        busyCount += if (on) 1 else -1
        if (busyCount < 0) busyCount = 0
        progress.visibility = if (busyCount > 0) View.VISIBLE else View.GONE
    }

    /** ব্যাকগ্রাউন্ডে work চালায়, ফল UI থ্রেডে done-এ দেয়; ব্যতিক্রম হলে টোস্ট। */
    private fun <T> bg(work: () -> T, done: (T) -> Unit) {
        setBusy(true)
        ToolsAsync.io {
            val r: Result<T> = try {
                Result.success(work())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            ToolsAsync.ui {
                if (!alive()) return@ui
                setBusy(false)
                r.onSuccess { done(it) }.onFailure { toast(errText(it)) }
            }
        }
    }

    // ---------- নেভিগেশন ও তালিকা ----------

    private fun openDir(rel: String) {
        currentRel = rel
        if (searchBar.visibility == View.VISIBLE) closeSearch(refreshAfter = false)
        buildBreadcrumb()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        val rel = currentRel
        val q = searchQuery
        ToolsAsync.io {
            val result: List<StorageManager.Entry>? = try {
                if (q.isBlank()) storage.list(rel) else storage.search(rel, q)
            } catch (e: Exception) {
                null
            }
            ToolsAsync.ui {
                if (!alive() || token != refreshToken) return@ui
                if (result == null) {
                    // ফোল্ডার আর নেই (যেমন মুছে গেছে) — রুটে ফিরি
                    if (currentRel.isNotEmpty()) {
                        currentRel = ""
                        buildBreadcrumb()
                        refresh()
                    }
                    return@ui
                }
                adapter.submit(result, q.isNotBlank())
                emptyView.text = getString(if (q.isBlank()) R.string.tools_storage_empty else R.string.tools_storage_search_empty)
                emptyView.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun buildBreadcrumb() {
        crumb.removeAllViews()
        addCrumb(getString(R.string.tools_storage_root), "")
        if (currentRel.isNotEmpty()) {
            var acc = ""
            for (part in currentRel.split('/')) {
                acc = if (acc.isEmpty()) part else "$acc/$part"
                addCrumbSep()
                addCrumb(part, acc)
            }
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addCrumb(label: String, target: String) {
        val tv = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.primary_action))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { if (target != currentRel) openDir(target) }
        }
        crumb.addView(tv)
    }

    private fun addCrumbSep() {
        val tv = TextView(this).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(getColor(R.color.text_muted_light))
        }
        crumb.addView(tv)
    }

    private fun toggleSearch() {
        if (searchBar.visibility == View.VISIBLE) {
            closeSearch()
        } else {
            searchBar.visibility = View.VISIBLE
            searchBar.requestFocus()
        }
    }

    private fun closeSearch(refreshAfter: Boolean = true) {
        ignoreWatcher = true
        searchBar.setText("")
        ignoreWatcher = false
        searchQuery = ""
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchBar.visibility = View.GONE
        if (refreshAfter) refresh()
    }

    // ---------- আপলোড / নতুন ফোল্ডার ----------

    private fun importUris(uris: List<Uri>) {
        val dest = currentRel
        val app = applicationContext
        setBusy(true)
        ToolsAsync.io {
            var ok = 0
            var fail = 0
            for (u in uris) {
                try {
                    storage.importFromUri(app, u, dest)
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            ToolsAsync.ui {
                if (!alive()) return@ui
                setBusy(false)
                toast(getString(R.string.tools_upload_result, ok, fail))
                refresh()
            }
        }
    }

    private fun promptNewFolder() {
        promptText(R.string.tools_new_folder_title, "", getString(R.string.tools_folder_name_hint)) { name ->
            val parent = currentRel
            bg({ storage.mkdir(parent, name) }) { refresh() }
        }
    }

    private fun promptText(titleRes: Int, initial: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setHint(hint)
            setSingleLine(true)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(R.string.tools_ok) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton(R.string.tools_cancel, null)
            .show()
    }

    // ---------- লং-প্রেস মেনু ----------

    private fun showMenu(e: StorageManager.Entry) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(getString(R.string.tools_act_download)); actions.add { doDownload(e) }
        labels.add(getString(R.string.tools_act_share)); actions.add { doShare(e) }
        labels.add(getString(R.string.tools_act_rename)); actions.add { doRename(e) }
        if (e.isDir) {
            labels.add(getString(R.string.tools_act_zip)); actions.add { doZip(e) }
        } else if (e.name.lowercase().endsWith(".zip")) {
            labels.add(getString(R.string.tools_act_extract)); actions.add { doExtract(e) }
        }
        labels.add(getString(R.string.tools_act_delete)); actions.add { doDelete(e) }
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    private fun doDownload(e: StorageManager.Entry) {
        val app = applicationContext
        bg({ storage.exportToDownloads(app, e.relPath) }) { where ->
            toast(getString(R.string.tools_downloaded, where))
        }
    }

    private fun doShare(e: StorageManager.Entry) {
        val app = applicationContext
        bg({ storage.prepareShareFile(app, e.relPath) }) { file ->
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = StorageManager.mimeOf(file.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.tools_share_chooser)))
            } catch (ex: Exception) {
                toast(errText(ex))
            }
        }
    }

    private fun doRename(e: StorageManager.Entry) {
        promptText(R.string.tools_rename_title, e.name, "") { newName ->
            bg({ storage.rename(e.relPath, newName) }) { refresh() }
        }
    }

    private fun doDelete(e: StorageManager.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_delete_title)
            .setMessage(getString(R.string.tools_delete_msg, e.name))
            .setPositiveButton(R.string.tools_act_delete) { _, _ ->
                bg({ storage.delete(e.relPath) }) { refresh() }
            }
            .setNegativeButton(R.string.tools_cancel, null)
            .show()
    }

    private fun doExtract(e: StorageManager.Entry) {
        bg({ storage.unzip(e.relPath) }) { n ->
            toast(getString(R.string.tools_extracted, n))
            refresh()
        }
    }

    private fun doZip(e: StorageManager.Entry) {
        bg({ storage.zipFolder(e.relPath) }) { rel ->
            toast(getString(R.string.tools_zipped, rel.substringAfterLast('/')))
            refresh()
        }
    }

    // ---------- প্রিভিউ (নতুন লাইব্রেরি ছাড়া) ----------

    private fun previewFile(e: StorageManager.Entry) {
        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext in IMAGE_EXT) previewImage(e) else previewText(e)
    }

    private fun previewText(e: StorageManager.Entry) {
        bg({
            val f = storage.resolve(e.relPath)
            val bytes = FileInputStream(f).use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < PREVIEW_MAX_BYTES) {
                    val n = ins.read(buf, 0, minOf(buf.size, PREVIEW_MAX_BYTES - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
            var binary = false
            for (i in 0 until minOf(bytes.size, 2048)) {
                if (bytes[i].toInt() == 0) { binary = true; break }
            }
            if (binary) null else Pair(String(bytes, Charsets.UTF_8), f.length() > PREVIEW_MAX_BYTES)
        }) { res ->
            if (res == null) {
                toast(getString(R.string.tools_no_preview))
            } else {
                val tv = TextView(this).apply {
                    text = if (res.second) res.first + getString(R.string.tools_preview_truncated) else res.first
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                val sv = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setView(sv)
                    .setPositiveButton(R.string.tools_close_preview, null)
                    .show()
            }
        }
    }

    private fun previewImage(e: StorageManager.Entry) {
        bg({
            val path = storage.resolve(e.relPath).path
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_IMAGE_MAX_DIM) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }) { bmp ->
            if (bmp == null) {
                toast(getString(R.string.tools_no_preview))
            } else {
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.6).toInt()
                    )
                }
                val box = FrameLayout(this).apply { addView(iv) }
                AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setView(box)
                    .setPositiveButton(R.string.tools_close_preview, null)
                    .show()
            }
        }
    }

    // ---------- অ্যাডাপ্টার ----------

    private class EntryAdapter(
        private val onClick: (StorageManager.Entry) -> Unit,
        private val onLongClick: (StorageManager.Entry) -> Unit
    ) : RecyclerView.Adapter<EntryAdapter.VH>() {

        private var items: List<StorageManager.Entry> = emptyList()
        private var showPath = false

        fun submit(list: List<StorageManager.Entry>, showPath: Boolean) {
            items = list
            this.showPath = showPath
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.entryIcon)
            val name: TextView = v.findViewById(R.id.entryName)
            val meta: TextView = v.findViewById(R.id.entryMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_storage_entry, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.icon.setImageResource(if (e.isDir) R.drawable.ic_tools_folder else R.drawable.ic_tools_file)
            holder.name.text = e.name
            val base = if (e.isDir) ToolsFormat.date(e.modified)
            else ToolsFormat.size(e.size) + " · " + ToolsFormat.date(e.modified)
            holder.meta.text = if (showPath && e.relPath.contains('/')) e.relPath.substringBeforeLast('/') + "/  ·  " + base else base
            holder.itemView.setOnClickListener { onClick(e) }
            holder.itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }
}
