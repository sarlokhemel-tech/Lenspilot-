package com.hemel.lenspilot.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Backend-voiced TTS for the on-screen guide voice — calls /api/tts,
 * which now routes through the SAME admin-switchable provider Learning
 * Mode uses (get_learning_tts_provider() / call_learning_tts() in
 * app.py — currently Edge TTS, Gemini TTS available via the same admin
 * toggle later, no app update needed to switch).
 *
 * This is a SEPARATE engine from [LocalTts] (the on-device, offline,
 * free Android TextToSpeech engine): calling this one costs a network
 * round trip and, depending on the admin's chosen provider, real money
 * per utterance — but it sounds the same everywhere instead of depending
 * on whatever voice pack a given phone happens to have installed, which
 * was the actual root cause of the "TTS মাঝে মাঝে কাজ করে না" complaints
 * LocalTts's own history (see its file doc) was chasing.
 *
 * Fails soft, on purpose: ANY error here (no network, empty response,
 * playback failure) calls [onError] and does NOT retry or queue —
 * speakGuidance() in LenspilotAccessibilityService/FallbackGuideService
 * is expected to fall back to [LocalTts] on that callback, so a network
 * hiccup never leaves the guide voice completely silent.
 */
object RemoteTts {
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun speak(
        context: Context,
        baseUrl: String,
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        if (text.isBlank()) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val requestBody = JSONObject().apply { put("text", text) }.toString()
            val result = ApiClient.fetchAudioAuthed(appContext, baseUrl, "/api/tts", requestBody)
            val bytes = result.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                mainHandler.post { onError?.invoke() }
                return@launch
            }
            // Sniff a WAV RIFF header vs everything else (MP3) for the temp
            // file's extension — MediaPlayer mostly sniffs content either
            // way, but a matching extension avoids relying on that (same
            // reasoning as LearningModeActivity.playWavWithProgress).
            val isWav = bytes.size > 4 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            val suffix = if (isWav) ".wav" else ".mp3"
            val file = try {
                File.createTempFile("guide_tts_", suffix, appContext.cacheDir).apply {
                    FileOutputStream(this).use { it.write(bytes) }
                }
            } catch (e: Exception) {
                mainHandler.post { onError?.invoke() }
                return@launch
            }

            mainHandler.post {
                try {
                    stop()
                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setOnPreparedListener {
                        onStart?.invoke()
                        it.start()
                    }
                    mp.setOnCompletionListener { player ->
                        onDone?.invoke()
                        player.release()
                        if (mediaPlayer === player) mediaPlayer = null
                        file.delete()
                    }
                    mp.setOnErrorListener { player, _, _ ->
                        onError?.invoke()
                        player.release()
                        if (mediaPlayer === player) mediaPlayer = null
                        file.delete()
                        true
                    }
                    mp.setDataSource(file.absolutePath)
                    mp.prepareAsync()
                } catch (e: Exception) {
                    onError?.invoke()
                    file.delete()
                }
            }
        }
    }

    /** Stops and releases any RemoteTts playback in progress — does NOT
     * touch [LocalTts]; callers that manage both stop each separately. */
    fun stop() {
        try { mediaPlayer?.stop() } catch (e: Exception) { /* ignore */ }
        try { mediaPlayer?.release() } catch (e: Exception) { /* ignore */ }
        mediaPlayer = null
    }
}
