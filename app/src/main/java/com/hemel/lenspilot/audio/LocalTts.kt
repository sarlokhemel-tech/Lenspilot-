package com.hemel.lenspilot.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * On-device Bangla text-to-speech (Android's built-in TextToSpeech engine —
 * instant, no network round-trip, no quota, no cost).
 *
 * Reliability fixes (round 2 — "মাঝে মাঝে কাজ করে না", intermittent silent
 * failures + slow first utterance):
 *
 * 1. [warmUp] lets a caller kick off engine init the moment a screen that
 *    might need voice appears (MainActivity.onCreate, the accessibility
 *    service connecting, the fallback guide service starting) instead of
 *    only starting init on the very first speak() call. By the time the
 *    user actually triggers voice, the engine has usually already finished
 *    the ~1-2s cold start, so the first utterance is no longer the slow one.
 * 2. THE MAIN BUG: `TextToSpeech.speak()` does not throw on failure — it
 *    returns ERROR (-1) as a plain return value when the engine is busy,
 *    the binder connection hiccuped, or the engine died silently. The old
 *    code never looked at that return value, so on ERROR nothing happened
 *    at all: no sound, no callback, no log — indistinguishable from "the
 *    button did nothing". Now that return value is checked; on failure the
 *    engine is torn down and one fresh attempt is made automatically
 *    (engine recreated, request re-queued) before giving up, so a single
 *    transient hiccup self-heals instead of going silent.
 * 3. A stale [UtteranceProgressListener] from a previous, already-finished
 *    speak() call (e.g. one with an onDone callback from the live-call
 *    flow) used to stay attached to the shared engine if a later speak()
 *    call passed no callbacks — that later call would run with an old
 *    listener meant for a different request. Every request now gets its
 *    own tagged utterance ID and the listener ignores callbacks that don't
 *    match the request it was registered for.
 * 4. Init failure/timeout still nulls the engine out (as before) so the
 *    very next call starts fresh instead of staying dead for the rest of
 *    the app session.
 * 5. speak() now also takes an [onStart] callback (engine's real
 *    UtteranceProgressListener.onStart, not just "request accepted") so a
 *    caller-side loading indicator (e.g. a spinning speak button) can
 *    switch off the instant audio actually begins, covering exactly the
 *    perceptible "slow" window — cold engine init / a busy engine's
 *    queueing delay — without guessing a fixed timeout.
 */
object LocalTts {
    private const val TAG = "LocalTts"
    private const val INIT_TIMEOUT_MS = 3500L

    private data class PendingRequest(
        val text: String,
        val onStart: (() -> Unit)?,
        val onDone: (() -> Unit)?,
        val onError: (() -> Unit)?
    )

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: PendingRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    // Bumped on every new speak() request so a callback tied to an older,
    // already-superseded utterance can't fire against the wrong caller.
    private var requestSeq = 0
    private var activeRequestId = ""
    private var retriedThisRequest = false

    /** Call as early as a screen/service that might need voice starts up
     * (e.g. Activity.onCreate, AccessibilityService.onServiceConnected) so
     * the engine is already warm by the time speak() is actually needed.
     * Safe to call repeatedly — no-ops once an engine exists. */
    fun warmUp(context: Context) = ensureInit(context)

    private fun ensureInit(context: Context) {
        if (tts != null) return
        ready = false

        val engine = TextToSpeech(context.applicationContext) { status ->
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            if (status == TextToSpeech.SUCCESS) {
                val current = tts
                var result = current?.setLanguage(Locale("bn", "BD"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = current?.setLanguage(Locale("bn"))
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // No Bangla voice at all on this device — fall back to
                    // whatever the device default is rather than leaving
                    // the engine in an undefined language state.
                    current?.setLanguage(Locale.getDefault())
                    Log.w(TAG, "No Bangla TTS voice on this device, using device default")
                } else {
                    // THE "TTS is slow" FIX: many engines' Bangla voice is
                    // a *network* voice — every single utterance makes a
                    // live round-trip to synthesize, unlike STT (one
                    // upload of an already-recorded clip to Groq). If the
                    // engine also ships an offline Bangla voice, prefer
                    // it explicitly — same language, no per-sentence
                    // network wait, and it keeps working with no signal.
                    val offlineBangla = current?.voices?.firstOrNull { v ->
                        v.locale.language == "bn" && !v.isNetworkConnectionRequired
                    }
                    if (offlineBangla != null && current?.voice != offlineBangla) {
                        current?.voice = offlineBangla
                        Log.i(TAG, "Switched to offline Bangla voice: ${offlineBangla.name}")
                    } else if (current?.voice?.isNetworkConnectionRequired == true) {
                        Log.w(TAG, "Only a network Bangla voice is available on this device " +
                            "(${current.voice?.name}) — TTS will be network-dependent and " +
                            "slower than a local voice; install an offline voice pack via " +
                            "Settings > Accessibility > Text-to-speech output for faster speech.")
                    }
                }
                ready = true
                pending?.let { req -> speakNow(req.text, req.onStart, req.onDone, req.onError) }
                pending = null
            } else {
                Log.w(TAG, "TextToSpeech init failed, status=$status — will retry fresh next call")
                tts = null
                ready = false
            }
        }
        tts = engine

        // Self-heal on a stuck/slow init instead of waiting forever.
        val timeout = Runnable {
            if (!ready) {
                Log.w(TAG, "TextToSpeech init timed out after ${INIT_TIMEOUT_MS}ms — resetting")
                try { tts?.shutdown() } catch (e: Exception) { /* ignore */ }
                tts = null
                ready = false
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, INIT_TIMEOUT_MS)
    }

    /** [onStart] fires the instant the engine actually begins producing
     * audio (TextToSpeech's onStart callback) — the moment a caller-side
     * "loading" indicator (e.g. a spinning button) should switch off,
     * since sound itself is now confirming the request is working. It
     * fires only once per request and never for a request that errors
     * out before reaching the engine. */
    fun speak(
        context: Context,
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        if (text.isBlank()) return
        ensureInit(context)

        requestSeq += 1
        val requestId = "u${requestSeq}_${UUID.randomUUID()}"
        activeRequestId = requestId
        retriedThisRequest = false

        val engine = tts
        if (!ready || engine == null) {
            pending = PendingRequest(text, onStart, onDone, onError)
            return
        }

        attachListener(engine, requestId, onStart, onDone, onError)
        attemptSpeak(context, text, requestId, onStart, onDone, onError)
    }

    private fun attachListener(
        engine: TextToSpeech,
        requestId: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: (() -> Unit)?
    ) {
        // Always (re)attach — never leave a previous request's listener in
        // place — and ignore any callback that arrives for an utterance ID
        // that isn't the current one (a late callback from an interrupted
        // earlier request racing in after a newer speak() call started).
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != requestId || requestId != activeRequestId) return
                onStart?.invoke()
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != requestId || requestId != activeRequestId) return
                onDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != requestId || requestId != activeRequestId) return
                // A mid-speech engine error shouldn't poison every future
                // call either — drop the instance so the next speak()
                // starts clean.
                tts = null
                ready = false
                onError?.invoke()
            }
        })
    }

    private fun attemptSpeak(
        context: Context,
        text: String,
        requestId: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: (() -> Unit)?
    ) {
        val result = try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId) ?: TextToSpeech.ERROR
        } catch (e: Exception) {
            Log.w(TAG, "speak() threw, resetting engine", e)
            TextToSpeech.ERROR
        }

        if (result == TextToSpeech.SUCCESS) return

        // THE MAIN FIX: speak() failed (busy engine / dead binder / etc.)
        // without throwing. Tear the engine down and retry exactly once
        // with a freshly created engine instead of silently doing nothing.
        Log.w(TAG, "speak() returned error for this utterance — resetting engine and retrying once")
        try { tts?.shutdown() } catch (e: Exception) { /* ignore */ }
        tts = null
        ready = false

        if (retriedThisRequest) {
            Log.w(TAG, "Retry also failed — giving up on this utterance")
            onError?.invoke()
            return
        }
        retriedThisRequest = true

        ensureInit(context)
        val freshEngine = tts
        if (!ready || freshEngine == null) {
            // Fresh engine still initializing — queue it, the init
            // callback's speakNow(pending) path will play it once ready.
            pending = PendingRequest(text, onStart, onDone, onError)
            return
        }
        attachListener(freshEngine, requestId, onStart, onDone, onError)
        attemptSpeak(context, text, requestId, onStart, onDone, onError)
    }

    private fun speakNow(text: String, onStart: (() -> Unit)? = null, onDone: (() -> Unit)? = null, onError: (() -> Unit)? = null) {
        val engine = tts ?: return
        requestSeq += 1
        val requestId = "u${requestSeq}_${UUID.randomUUID()}"
        activeRequestId = requestId
        retriedThisRequest = true // queued/init-triggered replays don't auto-retry again
        attachListener(engine, requestId, onStart, onDone, onError)
        try {
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() (queued replay) returned error — resetting engine")
                tts = null
                ready = false
                onError?.invoke()
            }
        } catch (e: Exception) {
            Log.w(TAG, "speak() threw, resetting engine", e)
            tts = null
            ready = false
            onError?.invoke()
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) { /* ignore */ }
    }
}
