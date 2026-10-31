package com.hemel.lenspilot.tools.browser

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.hemel.lenspilot.tools.ToolsPaths
import java.io.File
import java.util.Locale

/**
 * ব্রাউজার আপলোড: সেশনের Storage (workspace/) থেকে ফাইল বাছা।
 *  - [resolve]: এজেন্টের upload_file অ্যাকশন — নাম বা আপেক্ষিক পাথ দিয়ে ফাইল খোঁজে (workspace-এর বাইরে যাওয়া যায় না)।
 *  - [showChooser]: ম্যানুয়াল ফাইল-চুজারে Storage-এর সাম্প্রতিক ফাইল + "ফোনের ফাইল (সিস্টেম পিকার)"।
 */
object ToolsUploads {

    private const val LIST_LIMIT = 40
    private const val SEARCH_DEPTH = 8
    private val TEMP_SUFFIX = listOf(".part", ".crdownload", ".tmp", ".download")

    private fun isTemp(f: File): Boolean = TEMP_SUFFIX.any { f.name.endsWith(it, ignoreCase = true) }

    private fun insideRoot(root: File, f: File): Boolean = try {
        f.canonicalPath.startsWith(root.canonicalPath + File.separator)
    } catch (e: Exception) { false }

    /** নাম বা আপেক্ষিক পাথ → ফাইল। না পেলে বা workspace-এর বাইরে হলে null। একাধিক মিললে সবচেয়ে নতুনটা। */
    fun resolve(ctx: Context, sessionId: String, nameOrPath: String): File? {
        val wanted = nameOrPath.trim().replace('\\', '/').trimStart('/')
        if (wanted.isEmpty() || wanted.split('/').any { it == ".." }) return null
        val root = ToolsPaths.workspaceDir(ctx, sessionId)
        if (!root.isDirectory) return null
        val direct = File(root, wanted)
        if (direct.isFile && !isTemp(direct) && insideRoot(root, direct)) return direct
        val base = wanted.substringAfterLast('/')
        return root.walkTopDown().maxDepth(SEARCH_DEPTH).onEnter { it.name != ".git" }
            .take(5000)
            .filter { it.isFile && !isTemp(it) && it.name.equals(base, ignoreCase = true) && insideRoot(root, it) }
            .maxByOrNull { it.lastModified() }
    }

    /** পিকারে দেখানোর জন্য সাম্প্রতিক ফাইল (নতুন আগে)। */
    fun recentFiles(ctx: Context, sessionId: String, limit: Int = LIST_LIMIT): List<File> {
        val root = ToolsPaths.workspaceDir(ctx, sessionId)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(SEARCH_DEPTH).onEnter { it.name != ".git" }
            .take(5000)
            .filter { it.isFile && !isTemp(it) }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()
    }

    /** ফাইলের WebView-যোগ্য Uri (অ্যাপের নিজের ফোল্ডার, তাই file:// ঠিক চলে — ইন-প্রসেস কলব্যাক)। */
    fun uriOf(f: File): Uri = Uri.fromFile(f)

    private fun sizeText(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
    }

    /**
     * Storage-ফাইল + সিস্টেম-পিকারের তালিকা। [onPick] ঠিক একবার ডাকা হয়:
     * Storage-ফাইল বাছলে Uri, বাতিল করলে null। সিস্টেম-পিকার বাছলে [onSystem] ডাকা হয় (onPick নয়) —
     * তখন কলব্যাক সিস্টেম-পিকারের ফলাফলে পূরণ হবে।
     */
    fun showChooser(act: Activity, sessionId: String, onPick: (Uri?) -> Unit, onSystem: () -> Unit) {
        val files = try { recentFiles(act, sessionId) } catch (e: Exception) { emptyList() }
        val root = ToolsPaths.workspaceDir(act, sessionId)
        val labels = ArrayList<String>()
        for (f in files) {
            val rel = f.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
            labels.add("📁 $rel  (${sizeText(f.length())})")
        }
        labels.add("📱 ফোনের ফাইল (সিস্টেম পিকার)")
        var done = false
        fun once(block: () -> Unit) { if (!done) { done = true; block() } }
        AlertDialog.Builder(act)
            .setTitle("আপলোডের ফাইল বাছুন")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= files.size) once { onSystem() }
                else once { onPick(uriOf(files[which])) }
            }
            .setNegativeButton("বাতিল") { _, _ -> once { onPick(null) } }
            .setOnCancelListener { once { onPick(null) } }
            .show()
    }
}
