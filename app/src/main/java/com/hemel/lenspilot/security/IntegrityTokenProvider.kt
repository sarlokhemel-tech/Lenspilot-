package com.hemel.lenspilot.security

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Requests a Play Integrity token (Classic API) that the Space backend
 * decodes with playintegrity.googleapis.com:decodeIntegrityToken.
 *
 * IMPORTANT — this only returns a PLAY_RECOGNIZED verdict for an APK that
 * was installed *through Google Play* (at least an internal-testing track,
 * signed with the Play App Signing key). A sideloaded GitHub Actions debug
 * APK will still get a token back from this call, but the Space's
 * verify_integrity_token() will reject it as "App not recognized by Play"
 * until the app is uploaded to a Play Console testing track. Sign-in and
 * /api/health can be tested before that; /api/chat and the other
 * @require_verified_request endpoints cannot.
 */
class IntegrityTokenProvider(context: Context, private val cloudProjectNumber: Long) {

    private val integrityManager = IntegrityManagerFactory.create(context)

    suspend fun requestToken(): String = suspendCancellableCoroutine { cont ->
        val nonce = randomNonce()
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .setCloudProjectNumber(cloudProjectNumber)
            .build()

        integrityManager.requestIntegrityToken(request)
            .addOnSuccessListener { response -> cont.resume(response.token()) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
