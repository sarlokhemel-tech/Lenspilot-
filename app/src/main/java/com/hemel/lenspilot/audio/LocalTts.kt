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
    private var currentLangTag = ""

    /** Picks a speaking locale from the dominant script of [text] (any language). */
    private fun localeForText(text: String): Locale {
        val counts = HashMap<Character.UnicodeScript, Int>()
        for (ch in text.take(400)) {
            if (!ch.isLetter()) continue
            val sc = try { Character.UnicodeScript.of(ch.code) } catch (e: Exception) { continue }
            counts[sc] = (counts[sc] ?: 0) + 1
        }
        return when (counts.maxByOrNull { it.value }?.key) {
            Character.UnicodeScript.BENGALI -> Locale("bn", "BD")
            Character.UnicodeScript.DEVANAGARI -> Locale("hi", "IN")
            Character.UnicodeScript.ARABIC -> Locale("ar")
            Character.UnicodeScript.CYRILLIC -> Locale("ru")
            Character.UnicodeScript.HAN -> Locale.CHINA
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Locale.JAPAN
            Character.UnicodeScript.HANGUL -> Locale.KOREA
            Character.UnicodeScript.THAI -> Locale("th", "TH")
            Character.UnicodeScript.TAMIL -> Locale("ta", "IN")
            Character.UnicodeScript.TELUGU -> Locale("te", "IN")
            Character.UnicodeScript.GUJARATI -> Locale("gu", "IN")
            Character.UnicodeScript.KANNADA -> Locale("kn", "IN")
            Character.UnicodeScript.MALAYALAM -> Locale("ml", "IN")
            Character.UnicodeScript.GURMUKHI -> Locale("pa", "IN")
            Character.UnicodeScript.GREEK -> Locale("el", "GR")
            Character.UnicodeScript.HEBREW -> Locale("he", "IL")
            else -> {
                // Latin and others: use the device language unless it is a non-Latin one.
                val d = Locale.getDefault()
                val nonLatin = setOf("bn", "hi", "ar", "ru", "ja", "zh", "ko", "th", "ta", "te", "gu", "kn", "ml", "pa", "el", "he", "fa", "ur")
                if (d.language in nonLatin) Locale.ENGLISH else d
            }
        }
    }

    private fun applyLanguageFor(engine: TextToSpeech, text: String) {
        val locale = localeForText(text)
        val tag = locale.toLanguageTag()
        if (tag == currentLangTag) return
        var r = engine.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = engine.setLanguage(Locale(locale.language))
        }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
            Log.w(TAG, "No TTS voice for ${locale.language}, using device default")
        } else {
            // Prefer an offline voice of the same language (no network wait per sentence).
            val offline = engine.voices?.firstOrNull { v ->
                v.locale.language == locale.language && !v.isNetworkConnectionRequired
            }
            if (offline != null && engine.voice != offline) engine.voice = offline
        }
        currentLangTag = tag
    }

    /** Call as early as a screen/service that might need voice starts up
     * (e.g. Activity.onCreate, AccessibilityService.onServiceConnected) so
     * the engine is already warm by the time speak() is actually needed.
     * Safe to call repeatedly — no-ops once an engine exists. */
    fun warmUp(context: Context) = ensureInit(context)

    private fun ensureInit(context: Context) {
        if (tts != null) return
        ready = false
        currentLangTag = ""

        val engine = TextToSpeech(context.applicationContext) { status ->
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            if (status == TextToSpeech.SUCCESS) {
                val current = tts
                // The speaking language follows the text of each request (see applyLanguageFor),
                // so the engine starts on the device language.
                current?.setLanguage(Locale.getDefault())
                currentLangTag = ""
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
            tts?.let { applyLanguageFor(it, text) }
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
            applyLanguageFor(engine, text)
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
