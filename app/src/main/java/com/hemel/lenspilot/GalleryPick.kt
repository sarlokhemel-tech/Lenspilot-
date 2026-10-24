package com.hemel.lenspilot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * v40: ছবি আপলোডে সবসময় ফোনের **গ্যালারি** (ছবির অ্যালবাম) খোলে — ফাইল ম্যানেজার/ডকুমেন্ট-পিকার নয়।
 * Android 13+ এ সিস্টেমের ফটো-পিকার (শুধু গ্যালারির ছবি), তার আগে গ্যালারি অ্যাপ (ACTION_PICK, ছবির স্টোর)।
 * কোনো স্টোরেজ-অনুমতি লাগে না — পিক-করা ছবির URI-তে পড়ার অধিকার সিস্টেমই দেয়।
 */
object GalleryPick {

    fun intent(): Intent =
        if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }

    /** পুরোনো `GetContent()` + `"image/…"` (সব ছবির ধরন)-এর জায়গায়: `registerForActivityResult(GalleryPick.Contract()) { uri -> … }`, `launch(Unit)`। */
    class Contract : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent = intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}
