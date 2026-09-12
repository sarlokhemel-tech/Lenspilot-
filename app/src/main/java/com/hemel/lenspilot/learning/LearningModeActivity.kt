package com.hemel.lenspilot.learning

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import kotlinx.coroutines.channels.Channel
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
 *   "type"    — text appears AND is spoken at the same time.
 *   "speak"   — audio only, nothing shown on screen (a pure explanation).
 *   "diagram" — an image + highlighter boxes that light up in sync with
 *               the narration's playback position.
 *
 * NOTE ON THE IMAGE BUTTON: the top-right image picker currently attaches
 * the picture locally (so the UI/flow is in place), but /api/learning/lesson
 * does not yet accept an image field on the backend — that's a separate,
 * not-yet-done change. A picked image is kept as [pendingImageBase64] and a
 * short toast says so; nothing is silently dropped without telling the user.
 */
class LearningModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOPIC = "extra_topic"
    }

    private lateinit var closeButton: ImageButton
    private lateinit var lessonTitle: TextView
    private lateinit var outlineButton: ImageButton
    private lateinit var outlinePanel: View
    private lateinit var outlineList: LinearLayout
    private lateinit var lessonProgress: ProgressBar
    private lateinit var diagramImage: ImageView
    private lateinit var highlightView: DiagramHighlightView
    private lateinit var speakingIndicator: View
    private lateinit var captionCard: View
    private lateinit var captionText: TextView
    private lateinit var topicInput: EditText
    private lateinit var askButton: ImageButton
    private lateinit var narrationToggleButton: ImageButton
    private lateinit var attachImageButton: ImageButton
    private lateinit var newMessageButton: ImageButton
    private lateinit var inputRow: View

    // Unbounded — the server can compose up to LEARNING_MAX_SEGMENTS (8)
    // segments; buffering all of them costs nothing (they're small JSON +
    // a short WAV/PNG each) and lets the network side run fully ahead of
    // playback without ever blocking on a full channel.
    private val segmentChannel = Channel<JSONObject>(Channel.UNLIMITED)
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var currentMediaPlayer: MediaPlayer? = null

    // Center-top on/off button: pauses/resumes narration (audio + the
    // typewriter reveal + any fixed-delay waits) without tearing down the
    // lesson — resuming picks up exactly where it left off.
    private var isPaused = false

    // Picked from attachImageButton (top-right). See class doc comment —
    // the backend doesn't consume this yet.
    private var pendingImageBase64: String? = null
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val b64 = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        val out = ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                }.getOrNull()
            }
            if (b64 != null) {
                pendingImageBase64 = b64
                Toast.makeText(
                    this@LearningModeActivity,
                    "ছবি যুক্ত হলো। (এই স্ক্রিনে ছবি বিশ্লেষণ শীঘ্রই যোগ হবে — এখন এটা সার্ভারে পাঠানো হচ্ছে না)",
                    Toast.LENGTH_LONG
                ).show()
                inputRow.visibility = View.VISIBLE
                topicInput.requestFocus()
            }
        }
    }

    // Kept so a follow-up question in the input row gives the compose
    // agent a little context instead of starting cold every time.
    private val history = mutableListOf<Pair<String, String>>() // (role, text)

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
        speakingIndicator = findViewById(R.id.speakingIndicator)
        captionCard = findViewById(R.id.captionCard)
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
            val text = topicInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                topicInput.setText("")
                inputRow.visibility = View.GONE
                startLesson(text)
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

    /** Kicks off one lesson request. Safe to call again while a previous
     * lesson is still streaming/playing — new segments simply queue up
     * behind whatever's left of the current one in [segmentChannel]. */
    private fun startLesson(topic: String) {
        lessonTitle.text = "AI লার্নিং মোড"
        lessonProgress.visibility = View.VISIBLE
        lessonProgress.isIndeterminate = true
        outlineLines.clear()
        outlineVisible = false
        outlinePanel.visibility = View.GONE
        history.add("user" to topic)

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
            val body = JSONObject()
                .put("message", topic)
                .put("history", historyJson)
                .toString()

            val result = ApiClient.streamAuthed(this@LearningModeActivity, baseUrl, "/api/learning/lesson", body) { event ->
                when (event.optString("type")) {
                    "meta" -> {
                        val title = event.optString("title")
                        history.add("ai" to title)
                        runOnUiThread { lessonTitle.text = title }
                    }
                    "segment" -> {
                        val seg = event.getJSONObject("segment")
                        val summary = when (seg.optString("type")) {
                            "type" -> seg.optString("text")
                            "diagram" -> "[ডায়াগ্রাম] " + seg.optString("narration")
                            else -> seg.optString("narration")
                        }
                        outlineLines.add(summary)
                        if (outlineVisible) {
                            runOnUiThread { renderOutlineList() }
                        }
                        // trySend never blocks (channel is UNLIMITED) — safe
                        // to call straight from this background callback.
                        segmentChannel.trySend(seg)
                    }
                    "done" -> {
                        runOnUiThread { lessonProgress.visibility = View.GONE }
                    }
                    "error" -> {
                        val err = event.optString("error", "অজানা সমস্যা")
                        runOnUiThread {
                            lessonProgress.visibility = View.GONE
                            Toast.makeText(this@LearningModeActivity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            result.onFailure { e ->
                lessonProgress.visibility = View.GONE
                Toast.makeText(this@LearningModeActivity, "সংযোগ সমস্যা: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The single sequential consumer described in the class doc comment. */
    private fun playbackLoop() {
        playbackJob = lifecycleScope.launch {
            for (segment in segmentChannel) {
                try {
                    playSegment(segment)
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

        when (type) {
            "type" -> {
                val text = segment.optString("text")
                speakingIndicator.visibility = View.GONE
                diagramImage.visibility = View.GONE
                highlightView.visibility = View.GONE
                captionCard.visibility = View.VISIBLE
                if (audioB64 != null) {
                    // Reveal text and play audio together — a coroutine
                    // driving the typewriter runs concurrently with
                    // playback below, both started from here.
                    val revealJob = lifecycleScope.launch { typewriter(text, durationMs) }
                    playWav(audioB64, durationMs, audioMime)
                    revealJob.cancel()
                    captionText.text = text
                } else {
                    captionText.text = text
                    pausableDelay(minOf(4000L, maxOf(1200L, text.length * 45L)))
                }
            }
            "speak" -> {
                captionCard.visibility = View.GONE
                diagramImage.visibility = View.GONE
                highlightView.visibility = View.GONE
                speakingIndicator.visibility = View.VISIBLE
                if (audioB64 != null) {
                    playWav(audioB64, durationMs, audioMime)
                } else {
                    pausableDelay(1200L)
                }
                speakingIndicator.visibility = View.GONE
            }
            "diagram" -> {
                captionCard.visibility = View.GONE
                speakingIndicator.visibility = View.GONE
                val diagramB64 = if (segment.isNull("diagram_base64")) null else segment.optString("diagram_base64", null)
                if (diagramB64 != null) {
                    val bytes = Base64.decode(diagramB64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    diagramImage.setImageBitmap(bmp)
                    diagramImage.visibility = View.VISIBLE
                    highlightView.setHighlights(segment.optJSONArray("highlights"))
                    highlightView.visibility = View.VISIBLE
                } else {
                    // No picture (render failed server-side) — still give
                    // the narration, just without a highlighter overlay.
                    diagramImage.visibility = View.GONE
                    highlightView.visibility = View.GONE
                    speakingIndicator.visibility = View.VISIBLE
                }
                if (audioB64 != null) {
                    playWavWithProgress(audioB64, durationMs, { pct -> highlightView.setProgressPct(pct) }, audioMime)
                } else {
                    pausableDelay(1500L)
                }
                highlightView.clear()
            }
        }
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
     * Pauses (without losing progress) while [isPaused] is true. */
    private suspend fun typewriter(text: String, durationMs: Int) {
        if (text.isEmpty()) return
        val totalMs = if (durationMs > 0) durationMs else text.length * 40
        val perCharMs = (totalMs / text.length.coerceAtLeast(1)).coerceIn(8, 120).toLong()
        val builder = StringBuilder()
        for (ch in text) {
            while (isPaused) delay(120)
            builder.append(ch)
            captionText.text = builder.toString()
            delay(perCharMs)
        }
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
        }
    }
}
