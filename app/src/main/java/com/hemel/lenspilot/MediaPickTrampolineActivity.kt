package com.hemel.lenspilot

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * v81: সার্ভিস (বাবল-চ্যাট) নিজে পিকার খুলতে পারে না — এই অদৃশ্য Activity খুলে দেয়। [EXTRA_KIND]:
 *  "image" = গ্যালারির ছবি, "video" = গ্যালারির ভিডিও (ফ্রেম তুলে একটা কনট্যাক্ট-শিট), "file" = PDF/Word/Excel/PowerPoint/টেক্সট/কোড।
 * মেইন চ্যাটের "+" যা করে ([MainActivity.loadFileAttachment]) এখানেও একই নিয়ম। ফল [PendingMediaPick]-এ যায়, তারপর নিজে বন্ধ।
 */
class MediaPickTrampolineActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
        private const val REQUEST_CODE = 4178
        private const val MAX_DIMENSION = 1024
        private const val JPEG_QUALITY = 75
        private const val FILE_CAP = 24_000
    }

    private var kind = "image"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent?.getStringExtra(EXTRA_KIND) ?: "image"
        val pick = when (kind) {
            "video" -> GalleryPick.videoIntent()
            "file" -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            else -> GalleryPick.intent()
        }
        try {
            startActivityForResult(pick, REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "No app was found to pick this", Toast.LENGTH_SHORT).show()
            PendingMediaPick.deliver(null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) { PendingMediaPick.deliver(null); finish(); return }
        // ভারী কাজ ব্যাকগ্রাউন্ডে; শেষে মেইন থ্রেডে ফল দিয়ে বন্ধ
        thread(name = "media-pick") {
            val picked = runCatching { process(uri) }.getOrNull()
            runOnUiThread {
                if (picked == null) Toast.makeText(this, failMessage(), Toast.LENGTH_LONG).show()
                PendingMediaPick.deliver(picked)
                finish()
            }
        }
    }

    private fun failMessage() = when (kind) {
        "video" -> "Could not read this video (too large, protected or unsupported)."
        "file" -> "This file can't be read. Supported: PDF, Word, Excel, PowerPoint, text and code files."
        else -> "The image could not be read"
    }

    private fun process(uri: Uri): PendingMediaPick.Picked? = when (kind) {
        "video" -> VideoFrames.extract(this, uri)?.let {
            PendingMediaPick.Picked(it.toBase64(), it.context(), it.label())
        }
        "file" -> processFile(uri)
        else -> contentResolver.openInputStream(uri)?.use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return@use null
            PendingMediaPick.Picked(toB64(downscale(original)), null, "🖼️")
        }
    }

    private fun processFile(uri: Uri): PendingMediaPick.Picked? = when (val res = AttachmentExtractor.extract(this, uri)) {
        is AttachmentExtractor.Result.Text -> {
            val body = res.text.take(FILE_CAP)
            val cut = res.truncated || res.text.length > FILE_CAP
            PendingMediaPick.Picked(
                null,
                "[Attached file: ${res.name}]\n" + body + (if (cut) "\n[…file cut here — only the beginning was sent]" else "") + "\n[End of file]",
                "📎 ${res.name}" + (if (cut) " (beginning only)" else "")
            )
        }
        is AttachmentExtractor.Result.Pdf -> PendingMediaPick.Picked(
            toB64(res.bitmap),
            "[Attached PDF: ${res.name} — the image below shows its first ${res.shownPages} of ${res.totalPages} pages]",
            "📎 ${res.name}" + (if (res.totalPages > res.shownPages) " (first ${res.shownPages} pages)" else "")
        )
        else -> null
    }

    private fun downscale(b: Bitmap): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= MAX_DIMENSION) return b
        val s = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun toB64(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
