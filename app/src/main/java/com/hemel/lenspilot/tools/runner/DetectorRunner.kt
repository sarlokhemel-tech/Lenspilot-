package com.hemel.lenspilot.tools.runner

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * একটা detector-এর পোলিং-লুপ। অভিযোজিত বিরতি (অবস্থা না বদলালে ধীরে বাড়ে, বদলালে শুরুর বিরতিতে ফেরে),
 * ব্যর্থতায় দ্বিগুণ ব্যাকঅফ, ±২০% জিটার, কঠিন সর্বোচ্চ সময়, কোরুটিন-বাতিলযোগ্য।
 * stuck = [WatcherSpec.stuckAfterS] সেকেন্ড অবস্থা-অপরিবর্তিত অথবা একই ব্যর্থতা [SAME_FAILURE_LIMIT] বার।
 */
object DetectorRunner {
    const val SAME_FAILURE_LIMIT = 6
    private const val MAX_BACKOFF_MS = 300_000L
    private const val MAX_IDLE_INTERVAL_MS = 60_000L

    /** done/failed/needs_you ইভেন্ট ফেরত দেয় (লুপ শেষ); stuck শুধু [onStuck]-এ যায় ও নজরদারি চলতে থাকে। */
    suspend fun watch(ctx: Context, spec: WatcherSpec, d: Detector, onStuck: (RunnerEvent) -> Unit): RunnerEvent {
        val st = ProbeState()
        val start = System.currentTimeMillis()
        val baseInterval = d.intervalS * 1000L
        var interval = baseInterval
        var lastSig: String? = null
        var lastChange = start
        var stuckSent = false
        var sameErr = 0
        var lastErr = ""
        var wasError = false

        fun ev(kind: RunnerKind, text: String) = RunnerEvent(kind, spec.sessionId, spec.stepId, text)

        while (true) {
            val now0 = System.currentTimeMillis()
            if (now0 - start > d.maxDurationS * 1000L) {
                return ev(RunnerKind.FAILED, "সর্বোচ্চ অপেক্ষার সময় (${d.maxDurationS}s) পেরিয়ে গেছে; শেষ অবস্থা: ${lastSig ?: "অজানা"}")
            }
            val r = try {
                Probes.poll(ctx, spec.sessionId, d, st)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                PollResult(Outcome.ERROR, "ex:" + t.javaClass.simpleName, t.message ?: t.javaClass.simpleName)
            }
            when (r.outcome) {
                Outcome.MET -> return ev(RunnerKind.DONE, r.evidence)
                Outcome.FAILED -> return ev(RunnerKind.FAILED, r.evidence)
                Outcome.NEEDS_YOU -> return ev(RunnerKind.NEEDS_YOU, r.evidence)
                else -> { }
            }
            val now = System.currentTimeMillis()
            if (r.outcome == Outcome.ERROR) {
                if (r.signature == lastErr) sameErr++ else { sameErr = 1; lastErr = r.signature }
                wasError = true
                interval = minOf(interval * 2, MAX_BACKOFF_MS)
                if (sameErr >= SAME_FAILURE_LIMIT && !stuckSent) {
                    onStuck(ev(RunnerKind.STUCK, "একই ব্যর্থতা $sameErr বার: ${r.evidence}"))
                    stuckSent = true
                }
            } else {
                sameErr = 0; lastErr = ""
                if (r.signature != lastSig) {
                    lastSig = r.signature
                    lastChange = now
                    stuckSent = false
                    interval = baseInterval
                } else if (wasError) {
                    interval = baseInterval
                } else {
                    interval = minOf((interval * 3) / 2, maxOf(baseInterval, MAX_IDLE_INTERVAL_MS))
                }
                wasError = false
            }
            if (!stuckSent && now - lastChange >= spec.stuckAfterS * 1000L) {
                onStuck(ev(RunnerKind.STUCK, "${spec.stuckAfterS} সেকেন্ড ধরে কোনো পরিবর্তন নেই; অবস্থা: ${lastSig ?: "অজানা"}"))
                stuckSent = true
            }
            val jitter = 0.8 + Math.random() * 0.4
            delay(maxOf(1000L, (interval * jitter).toLong()))
        }
    }
}
