package com.hemel.lenspilot.tools.shell

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hemel.lenspilot.R
import com.hemel.lenspilot.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub লগইন — OAuth Device Flow।
 *
 * নিয়ম: টোকেন কখনো প্রিন্ট/লগ হয় না এবং প্লেইন টেক্সট ফেরত দেওয়ার কোনো পাবলিক API নেই।
 * টোকেন শুধু Android Keystore (AES-256-GCM) দিয়ে এনক্রিপ্ট হয়ে SharedPreferences-এ থাকে
 * (SecretVault-এর একই প্যাটার্ন, আলাদা কী/ফাইল) এবং [credentialsProvider]-এর ভেতর দিয়ে সরাসরি JGit-এ যায়।
 */
object GitHubAuth {
    private const val PREFS = "lenspilot_github_auth"
    private const val KEY_ALIAS = "lenspilot_github_token_key_v1"
    private const val KEY_TOKEN = "tok"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val SCOPES = "repo workflow"
    private const val PLACEHOLDER = "YOUR_GITHUB_OAUTH_CLIENT_ID"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class PollResult {
        object Success : PollResult()
        object Expired : PollResult()
        object Denied : PollResult()
        class Failed(val message: String) : PollResult()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** strings.xml-এর github_oauth_client_id; ফাঁকা বা প্লেসহোল্ডার থাকলে null। */
    fun clientId(ctx: Context): String? {
        val v = ctx.applicationContext.getString(R.string.github_oauth_client_id).trim()
        return if (v.isEmpty() || v == PLACEHOLDER) null else v
    }

    fun isLoggedIn(ctx: Context): Boolean = loadToken(ctx) != null

    fun logout(ctx: Context) {
        SecretVault.deleteTitle(ctx.applicationContext, SecretVault.GITHUB_TITLE)
        prefs(ctx).edit().remove(KEY_TOKEN).apply()
    }

    /** JGit-কে টোকেন দেওয়ার একমাত্র পথ। লগইন না থাকলে null। */
    fun credentialsProvider(ctx: Context): CredentialsProvider? {
        if (!isLoggedIn(ctx)) return null
        val app = ctx.applicationContext
        return GitHubCredentialsProvider { loadToken(app) }
    }

    // ------------------------------------------------------------ Device Flow
    suspend fun requestDeviceCode(ctx: Context): DeviceCode = withContext(Dispatchers.IO) {
        val cid = clientId(ctx) ?: throw IOException(ctx.getString(R.string.shell_gh_no_client_id))
        val body = FormBody.Builder().add("client_id", cid).add("scope", SCOPES).build()
        val req = Request.Builder().url(DEVICE_CODE_URL).header("Accept", "application/json").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("GitHub থেকে অবৈধ উত্তর (${resp.code})")
            }
            if (o.has("error")) {
                val err = o.optString("error")
                if (err == "device_flow_disabled") throw IOException(ctx.getString(R.string.shell_gh_flow_disabled))
                throw IOException(o.optString("error_description").ifEmpty { err })
            }
            DeviceCode(
                deviceCode = o.getString("device_code"),
                userCode = o.getString("user_code"),
                verificationUri = o.optString("verification_uri", "https://github.com/login/device"),
                expiresIn = o.optInt("expires_in", 900),
                interval = o.optInt("interval", 5)
            )
        }
    }

    /** সার্ভারের দেওয়া interval ধরে পোল; slow_down ও মেয়াদ-শেষ সামলায়। সফল হলে টোকেন ভেতরে সেভ হয় (ফেরত আসে না)। */
    suspend fun pollForToken(ctx: Context, dc: DeviceCode): PollResult {
        val cid = clientId(ctx) ?: return PollResult.Failed(ctx.getString(R.string.shell_gh_no_client_id))
        var interval = if (dc.interval > 0) dc.interval else 5
        val deadline = SystemClock.elapsedRealtime() + dc.expiresIn * 1000L
        var netFails = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(interval * 1000L)
            val o: JSONObject? = withContext(Dispatchers.IO) { pollOnce(cid, dc.deviceCode) }
            if (o == null) {
                netFails++
                if (netFails >= 4) return PollResult.Failed(ctx.getString(R.string.shell_gh_network))
                continue
            }
            netFails = 0
            val tok = o.optString("access_token")
            if (tok.isNotEmpty()) {
                return if (saveToken(ctx, tok)) PollResult.Success
                else PollResult.Failed(ctx.getString(R.string.shell_gh_save_failed))
            }
            when (val err = o.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> {
                    val srv = o.optInt("interval", 0)
                    interval = if (srv > interval) srv else interval + 5
                }
                "expired_token" -> return PollResult.Expired
                "access_denied" -> return PollResult.Denied
                else -> return PollResult.Failed(o.optString("error_description").ifEmpty { err.ifEmpty { "অজানা ত্রুটি" } })
            }
        }
        return PollResult.Expired
    }

    private fun pollOnce(clientId: String, deviceCode: String): JSONObject? = try {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder().url(TOKEN_URL).header("Accept", "application/json").post(body).build()
        http.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ Keystore (SecretVault-এর প্যাটার্ন)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** সেশন ৩: টোকেন এখন একই SecretVault-এ ("GitHub"/"token") — আলাদা স্টোর নয়। */
    private fun saveToken(ctx: Context, token: String): Boolean =
        SecretVault.put(ctx.applicationContext, SecretVault.GITHUB_TITLE, SecretVault.GITHUB_FIELD, token)

    /** private — কোনো পাবলিক API এটা ফেরত দেয় না। ভল্টে না থাকলে পুরনো আলাদা স্টোর থেকে একবার সরিয়ে আনে। */
    private fun loadToken(ctx: Context): String? {
        val app = ctx.applicationContext
        SecretVault.get(app, SecretVault.GITHUB_TITLE, SecretVault.GITHUB_FIELD)?.let { return it }
        val legacy = loadLegacyToken(app) ?: return null
        if (SecretVault.put(app, SecretVault.GITHUB_TITLE, SecretVault.GITHUB_FIELD, legacy)) {
            prefs(app).edit().remove(KEY_TOKEN).apply()
        }
        return legacy
    }

    /** শুধু মাইগ্রেশনের জন্য (সেশন ২-এর পুরনো এনক্রিপ্টেড স্টোর)। */
    private fun loadLegacyToken(ctx: Context): String? = try {
        val blob = prefs(ctx).getString(KEY_TOKEN, null)
        if (blob == null) {
            null
        } else {
            val i = blob.indexOf(':')
            val iv = Base64.decode(blob.substring(0, i), Base64.NO_WRAP)
            val ct = Base64.decode(blob.substring(i + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    /** টোকেন শুধু github.com-এর জন্য; অন্য হোস্টে কখনো পাঠানো হয় না। toString-এ কিছু ফাঁস হয় না। */
    private class GitHubCredentialsProvider(private val token: () -> String?) : CredentialsProvider() {
        override fun isInteractive(): Boolean = false

        override fun supports(vararg items: CredentialItem): Boolean =
            items.all { it is CredentialItem.Username || it is CredentialItem.Password }

        override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
            val host = uri.host ?: return false
            if (!host.equals("github.com", ignoreCase = true)) return false
            val t = token() ?: return false
            for (item in items) {
                when (item) {
                    is CredentialItem.Username -> item.value = "x-access-token"
                    is CredentialItem.Password -> item.value = t.toCharArray()
                    else -> throw UnsupportedCredentialItem(uri, item.javaClass.name + ":" + item.promptText)
                }
            }
            return true
        }

        override fun toString(): String = "GitHubCredentialsProvider"
    }
}
