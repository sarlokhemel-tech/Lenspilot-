package com.hemel.lenspilot.intent

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.text.Normalizer

/**
 * Opens apps / exact Settings pages / deep links with plain Intents — no
 * Accessibility click needed. The server only sends an app NAME (never a
 * guessed package); the package is found here from the apps actually
 * installed on this phone. If the app is not found we say so (v45: the Play Store
 * is NO LONGER opened on its own — the app may be installed under another name, or
 * already open; opening the store confused users. Use [openPlayStoreSearch] only when
 * the user asks to install).
 */
object IntentLauncher {
    private const val TAG = "IntentLauncher"

    sealed class Result {
        object Opened : Result()
        data class NotInstalled(val appName: String, val similar: List<String> = emptyList()) : Result()
        data class Fallback(val note: String) : Result()   // opened something close (e.g. main Settings)
        data class Failed(val why: String) : Result()
    }

    data class AppRef(val pkg: String, val label: String)

    private val BLOCKED_SCHEMES = setOf(
        "file", "content", "javascript", "intent", "tel", "sms", "smsto", "mms", "mmsto", "ussd", "data", "blob"
    )

    fun execute(ctx: Context, actionType: String, target: JSONObject): Result {
        return try {
            when (actionType) {
                "open_app" -> openApp(ctx, target)
                "open_settings" -> openSettings(ctx, target)
                "open_app_settings" -> withApp(ctx, target) { app -> start(ctx, appDetailsIntent(app.pkg)); Result.Opened }
                "open_app_notification_settings" -> withApp(ctx, target) { app -> openNotificationSettings(ctx, app.pkg) }
                "open_url" -> openUri(ctx, target.optString("url", ""), null, requireHttp = true)
                "deep_link" -> openUri(ctx, target.optString("uri", ""), findApp(ctx, target), requireHttp = false)
                else -> Result.Failed("unknown action")
            }
        } catch (e: Exception) {
            Log.w(TAG, "execute failed: $actionType", e)
            Result.Failed(e.javaClass.simpleName)
        }
    }

    // ---------------- apps ----------------

    private fun openApp(ctx: Context, target: JSONObject): Result {
        val name = target.optString("app_name", "").trim()
        val app = findApp(ctx, target)
        if (app == null) {
            val shown = name.ifBlank { target.optString("package", "") }
            return Result.NotInstalled(shown, similarApps(ctx, shown))
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return Result.NotInstalled(app.label)
        start(ctx, launch)
        return Result.Opened
    }

    private inline fun withApp(ctx: Context, target: JSONObject, block: (AppRef) -> Result): Result {
        val app = findApp(ctx, target)
        if (app == null) {
            val shown = target.optString("app_name", "").ifBlank { target.optString("package", "") }
            return Result.NotInstalled(shown, similarApps(ctx, shown))
        }
        return block(app)
    }

    /** Installed app that best matches app_name (or an installed `package`). */
    fun findApp(ctx: Context, target: JSONObject): AppRef? {
        val pm = ctx.packageManager
        val pkgHint = target.optString("package", "").trim()
        if (pkgHint.isNotEmpty() && isInstalled(pm, pkgHint)) return AppRef(pkgHint, labelOf(pm, pkgHint))
        val q = norm(target.optString("app_name", ""))
        if (q.isEmpty()) return null

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(launcher, 0)
        var best: AppRef? = null
        var bestScore = 0
        for (ri in infos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { continue }
            val l = norm(label)
            val pkgTokens = pkg.lowercase().split('.').map { norm(it) }
            val score = when {
                l == q -> 100
                pkgTokens.any { it == q } && q.length >= 3 -> 90
                l.isNotEmpty() && q.length >= 3 && (l.startsWith(q) || q.startsWith(l)) -> 85 - kotlin.math.abs(l.length - q.length).coerceAtMost(20)
                l.isNotEmpty() && q.length >= 3 && (l.contains(q) || q.contains(l)) && l.length >= 3 -> 70 - kotlin.math.abs(l.length - q.length).coerceAtMost(20)
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = AppRef(pkg, label) }
        }
        return if (bestScore >= 50) best else null
    }

    /** নাম মেলেনি — ফোনে ইনস্টল-করা সব অ্যাপের লেবেল (সর্বোচ্চ ১৫০), যাতে AI তালিকা থেকে হুবহু বেছে নিতে পারে। কোনো নাম-তালিকা কোডে বসানো নেই। */
    fun similarApps(ctx: Context, name: String): List<String> {
        return try {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            ctx.packageManager.queryIntentActivities(launcher, 0).mapNotNull { ri ->
                try { ri.loadLabel(ctx.packageManager).toString().trim().takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
            }.distinct().sorted().take(150)
        } catch (e: Exception) { emptyList() }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        try { pm.getLaunchIntentForPackage(pkg) != null || pm.getApplicationInfo(pkg, 0) != null } catch (e: Exception) { false }

    private fun labelOf(pm: PackageManager, pkg: String): String =
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }

    // ---------------- settings ----------------

    private val ACTION_RE = Regex("^android\\.settings\\.[A-Z0-9_]{3,64}$")

    private fun openSettings(ctx: Context, target: JSONObject): Result {
        val action = target.optString("settings_action", "")
        if (!ACTION_RE.matches(action)) return openMainSettings(ctx, "Opened general settings")
        val app = if (target.has("app_name") || target.has("package")) findApp(ctx, target) else null
        val attempts = mutableListOf<Intent>()
        if (app != null) {
            attempts += Intent(action).apply {
                data = Uri.fromParts("package", app.pkg, null)
                putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg)
            }
        }
        attempts += Intent(action)
        for (i in attempts) {
            try { start(ctx, i); return Result.Opened } catch (e: ActivityNotFoundException) { /* try next */ } catch (e: SecurityException) { /* try next */ }
        }
        return openMainSettings(ctx, "The exact page could not be opened on this phone. Opened general settings instead.")
    }

    private fun openMainSettings(ctx: Context, note: String): Result {
        start(ctx, Intent(Settings.ACTION_SETTINGS))
        return Result.Fallback(note)
    }

    private fun appDetailsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", pkg, null)
    }

    private fun openNotificationSettings(ctx: Context, pkg: String): Result {
        val uid = try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (e: Exception) { -1 }
        val i = Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", pkg)
            if (uid >= 0) putExtra("app_uid", uid)
        }
        return try { start(ctx, i); Result.Opened } catch (e: ActivityNotFoundException) {
            start(ctx, appDetailsIntent(pkg)); Result.Fallback("Opened the app's settings page")
        }
    }

    // ---------------- links ----------------

    private fun openUri(ctx: Context, uriStr: String, app: AppRef?, requireHttp: Boolean): Result {
        val uri = try { Uri.parse(uriStr) } catch (e: Exception) { return Result.Failed("bad uri") }
        val scheme = uri.scheme?.lowercase() ?: return Result.Failed("no scheme")
        if (scheme in BLOCKED_SCHEMES) return Result.Failed("blocked scheme")
        if (requireHttp && scheme != "http" && scheme != "https") return Result.Failed("not http")
        val tries = mutableListOf<Intent>()
        if (app != null) tries += Intent(Intent.ACTION_VIEW, uri).setPackage(app.pkg)
        tries += Intent(Intent.ACTION_VIEW, uri)
        for (i in tries) {
            try { start(ctx, i); return Result.Opened } catch (e: ActivityNotFoundException) { /* next */ }
        }
        return Result.Failed("no app handles link")
    }

    // ---------------- Play Store ----------------

    fun openPlayStoreSearch(ctx: Context, name: String) {
        val q = Uri.encode(name.ifBlank { "app" })
        openStore(ctx, Uri.parse("market://search?q=$q&c=apps"), Uri.parse("https://play.google.com/store/search?q=$q&c=apps"))
    }

    fun openPlayStoreDetails(ctx: Context, pkg: String) =
        openStore(ctx, Uri.parse("market://details?id=$pkg"), Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))

    private fun openStore(ctx: Context, market: Uri, web: Uri) {
        try { start(ctx, Intent(Intent.ACTION_VIEW, market)) }
        catch (e: Exception) { runCatching { start(ctx, Intent(Intent.ACTION_VIEW, web)) } }
    }

    // ---------------- launching from a background service ----------------

    private fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw e
        } catch (e: Exception) {
            // Background-activity-start restrictions: retry through a PendingIntent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(ctx, 0, intent, flags).send()
        }
    }
}
