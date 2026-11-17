package com.hemel.lenspilot.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions

/**
 * Wraps AdMob's rewarded-ad flow for the token top-up feature (ad unit:
 * see R.string.reward_ad_unit_id, matches
 * ca-app-pub-7007962993307475/8751457637 on the backend's admin panel
 * hint). One ad is kept preloaded at a time so tapping "watch ad" feels
 * instant instead of showing a load spinner every single time.
 *
 * Server-side crediting: the backend's actual primary crediting path is
 * POST /api/ads/claim, called by [AdBreakActivity] right after
 * [onEarnedReward] fires below — that's what makes the reward land even
 * before AdMob's console has SSV configured. [setServerSideVerificationOptions]
 * is ALSO set here so that once SSV *is* configured in the AdMob console
 * (see the admin panel's "Ads & Tokens" tab for the callback URL to paste
 * in), Google's own signed server-to-server callback becomes a second,
 * spoof-proof confirmation — see app.py's /api/ads/ssv-callback.
 */
object RewardedAdManager {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(context.applicationContext) {}
    }

    fun isReady(): Boolean = rewardedAd != null

    fun load(context: Context, adUnitId: String, uid: String?, onResult: (Boolean) -> Unit = {}) {
        if (rewardedAd != null) {
            onResult(true)
            return
        }
        if (isLoading) {
            onResult(false)
            return
        }
        isLoading = true
        RewardedAd.load(
            context.applicationContext, adUnitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    if (uid != null) {
                        ad.setServerSideVerificationOptions(
                            ServerSideVerificationOptions.Builder().setUserId(uid).build()
                        )
                    }
                    rewardedAd = ad
                    onResult(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    Log.w(
                        "RewardedAdManager",
                        "Ad failed to load — domain=${error.domain} code=${error.code} message=${error.message}"
                    )
                    onResult(false)
                }
            }
        )
    }

    /**
     * [onEarnedReward] fires only once AdMob confirms the ad was watched
     * through to completion — that's the cue to call /api/ads/claim and
     * animate the count-up. [onDismissed] always fires when the full-screen
     * ad closes (reward earned or not) — that's the cue to show the
     * "watch more?" break state again.
     */
    fun show(
        activity: Activity,
        onEarnedReward: () -> Unit,
        onDismissed: () -> Unit,
        onFailedToShow: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onFailedToShow()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                Log.w(
                    "RewardedAdManager",
                    "Ad failed to show — domain=${error.domain} code=${error.code} message=${error.message}"
                )
                onFailedToShow()
            }
        }
        ad.show(activity) { onEarnedReward() }
    }
}
