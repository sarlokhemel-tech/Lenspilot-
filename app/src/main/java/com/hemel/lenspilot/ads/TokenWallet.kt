package com.hemel.lenspilot.ads

import android.content.Context
import com.hemel.lenspilot.net.ApiClient
import org.json.JSONObject

/**
 * Local cache + backend sync for the two token wallets (input/output).
 * Source of truth is always the Space backend (/api/tokens/balance) — this
 * is just a SharedPreferences mirror so the top bar has something to show
 * instantly on launch, before the first network round-trip finishes.
 *
 * Wallets are deducted server-side (see app.py deduct_tokens_async) after
 * every chat/workflow/guidance reply, and credited server-side after a
 * verified rewarded-ad watch (AdMob SSV, or /api/ads/claim as a dev-mode
 * fallback) — so [refresh] should be called after both: right after a chat
 * reply finishes streaming, and right after an ad reward is claimed.
 */
data class TokenBalance(
    val inputTokens: Int,
    val outputTokens: Int,
    val adRewardInputTokens: Int,
    val adRewardOutputTokens: Int,
    val freeInputTokens: Int,
    val freeOutputTokens: Int,
    val lowBalanceThresholdPct: Int
) {
    /** True if EITHER wallet is at/under the admin-configured red-mark
     * percentage of the original free grant — drives the red dot on the
     * top bar's "get tokens" icon. */
    fun isLow(): Boolean {
        val inputFloor = freeInputTokens * lowBalanceThresholdPct / 100
        val outputFloor = freeOutputTokens * lowBalanceThresholdPct / 100
        return inputTokens <= inputFloor || outputTokens <= outputFloor
    }

    fun isDepleted(): Boolean = inputTokens <= 0 || outputTokens <= 0

    companion object {
        fun fromJson(json: JSONObject) = TokenBalance(
            inputTokens = json.optInt("input_tokens", 0),
            outputTokens = json.optInt("output_tokens", 0),
            adRewardInputTokens = json.optInt("ad_reward_input_tokens", 0),
            adRewardOutputTokens = json.optInt("ad_reward_output_tokens", 0),
            freeInputTokens = json.optInt("free_input_tokens", 1),
            freeOutputTokens = json.optInt("free_output_tokens", 1),
            lowBalanceThresholdPct = json.optInt("low_balance_threshold_pct", 20)
        )
    }
}

object TokenWallet {
    private const val FILE = "lenspilot_token_wallet"
    private const val KEY_INPUT = "cached_input_tokens"
    private const val KEY_OUTPUT = "cached_output_tokens"

    /** Cheap, offline-safe read for the very first frame (top bar shows
     * this immediately; [refresh] then corrects it once the network call
     * returns). */
    fun cached(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_INPUT, 0) to p.getInt(KEY_OUTPUT, 0)
    }

    private fun cache(context: Context, balance: TokenBalance) {
        prefs(context).edit()
            .putInt(KEY_INPUT, balance.inputTokens)
            .putInt(KEY_OUTPUT, balance.outputTokens)
            .apply()
    }

    /** Hits GET /api/tokens/balance and updates the local cache. Call this
     * on launch, after every chat reply, and after every ad reward. */
    suspend fun refresh(context: Context, baseUrl: String): Result<TokenBalance> {
        val result = ApiClient.getAuthed(context, baseUrl, "/api/tokens/balance")
        return result.mapCatching { body ->
            val balance = TokenBalance.fromJson(JSONObject(body))
            cache(context, balance)
            balance
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
