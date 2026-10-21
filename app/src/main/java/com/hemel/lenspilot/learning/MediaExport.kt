package com.hemel.lenspilot.learning

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * v31 — তৈরি-হওয়া ক্লাস ভিডিও (MP4) ও নোট PDF ফোনের "Download/Lenspilot" ফোল্ডারে সেভ করা এবং খোলা/শেয়ার করা।
 *
 * Android 10+ (API 29+): MediaStore.Downloads — কোনো স্টোরেজ অনুমতি লাগে না, ফাইল-ম্যানেজার/গ্যালারিতে সরাসরি দেখা যায়।
 * Android 8–9: অ্যাপের নিজস্ব এক্সটার্নাল ডাউনলোড-ফোল্ডার (অনুমতি ছাড়া) + FileProvider দিয়ে খোলা/শেয়ার।
 */
object MediaExport {
    const val FOLDER = "Lenspilot"

    class Saved(val uri: Uri, val mime: String, val where: String)

    /** [src] ফাইলটা Download/Lenspilot-এ [displayName] নামে কপি করে। ব্যর্থ হলে null। ব্লকিং — IO থ্রেডে ডাকো। */
    fun saveToDownloads(context: Context, src: File, displayName: String, mime: String): Saved? {
        val ctx = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: return null
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } catch (t: Throwable) {
                    try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                    return null
                }
                Saved(uri, mime, "Download/$FOLDER/$displayName")
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, FOLDER)
                dir.mkdirs()
                val dest = File(dir, displayName)
                src.copyTo(dest, overwrite = true)
                Saved(FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", dest), mime, dest.absolutePath)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** ফাইলের নামে ব্যবহার-অযোগ্য চিহ্ন বাদ দিয়ে ছোট নাম বানায়। */
    fun safeName(title: String, fallback: String): String {
        val cleaned = title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim()
        val base = cleaned.ifEmpty { fallback }
        return base.take(60).trim().ifEmpty { fallback }
    }

    fun open(context: Context, saved: Saved): Boolean {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(saved.uri, saved.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(i)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun share(context: Context, saved: Saved, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = saved.mime
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            val chooser = Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
