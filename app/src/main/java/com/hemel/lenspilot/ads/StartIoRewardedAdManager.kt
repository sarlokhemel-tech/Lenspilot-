package com.hemel.lenspilot.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.VideoListener
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener

/**
 * Wraps Start.io's rewarded-video flow with the exact same shape as
 * [RewardedAdManager] (init/isReady/load/show) so [AdBreakActivity] can
 * treat the two networks interchangeably — see [AdNetworkManager], which
 * picks between them based on the server's /api/ads/network switch
 * (app.py get_ad_network(), admin-panel toggle).
 *
 * Start.io's App ID (see AndroidManifest.xml's
 * com.startapp.sdk.APPLICATION_ID meta-data / strings.xml's
 * startio_app_id — both must match) is enough for a plain Rewarded Video
 * placement; unlike AdMob there's no separate per-placement "ad unit ID"
 * to create in their dashboard for this.
 *
 * Reward semantics differ slightly from AdMob: Start.io has no distinct
 * "onEarnedReward" callback of its own — the convention is to grant the
 * reward when the video actually finishes playing
 * (VideoListener.onVideoCompleted), which is what [show]'s onEarnedReward
 * is wired to below.
 *
 * NOTE: I don't have live access to Start.io's docs from here to confirm
 * this against their current SDK version's exact class/method names — if
 * something below doesn't compile after adding the dependency, check
 * Start.io's Android Integration Guide for what changed and fix just this
 * file (AdBreakActivity/AdNetworkManager don't need to know either way).
 */
object StartIoRewardedAdManager {

    private var initialized = false
    private var startAppAd: StartAppAd? = null
    private var isLoading = false

    fun init(context: Context, appId: String) {
        if (initialized) return
        initialized = true
        try {
            // returnAdsEnabled=false — this app only ever wants the one
            // specific rewarded-video flow triggered from AdBreakActivity,
            // not Start.io's own automatic "ad on app return" behavior.
            StartAppSDK.init(context.applicationContext, appId, false)
            // DIAGNOSTIC (temporary — see Md Golam Sorwar's account, App ID
            // 208622167, on Start.io's portal: two duplicate app entries
            // conflicting under "Mismatch Products", and both missing an
            // App URL, which is very likely why real ads aren't filling).
            // true here loads Start.io's own test creative regardless of
            // account/fill status, so a test ad appearing confirms the SDK
            // wiring itself is correct and the issue is purely fill/portal
            // config on their side — not this code. Set this back to
            // false before a real release build (Play Store policy
            // requires production apps never show test ads).
            StartAppSDK.setTestAdsEnabled(true)
        } catch (e: Exception) {
            Log.w("StartIoRewardedAdManager", "init threw", e)
        }
    }

    fun isReady(): Boolean = startAppAd != null

    fun load(context: Context, onResult: (Boolean) -> Unit = {}) {
        if (startAppAd != null) {
            onResult(true)
            return
        }
        if (isLoading) {
            onResult(false)
            return
        }
        isLoading = true
        try {
            // BUGFIX (app was crashing straight to the home screen the
            // instant "বিজ্ঞাপন দেখুন" was tapped): a full-screen ad object
            // built with the plain applicationContext instead of the
            // actual Activity can crash when it later tries to show
            // itself (needs a real window/Activity token) — use the
            // Activity context that's actually passed in here (this is
            // called with an Activity, just typed as Context).
            val ad = StartAppAd(context)
            ad.loadAd(
                StartAppAd.AdMode.REWARDED_VIDEO,
                object : AdEventListener {
                    override fun onReceiveAd(receivedAd: Ad) {
                        isLoading = false
                        startAppAd = ad
                        onResult(true)
                    }

                    override fun onFailedToReceiveAd(failedAd: Ad?) {
                        isLoading = false
                        startAppAd = null
                        Log.w("StartIoRewardedAdManager", "Ad failed to load")
                        onResult(false)
                    }
                }
            )
        } catch (e: Exception) {
            // Whatever goes wrong inside the SDK here, fail this ONE ad
            // load gracefully instead of letting it crash the whole app
            // (see the class doc — I can't verify this SDK's exact
            // runtime behavior against live docs from here).
            isLoading = false
            startAppAd = null
            Log.w("StartIoRewardedAdManager", "Ad load threw", e)
            onResult(false)
        }
    }

    /**
     * Same contract as [RewardedAdManager.show]: [onEarnedReward] fires
     * only once the video is confirmed watched through; [onDismissed]
     * always fires when the full-screen ad closes either way.
     */
    fun show(
        activity: Activity,
        onEarnedReward: () -> Unit,
        onDismissed: () -> Unit,
        onFailedToShow: () -> Unit
    ) {
        val ad = startAppAd
        if (ad == null) {
            onFailedToShow()
            return
        }
        try {
            ad.setVideoListener(object : VideoListener {
                override fun onVideoCompleted() {
                    onEarnedReward()
                }
            })
            ad.showAd(object : AdDisplayListener {
                override fun adHidden(hiddenAd: Ad?) {
                    startAppAd = null
                    onDismissed()
                }

                override fun adDisplayed(displayedAd: Ad?) {}

                override fun adClicked(clickedAd: Ad?) {}

                override fun adNotDisplayed(notDisplayedAd: Ad?) {
                    startAppAd = null
                    Log.w("StartIoRewardedAdManager", "Ad failed to show")
                    onFailedToShow()
                }
            })
        } catch (e: Exception) {
            // Same crash-proofing as load() — never let showing an ad take
            // the whole app down with it.
            startAppAd = null
            Log.w("StartIoRewardedAdManager", "Ad show threw", e)
            onFailedToShow()
        }
    }
}
