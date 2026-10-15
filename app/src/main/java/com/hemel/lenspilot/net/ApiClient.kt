package com.hemel.lenspilot.net

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.hemel.lenspilot.R
import com.hemel.lenspilot.security.IntegrityTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to the Lenspilot Space backend using its SESSION-TOKEN flow:
 *
 *   1. Once per install (or after the stored session token is rejected),
 *      call [login] with a fresh Firebase ID token + Play Integrity token.
 *      The Space does its full verification chain ONE time and returns a
 *      `session_token` that never expires server-side.
 *   2. Every other call (chat, analyze-screen, tts, ...) goes through
 *      [callAuthed], which sends ONLY that stored session_token — no
 *      Firebase/Integrity token, no extra network round-trip, near-zero
 *      overhead per request.
 *   3. If the Space ever rejects the stored token (e.g. "code":
 *      "NO_SESSION" — this shouldn't normally happen since tokens don't
 *      expire, but could happen if SESSION_TOKEN_SECRET rotates on the
 *      Space), [callAuthed] returns that error back to the caller, who
 *      should call [login] again and retry.
 *
 * The session token is cached in SharedPreferences via [SessionStore] so
 * it survives app restarts — login only has to happen again if the user
 * signs out or the stored token becomes invalid.
 */
object ApiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Server caps each Gemini attempt at 20s and may retry once with a
        // fallback model on quota errors (worst case ~40s of actual AI
        // call time) before it ever writes a byte back — 60s here was too
        // tight against that and could time out client-side with NOTHING
        // ever coming back, even though the server was still working.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** No auth required — matches @app.route("/api/health") in app.py. */
    suspend fun health(baseUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").get().build()
            client.newCall(request).execute().use { resp ->
                "HTTP ${resp.code}: ${resp.body?.string()}"
            }
        }
    }

    /**
     * Step 1 — full verification, once. Matches @require_full_verification
     * on /api/session/login in app.py. On success, caches the returned
     * session_token via [SessionStore] and also returns it.
     */
    suspend fun login(
        context: Context,
        baseUrl: String,
        idToken: String,
        integrityToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/session/login")
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("X-Integrity-Token", integrityToken)
                .post("{}".toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, bodyStr)
                }
                val sessionToken = JSONObject(bodyStr).getString("session_token")
                SessionStore.save(context, sessionToken)
                sessionToken
            }
        }
    }

    /**
     * Step 2 — every other authenticated call. Matches @require_session
     * endpoints in app.py (/api/chat, /api/analyze-screen, /api/tts, ...).
     * Sends the cached session token; if none is cached, fails fast with a
     * clear message telling the caller to [login] first.
     */
    private suspend fun callAuthedOnce(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = SessionStore.get(context)
                ?: throw ApiException(401, """{"error":"No session token cached — call login() first.","code":"NO_SESSION_LOCAL"}""")

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(jsonBody.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, bodyStr)
                }
                bodyStr
            }
        }
    }


    // ---------------------------------------------------------------------------------------------
    // v14 FIX ("অ্যাপ আপডেট দিলে সেশন হারায়, লগ আউট/লগ ইন করতে হয়"):
    // সার্ভার রিস্টার্ট/আপডেটের পর পুরনো session token বাতিল (SESSION_EXPIRED / NO_SESSION) হতে পারে। আগে অ্যাপ সেটা
    // দেখেও আবার লগইন করত না — তাই টোকেন আসত না, ইউজারকে হাতে লগ আউট → লগ ইন করতে হতো। এখন যেকোনো authed
    // কলে ওই ত্রুটি এলে নিজে থেকেই (Firebase ID token + Integrity দিয়ে) নতুন session নিয়ে কলটা আবার চালানো হয়।
    // ---------------------------------------------------------------------------------------------
    private val sessionRefreshMutex = Mutex()

    private fun needsRelogin(t: Throwable): Boolean {
        val e = t as? ApiException ?: return false
        return e.code == 401 && (e.body.contains("NO_SESSION") || e.body.contains("SESSION_EXPIRED"))
    }

    /** true = এখন একটা (নতুন বা অন্য কেউ এইমাত্র নেওয়া) বৈধ session আছে, কলটা আবার চালানো যায়। */
    private suspend fun refreshSession(context: Context, baseUrl: String, staleToken: String?): Boolean {
        return sessionRefreshMutex.withLock {
            val current = SessionStore.get(context)
            if (current != null && current != staleToken) return@withLock true // অন্য কল ইতিমধ্যে নতুন নিয়েছে
            val user = FirebaseAuth.getInstance().currentUser ?: return@withLock false // সত্যিই সাইন-আউট
            try {
                val idToken = user.getIdToken(true).await().token ?: return@withLock false
                val integrity = try {
                    IntegrityTokenProvider(
                        context.applicationContext,
                        context.applicationContext.getString(R.string.cloud_project_number).toLong()
                    ).requestToken()
                } catch (e: Exception) {
                    "" // DEV_MODE_SKIP_INTEGRITY থাকলে খালি টোকেনই চলে
                }
                login(context, baseUrl, idToken, integrity).isSuccess
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun <T> withSessionRetry(
        context: Context,
        baseUrl: String,
        block: suspend () -> Result<T>
    ): Result<T> {
        val staleToken = SessionStore.get(context)
        val first = block()
        val err = first.exceptionOrNull() ?: return first
        if (!needsRelogin(err)) return first
        return if (refreshSession(context, baseUrl, staleToken)) block() else first
    }

    suspend fun callAuthed(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String
    ): Result<String> = withSessionRetry(context, baseUrl) { callAuthedOnce(context, baseUrl, path, jsonBody) }

    suspend fun streamAuthed(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String,
        onEvent: (JSONObject) -> Unit
    ): Result<Unit> = withSessionRetry(context, baseUrl) { streamAuthedOnce(context, baseUrl, path, jsonBody, onEvent) }

    suspend fun uploadAudioAuthed(
        context: Context,
        baseUrl: String,
        path: String,
        audioFile: File
    ): Result<String> = withSessionRetry(context, baseUrl) { uploadAudioAuthedOnce(context, baseUrl, path, audioFile) }

    suspend fun fetchAudioAuthed(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String
    ): Result<ByteArray> = withSessionRetry(context, baseUrl) { fetchAudioAuthedOnce(context, baseUrl, path, jsonBody) }

    suspend fun getAuthed(context: Context, baseUrl: String, path: String): Result<String> =
        withSessionRetry(context, baseUrl) { getAuthedOnce(context, baseUrl, path) }

    /** True if the Space rejected our token specifically because the
     * session is gone/invalid — the signal to call [login] again. */
    fun isSessionError(throwable: Throwable): Boolean {
        val apiEx = throwable as? ApiException ?: return false
        return apiEx.code == 401 &&
            (apiEx.body.contains("NO_SESSION") || apiEx.body.contains("SESSION_EXPIRED"))
    }

    /** GET variant of [callAuthed] (e.g. /api/history). */
    private suspend fun getAuthedOnce(context: Context, baseUrl: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sessionToken = SessionStore.get(context)
                    ?: throw ApiException(401, """{"error":"No session token cached — call login() first.","code":"NO_SESSION_LOCAL"}""")
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}$path")
                    .addHeader("Authorization", "Bearer $sessionToken")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(resp.code, bodyStr)
                    bodyStr
                }
            }
        }

    /**
     * SSE-aware variant of [callAuthed] for endpoints that respond with
     * `text/event-stream` (/api/chat, /api/analyze-screen): reads the
     * response line-by-line AS BYTES ARRIVE and invokes [onEvent] for every
     * `data: {...}` line, parsed into a JSONObject — instead of blocking
     * until the whole stream finishes like plain `.string()` would (which
     * is what made the old "Test Backend Connection" screen dump the raw
     * `data: {...}` text instead of live, incremental replies).
     *
     * [onEvent] runs on a background thread — callers must hop back to the
     * main thread themselves (e.g. `runOnUiThread { ... }`) before touching
     * any View.
     */
    private suspend fun streamAuthedOnce(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String,
        onEvent: (JSONObject) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = SessionStore.get(context)
                ?: throw ApiException(401, """{"error":"No session token cached — call login() first.","code":"NO_SESSION_LOCAL"}""")

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(jsonBody.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, resp.body?.string().orEmpty())
                }
                val source = resp.body?.source() ?: return@use
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isEmpty()) continue
                    val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
                    onEvent(obj)
                }
            }
        }
    }

    /** Multipart upload for /api/transcribe (Groq Whisper). [audioFile]
     * should already be a finished recording (mic recording must be
     * stopped first). */
    private suspend fun uploadAudioAuthedOnce(
        context: Context,
        baseUrl: String,
        path: String,
        audioFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = SessionStore.get(context)
                ?: throw ApiException(401, """{"error":"No session token cached — call login() first.","code":"NO_SESSION_LOCAL"}""")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, bodyStr)
                bodyStr
            }
        }
    }

    /** Downloads raw audio bytes from /api/tts (a WAV file, non-JSON). Not
     * chunked/progressive playback yet — see TtsPlayer for the next-phase
     * TODO on true streamed playback. */
    private suspend fun fetchAudioAuthedOnce(
        context: Context,
        baseUrl: String,
        path: String,
        jsonBody: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = SessionStore.get(context)
                ?: throw ApiException(401, """{"error":"No session token cached — call login() first.","code":"NO_SESSION_LOCAL"}""")

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(jsonBody.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, resp.body?.string().orEmpty())
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    /** Screen VLM tier — POST /api/vision/screen-elements. Replaces the old
     * on-device YOLO detector+classifier (removed from the app): the Space
     * runs Florence-2 on the screenshot and returns element boxes+labels in
     * the same ScreenElement wire shape. Called by [VisionFallbackManager]
     * either because the user picked "স্ক্রিন VLM" in Settings, or because
     * Accessibility permission isn't granted (the fallback-only case that
     * already existed before this feature). */
    suspend fun screenVlmElements(
        context: Context,
        baseUrl: String,
        imageBase64: String
    ): Result<JSONObject> {
        val body = JSONObject().apply { put("image_base64", imageBase64) }
        return callAuthed(context, baseUrl, "/api/vision/screen-elements", body.toString())
            .map { JSONObject(it) }
    }
}

class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")
