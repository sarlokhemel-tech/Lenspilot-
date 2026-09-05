package com.hemel.lenspilot.ads

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import kotlinx.coroutines.launch

/**
 * "টোকেন নিন" screen — reachable from the top bar's coin icon at any time
 * (tokens don't need to be empty to open this), and also the destination
 * the token-limit popup sends the user to once their wallet hits zero.
 *
 * Flow, per spec:
 *   1. Shows the current balance + how many tokens the next ad is worth.
 *   2. Tapping "বিজ্ঞাপন দেখুন" loads (if needed) and shows ONE full-screen
 *      rewarded ad — never a bundle of several back to back.
 *   3. When the ad is watched through, the reward is claimed from the
 *      backend and the two balance numbers animate upward right on this
 *      screen (the "বিরতি পেজ" / break page), together with a running
 *      count of how many ads were watched this session.
 *   4. "➕ আরও দেখুন" lets them repeat step 2 — suggested minimum 4 ads,
 *      but never enforced; "এখন থাক" or the × always closes immediately,
 *      keeping whatever was already earned.
 */
class AdBreakActivity : AppCompatActivity() {

    private lateinit var inputValueText: TextView
    private lateinit var outputValueText: TextView
    private lateinit var rewardPreviewText: TextView
    private lateinit var watchedCountText: TextView
    private lateinit var watchButton: AppCompatButton
    private lateinit var stopButton: TextView
    private lateinit var closeButton: ImageButton

    private var adsWatchedThisSession = 0
    private var currentInput = 0
    private var currentOutput = 0
    private var rewardInput = 0
    private var rewardOutput = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_break)

        inputValueText = findViewById(R.id.adBreakInputValue)
        outputValueText = findViewById(R.id.adBreakOutputValue)
        rewardPreviewText = findViewById(R.id.adBreakRewardPreviewText)
        watchedCountText = findViewById(R.id.adBreakWatchedCountText)
        watchButton = findViewById(R.id.adBreakWatchButton)
        stopButton = findViewById(R.id.adBreakStopButton)
        closeButton = findViewById(R.id.adBreakCloseButton)

        closeButton.setOnClickListener { finish() }
        stopButton.setOnClickListener { finish() }
        watchButton.setOnClickListener { onWatchTapped() }

        RewardedAdManager.init(this)
        updateWatchedCountText()
        refreshBalance(animate = false)
        preloadAd()
    }

    private val baseUrl get() = getString(R.string.space_base_url)
    private val adUnitId get() = getString(R.string.reward_ad_unit_id)

    private fun preloadAd() {
        RewardedAdManager.load(this, adUnitId, uid = null)
    }

    private fun onWatchTapped() {
        if (!RewardedAdManager.isReady()) {
            watchButton.isEnabled = false
            watchButton.text = getString(R.string.ad_loading)
            RewardedAdManager.load(this, adUnitId, uid = null) { ready ->
                runOnUiThread {
                    watchButton.isEnabled = true
                    watchButton.text = getString(R.string.token_limit_watch_ad)
                    if (ready) showAd() else Toast.makeText(this, getString(R.string.ad_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            showAd()
        }
    }

    private fun showAd() {
        RewardedAdManager.show(
            activity = this,
            onEarnedReward = { claimReward() },
            onDismissed = { preloadAd() },
            onFailedToShow = {
                runOnUiThread { Toast.makeText(this, getString(R.string.ad_failed), Toast.LENGTH_SHORT).show() }
            }
        )
    }

    private fun claimReward() {
        lifecycleScope.launch {
            val result = com.hemel.lenspilot.net.ApiClient.callAuthed(
                this@AdBreakActivity, baseUrl, "/api/ads/claim", "{}"
            )
            adsWatchedThisSession += 1
            updateWatchedCountText()
            if (result.isSuccess) {
                refreshBalance(animate = true)
            } else {
                // Reward claim failed (network hiccup, etc.) — still refresh
                // from the server so the screen never shows a stale number,
                // and let the person know nothing was lost, just not yet reflected.
                refreshBalance(animate = false)
                Toast.makeText(
                    this@AdBreakActivity,
                    "টোকেন যোগ করতে সমস্যা হয়েছে, একটু পর ব্যালেন্স চেক করো",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun refreshBalance(animate: Boolean) {
        lifecycleScope.launch {
            val result = TokenWallet.refresh(this@AdBreakActivity, baseUrl)
            result.onSuccess { balance ->
                rewardInput = balance.adRewardInputTokens
                rewardOutput = balance.adRewardOutputTokens
                rewardPreviewText.text = "প্রতি বিজ্ঞাপনে +$rewardInput ইনপুট, +$rewardOutput আউটপুট টোকেন"
                if (animate) {
                    animateValue(inputValueText, currentInput, balance.inputTokens)
                    animateValue(outputValueText, currentOutput, balance.outputTokens)
                } else {
                    inputValueText.text = balance.inputTokens.toString()
                    outputValueText.text = balance.outputTokens.toString()
                }
                currentInput = balance.inputTokens
                currentOutput = balance.outputTokens
            }
        }
    }

    /** Live count-up animation — "লাইভে বাড়বে এরকম এনিমেশন" from spec. */
    private fun animateValue(view: TextView, from: Int, to: Int) {
        if (from == to) {
            view.text = to.toString()
            return
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    private fun updateWatchedCountText() {
        watchedCountText.text = getString(R.string.ad_break_watched_count, adsWatchedThisSession)
        watchButton.text = if (adsWatchedThisSession == 0) {
            getString(R.string.token_limit_watch_ad)
        } else {
            getString(R.string.ad_break_watch_more)
        }
    }
}
