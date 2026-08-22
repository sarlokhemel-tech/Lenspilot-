package com.hemel.lenspilot.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Plays AI replies aloud via the Space's /api/tts (Groq PlayAI TTS).
 *
 * NOTE — not truly chunked/progressive playback yet: [fetchAudioAuthed]
 * downloads the whole WAV before MediaPlayer starts, so there's a short
 * wait before sound begins rather than audio starting the instant the
 * first bytes arrive (the backend itself DOES stream the bytes down —
 * this is purely an Android-side simplification for this phase). A later
 * pass can swap this for an OkHttp streaming pipe into MediaPlayer /
 * AudioTrack for true near-real-time playback.
 */
object TtsPlayer {
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun speak(
        context: Context,
        baseUrl: String,
        text: String,
        scope: CoroutineScope,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stop() // never overlap two replies
        scope.launch {
            val body = JSONObject().apply { put("text", text) }.toString()
            val result = ApiClient.fetchAudioAuthed(context, baseUrl, "/api/tts", body)
            result.fold(
                onSuccess = { bytes ->
                    if (bytes.isEmpty()) {
                        mainHandler.post { onError("Empty audio") }
                        return@fold
                    }
                    val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                    file.writeBytes(bytes)
                    mainHandler.post {
                        try {
                            val mp = MediaPlayer()
                            player = mp
                            mp.setDataSource(file.absolutePath)
                            mp.setOnPreparedListener {
                                onStart()
                                it.start()
                            }
                            mp.setOnCompletionListener {
                                onDone()
                                releaseInternal()
                                file.delete()
                            }
                            mp.setOnErrorListener { _, _, _ ->
                                onError("Playback error")
                                releaseInternal()
                                file.delete()
                                true
                            }
                            mp.prepareAsync()
                        } catch (e: Exception) {
                            onError(e.message ?: "TTS playback failed")
                        }
                    }
                },
                onFailure = { mainHandler.post { onError(it.message ?: "TTS request failed") } }
            )
        }
    }

    /** Stops playback immediately (used by the top-bar Stop button). Does
     * NOT invoke the onDone callback that was passed to [speak] — the
     * caller decides what "stopped early" should mean (e.g. clearing
     * highlights immediately anyway). */
    fun stop() {
        releaseInternal()
    }

    private fun releaseInternal() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }
}
