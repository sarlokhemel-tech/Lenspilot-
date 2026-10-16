package com.hemel.lenspilot

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Invisible one-frame Activity used ONLY so a Service (which can't launch
 * an image picker or receive its result on its own) can still let the user
 * attach a picture to send to the AI — see [PendingImagePick]. Opens the
 * system gallery/photo picker, downscales+compresses whatever comes back
 * to a small JPEG (this rides along in the SAME request as the typed
 * message, so it has to stay cheap — long side capped at 1024px, quality
 * 75), base64-encodes it, hands it to [PendingImagePick.deliver], then
 * finishes itself immediately. No visible UI at any point.
 */
class ImagePickTrampolineActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 4177
        private const val MAX_DIMENSION = 1024
        private const val JPEG_QUALITY = 75
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "ছবি বেছে নেওয়ার অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            PendingImagePick.deliver(null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) {
            PendingImagePick.deliver(null)
            finish()
            return
        }
        val base64 = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return@use null
                val scaled = downscale(original, MAX_DIMENSION)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
        if (base64 == null) {
            Toast.makeText(this, "ছবিটা পড়া গেল না", Toast.LENGTH_SHORT).show()
        }
        PendingImagePick.deliver(base64)
        finish()
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
