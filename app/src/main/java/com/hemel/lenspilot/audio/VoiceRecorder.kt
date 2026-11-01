package com.hemel.lenspilot.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Thin wrapper around [MediaRecorder] that records to an AAC/.m4a file in
 * the app's cache dir, ready to hand to Groq Whisper via
 * ApiClient.uploadAudioAuthed. One recording at a time. */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): Boolean {
        stop() // safety: never leave a previous session dangling
        return try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            true
        } catch (e: Exception) {
            recorder = null
            outputFile = null
            false
        }
    }

    /** Stops the recording and returns the finished file, or null if
     * nothing was recording / it failed. */
    fun stop(): File? {
        val mr = recorder ?: return null
        val file = outputFile
        return try {
            mr.stop()
            mr.release()
            recorder = null
            outputFile = null
            file
        } catch (e: Exception) {
            mr.release()
            recorder = null
            outputFile = null
            null
        }
    }

    val isRecording: Boolean get() = recorder != null
}
