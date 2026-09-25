package com.hemel.lenspilot.freemium

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import com.hemel.lenspilot.databinding.ActivityFreemiumBinding
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.vision.ScreenCaptureService
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ফ্রিমিয়াম প্রোমো স্ক্রিন — "নিজের Gemini কী বসাও, কম বিজ্ঞাপন +
 * আনলিমিটেড ব্যবহার করো"। দুইটা পথ:
 *   ১) সরাসরি বক্সে কী পেস্ট করা (কেউ যদি আগে থেকেই কী বানিয়ে রেখেছে)
 *   ২) "গাইডলাইন শুরু করো" — একদম শূন্য থেকে ধাপে ধাপে কী বানানো শেখাবে
 *      (দেখো [FreemiumGuideOverlayService])।
 *
 * এই দুই পথই শেষমেশ একই সার্ভার এন্ডপয়েন্টে যায়: /api/freemium/activate
 * (দেখো app.py) — লাইভ চেক করে তবেই সেভ হয়।
 */
class FreemiumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFreemiumBinding

    // --- Tier 2 পারমিশন জোড়া, ঠিক MainActivity-র প্যাটার্নেই (দেখো ওখানকার
    // overlayPermissionLauncher/screenCaptureLauncher) — এখানে আলাদা রাখা
    // হয়েছে যাতে মূল টাস্ক-অটোমেশন ফ্লো-কে না ছুঁয়ে স্বয়ংসম্পূর্ণ থাকে। ---
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCaptureThenStartGuide()
        } else {
            Toast.makeText(this, "\"অন্য অ্যাপের উপর দেখানো\" অনুমতি ছাড়া গাইডলাইন দেখানো যাবে না", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)
            launchGuideOverlayAndOpenChrome()
        } else {
            Toast.makeText(this, "স্ক্রিন-ক্যাপচার অনুমতি ছাড়া গাইডলাইন কাজ করবে না", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreemiumBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        // পথ ১ — গাইডলাইন ছাড়াই সরাসরি বসানো
        binding.btnActivateDirect.setOnClickListener {
            val key = binding.editApiKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, "প্রথমে তোমার API কী বক্সে লেখো", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activateKey(key)
        }

        // পথ ২ — গাইডেড সেটআপ শুরু (overlay পারমিশন চেইন → Chrome deep link)
        binding.btnStartGuide.setOnClickListener {
            startGuidedSetup()
        }
    }

    private fun startGuidedSetup() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        requestScreenCaptureThenStartGuide()
    }

    private fun requestScreenCaptureThenStartGuide() {
        if (ScreenCaptureService.instance != null) {
            launchGuideOverlayAndOpenChrome()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** overlay সার্ভিস চালু করে + সাথে সাথেই Chrome-এ deep link দিয়ে সরাসরি
     * Gemini API-key পেজে (login লাগলে Google নিজেই login flow-এ নিয়ে
     * যাবে) — এটাই সেই "instant + deep linking দিয়ে chrome-এ ঢুকিয়ে দেওয়া"। */
    private fun launchGuideOverlayAndOpenChrome() {
        startService(Intent(this, FreemiumGuideOverlayService::class.java))

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FreemiumGuideOverlayService.TARGET_URL))
        // সম্ভব হলে সরাসরি Chrome — অন্য কোনো ব্রাউজারের নিজস্ব UI/OCR ভিন্ন
        // হতে পারে, তাই "chrome-এ ঢুকিয়ে দেওয়া"-র কথা মাথায় রেখে যতটা সম্ভব
        // নির্দিষ্ট করেই খোলা, তবে Chrome না থাকলে ডিফল্ট ব্রাউজারে fallback।
        intent.setPackage("com.android.chrome")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.setPackage(null)
            startActivity(intent)
        }
        Toast.makeText(this, "Chrome-এ নিয়ে যাচ্ছি — উপরের বাবল-এ নির্দেশনা দেখো", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun activateKey(key: String) {
        binding.btnActivateDirect.isEnabled = false
        lifecycleScope.launch {
            val body = JSONObject().put("api_key", key).toString()
            val result = ApiClient.callAuthed(this@FreemiumActivity, getString(R.string.space_base_url), "/api/freemium/activate", body)
            result.onSuccess {
                Toast.makeText(this@FreemiumActivity, "🎉 ফ্রিমিয়াম চালু হয়েছে!", Toast.LENGTH_LONG).show()
                finish()
            }.onFailure { e ->
                binding.btnActivateDirect.isEnabled = true
                Toast.makeText(this@FreemiumActivity, "কী দিয়ে কাজ করেনি: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
