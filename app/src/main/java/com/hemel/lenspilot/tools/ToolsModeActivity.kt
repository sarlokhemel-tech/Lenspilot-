package com.hemel.lenspilot.tools

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hemel.lenspilot.R

/**
 * Tools মোড — MainActivity-এর Learning Mode বাটনের ঠিক পাশ থেকে খোলে
 * (MainActivity.openToolsMode)। ধারণাটা: একটা ভিডিও বেছে নাও, স্বাধীনভাবে
 * (কোনো ধরাবাঁধা সিস্টেম প্রম্পট ছাড়াই) লিখে দাও কীভাবে এডিট করতে হবে,
 * আর সেই নির্দেশনা অনুযায়ী FFmpeg দিয়ে এডিট হয়ে যাবে।
 *
 * বর্তমান অবস্থা — শুধু UI (DEMO):
 * এই বিল্ডে শুধু ভিডিও বাছাই আর নির্দেশনা লেখার অংশটাই আছে; আসল এডিট
 * ইঞ্জিন (FFmpeg) এখনো যুক্ত করা হয়নি (ইউজারের সাথে আলোচনায় সিদ্ধান্ত:
 * "আপাতত ডেমো দিয়ে রাখো পরে এই FFmpeg দিব")। "এডিট করো" বাটন তাই সবসময়
 * নিষ্ক্রিয় থাকে, আর ভিডিও+নির্দেশনা দুটোই ঠিকঠাক দেওয়া থাকলেও শুধু একটা
 * "শীঘ্রই আসছে" টোস্ট দেখায়।
 *
 * FFmpeg যোগ করার সময় যা মনে রাখতে হবে (আগের আলোচনা থেকে):
 * Android 10+ ডাউনলোড করা নেটিভ বাইনারি রান করতে দেয় না (W^X নীতি) — তাই
 * "ইনস্টল বাটন চাপলে FFmpeg রানটাইমে ডাউনলোড হয়ে রান হবে" এই প্ল্যানটা
 * বাস্তবে কাজ করবে না। FFmpeg-কে build.gradle.kts-এ একটা normal dependency
 * (যেমন ffmpeg-kit-android-এর "min" প্যাকেজ) হিসেবে APK বিল্ড করার সময়ই
 * বসাতে হবে, তাহলেই এটা ইনস্টলড অ্যাপের বৈধ অংশ হিসেবে রান করার অনুমতি
 * পাবে।
 */
class ToolsModeActivity : AppCompatActivity() {

    private lateinit var videoPreview: VideoView
    private lateinit var videoPickPrompt: View
    private lateinit var videoFileNameLabel: TextView
    private lateinit var instructionInput: EditText
    private lateinit var runButton: Button

    private var pickedVideoUri: Uri? = null

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        pickedVideoUri = uri
        videoPreview.setVideoURI(uri)
        videoPreview.visibility = View.VISIBLE
        videoPreview.setOnPreparedListener { it.isLooping = true; videoPreview.start() }
        videoPickPrompt.visibility = View.GONE
        videoFileNameLabel.visibility = View.VISIBLE
        videoFileNameLabel.text = uri.lastPathSegment ?: "ভিডিও বাছাই হয়েছে"
        updateRunButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_mode)

        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { finish() }

        videoPreview = findViewById(R.id.videoPreview)
        videoPickPrompt = findViewById(R.id.videoPickPrompt)
        videoFileNameLabel = findViewById(R.id.videoFileNameLabel)
        instructionInput = findViewById(R.id.instructionInput)
        runButton = findViewById(R.id.runButton)

        findViewById<FrameLayout>(R.id.videoPickCard).setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        instructionInput.addTextChangedListener(onChanged = { updateRunButtonState() })

        runButton.setOnClickListener {
            // DEMO: এখনো এডিট ইঞ্জিন (FFmpeg) যুক্ত হয়নি — দেখো ক্লাস-কমেন্ট।
            Toast.makeText(
                this,
                "এডিট ইঞ্জিন শীঘ্রই আসছে — আপাতত এটা শুধু ডেমো",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** ভিডিও আর নির্দেশনা — দুটোই দেওয়া হলে তবেই বাটন সক্রিয় দেখায়, যদিও
     * এখনো চাপলে শুধু ডেমো টোস্ট দেখাবে (উপরে দেখো)। */
    private fun updateRunButtonState() {
        val hasVideo = pickedVideoUri != null
        val hasInstruction = instructionInput.text?.toString()?.trim()?.isNotEmpty() == true
        runButton.isEnabled = hasVideo && hasInstruction
        runButton.alpha = if (runButton.isEnabled) 1f else 0.5f
    }
}

/** EditText-এ addTextChangedListener(onChanged = ...) না থাকায় (এই
 * প্রজেক্টে core-ktx টেক্সট-ওয়াচার এক্সটেনশন যোগ করা নেই), একটা ছোট
 * সাধারণ ভার্সন এখানে বানিয়ে দেওয়া হলো। */
private fun EditText.addTextChangedListener(onChanged: (String) -> Unit) {
    this.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString() ?: "")
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
}
