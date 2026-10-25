package com.hemel.lenspilot.ads

import android.app.Activity
import android.content.Context
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import org.json.JSONObject

/**
 * Picks which rewarded-ad network is actually used — decided entirely
 * server-side (GET /api/ads/network; admin-panel switchable, see app.py's
 * get_ad_network()/set_ad_network() and the "🎬 Rewarded ad network"
 * dashboard section), NEVER shown as a choice to the user. AdBreakActivity
 * talks only to this object, never to RewardedAdManager or
 * StartIoRewardedAdManager directly — so a later admin-panel flip back to
 * AdMob needs zero app changes/updates.
 *
 * Defaults to "startio" — both as the fallback when /api/ads/network can't
 * be reached, and matching the server's own current default — per the
 * present rollout: Start.io is the only network actually serving ads for
 * now, AdMob stays fully wired and ready for whenever the admin panel
 * switches back.
 */
object AdNetworkManager {

    private const val DEFAULT_NETWORK = "startio"

    @Volatile
    private var resolvedNetwork: String = DEFAULT_NETWORK

    /** Call once, early (AdBreakActivity.onCreate, inside a coroutine).
     * Inits BOTH SDKs — cheap, no ad is actually requested yet — then
     * resolves which one is active from the server. */
    suspend fun init(context: Context, baseUrl: String) {
        try {
            RewardedAdManager.init(context)
            StartIoRewardedAdManager.init(context, context.getString(R.string.startio_app_id))
            resolvedNetwork = fetchActiveNetwork(context, baseUrl)
        } catch (e: Exception) {
            // Never let a failed network resolve/SDK init crash the
            // ad-break screen — fall back to the default network.
            resolvedNetwork = DEFAULT_NETWORK
        }
    }

    private suspend fun fetchActiveNetwork(context: Context, baseUrl: String): String {
        val result = ApiClient.getAuthed(context, baseUrl, "/api/ads/network")
        val network = result.mapCatching { body -> JSONObject(body).optString("network", DEFAULT_NETWORK) }
            .getOrNull()
        return if (network == "admob" || network == "startio") network else DEFAULT_NETWORK
    }

    fun isReady(): Boolean = when (resolvedNetwork) {
        "admob" -> RewardedAdManager.isReady()
        else -> StartIoRewardedAdManager.isReady()
    }

    fun load(context: Context, adUnitId: String, uid: String?, onResult: (Boolean) -> Unit = {}) {
        when (resolvedNetwork) {
            "admob" -> RewardedAdManager.load(context, adUnitId, uid, onResult)
            else -> StartIoRewardedAdManager.load(context, onResult)
        }
    }

    fun show(
        activity: Activity,
        onEarnedReward: () -> Unit,
        onDismissed: () -> Unit,
        onFailedToShow: () -> Unit
    ) {
        when (resolvedNetwork) {
            "admob" -> RewardedAdManager.show(activity, onEarnedReward, onDismissed, onFailedToShow)
            else -> StartIoRewardedAdManager.show(activity, onEarnedReward, onDismissed, onFailedToShow)
        }
    }
}
