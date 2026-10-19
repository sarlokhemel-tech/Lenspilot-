package com.hemel.lenspilot.guide

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.ScreenElement
import com.hemel.lenspilot.accessibility.ScreenReader
import com.hemel.lenspilot.accessibility.toAnalyzeScreenJson
import com.hemel.lenspilot.intent.IntentLauncher
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.overlay.Highlight
import com.hemel.lenspilot.userinfo.UserInfoEntry
import com.hemel.lenspilot.vision.ScreenshotProcessor
import com.hemel.lenspilot.workflow.ContextWindowStore
import com.hemel.lenspilot.workflow.WorkflowContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Guide v3 — ধাপ নেই, workflow নেই, আগে-বানানো প্ল্যান নেই।
 *
 * ব্যবহারকারীর লক্ষ্য + ছোট ইতিহাস (শেষ ৮টা নোট) + ফোনের এখনকার স্ক্রিন নিয়ে AI প্রতিবার ঠিক করে "এখন কী করতে হবে"
 * (/api/guide-v3, উত্তর JSON {note,status,say,do}); এই ক্লাস সেটাই চালায়: হাইলাইট দেখায়, say কার্ডে লেখে ও পড়ে,
 * intent চালায়, স্ক্রল/ব্যাক বললে ইউজারকে বলে, অপেক্ষা করে, থামে। স্ক্রিন বদলালে host অটো-কল করে; না বদলালে নতুন কল নেই।
 *
 * একই ইঞ্জিন সব মোডে: Accessibility সার্ভিস (tree আগে, ফাঁকা/অর্থহীন হলে ছবি) ও Vision সার্ভিস (সবসময় ছবি)।
 * কোডের নিরাপত্তা-জাল এখানে (ক্লায়েন্ট-পক্ষ): একই স্ক্রিনে একই pick ৩ বার → ছবি নিয়ে আবার; তাতেও না হলে ইউজারকে জিজ্ঞেস;
 * intent না খুললে ত্রুটিসহ AI-কে আবার; সংবেদনশীল ঘরের মান কখনো পাঠানো হয় না (ScreenReader + ছবিতে কালো মাস্ক);
 * হাইলাইট-বক্স সবসময় আসল উপাদানের id থেকে (ছবি-মোডে AI-র box_2d আলাদা ভাঙা-রেখার "অনুমানের বক্স")।
 * সার্ভারের দিকের নিরাপত্তা-জাল (যাচাই + ত্রুটিসহ সংশোধন-অনুরোধ, ছবি চাওয়া) app.py-র guide_v3()-তে।
 */
interface GuideHost {
    val context: Context
    val scope: CoroutineScope

    /** true হলে host স্ক্রিন বদলালে নিজে [GuideLoopV3.onScreenChanged] ডাকে; false হলে intent খোলার পর লুপ নিজেই আবার দেখে। */
    val hasScreenEvents: Boolean

    /** true = Vision মোড: ছবিই মূল উৎস (tree থাকলে হাইব্রিড — id দিয়ে নির্ভুল বক্স)। false = tree আগে, ফাঁকা/অর্থহীন হলে ছবি। */
    val imageFirst: Boolean

    /** Accessibility tree। null = tree নেই (Accessibility বন্ধ) → সবসময় ছবি। */
    fun readTree(): ScreenReader.Snapshot?

    /** স্ক্রিনের ছাপ (বদলেছে কিনা দেখতে); ০ = জানা নেই। */
    fun fingerprintNow(): Int

    /** স্ক্রিনশট (JPG, সাদাকালো/ছোট বা [boost] হলে ভালো মান)। [masks] = কালো করার সংবেদনশীল ঘর। না পেলে cb(null)। */
    fun captureShot(boost: Boolean, masks: List<Rect>, cb: (ScreenshotProcessor.Result?) -> Unit)

    /** tree ছাড়া হোস্টের জন্য: সংবেদনশীল ঘরের জায়গা (থাকলে Accessibility থেকে), না থাকলে খালি। */
    fun sensitiveRects(): List<Rect>

    fun showThinking()
    fun showCard(highlights: List<Highlight>, caption: String)
    fun clearHighlights()

    /** say পড়া (অটো-স্পিক/লাইভ-কলের নীতি host-এর)। */
    fun deliverSay(say: String)

    /** MainActivity-র কার্ড-স্ট্যাটাসের জন্য। */
    fun onUpdate(say: String, done: Boolean)
    fun onError(msg: String)

    /** কাজ শেষে ওভারলে/কন্ট্রোল-বার/বাবল গুটিয়ে নেওয়া। */
    fun finishUi()
}

class GuideLoopV3(private val host: GuideHost) {

    private companion object {
        const val TAG = "GuideLoopV3"
        const val BUSY_TIMEOUT_MS = 40_000L
        const val HISTORY_MAX = 8
        const val MAX_WAIT_STREAK = 4
        const val REPEAT_LIMIT = 3
        const val GIVE_UP_SAY = "এই ধাপটা আমি বুঝতে পারছি না, আপনি একটু এগিয়ে দিন।"
        const val REPEAT_ASK_SAY = "এটা ঠিক জায়গা কিনা বুঝতে পারছি না — আপনি একটু এগিয়ে দিন, তারপর ট্রিগার চাপুন।"
    }

    private class Screen(
        val elements: List<ScreenElement>,
        val meta: JSONObject?,
        val fp: Int,
        val shot: ScreenshotProcessor.Result?,
        val w: Int,
        val h: Int
    )

    private val ctx: Context get() = host.context
    private val main = Handler(Looper.getMainLooper())

    var active = false
        private set
    var lastSay: String = ""
        private set

    private var runId = 0
    private var goal = ""
    private var system = "super_1_2"
    private var sessionId = ""
    private val history = ArrayList<String>()

    private var busy = false
    private var busySince = 0L
    private var pendingChange = false
    private var paused = false          // ask/blocked/give-up/টোকেন-শেষ: ইউজার ট্রিগার না চাপা পর্যন্ত অটো-কল নেই
    private var firstCall = true
    private var lastFp = 0
    private var seq = 0
    private var waitStreak = 0
    private var forceImageNext = false

    private var repeatKey = ""
    private var repeatCount = 0
    private var repeatImageTried = false

    private var intentRetryUsed = false
    private var lastIntentSig = ""
    private var lastIntentAt = 0L
    private var retryError: String? = null
    private var retryPrev: String? = null

    // ------------------------------------------------------------------
    // জীবনচক্র
    // ------------------------------------------------------------------

    fun start(goal: String, system: String, extraUserInfo: List<UserInfoEntry>) {
        runId++
        main.removeCallbacksAndMessages(null)
        this.goal = goal
        this.system = system.ifBlank { "super_1_2" }
        history.clear()
        busy = false; pendingChange = false; paused = false
        firstCall = true; lastFp = 0; seq = 0; waitStreak = 0; forceImageNext = false
        repeatKey = ""; repeatCount = 0; repeatImageTried = false
        intentRetryUsed = false; lastIntentSig = ""; lastIntentAt = 0L
        retryError = null; retryPrev = null
        lastSay = ""
        active = true
        sessionId = UUID.randomUUID().toString().replace("-", "")
        WorkflowContext.start(goal, extraUserInfo)
        ContextWindowStore.startSession(ctx, sessionId, goal)
        trigger(manual = false)
    }

    fun stop() {
        runId++
        main.removeCallbacksAndMessages(null)
        if (active) {
            WorkflowContext.stop()
            ContextWindowStore.clearSession(ctx, sessionId)
        }
        active = false
        busy = false; pendingChange = false; paused = false
    }

    /** স্ক্রিন বদলেছে (host-এর ডিবাউন্স/থিতু-হওয়া অপেক্ষার পর)। */
    fun onScreenChanged() {
        trigger(manual = false)
    }

    /**
     * এখনকার স্ক্রিন দেখে AI-কে "এরপর কী?" জিজ্ঞেস।
     * [manual] = ইউজার নিজে চেপেছে (বাবল/মাইক/মেসেজ) — থামা অবস্থা ভাঙে, ছাপ না বদলালেও কল যায়।
     */
    fun trigger(userSaid: String? = null, extraImage: String? = null, manual: Boolean = true) {
        if (!active) return
        if (manual) paused = false else if (paused) return
        if (busy) {
            if (SystemClock.elapsedRealtime() - busySince > BUSY_TIMEOUT_MS) {
                busy = false
            } else {
                pendingChange = true
                if (manual) toast("দেখছি… একটু অপেক্ষা করুন")
                return
            }
        }
        val said = userSaid?.trim()?.takeIf { it.isNotEmpty() }
        if (said != null) {
            WorkflowContext.noteUserAnswered()
            remember("ইউজার বলেছে: $said")
        }
        busy = true
        busySince = SystemClock.elapsedRealtime()
        val mySeq = ++seq
        val myRun = runId
        host.showThinking()
        val force = forceImageNext
        forceImageNext = false
        gather(
            forceImage = force, manual = manual,
            onSkip = { releaseBusy() },
            onFail = { msg -> fail(msg, mySeq, myRun) },
            onReady = { screen -> send(screen, said, extraImage, false, mySeq, myRun) }
        )
    }

    // ------------------------------------------------------------------
    // স্ক্রিন নেওয়া: Accessibility tree আগে; ফাঁকা বা অর্থহীন হলে (বা ছবি জোর করলে) ছবি
    // ------------------------------------------------------------------

    private fun isMeaningless(els: List<ScreenElement>): Boolean {
        val clickable = els.filter { it.clickable }
        if (clickable.isEmpty()) return true
        val unnamed = clickable.count { it.flags.contains("unnamed") }
        return unnamed.toDouble() / clickable.size > 0.8
    }

    private fun gather(
        forceImage: Boolean, manual: Boolean,
        onSkip: () -> Unit, onFail: (String) -> Unit, onReady: (Screen) -> Unit
    ) {
        val snap = try { host.readTree() } catch (e: Exception) { null }
        val dm = ctx.resources.displayMetrics
        val fp = snap?.fingerprint ?: host.fingerprintNow()
        // স্ক্রিন না বদলালে AI একই গাইডলাইন ধরে রাখে — নতুন কল লাগে না
        if (!manual && !forceImage && fp != 0 && fp == lastFp) { onSkip(); return }
        val treeUsable = snap != null && !isMeaningless(snap.elements)
        if (treeUsable && !forceImage && !host.imageFirst && snap != null) {
            onReady(Screen(snap.elements, snap.meta, fp, null, dm.widthPixels, dm.heightPixels))
            return
        }
        val masks = snap?.sensitive ?: host.sensitiveRects()
        host.captureShot(forceImage, masks) { shot ->
            if (!active) return@captureShot
            when {
                shot != null -> onReady(
                    Screen(if (treeUsable && snap != null) snap.elements else emptyList(), snap?.meta, fp, shot, shot.origW, shot.origH)
                )
                snap != null && snap.elements.isNotEmpty() ->
                    onReady(Screen(snap.elements, snap.meta, fp, null, dm.widthPixels, dm.heightPixels))
                else -> onFail("স্ক্রিন পড়া যাচ্ছে না — একটু অপেক্ষা করে আবার চেষ্টা করুন।")
            }
        }
    }

    // ------------------------------------------------------------------
    // কল
    // ------------------------------------------------------------------

    private fun buildBody(screen: Screen, userSaid: String?, extraImage: String?, imageRetry: Boolean): JSONObject {
        val shot = screen.shot
        return JSONObject().apply {
            put("goal", goal)
            put("system", system)
            put("history", JSONArray(history.toList()))
            put("first_call", firstCall)
            if (userSaid != null) put("user_said", userSaid)
            put("screen_width", screen.w)
            put("screen_height", screen.h)
            if (screen.elements.isNotEmpty()) put("elements", screen.elements.toAnalyzeScreenJson())
            screen.meta?.let { put("screen_meta", it) }
            if (shot != null) put("image_base64", shot.base64)
            put("screen_source", when {
                shot != null && screen.elements.isNotEmpty() -> "hybrid"
                shot != null -> "image"
                else -> "tree"
            })
            if (!extraImage.isNullOrBlank()) put("extra_image_base64", extraImage)
            if (imageRetry) put("image_retry", true)
            retryError?.let { e ->
                put("retry_error", e)
                retryPrev?.let { p -> put("prev_answer", p) }
            }
            Prefs.userInfoJsonArrayOrNull(ctx)?.let { put("user_info", it) }
        }
    }

    private fun send(screen: Screen, userSaid: String?, extraImage: String?, imageRetry: Boolean, mySeq: Int, myRun: Int) {
        if (!active || myRun != runId || mySeq != seq) { return }
        val body = buildBody(screen, userSaid, extraImage, imageRetry)
        retryError = null
        retryPrev = null
        val baseUrl = ctx.getString(R.string.space_base_url)
        host.scope.launch {
            var finished = false
            val result = ApiClient.streamAuthed(ctx, baseUrl, "/api/guide-v3", body.toString()) { evt ->
                when (evt.optString("type")) {
                    "done" -> {
                        finished = true
                        val r = evt.optJSONObject("result") ?: JSONObject()
                        main.post { onResult(r, screen, userSaid, extraImage, imageRetry, mySeq, myRun) }
                    }
                    "error" -> {
                        finished = true
                        val msg = evt.optString("error", "guide error")
                        main.post { fail(msg, mySeq, myRun) }
                    }
                }
            }
            if (!finished) {
                result.onFailure { e -> main.post { fail(e.message ?: "guide request failed", mySeq, myRun) } }
            }
        }
    }

    private fun fail(msg: String, mySeq: Int, myRun: Int) {
        if (!active || myRun != runId || mySeq != seq) return
        busy = false
        pendingChange = false
        host.clearHighlights()
        if (msg.contains("TOKEN_LIMIT")) {
            paused = true
            toast("টোকেন শেষ — অ্যাপ খুলে বিজ্ঞাপন দেখে টোকেন নাও", long = true)
            host.onError("টোকেন শেষ হয়ে গেছে")
        } else {
            host.onError(msg)
        }
    }

    private fun releaseBusy() {
        busy = false
        if (pendingChange && active) {
            pendingChange = false
            val myRun = runId
            main.postDelayed({ if (active && myRun == runId) trigger(manual = false) }, 250)
        }
    }

    // ------------------------------------------------------------------
    // উত্তর চালানো
    // ------------------------------------------------------------------

    private fun onResult(
        r: JSONObject, screen: Screen, userSaid: String?, extraImage: String?,
        imageRetry: Boolean, mySeq: Int, myRun: Int
    ) {
        if (!active || myRun != runId || mySeq != seq) return

        // সার্ভার সংশোধনেও ঠিক করতে পারেনি → ছবি যোগ করে আবার জিজ্ঞেস (একবার)
        if (r.optBoolean("need_image", false) && !imageRetry) {
            busy = true
            busySince = SystemClock.elapsedRealtime()
            gather(
                forceImage = true, manual = true,
                onSkip = { releaseBusy() },
                onFail = { msg -> fail(msg, mySeq, myRun) },
                onReady = { s2 -> send(s2, userSaid, extraImage, true, mySeq, myRun) }
            )
            return
        }
        busy = false
        firstCall = false

        // তাতেও না হলে ইউজারকে বলা; লুপ থেমে থাকে, ইউজার চাইলে (ট্রিগার চাপলে) আবার চালু
        if (r.optBoolean("give_up", false) || r.optBoolean("need_image", false)) {
            paused = true
            lastFp = 0
            present(emptyList(), r.optString("say").ifBlank { GIVE_UP_SAY })
            return
        }

        val status = r.optString("status", "on_track")
        val note = r.optString("note", "")
        val say = r.optString("say", "")
        val doObj = r.optJSONObject("do") ?: JSONObject().put("type", "wait")
        val type = doObj.optString("type", "wait")

        remember(historyLine(screen, note, say))
        lastFp = screen.fp
        if (type != "wait") waitStreak = 0

        if (status == "done" || type == "done") {
            finish(say)
            return
        }
        if (status == "blocked") {
            // ক্যাপচা/OTP/লগইন — ইউজারকে জানানো হয়, ইউজার নিজে সামলে ট্রিগার চাপে
            paused = true
            lastFp = 0
            present(emptyList(), say)
            return
        }

        when (type) {
            "highlight" -> showHighlight(r, doObj, screen, say)
            "intent" -> runIntent(doObj, say, r, myRun)
            "scroll", "back" -> {
                intentRetryUsed = false
                repeatKey = ""
                present(emptyList(), say)
                releasePending()
            }
            "wait" -> {
                waitStreak++
                lastFp = 0   // একই স্ক্রিন হলেও আবার দেখতে হবে
                if (say.isNotBlank()) present(emptyList(), say) else host.showThinking()
                if (waitStreak > MAX_WAIT_STREAK) {
                    paused = true
                    present(emptyList(), say.ifBlank { "স্ক্রিন এখনো তৈরি হচ্ছে — একটু পরে ট্রিগার চাপুন।" })
                } else {
                    scheduleAutoCheck(1600)
                }
            }
            "ask" -> {
                paused = true
                lastFp = 0
                present(emptyList(), say.ifBlank { "আমার একটা তথ্য লাগবে — নিচে লিখে বা বলে জানান।" })
            }
            else -> {
                present(emptyList(), say)
                releasePending()
            }
        }
    }

    private fun showHighlight(r: JSONObject, doObj: JSONObject, screen: Screen, say: String) {
        intentRetryUsed = false
        val arr = r.optJSONArray("highlights")
        val byId = screen.elements.associateBy { it.id }
        val list = ArrayList<Highlight>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val id = h.optString("element_id", "")
                val guess = h.optBoolean("guess", false)
                // বক্স সবসময় আসল উপাদান থেকে (id দিয়ে খুঁজে); না পেলে সার্ভারের দেওয়া (যা-ও ক্লায়েন্টের পাঠানো উপাদান থেকে)
                val rect: Rect = byId[id]?.bbox?.let { Rect(it) } ?: run {
                    val b = h.optJSONArray("bbox")
                    if (b != null && b.length() == 4) {
                        val x = b.optDouble(0, 0.0).toInt()
                        val y = b.optDouble(1, 0.0).toInt()
                        Rect(x, y, x + b.optDouble(2, 0.0).toInt(), y + b.optDouble(3, 0.0).toInt())
                    } else Rect(0, 0, 0, 0)
                }
                val color = try { Color.parseColor(h.optString("color", "#2563EB")) } catch (e: IllegalArgumentException) { Color.parseColor("#2563EB") }
                list.add(Highlight(id, rect, color, h.optString("action_hint", "tap"), h.optString("label", ""), guess))
            }
        }
        present(list, say)

        // একই স্ক্রিনে একই pick ৩ বার → ছবি নিয়ে আবার দেখা; তাতেও না হলে ইউজারকে জিজ্ঞেস
        val pick = doObj.optString("pick", "").ifBlank { doObj.optJSONArray("box_2d")?.toString().orEmpty() }
        val key = "${screen.fp}|$pick"
        if (key == repeatKey) repeatCount++ else { repeatKey = key; repeatCount = 1; repeatImageTried = false }
        if (repeatCount >= REPEAT_LIMIT) {
            if (!repeatImageTried) {
                repeatImageTried = true
                forceImageNext = true
                lastFp = 0
                scheduleAutoCheck(300)
            } else {
                paused = true
                repeatKey = ""; repeatCount = 0; repeatImageTried = false
                lastFp = 0
                present(emptyList(), REPEAT_ASK_SAY)
            }
            return
        }
        releasePending()
    }

    private fun runIntent(doObj: JSONObject, say: String, raw: JSONObject, myRun: Int) {
        val action = doObj.optString("action", "")
        val target = doObj.optJSONObject("target")
        if (action.isBlank() || target == null) {
            paused = true
            present(emptyList(), GIVE_UP_SAY)
            return
        }
        val sig = "$action|$target"
        val now = SystemClock.elapsedRealtime()
        if (sig == lastIntentSig && now - lastIntentAt < 30_000L) {
            // একই intent আবার — আগেরবার চালানো হয়েছিল কিন্তু স্ক্রিন বদলায়নি (ফোন/অ্যান্ড্রয়েড ব্যাকগ্রাউন্ড থেকে খুলতে দেয়নি, বা অ্যাপ অন্য জায়গায় খুলেছে)।
            // আবার না চালিয়ে ত্রুটিসহ AI-কে জানাই: ছবি দেখে স্ক্রিন থেকেই এগোক।
            lastIntentSig = ""
            if (!intentRetryUsed) {
                intentRetryUsed = true
                retryError = "এই intent একবার চালানো হয়েছে কিন্তু স্ক্রিন বদলায়নি, তাই আবার চালানো যাবে না। এখনকার স্ক্রিন দেখে highlight দাও।"
                retryPrev = raw.optJSONObject("do")?.toString()
                lastFp = 0
                forceImageNext = true
                scheduleAutoCheck(100)
            } else {
                paused = true
                present(emptyList(), "এটা নিজে খুলতে পারছি না — আপনি হাতে খুলে নিয়ে ট্রিগার চাপুন।")
            }
            return
        }
        lastIntentSig = sig
        lastIntentAt = now
        present(emptyList(), say)
        when (val res = IntentLauncher.execute(ctx, action, target)) {
            is IntentLauncher.Result.Opened -> {
                intentRetryUsed = false
                repeatKey = ""
                lastFp = 0
                followUpAfterOpen(myRun)
            }
            is IntentLauncher.Result.NotInstalled -> {
                paused = true
                present(emptyList(), "${res.appName.ifBlank { "এই অ্যাপ" }} আপনার ফোনে নেই। Play Store খুলে দিলাম — ইনস্টল করে আবার ট্রিগার চাপুন।")
            }
            is IntentLauncher.Result.Fallback -> {
                present(emptyList(), res.note)
                lastFp = 0
                followUpAfterOpen(myRun)
            }
            is IntentLauncher.Result.Failed -> {
                Log.w(TAG, "intent failed: $action (${res.why})")
                lastIntentSig = ""
                if (!intentRetryUsed) {
                    // এই intent ফোনে খোলা যায়নি — ত্রুটিসহ AI-কে আবার জিজ্ঞেস, স্ক্রিন দেখে highlight দিতে
                    intentRetryUsed = true
                    retryError = "এই intent ফোনে খোলা যাচ্ছে না (কারণ: ${res.why})। স্ক্রিন দেখে highlight দাও।"
                    retryPrev = raw.optJSONObject("do")?.toString()
                    lastFp = 0
                    scheduleAutoCheck(100)
                } else {
                    paused = true
                    present(emptyList(), "এটা নিজে খুলতে পারিনি — আপনি হাতে খুলে নিয়ে ট্রিগার চাপুন।")
                }
            }
        }
    }

    /** intent খোলার পর স্ক্রিন বদলাবে; ইভেন্ট দেওয়া host নিজেই ধরে, বাকিদের জন্য লুপ নিজে আবার দেখে। */
    private fun followUpAfterOpen(myRun: Int) {
        if (host.hasScreenEvents) { releasePending(); return }
        scheduleAutoCheck(1800)
    }

    private fun finish(say: String) {
        val myRun = runId
        val text = say.ifBlank { "কাজ শেষ হয়েছে।" }
        host.showCard(emptyList(), text)
        lastSay = text
        host.deliverSay(text)
        host.onUpdate(text, true)
        // লুপ বন্ধ (ইউজারের পরের Run-এ নতুন করে শুরু); কার্ডটা একটু দেখিয়ে UI গুটানো
        WorkflowContext.stop()
        ContextWindowStore.clearSession(ctx, sessionId)
        active = false
        busy = false
        pendingChange = false
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (runId == myRun) host.finishUi() }, 3500)
    }

    // ------------------------------------------------------------------
    // ছোট সহায়ক
    // ------------------------------------------------------------------

    private fun present(highlights: List<Highlight>, say: String) {
        host.showCard(highlights, say)
        if (say.isNotBlank()) {
            lastSay = say
            host.deliverSay(say)
            host.onUpdate(say, false)
        }
    }

    private fun releasePending() {
        if (pendingChange && active) {
            pendingChange = false
            val myRun = runId
            main.postDelayed({ if (active && myRun == runId) trigger(manual = false) }, 250)
        }
    }

    private fun scheduleAutoCheck(delayMs: Long) {
        val myRun = runId
        main.postDelayed({ if (active && myRun == runId) trigger(manual = false) }, delayMs)
    }

    private fun historyLine(screen: Screen, note: String, say: String): String {
        val pkg = screen.meta?.optString("pkg").orEmpty()
        val title = screen.meta?.optString("title").orEmpty()
        val where = listOf(pkg, title).filter { it.isNotBlank() }.joinToString("/")
        val body = when {
            note.isNotBlank() && say.isNotBlank() -> "$note | $say"
            note.isNotBlank() -> note
            else -> say
        }
        return (if (where.isNotBlank()) "$where: $body" else body).take(200)
    }

    private fun remember(line: String) {
        if (line.isBlank()) return
        history.add(line)
        while (history.size > HISTORY_MAX) history.removeAt(0)
        ContextWindowStore.remember(ctx, sessionId, line.take(160))
    }

    private fun toast(msg: String, long: Boolean = false) {
        main.post { Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}
