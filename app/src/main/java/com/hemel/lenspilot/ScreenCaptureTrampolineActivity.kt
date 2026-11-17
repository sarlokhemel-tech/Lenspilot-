package com.hemel.lenspilot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.hemel.lenspilot.vision.ScreenCaptureService

/**
 * v60: Accessibility ছাড়া চলা মোডগুলোর (বাবল এজেন্টের "Screen", Guide, Scan) জন্য স্ক্রিন-ক্যাপচার অনুমতি।
 * Service নিজে অনুমতির ডায়ালগ খুলতে পারে না — তাই এই অদৃশ্য Activity সিস্টেমের "স্ক্রিন রেকর্ড/কাস্ট শুরু করবে?"
 * ডায়ালগ দেখায়, অনুমতি পেলে [ScreenCaptureService] চালু করে, ফলাফল [ScreenCaptureGrant]-এর মাধ্যমে কলারকে জানায়
 * (ImagePickTrampolineActivity/PendingImagePick-এর একই ধাঁচ)।
 */
class ScreenCaptureTrampolineActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 4178
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            // Android 14+: "শুধু একটা অ্যাপ" বাছার সুযোগ বাদ — পুরো স্ক্রিন, নইলে হোম/অন্য অ্যাপ দেখা যাবে না
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mgr.createScreenCaptureIntent()
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture isn't available on this phone", Toast.LENGTH_SHORT).show()
            ScreenCaptureGrant.deliver(false)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            val started = try { startForegroundService(svc); true } catch (e: Exception) { false }
            ScreenCaptureGrant.deliver(started)
        } else {
            ScreenCaptureGrant.deliver(false)
        }
        finish()
    }

    override fun onDestroy() {
        // ইউজার ব্যাক চেপে বেরোলেও কলার যেন আটকে না থাকে (deliver দ্বিতীয়বার হলে কিছু করে না)
        ScreenCaptureGrant.deliver(false)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

/** [ScreenCaptureTrampolineActivity] → অনুরোধকারী সার্ভিস: এককালীন কলব্যাক (দেখো PendingImagePick)। */
object ScreenCaptureGrant {
    private var callback: ((Boolean) -> Unit)? = null

    fun awaitNext(cb: (Boolean) -> Unit) { callback = cb }

    fun deliver(granted: Boolean) {
        val cb = callback
        callback = null
        cb?.invoke(granted)
    }
}
