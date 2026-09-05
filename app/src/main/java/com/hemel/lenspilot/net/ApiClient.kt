package com.hemel.lenspilot.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
    suspend fun callAuthed(
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

    /** True if the Space rejected our token specifically because the
     * session is gone/invalid — the signal to call [login] again. */
    fun isSessionError(throwable: Throwable): Boolean {
        val apiEx = throwable as? ApiException ?: return false
        return apiEx.code == 401 &&
            (apiEx.body.contains("NO_SESSION") || apiEx.body.contains("SESSION_EXPIRED"))
    }

    /** GET variant of [callAuthed] (e.g. /api/history). */
    suspend fun getAuthed(context: Context, baseUrl: String, path: String): Result<String> =
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
    suspend fun streamAuthed(
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
    suspend fun uploadAudioAuthed(
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
    suspend fun fetchAudioAuthed(
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
}

class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")
