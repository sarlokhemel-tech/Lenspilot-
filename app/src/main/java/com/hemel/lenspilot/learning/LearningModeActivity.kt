package com.hemel.lenspilot.learning

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.media.MediaPlayer
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * AI Learning Mode — full-screen "online class" teaching UI (Gemini
 * pipeline), opened from the chat box's "learningModeButton" (see
 * MainActivity.openLearningMode). Landscape + immersive, blackboard-style
 * dark theme per the latest product redesign:
 *   - top-left corner:  close + step list (tap a step to rename it)
 *   - top-right corner: new message / image
 *   - top-center:       narration on/off (pause/resume) toggle
 *
 * Talks to /api/learning/lesson (SSE — see app.py): the server streams one
 * "segment" event per finished piece of the lesson as soon as that piece's
 * narration (and diagram, if any) is ready, so playback can start on
 * segment 1 while later segments are still being composed/rendered on the
 * server. Segments arrive on a background thread (streamAuthed's callback)
 * and are pushed into [segmentChannel]; a single dedicated coroutine
 * ([playbackLoop]) consumes that channel and plays segments ONE AT A TIME,
 * in order, waiting for each one's audio to finish before moving on —
 * that's what actually keeps arrival (network-speed, out of our control)
 * decoupled from playback (must be strictly sequential, since it's a
 * spoken lesson).
 *
 * Segment types (see LEARNING_COMPOSE_INSTRUCTIONS / _sanitize_learning_segments
 * in app.py):
 *   "write"        — text appears AND is spoken at the same time.
 *   "write_silent" — text appears, nothing is spoken (no audio fields sent).
 *   "speak"        — audio only, nothing shown on screen (a pure explanation).
 *   "diagram"      — an image + highlighter boxes that light up in sync
 *                    with the narration's playback position.
 *   "image"        — a fetched picture with narration.
 *   "highlight"    — re-highlights an earlier diagram/image (via target_id)
 *                    with new narration.
 *
 * IMAGE BUTTON (top-right): the picked picture is shown big on the stage at once
 * (id [USER_IMAGE_ID]) with a scanning animation, and is sent with the question to
 * /api/learning/lesson. The server runs Gemini Vision / Groq Vision in the BACKGROUND while
 * the lesson is already being composed & spoken; "highlight" segments that point at the
 * picture arrive with their boxes resolved, and the teacher's finger then moves over it.
 * Status text (Thinking… / Detecting image… / Image searching… / Speaking…) is shown in
 * the small top pill — server statuses only while there is nothing left to play.
 */
class LearningModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOPIC = "extra_topic"
        private const val USER_IMAGE_ID = "user_image"
        // সার্ভারের LEARNING_IMAGE_DEFAULT_PROMPT-এর সাথে মিল থাকতে হবে
        private const val DEFAULT_IMAGE_PROMPT = "এই ছবিটা বুঝিয়ে দাও"
        private const val STATUS_THINKING = "Thinking…"
        private const val STATUS_SPEAKING = "Speaking…"
    }

    private lateinit var closeButton: ImageButton
    private lateinit var lessonTitle: TextView
    private lateinit var outlineButton: ImageButton
    private lateinit var outlinePanel: View
    private lateinit var outlineList: LinearLayout
    private lateinit var lessonProgress: ProgressBar
    private lateinit var diagramImage: ImageView
    private lateinit var highlightView: DiagramHighlightView
    private lateinit var stageFrame: FrameLayout
    private lateinit var speakingIndicator: View
    private lateinit var statusText: TextView
    private lateinit var captionCardView: View
    private lateinit var captionCard: View
    private lateinit var captionScroll: ScrollView
    private lateinit var captionText: TextView
    private lateinit var topicInput: EditText
    private lateinit var askButton: ImageButton
    private lateinit var narrationToggleButton: ImageButton
    private lateinit var attachImageButton: ImageButton
    private lateinit var newMessageButton: ImageButton
    private lateinit var inputRow: View

    // Unbounded — the server composes as many segments as the topic
    // genuinely needs (LEARNING_MAX_SEGMENTS on the backend is just a high
    // safety backstop, not a real target); buffering all of them costs
    // nothing (they're small JSON + a short WAV/PNG each) and lets the
    // network side run fully ahead of playback without ever blocking on a
    // full channel.
    private val segmentChannel = Channel<JSONObject>(Channel.UNLIMITED)
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var currentMediaPlayer: MediaPlayer? = null

    // Center-top on/off button: pauses/resumes narration (audio + the
    // typewriter reveal + any fixed-delay waits) without tearing down the
    // lesson — resuming picks up exactly where it left off.
    private var isPaused = false

    // Picked from attachImageButton (top-right). Sent with the next question and shown on the
    // stage straight away (see startLesson / showUserImage).
    private var pendingImageBase64: String? = null
    private var pendingImageBitmap: Bitmap? = null
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { decodePickedImage(uri) }.getOrNull() }
            if (picked == null) {
                Toast.makeText(this@LearningModeActivity, "ছবিটা খোলা যায়নি, অন্য ছবি দাও।", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingImageBitmap = picked.first
            pendingImageBase64 = picked.second
            topicInput.hint = "ছবি নিয়ে কী জানতে চাও? (না লিখলেও চলবে)"
            Toast.makeText(
                this@LearningModeActivity,
                "ছবি যুক্ত হলো — পাঠানোর বোতাম চাপো।",
                Toast.LENGTH_SHORT
            ).show()
            inputRow.visibility = View.VISIBLE
            topicInput.requestFocus()
        }
    }

    /** Decodes the picked picture down-sampled (never the full 12MP bitmap), fixes the
     * camera's EXIF rotation, caps the longest side at 1280px and returns it together with
     * its JPEG base64 (what the server's vision models get). Runs on IO. */
    private fun decodePickedImage(uri: Uri): Pair<Bitmap, String>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                when (android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f

        val longest = maxOf(bmp.width, bmp.height)
        val scale = if (longest > 1280) 1280f / longest else 1f
        if (rotation != 0f || scale != 1f) {
            val m = Matrix().apply {
                if (scale != 1f) postScale(scale, scale)
                if (rotation != 0f) postRotate(rotation)
            }
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return bmp to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---- lesson / status state --------------------------------------------------------
    // Bumped on every new lesson (and on destroy): events from an OLD stream that is still
    // running on its background thread compare against it and are dropped, so two lessons
    // never mix on the board.
    @Volatile private var lessonGen = 0
    @Volatile private var lessonStreaming = false
    @Volatile private var waitingForSegment = false
    @Volatile private var latestStatus = STATUS_THINKING

    // Kept so a follow-up question in the input row gives the compose
    // agent a little context instead of starting cold every time.
    private val history = mutableListOf<Pair<String, String>>() // (role, text)

    // FEATURE ("লেখা উপর থেকে শুরু হবে, ডিলিট হবে না, টেনে আবার দেখা যাবে"):
    // এই পুরো লেসনের সব "write"/"write_silent" সেগমেন্টের টেক্সট এখানে
    // জমা হয় (নতুন সেগমেন্ট = নতুন প্যারাগ্রাফ, আগেরটা মুছে যায় না)।
    // captionText-এ সবসময় পুরো boardLog দেখানো হয়, captionScroll (একটা
    // ScrollView) এতে বোর্ড ভরে গেলে উপর-নিচে স্ক্রল করা যায় — নতুন লাইন
    // লেখার সময় নিচে অটো-স্ক্রল হয়ে সবসময় দৃশ্যমান থাকে, কিন্তু ইউজার
    // টেনে উপরে উঠে আগের লেখাও দেখতে পারবে। শুধু নতুন টপিক শুরু করলে
    // (startLesson) বোর্ড সাফ হয় — এক লেসনের মধ্যে কখনো নিজে থেকে মোছে না।
    private val boardLog = StringBuilder()

    // FEATURE ("বোর্ড মুছবে না + highlight আগের ছবিতেই পড়বে"): আগে নতুন "write"
    // সেগমেন্ট এলেই ছবি লুকিয়ে যেত, আর পরে "highlight" সেগমেন্ট এলে ছবি ফিরিয়ে
    // আনার কোনো উপায় ছিল না — ফলে বক্স ফাঁকা কালো স্ক্রিনে ভাসত (ভিডিওতে যেমন
    // দেখা গেছে)। এখন প্রতিটি diagram/image সেগমেন্টের বিটম্যাপ তার id-তে মনে
    // রাখা হয়; highlight সেগমেন্ট target_id দিয়ে সেই ছবিটাই ফিরিয়ে এনে তার উপর
    // আঙ্গুল দিয়ে দেখায়।
    private val stageBitmaps = mutableMapOf<String, Bitmap>()
    private var currentStageId: String? = null

    // "write" সেগমেন্টের id -> boardLog-এর ভেতর তার লেখার রেঞ্জ, যাতে পরে "highlight"
    // সেগমেন্ট বোর্ডের লেখাতেও (ছবি ছাড়া) মার্কার দিয়ে দেখাতে পারে।
    private val boardRanges = mutableMapOf<String, IntRange>()

    // Plain-text summary of every segment seen so far, one line each —
    // built up as segments stream in, editable (tap-to-rename) via the
    // top-left step list.
    private val outlineLines = mutableListOf<String>()
    private var outlineVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full "online class" feel: landscape + immersive, like watching a
        // lecture. The manifest already declares configChanges for this
        // activity so rotating doesn't recreate it.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableImmersiveMode()
        setContentView(R.layout.activity_learning_mode)

        closeButton = findViewById(R.id.closeButton)
        lessonTitle = findViewById(R.id.lessonTitle)
        outlineButton = findViewById(R.id.outlineButton)
        outlinePanel = findViewById(R.id.outlinePanel)
        outlineList = findViewById(R.id.outlineList)
        lessonProgress = findViewById(R.id.lessonProgress)
        diagramImage = findViewById(R.id.diagramImage)
        highlightView = findViewById(R.id.highlightView)
        stageFrame = findViewById(R.id.stageFrame)
        speakingIndicator = findViewById(R.id.speakingIndicator)
        statusText = findViewById(R.id.statusText)
        captionCardView = findViewById(R.id.captionCard)
        captionCard = findViewById(R.id.captionCard)
        captionScroll = findViewById(R.id.captionScroll)
        captionText = findViewById(R.id.captionText)
        topicInput = findViewById(R.id.topicInput)
        askButton = findViewById(R.id.askButton)
        narrationToggleButton = findViewById(R.id.narrationToggleButton)
        attachImageButton = findViewById(R.id.attachImageButton)
        newMessageButton = findViewById(R.id.newMessageButton)
        inputRow = findViewById(R.id.inputRow)

        closeButton.setOnClickListener { finish() }
        outlineButton.setOnClickListener { toggleOutline() }
        newMessageButton.setOnClickListener { toggleInputRow() }
        attachImageButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
        narrationToggleButton.setOnClickListener { setPaused(!isPaused) }
        narrationToggleButton.isSelected = true // starts "on" (playing), matches isPaused = false
        askButton.setOnClickListener {
            val typed = topicInput.text?.toString()?.trim().orEmpty()
            val img64 = pendingImageBase64
            val imgBmp = pendingImageBitmap
            // ছবি দিয়ে কিছু না লিখলেও চলে — তখন ডিফল্ট প্রশ্ন "এই ছবিটা বুঝিয়ে দাও"
            val text = if (typed.isNotEmpty()) typed else if (img64 != null) DEFAULT_IMAGE_PROMPT else ""
            if (text.isNotEmpty()) {
                topicInput.setText("")
                topicInput.hint = "কী শিখতে চাও?"
                inputRow.visibility = View.GONE
                pendingImageBase64 = null
                pendingImageBitmap = null
                startLesson(text, img64, imgBmp)
            }
        }

        // One playback consumer for the whole activity's lifetime.
        playbackLoop()

        val initialTopic = intent.getStringExtra(EXTRA_TOPIC)?.trim().orEmpty()
        if (initialTopic.isNotEmpty()) {
            startLesson(initialTopic)
        } else {
            captionCard.visibility = View.VISIBLE
            captionText.text = "উপরে ডান পাশের '+' চিহ্নে চেপে লেখো কী শিখতে চাও।"
            inputRow.visibility = View.VISIBLE
            topicInput.requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        lessonGen++ // ignore anything still arriving from a running stream
        currentMediaPlayer?.release()
        segmentChannel.close()
    }

    /** Center-top toggle: pause/resume the narration in place (audio +
     * typewriter reveal + any fixed-delay waits) without losing the
     * lesson's position. */
    private fun setPaused(paused: Boolean) {
        isPaused = paused
        val mp = currentMediaPlayer
        try {
            if (mp != null) {
                if (paused && mp.isPlaying) mp.pause()
                else if (!paused && !mp.isPlaying) mp.start()
            }
        } catch (_: Exception) {
            // Player may be mid-transition (prepared but not started yet,
            // or already released) — the next segment will pick up the
            // paused/resumed state fine either way.
        }
        narrationToggleButton.setImageResource(if (paused) R.drawable.ic_play_filled else R.drawable.ic_pause_filled)
        narrationToggleButton.isSelected = !paused
    }

    private fun toggleInputRow() {
        inputRow.visibility = if (inputRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (inputRow.visibility == View.VISIBLE) topicInput.requestFocus()
    }

    /** Rebuilds the top-left step list from [outlineLines]. Tapping a row
     * lets the user rename that step's label (display-only — it doesn't
     * change what's already been taught, just how it's labeled here). */
    private fun toggleOutline() {
        outlineVisible = !outlineVisible
        outlinePanel.visibility = if (outlineVisible) View.VISIBLE else View.GONE
        if (outlineVisible) renderOutlineList()
    }

    private fun renderOutlineList() {
        outlineList.removeAllViews()
        if (outlineLines.isEmpty()) {
            val tv = TextView(this)
            tv.text = "এখনো কোনো ধাপ তৈরি হয়নি।"
            tv.setTextColor(getColor(R.color.chalk_muted))
            tv.textSize = 13f
            outlineList.addView(tv)
            return
        }
        outlineLines.forEachIndexed { i, line ->
            val row = TextView(this)
            row.text = "${i + 1}. $line"
            row.setTextColor(getColor(R.color.chalk_white))
            row.textSize = 13f
            row.setPadding(0, 12, 0, 12)
            row.setOnClickListener { showRenameStepDialog(i) }
            outlineList.addView(row)
        }
    }

    private fun showRenameStepDialog(index: Int) {
        val input = EditText(this)
        input.setText(outlineLines.getOrNull(index).orEmpty())
        AlertDialog.Builder(this)
            .setTitle("ধাপ ${index + 1} পরিবর্তন করো")
            .setView(input)
            .setPositiveButton("ঠিক আছে") { _, _ ->
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isNotEmpty() && index in outlineLines.indices) {
                    outlineLines[index] = newText
                    renderOutlineList()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    /** Kicks off one lesson request. A previous lesson that is still streaming/playing is
     * stopped cleanly first (audio cut, queued segments dropped, its late events ignored) —
     * before this fix the old lesson's remaining segments kept playing into the new board.
     * [imageB64]/[imageBmp]: a picture the student attached — shown big at once, sent to the
     * server's Gemini/Groq vision (background) while the lesson starts speaking. */
    private fun startLesson(topic: String, imageB64: String? = null, imageBmp: Bitmap? = null) {
        val myGen = ++lessonGen
        // stop whatever the previous lesson was doing
        playbackJob?.cancel()
        while (segmentChannel.tryReceive().isSuccess) { /* drop queued segments */ }
        currentMediaPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        currentMediaPlayer = null
        playbackLoop()

        lessonTitle.text = "AI লার্নিং মোড"
        lessonProgress.visibility = View.VISIBLE
        lessonProgress.isIndeterminate = true
        outlineLines.clear()
        outlineVisible = false
        outlinePanel.visibility = View.GONE
        boardLog.clear()
        boardRanges.clear()
        stageBitmaps.clear()
        currentStageId = null
        highlightView.clear()
        highlightView.setScanning(false)
        stageFrame.visibility = View.GONE
        captionText.text = ""
        setStageLayout(bigStage = false)
        isPaused = false
        narrationToggleButton.setImageResource(R.drawable.ic_pause_filled)
        narrationToggleButton.isSelected = true
        history.add("user" to topic)

        lessonStreaming = true
        latestStatus = if (imageB64 != null) "Thinking… · Detecting image…" else STATUS_THINKING
        if (imageBmp != null) {
            // ছবি আগেই স্ক্রিনে বড় করে + স্ক্যান অ্যানিমেশন — পাঠ শুরুর অপেক্ষাটা "কাজ চলছে" মনে হবে
            stageBitmaps[USER_IMAGE_ID] = imageBmp
            setStageLayout(bigStage = true)
            showStage(USER_IMAGE_ID, imageBmp)
            highlightView.setScanning(true)
        }
        showStatus(latestStatus)

        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val historyJson = JSONArray().apply {
                // last few turns only — keeps the request small, matches
                // the "few recent lines, not full history" philosophy
                // used elsewhere in this app's context-window design.
                for ((role, text) in history.takeLast(6)) {
                    put(JSONObject().put("role", role).put("text", text))
                }
            }
            val bodyJson = JSONObject()
                .put("message", topic)
                .put("history", historyJson)
            if (imageB64 != null) bodyJson.put("image_base64", imageB64)
            val body = bodyJson.toString()

            val result = ApiClient.streamAuthed(this@LearningModeActivity, baseUrl, "/api/learning/lesson", body) { event ->
                if (myGen != lessonGen) return@streamAuthed // a newer lesson replaced this one
                when (event.optString("type")) {
                    "status" -> {
                        val stage = event.optString("stage")
                        if (stage == "vision_done") {
                            runOnUiThread { highlightView.setScanning(false) }
                        } else {
                            val text = event.optString("text")
                            if (text.isNotBlank()) {
                                latestStatus = text
                                // only visible while we are waiting with nothing to play
                                if (waitingForSegment) runOnUiThread { if (myGen == lessonGen) showStatus(text) }
                            }
                        }
                    }
                    "meta" -> {
                        val title = event.optString("title")
                        runOnUiThread {
                            if (myGen != lessonGen) return@runOnUiThread
                            history.add("ai" to title)
                            lessonTitle.text = title
                        }
                    }
                    "segment" -> {
                        val seg = event.getJSONObject("segment")
                        val summary = when (seg.optString("type")) {
                            "write", "write_silent" -> seg.optString("text")
                            "diagram" -> "[ডায়াগ্রাম] " + seg.optString("narration")
                            "image" -> "[ছবি] " + seg.optString("narration")
                            else -> seg.optString("narration")
                        }
                        // outlineLines is a UI-thread list (renderOutlineList reads it) —
                        // was mutated from this background thread before (race).
                        runOnUiThread {
                            if (myGen != lessonGen) return@runOnUiThread
                            outlineLines.add(summary)
                            if (outlineVisible) renderOutlineList()
                        }
                        // trySend never blocks (channel is UNLIMITED) — safe
                        // to call straight from this background callback.
                        segmentChannel.trySend(seg)
                    }
                    "done" -> {
                        lessonStreaming = false
                        runOnUiThread {
                            if (myGen != lessonGen) return@runOnUiThread
                            lessonProgress.visibility = View.GONE
                            highlightView.setScanning(false)
                            if (waitingForSegment) hideStatus()
                        }
                    }
                    "error" -> {
                        lessonStreaming = false
                        val err = event.optString("error", "অজানা সমস্যা")
                        runOnUiThread {
                            if (myGen != lessonGen) return@runOnUiThread
                            lessonProgress.visibility = View.GONE
                            highlightView.setScanning(false)
                            hideStatus()
                            Toast.makeText(this@LearningModeActivity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            if (myGen != lessonGen) return@launch
            // stream ended (normally or not): nothing more will arrive for this lesson
            lessonStreaming = false
            result.onFailure { e ->
                lessonProgress.visibility = View.GONE
                highlightView.setScanning(false)
                if (waitingForSegment) hideStatus()
                val msg = (e as? com.hemel.lenspilot.net.ApiException)?.let { ex ->
                    // server sent {"error": "..."} JSON (e.g. token limit / bad image) — show that
                    runCatching { JSONObject(ex.body).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                } ?: "সংযোগ সমস্যা: ${e.message}"
                Toast.makeText(this@LearningModeActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Makes the picture panel bigger (user's own photo) or restores the normal board/picture split. */
    private fun setStageLayout(bigStage: Boolean) {
        val cap = captionCardView.layoutParams as LinearLayout.LayoutParams
        val stg = stageFrame.layoutParams as LinearLayout.LayoutParams
        cap.weight = if (bigStage) 0.30f else 0.42f
        stg.weight = if (bigStage) 0.70f else 0.58f
        captionCardView.layoutParams = cap
        stageFrame.layoutParams = stg
    }

    // ---- status pill (top, small): "Thinking…", "Detecting image…", "Speaking…" ... -------
    private fun showStatus(text: String) {
        statusText.text = text
        speakingIndicator.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        speakingIndicator.visibility = View.GONE
    }

    /** The single sequential consumer described in the class doc comment. While it is
     * waiting for the next segment (server still planning / drawing / searching an image)
     * it shows the server's latest status text instead of a silent blank screen. */
    private fun playbackLoop() {
        playbackJob = lifecycleScope.launch {
            while (true) {
                var segment = segmentChannel.tryReceive().getOrNull()
                if (segment == null) {
                    waitingForSegment = true
                    if (lessonStreaming) showStatus(latestStatus) else hideStatus()
                    segment = segmentChannel.receiveCatching().getOrNull()
                    waitingForSegment = false
                    if (segment == null) break // channel closed (activity destroyed)
                }
                hideStatus()
                try {
                    playSegment(segment)
                } catch (e: CancellationException) {
                    throw e // a new lesson replaced this one — stop, don't swallow
                } catch (e: Exception) {
                    // One bad segment (bad audio, decode failure, ...)
                    // should never kill the rest of the lesson.
                }
            }
        }
    }

    private suspend fun playSegment(segment: JSONObject) {
        val type = segment.optString("type")
        val durationMs = segment.optInt("duration_ms", 0)
        val audioB64 = if (segment.isNull("audio_base64")) null else segment.optString("audio_base64", null)
        // Same isNull-guard pattern as audioB64 above: org.json's optString
        // returns the literal string "null" (not the fallback) when the key
        // maps to JSON null, which the backend sends whenever TTS failed for
        // this segment (see call_learning_tts's except branch). Harmless
        // today since audioB64 is null in that same case and playWav is
        // never called, but guarding it keeps this line correct on its own.
        val audioMime = if (segment.isNull("audio_mime")) "audio/wav" else segment.optString("audio_mime", "audio/wav")
        val segId = segment.optString("id", "")

        when (type) {
            // BUGFIX ("শিক্ষামূলক অপশনে কিছু লেখা আসে না"): the backend's
            // segment schema (see LEARNING_COMPOSE_INSTRUCTIONS / the SSE
            // doc comment above learning_lesson() in app.py) moved from a
            // single "type" segment to six shapes — "write", "write_silent",
            // "speak", "diagram", "image", "highlight" — but this when()
            // block still only matched the old "type"/"speak"/"diagram"
            // names. Every "write" segment (the one that actually carries
            // the on-screen text) fell through with no matching branch and
            // silently did nothing: no text, no audio, no error — exactly
            // the "লেখা একদমই বন্ধ" symptom. All six current types are
            // handled below now.
            "write" -> {
                val text = segment.optString("text")
                hideStatus()
                // The board never clears: a picture that's already on the stage
                // stays there (only the pointer is lifted).
                highlightView.clear()
                captionCard.visibility = View.VISIBLE
                try {
                    if (audioB64 != null) {
                        // Reveal text and play audio together. The typewriter is a CHILD of this
                        // coroutine (coroutineScope): before, it lived in lifecycleScope, so when
                        // a lesson was replaced it kept typing old text into the new board, and a
                        // failed audio left it running with the text never committed.
                        coroutineScope {
                            val revealJob = launch { typewriter(text, durationMs) }
                            try {
                                playWav(audioB64, durationMs, audioMime)
                            } finally {
                                revealJob.cancel()
                            }
                        }
                    } else {
                        pausableDelay(minOf(4000L, maxOf(1200L, text.length * 45L)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // audio problem — still keep the written text on the board below
                }
                commitToBoard(text, segId)
            }
            "write_silent" -> {
                // Text appears, nothing is spoken — no audio fields are
                // sent for this type at all (see app.py), so there's
                // nothing to play; just show it for a comfortable reading
                // pause.
                val text = segment.optString("text")
                hideStatus()
                // The board never clears: a picture that's already on the stage
                // stays there (only the pointer is lifted).
                highlightView.clear()
                captionCard.visibility = View.VISIBLE
                commitToBoard(text, segId)
                pausableDelay(minOf(4000L, maxOf(1200L, text.length * 45L)))
            }
            "image" -> {
                hideStatus()
                val imgB64 = if (segment.isNull("image_base64")) null else segment.optString("image_base64", null)
                val bmp = if (imgB64 != null) withContext(Dispatchers.Default) { decodeBitmapSafe(imgB64) } else null
                if (bmp != null) {
                    stageBitmaps[segId] = bmp
                    showStage(segId, bmp)
                    highlightView.setHighlights(segment.optJSONArray("highlights"))
                } else {
                    // Image fetch/decode failed — still give the narration
                    // rather than stalling the lesson.
                    highlightView.clear()
                    }
                playWithPointer(audioB64, durationMs, audioMime)
                highlightView.clear()
                hideStatus()
            }
            "highlight" -> {
                // FIX ("ছবি আসে না, শুধু হাইলাইট বক্স আসে"): re-points at an
                // EARLIER diagram/image (target_id). That picture is brought
                // back on the stage first (it may have been replaced by a
                // later one), then the finger moves over it in sync with the
                // narration. If the target was board text instead, the text
                // is marked like with a highlighter pen.
                hideStatus()
                val targetId = segment.optString("target_id", "")
                val targetBmp = stageBitmaps[targetId]
                val boardRange = boardRanges[targetId]
                if (targetBmp != null) {
                    showStage(targetId, targetBmp)
                    highlightView.setHighlights(segment.optJSONArray("highlights"))
                    playWithPointer(audioB64, durationMs, audioMime)
                    highlightView.clear()
                } else if (boardRange != null) {
                    captionCard.visibility = View.VISIBLE
                    highlightBoardText(boardRange)
                    if (audioB64 != null) {
                        playWav(audioB64, durationMs, audioMime)
                    } else {
                        pausableDelay(1800L)
                    }
                    clearBoardHighlight()
                } else {
                        if (audioB64 != null) {
                        playWav(audioB64, durationMs, audioMime)
                    } else {
                        pausableDelay(1200L)
                    }
                    hideStatus()
                }
            }
            "speak" -> {
                // FEATURE ("কথা বলার সময় স্ক্রিনে বড় আইকন আসবে না, বোর্ড
                // হুবহু থাকবে"): আগে এখানে captionCard/diagram/highlight
                // সব লুকিয়ে ফেলে বড় "বলছে" ইন্ডিকেটর দেখাত। এখন স্টেজে
                // যা আছে (বোর্ড/ডায়াগ্রাম) তা-ই অপরিবর্তিত থাকে — শুধু
                // ছোট speakingIndicator badge (উপরে) audio চলাকালীন দেখায়।
                if (audioB64 != null) {
                    playWav(audioB64, durationMs, audioMime)
                } else {
                    pausableDelay(1200L)
                }
                hideStatus()
            }
            "diagram" -> {
                hideStatus()
                val diagramB64 = if (segment.isNull("diagram_base64")) null else segment.optString("diagram_base64", null)
                val bmp = if (diagramB64 != null) withContext(Dispatchers.Default) { decodeBitmapSafe(diagramB64) } else null
                if (bmp != null) {
                    stageBitmaps[segId] = bmp
                    showStage(segId, bmp)
                    highlightView.setHighlights(segment.optJSONArray("highlights"))
                } else {
                    // No picture (render failed server-side) — still give
                    // the narration, just without a highlighter overlay.
                    highlightView.clear()
                    }
                playWithPointer(audioB64, durationMs, audioMime)
                highlightView.clear()
                hideStatus()
            }
        }
    }

    /** Decodes a base64 picture without ever blowing up on a huge / odd one:
     * reads the size first and down-samples anything much bigger than the
     * screen can show (a 4000x3000 photo used to be decoded at full size —
     * memory spike or a silent decode failure = "ছবি আসে না"). Returns null if
     * the bytes are not a decodable image. */
    private fun decodeBitmapSafe(b64: String, maxSide: Int = 1600): Bitmap? {
        var result: Bitmap? = null
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Throwable) {
            result = null
        }
        return result
    }

    /** Shows [bmp] (segment [id]'s picture) in the white side panel — the
     * board text stays visible next to it. No-op swap if it's already showing. */
    private fun showStage(id: String, bmp: Bitmap) {
        if (currentStageId != id) {
            diagramImage.setImageBitmap(bmp)
            highlightView.setImageSize(bmp.width, bmp.height)
            currentStageId = id
        }
        stageFrame.visibility = View.VISIBLE
    }

    /** Plays a segment's narration while the highlighter follows it (the
     * pointer moves part-to-part as the audio progresses). With no audio
     * (TTS failed) the pointer is walked through on a timer instead, so the
     * picture still gets "taught" rather than just sitting there. */
    private suspend fun playWithPointer(audioB64: String?, durationMs: Int, audioMime: String) {
        if (audioB64 != null) {
            playWavWithProgress(audioB64, durationMs, { pct -> highlightView.setProgressPct(pct) }, audioMime)
        } else {
            pausableDelayWithProgress(4500L) { pct -> highlightView.setProgressPct(pct) }
        }
    }

    /** Like [pausableDelay], but reports 0..100 progress while it waits. */
    private suspend fun pausableDelayWithProgress(ms: Long, onProgress: (Int) -> Unit) {
        var elapsed = 0L
        while (elapsed < ms) {
            if (isPaused) {
                delay(120)
            } else {
                delay(50)
                elapsed += 50
                onProgress(((elapsed * 100) / ms).toInt().coerceIn(0, 100))
            }
        }
    }

    /** Marks [range] of the board text with a highlighter band and scrolls it into view. */
    private fun highlightBoardText(range: IntRange) {
        val full = boardLog.toString()
        if (range.first < 0 || range.last + 1 > full.length) return
        val sp = SpannableString(full)
        sp.setSpan(
            BackgroundColorSpan(Color.parseColor("#66F59E0B")),
            range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        captionText.text = sp
        captionText.post {
            val layout = captionText.layout
            if (layout != null) {
                val line = layout.getLineForOffset(range.first)
                captionScroll.smoothScrollTo(0, (layout.getLineTop(line) - 40).coerceAtLeast(0))
            }
        }
    }

    private fun clearBoardHighlight() {
        captionText.text = boardLog.toString()
    }

    /** Sleeps for [ms], but while [isPaused] is true it just idles instead
     * of counting down — so toggling the center-top button back on resumes
     * a fixed-delay wait exactly where it left off, same as it does for
     * audio playback (see setPaused / playWavWithProgress). */
    private suspend fun pausableDelay(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (isPaused) {
                delay(120)
            } else {
                val step = minOf(80L, remaining)
                delay(step)
                remaining -= step
            }
        }
    }

    /** Types [text] out over roughly [durationMs] (matching the audio
     * length) so writing and speech finish at about the same time — if
     * duration is unknown/zero, falls back to a fixed comfortable pace.
     * Pauses (without losing progress) while [isPaused] is true. Renders
     * on top of whatever's already committed to [boardLog] (previous
     * segments stay on screen — see boardLog's doc comment), auto-
     * scrolling the board down as new characters appear. */
    private suspend fun typewriter(text: String, durationMs: Int) {
        if (text.isEmpty()) return
        val totalMs = if (durationMs > 0) durationMs else text.length * 40
        val perCharMs = (totalMs / text.length.coerceAtLeast(1)).coerceIn(8, 120).toLong()
        val prefix = if (boardLog.isEmpty()) "" else boardLog.toString() + "\n\n"
        val builder = StringBuilder()
        for (ch in text) {
            while (isPaused) delay(120)
            builder.append(ch)
            captionText.text = prefix + builder.toString()
            scrollBoardToBottom()
            delay(perCharMs)
        }
    }

    /** Permanently appends [text] as a new paragraph on the board (never
     * replaces earlier text — see boardLog's doc comment) and scrolls
     * down so it's visible. Call once per "write"/"write_silent" segment,
     * after typewriter() (if any) has finished animating it. */
    private fun commitToBoard(text: String, id: String = "") {
        if (boardLog.isNotEmpty()) boardLog.append("\n\n")
        val start = boardLog.length
        boardLog.append(text)
        if (id.isNotEmpty()) boardRanges[id] = start until boardLog.length
        captionText.text = boardLog.toString()
        scrollBoardToBottom()
    }

    private fun scrollBoardToBottom() {
        captionScroll.post { captionScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Writes [wavB64] to a temp file and plays it, suspending until
     * playback finishes (or fails). [durationMs] is only used as a safety
     * timeout in case MediaPlayer's completion callback never fires.
     * [mime] picks the temp file's extension (server now sends either WAV
     * from Gemini TTS or MP3 from Edge TTS — see call_learning_tts on the
     * backend); MediaPlayer mostly sniffs content either way, but a
     * matching extension avoids relying on that. */
    private suspend fun playWav(wavB64: String, durationMs: Int, mime: String = "audio/wav") {
        playWavWithProgress(wavB64, durationMs, null, mime)
    }

    private suspend fun playWavWithProgress(
        wavB64: String, durationMs: Int, onProgress: ((Int) -> Unit)?, mime: String = "audio/wav"
    ) {
        val suffix = if (mime == "audio/mpeg") ".mp3" else ".wav"
        showStatus(STATUS_SPEAKING)
        val file = withContext(Dispatchers.IO) {
            val bytes = Base64.decode(wavB64, Base64.DEFAULT)
            File.createTempFile("learning_seg_", suffix, cacheDir).apply {
                FileOutputStream(this).use { it.write(bytes) }
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                currentMediaPlayer = mp
                var progressJob: kotlinx.coroutines.Job? = null
                mp.setOnPreparedListener {
                    // Respect a toggle-off that happened while this segment
                    // was still being prepared (e.g. paused during the
                    // brief gap between segments).
                    if (isPaused) {
                        // Stay paused; playback starts once setPaused(false)
                        // calls mp.start() on this same instance.
                    } else {
                        it.start()
                    }
                    progressJob = CoroutineScope(Dispatchers.Main).launch {
                        while (it.isPlaying || isPaused) {
                            if (!isPaused) {
                                val dur = if (it.duration > 0) it.duration else durationMs.coerceAtLeast(1)
                                val pct = ((it.currentPosition * 100L) / dur).toInt().coerceIn(0, 100)
                                onProgress?.invoke(pct)
                            }
                            delay(50)
                        }
                    }
                }
                mp.setOnCompletionListener {
                    progressJob?.cancel()
                    onProgress?.invoke(100)
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, _, _ ->
                    progressJob?.cancel()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                cont.invokeOnCancellation {
                    progressJob?.cancel()
                    runCatching { mp.stop() }
                    mp.release()
                }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepareAsync()
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } finally {
            currentMediaPlayer?.release()
            currentMediaPlayer = null
            file.delete()
            hideStatus()
        }
    }
}
