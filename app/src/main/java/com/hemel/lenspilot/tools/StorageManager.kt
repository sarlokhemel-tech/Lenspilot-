package com.hemel.lenspilot.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Storage ওয়ার্কস্পেসের ফাইল-ম্যানেজার লজিক। প্রতিটি পাথ canonical-path চেক দিয়ে
 * রুটের ভেতরে আটকে রাখা হয় (".." বা symlink দিয়ে বাইরে যাওয়া যায় না)। সব মেথড blocking —
 * UI থ্রেডের বাইরে (ToolsAsync.io) থেকে ডাকতে হবে। সব পাথ রুটের সাপেক্ষে "a/b/c" ধরনের
 * আপেক্ষিক স্ট্রিং; রুট নিজে "" (খালি)।
 */
class StorageManager(root: File) {

    data class Entry(
        val name: String,
        val relPath: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long
    )

    private val rootDir: File = run {
        root.mkdirs()
        root.canonicalFile
    }

    // ---------- পাথ নিরাপত্তা ----------

    private fun isUnder(base: File, f: File): Boolean =
        f.path == base.path || f.path.startsWith(base.path + File.separator)

    /** আপেক্ষিক পাথ → canonical File; রুটের বাইরে গেলে SecurityException। */
    fun resolve(rel: String): File {
        val clean = rel.trim().trimStart('/', '\\')
        val f = if (clean.isEmpty()) rootDir else File(rootDir, clean).canonicalFile
        if (!isUnder(rootDir, f)) throw SecurityException("পাথ ওয়ার্কস্পেসের বাইরে")
        return f
    }

    fun relOf(f: File): String {
        val c = f.canonicalFile
        if (c.path == rootDir.path) return ""
        if (!isUnder(rootDir, c)) throw SecurityException("পাথ ওয়ার্কস্পেসের বাইরে")
        return c.path.substring(rootDir.path.length + 1)
    }

    private fun isSymlink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    // ---------- তালিকা ও খোঁজা ----------

    fun list(rel: String): List<Entry> {
        val dir = resolve(rel)
        if (!dir.isDirectory) throw IOException("এটা ফোল্ডার নয়")
        val kids = dir.listFiles() ?: return emptyList()
        return sortEntries(kids.mapNotNull { toEntry(it) })
    }

    /** নামের অংশ (বড়-ছোট হাত নির্বিশেষে) দিয়ে rel-এর ভেতরে রিকার্সিভ খোঁজা। */
    fun search(rel: String, query: String, limit: Int = SEARCH_LIMIT): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val start = resolve(rel)
        if (!start.isDirectory) return emptyList()
        val out = ArrayList<Entry>()
        var visited = 0
        val walk = start.walkTopDown().onEnter { d -> d == start || !isSymlink(d) }
        for (f in walk) {
            visited++
            if (visited > SEARCH_MAX_VISITED) break
            if (f == start) continue
            if (f.name.lowercase().contains(q)) {
                val e = toEntry(f)
                if (e != null) out.add(e)
                if (out.size >= limit) break
            }
        }
        return sortEntries(out)
    }

    private fun sortEntries(list: List<Entry>): List<Entry> =
        list.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })

    private fun toEntry(f: File): Entry? {
        val c = try {
            f.canonicalFile
        } catch (e: IOException) {
            return null
        }
        if (!isUnder(rootDir, c) || c.path == rootDir.path) return null
        return Entry(f.name, relOf(c), c.isDirectory, if (c.isDirectory) 0L else c.length(), c.lastModified())
    }

    // ---------- তৈরি / নাম বদল / মোছা ----------

    fun mkdir(parentRel: String, name: String): String {
        val parent = resolve(parentRel)
        if (!parent.isDirectory) throw IOException("প্যারেন্ট ফোল্ডার নেই")
        val clean = validateName(name)
        val dir = File(parent, clean)
        if (dir.exists()) throw IOException("এই নামে আগে থেকেই আছে")
        if (!dir.mkdir()) throw IOException("ফোল্ডার বানানো যায়নি")
        return relOf(dir)
    }

    fun rename(rel: String, newName: String): String {
        val src = resolve(rel)
        if (src.path == rootDir.path) throw SecurityException("রুট বদলানো যায় না")
        if (!src.exists()) throw FileNotFoundException(rel)
        val clean = validateName(newName)
        val parent = src.parentFile ?: throw IOException("প্যারেন্ট নেই")
        val dest = File(parent, clean)
        if (dest.exists()) throw IOException("এই নামে আগে থেকেই আছে")
        if (!src.renameTo(dest)) throw IOException("নাম বদলানো যায়নি")
        return relOf(dest)
    }

    fun delete(rel: String) {
        val f = resolve(rel)
        if (f.path == rootDir.path) throw SecurityException("রুট মোছা যায় না")
        if (!f.exists()) return
        if (!SafeFs.deleteTree(f)) throw IOException("পুরোটা মোছা যায়নি")
    }

    // ---------- আমদানি (SAF) ----------

    /** SAF Uri থেকে স্ট্রিম-কপি (পুরো ফাইল মেমরিতে আনে না)। নাম মিললে "(1)" যোগ হয়। */
    fun importFromUri(ctx: Context, uri: Uri, destRel: String = ""): File {
        val dir = resolve(destRel)
        if (!dir.isDirectory) throw IOException("গন্তব্য ফোল্ডার নেই")
        val display = queryDisplayName(ctx, uri) ?: uri.lastPathSegment ?: "file"
        val out = File(dir, uniqueName(dir, sanitizeName(display)))
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("ফাইল খোলা যায়নি")
            input.use { ins ->
                FileOutputStream(out).use { fos ->
                    // সেশন ৮: ডিস্ক ভরে যাওয়া ঠেকাতে সীমা (অজানা-আকারের স্ট্রিমও ধরা পড়ে)
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > IMPORT_MAX_BYTES) throw IOException("ফাইল অনেক বড় (সীমা ২ জিবি)")
                        fos.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- ফোনের Downloads-এ রপ্তানি ----------

    /** ফাইল সরাসরি, ফোল্ডার হলে ZIP করে। API 29+ MediaStore (পারমিশন লাগে না); পুরনো ফোনে অ্যাপের Download ফোল্ডার। */
    fun exportToDownloads(ctx: Context, rel: String): String {
        if (rel.isBlank()) throw IOException("রুট সরাসরি রপ্তানি হয় না — একটা ফাইল/ফোল্ডার বেছে নিন")
        val tempDir = File(ctx.cacheDir, "tools_export")
        SafeFs.deleteTree(tempDir)
        val src: File = materialize(resolve(rel), tempDir)
        try {
            val name = src.name
            val mime = mimeOf(name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lenspilot")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Downloads-এ ফাইল তৈরি হয়নি")
                try {
                    val os = resolver.openOutputStream(uri) ?: throw IOException("Downloads-এ লেখা যায়নি")
                    os.use { out -> FileInputStream(src).use { it.copyTo(out, 64 * 1024) } }
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                return "Download/Lenspilot/$name"
            } else {
                val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IOException("ডাউনলোড ফোল্ডার পাওয়া যায়নি")
                val dir = File(base, "Lenspilot")
                dir.mkdirs()
                val dest = File(dir, uniqueName(dir, name))
                src.copyTo(dest, overwrite = false)
                return dest.absolutePath
            }
        } finally {
            SafeFs.deleteTree(tempDir)
        }
    }

    /** শেয়ারের জন্য cache-এ কপি (ফোল্ডার হলে ZIP)। FileProvider-এর cache-path এটা কভার করে। */
    fun prepareShareFile(ctx: Context, rel: String): File {
        if (rel.isBlank()) throw IOException("রুট সরাসরি শেয়ার হয় না")
        val dir = File(ctx.cacheDir, "tools_share")
        SafeFs.deleteTree(dir)
        return materialize(resolve(rel), dir)
    }

    private fun materialize(src: File, outDir: File): File {
        if (!src.exists()) throw FileNotFoundException(src.name)
        outDir.mkdirs()
        return if (src.isDirectory) {
            val out = File(outDir, src.name + ".zip")
            zipDirTo(src, out)
            out
        } else {
            val out = File(outDir, src.name)
            src.copyTo(out, overwrite = true)
            out
        }
    }

    // ---------- ZIP ----------

    /** ফোল্ডারের পাশেই "<নাম>.zip" বানায়; নতুন ZIP-এর আপেক্ষিক পাথ ফেরত দেয়। */
    fun zipFolder(rel: String): String {
        val dir = resolve(rel)
        if (dir.path == rootDir.path) throw IOException("রুট ZIP করা যায় না — একটা ফোল্ডার বেছে নিন")
        if (!dir.isDirectory) throw IOException("এটা ফোল্ডার নয়")
        val parent = dir.parentFile ?: throw IOException("প্যারেন্ট নেই")
        val out = File(parent, uniqueName(parent, dir.name + ".zip"))
        try {
            zipDirTo(dir, out)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return relOf(out)
    }

    private fun zipDirTo(dir: File, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            var count = 0
            val walk = dir.walkTopDown().onEnter { d -> d == dir || !isSymlink(d) }
            for (f in walk) {
                if (f == dir || f == out || isSymlink(f)) continue
                count++
                if (count > ZIP_MAX_ENTRIES) throw IOException("ZIP-এ আইটেম সীমার বেশি ($ZIP_MAX_ENTRIES)")
                val name = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$name/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(name))
                    FileInputStream(f).use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * ZIP-টা যে ফোল্ডারে আছে সেখানেই এক্সট্র্যাক্ট। zip-slip নিরাপদ (প্রতিটি এন্ট্রি canonical চেক),
     * এন্ট্রি-সংখ্যা ও মোট বাইটে সীমা। ব্যর্থ হলে এই রানে নতুন তৈরি ফাইলগুলো মুছে ফেলে। এক্সট্র্যাক্ট-করা আইটেম সংখ্যা ফেরত দেয়।
     */
    fun unzip(zipRel: String): Int {
        val zipFile = resolve(zipRel)
        if (!zipFile.isFile) throw FileNotFoundException(zipRel)
        val destDir = (zipFile.parentFile ?: rootDir).canonicalFile
        if (!isUnder(rootDir, destDir)) throw SecurityException("পাথ ওয়ার্কস্পেসের বাইরে")
        val created = ArrayList<File>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
                val buf = ByteArray(64 * 1024)
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    entries++
                    if (entries > UNZIP_MAX_ENTRIES) throw IOException("ZIP-এ আইটেম সীমার বেশি ($UNZIP_MAX_ENTRIES)")
                    val target = File(destDir, e.name).canonicalFile
                    if (!isUnder(destDir, target)) throw SecurityException("সন্দেহজনক ZIP পাথ: ${e.name}")
                    if (target == zipFile || target == destDir) throw SecurityException("সন্দেহজনক ZIP পাথ: ${e.name}")
                    if (e.isDirectory) {
                        if (!target.exists()) {
                            target.mkdirs()
                            created.add(target)
                        }
                    } else {
                        target.parentFile?.mkdirs()
                        val existed = target.exists()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = zin.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > UNZIP_MAX_BYTES) throw IOException("ZIP-এর মোট আকার সীমার বেশি")
                                out.write(buf, 0, n)
                            }
                        }
                        if (!existed) created.add(target)
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        } catch (ex: Exception) {
            // নতুন তৈরি আইটেম পেছন থেকে মুছি (ফাইল আগে, ফোল্ডার পরে)
            for (f in created.asReversed()) SafeFs.deleteTree(f)
            throw ex
        }
        return entries
    }

    // ---------- নামের হেল্পার ----------

    /** ইউজারের দেওয়া নাম যাচাই — "/" বা অবৈধ অক্ষর থাকলে বাতিল। */
    private fun validateName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == "..") throw IOException("নামটা সঠিক নয়")
        if (n.any { it == '/' || it == '\\' || it.code < 32 }) throw IOException("নামে / বা বিশেষ অক্ষর থাকতে পারে না")
        if (n.length > 200) throw IOException("নাম অনেক লম্বা")
        return n
    }

    private fun sanitizeName(name: String): String {
        var n = name.trim().map { if (it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' || it.code < 32) '_' else it }
            .joinToString("")
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        if (n.length > 200) n = n.take(200)
        return n
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i++
        return "$base ($i)$ext"
    }

    companion object {
        const val SEARCH_LIMIT = 200
        const val SEARCH_MAX_VISITED = 50_000
        const val ZIP_MAX_ENTRIES = 50_000
        const val UNZIP_MAX_ENTRIES = 20_000
        const val UNZIP_MAX_BYTES = 800L * 1024 * 1024
        const val IMPORT_MAX_BYTES = 2L * 1024 * 1024 * 1024

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
