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
import com.hemel.lenspilot.overlay.ScreenMetrics
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
    /** [asks] = AI ইউজারের উত্তর/পদক্ষেপের অপেক্ষায় (প্রশ্ন, বাধা, শেখানোর শেষে জিজ্ঞাসা) — চ্যাট-আইকনে না-পড়া ব্যাজ ওঠে। */
    fun deliverSay(say: String, asks: Boolean = false)

    /** MainActivity-র কার্ড-স্ট্যাটাসের জন্য। */
    fun onUpdate(say: String, done: Boolean)
    fun onError(msg: String)

    /** কাজ শেষে ওভারলে/কন্ট্রোল-বার/বাবল গুটিয়ে নেওয়া। */
    fun finishUi()

    /** v142: অ্যাপ নিজে স্ক্রিনের মূল স্ক্রল-এলাকা স্ক্রল করে (dir = down|up|left|right)। করতে পারলে true; না পারলে false → ইউজারকে বলা হয়। */
    fun scrollScreen(dir: String): Boolean = false
}

class GuideLoopV3(private val host: GuideHost) {

    private companion object {
        const val TAG = "GuideLoopV3"
        const val BUSY_TIMEOUT_MS = 40_000L
        const val HISTORY_MAX = 40   // v140: was 8 — a circle longer than 8 steps was invisible to the model; the model now sees the whole trail
        const val MAX_WAIT_STREAK = 4
        const val REPEAT_LIMIT = 3
        const val MAX_NAV_STREAK = 14   // v142: অ্যাপ নিজে স্ক্রল করে বলে লম্বা তালিকার জন্য ৬ কম পড়ত
        const val MAX_STALE_DROPS = 3   // v41/v42: পরপর এতবার "পুরনো স্ক্রিনের উত্তর" বাদ দিয়ে আবার জিজ্ঞেস (টোকেন-খরচ ঠেকাতে); এরপর পুরনো উত্তর দেখানো হয় না — থেমে ইউজারকে জানানো হয়
        const val STALE_ASK_SAY = "The screen is changing too quickly to show the right spot. Hold the screen still and tap the trigger."
        const val NAV_ASK_SAY = "I have looked many times and cannot find it. Please open it yourself or tell me where it is, then tap the trigger."
        const val GIVE_UP_SAY = "I do not understand this step. Please move ahead a little."
        const val REPEAT_ASK_SAY = "I am not sure this is the right place. Please move ahead a little, then tap the trigger."
    }

    private class Screen(
        val elements: List<ScreenElement>,
        val meta: JSONObject?,
        val fp: Int,
        val shot: ScreenshotProcessor.Result?,
        val w: Int,
        val h: Int,
        val noScreen: Boolean = false
    )

    private val ctx: Context get() = host.context
    private val main = Handler(Looper.getMainLooper())

    var active = false
        private set
    var lastSay: String = ""
        private set

    /** v40: গাইড-চ্যাট থেকে গ্যালারিতে ছবি বাছার সময় true — গ্যালারির স্ক্রিন দেখে AI-কে অকারণে ডাকা হয় না (ইউজারের নিজের বার্তা ছাড়া)। */
    @Volatile var suspended = false

    private var runId = 0
    private var goal = ""
    private var system = "super_1_2"
    private var sessionId = ""
    private val history = ArrayList<String>()
    // v143: সত্যিকারের চ্যাট-context — প্রতি ধাপের (স্ক্রিন-বার্তা, মডেলের উত্তর) জোড়া; সার্ভার এগুলো multi-turn চ্যাট হিসেবে দেয়
    private val chatTurns = ArrayList<Pair<String, String>>()

    private var busy = false
    private var busySince = 0L
    private var pendingChange = false
    private var paused = false          // ask/blocked/give-up/টোকেন-শেষ: ইউজার ট্রিগার না চাপা পর্যন্ত অটো-কল নেই
    private var firstCall = true
    private var lastFp = 0
    private var seq = 0
    private var waitStreak = 0
    private var navStreak = 0           // v39: পরপর scroll/back (highlight/intent ছাড়া) — অন্তহীন খোঁজা ঠেকাতে
    private var staleDrops = 0          // v41: পরপর কতবার পুরনো-স্ক্রিনের উত্তর বাদ দেওয়া হয়েছে
    private var forceImageNext = false
    // v40: গাইড-চ্যাট থেকে AI ব্যস্ত থাকার সময় ইউজার কিছু পাঠালে হারায় না — পরের কলে যায়
    private var queuedSaid: String? = null
    private var queuedImage: String? = null
    // v81: বাবল-চ্যাটের "স্ক্রিন দেখা" বাটন বন্ধ রেখে পাঠানো শেষ বার্তার পর AI নিজে থেকে (স্ক্রিন বদলালে) স্ক্রিন দেখে না —
    // ইউজার আবার চালু করে পাঠালে বা ট্রিগার-বাবলে চাপলে তবেই। queuedWithScreen = ব্যস্ত থাকার সময় জমে থাকা বার্তার জন্য।
    private var screenBlocked = false
    private var queuedWithScreen = true

    private var repeatKey = ""
    private var repeatCount = 0
    private var repeatImageTried = false

    private var kbDeferScheduled = false

    // v38: এখন স্ক্রিনে যে হাইলাইট দেখানো হচ্ছে তার আসল উপাদান ও স্ক্রিনের অবস্থা।
    // স্ক্রিনের "ছাপ" ছোটখাটো কারণে (অ্যানিমেশন, ঘড়ি, ওভারলে নিজের ইভেন্ট) বদলালেও যদি হাইলাইট-করা উপাদান
    // একই জায়গায়, একই অবস্থায় থাকে — তাহলে গাইডলাইন বদলায়নি; নতুন কল নয়, হাইলাইট যেমন আছে তেমন।
    private var shownTargets: List<ScreenElement> = emptyList()
    private var shownPkg = ""
    private var shownPopup = false
    // v93: এখন স্ক্রিনে আঁকা হাইলাইট ও তার ক্যাপশন — (ক) ইউজারের চাপ (tap) চেনা, (খ) স্ক্রল/কিবোর্ডে উপাদান সরলে বক্স নতুন জায়গায় নেওয়া
    private var shownHighlights: List<Highlight> = emptyList()
    private var shownCaption = ""
    private val tapCheck = Runnable { if (active) trigger(manual = false) }

    private fun sameBox(a: Rect, b: Rect, tol: Int = 6) =
        kotlin.math.abs(a.left - b.left) <= tol && kotlin.math.abs(a.top - b.top) <= tol &&
            kotlin.math.abs(a.right - b.right) <= tol && kotlin.math.abs(a.bottom - b.bottom) <= tol

    private fun highlightStillValid(snap: ScreenReader.Snapshot?): Boolean {
        if (snap == null || shownTargets.isEmpty()) return false
        val m = snap.meta
        if ((m?.optString("pkg").orEmpty()) != shownPkg) return false
        if ((m?.optBoolean("popup", false) ?: false) != shownPopup) return false
        return shownTargets.all { t ->
            snap.elements.any { e -> e.label == t.label && e.type == t.type && e.flags == t.flags && sameBox(e.bbox, t.bbox) }
        }
    }

    /**
     * v41/v42: এই উত্তর যে স্ক্রিন দেখে তৈরি হয়েছিল সেটা এখনো আছে কিনা। প্যাকেজ/পপআপ বদলেছে → পুরনো (stale)।
     * হাইলাইটের আসল উপাদান থাকলে: সেগুলো এখনকার tree-তে একই জায়গায় না থাকলে পুরনো। [lenient] = explain (অনেকগুলো item):
     * অর্ধেকের বেশি হারালে তবেই পুরনো। লেখার ঘরের label ইউজারের টাইপ করা মানসহ বদলায়, তাই ঘরের ক্ষেত্রে জায়গা ও ধরন মেলানো হয়।
     * v42: উপাদান-ভিত্তিক হাইলাইট নেই (ছবি-মোডের অনুমান-বক্স; scroll/back; "শেষ"; বাধা) — তখন স্ক্রিনের ছাপ (fingerprint) মিলিয়ে দেখা হয়।
     * আগে এসব ক্ষেত্রে কোনো যাচাই ছিল না: ছবি-মোডে ২-৫ সেকেন্ড পরে নতুন স্ক্রিনে পুরনো বক্স বসত, আর স্ক্রিন বদলে গেলেও
     * পুরনো "ব্যাক চাপুন"/"স্ক্রল করুন"/"কাজ শেষ" ইউজারকে বলা হতো।
     */
    private fun isStaleResult(r: JSONObject, screen: Screen, lenient: Boolean): Boolean {
        val snap = try { host.readTree() } catch (e: Exception) { null }
        val before = screen.meta
        if (snap != null && before != null) {
            val now = snap.meta
            if (before.optString("pkg") != now.optString("pkg")) return true
            if (before.optBoolean("popup", false) != now.optBoolean("popup", false)) return true
        }
        val arr = r.optJSONArray("highlights")
        if (snap != null && screen.elements.isNotEmpty() && arr != null) {
            val byId = screen.elements.associateBy { it.id }
            val targets = ArrayList<ScreenElement>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("element_id", "").orEmpty()
                val el = byId[id] ?: continue
                targets.add(el)
            }
            if (targets.isNotEmpty()) {
                val missing = targets.count { t ->
                    snap.elements.none { e ->
                        e.type == t.type && sameBox(e.bbox, t.bbox) && (t.type == "input" || e.label == t.label)
                    }
                }
                return if (lenient) missing * 2 > targets.size else missing > 0
            }
        }
        // উপাদান-ভিত্তিক হাইলাইট নেই → পুরো স্ক্রিনের ছাপ মেলাও (জানা না থাকলে — ০ — পুরনো ধরা হয় না)
        val nowFp = snap?.fingerprint ?: host.fingerprintNow()
        return screen.fp != 0 && nowFp != 0 && nowFp != screen.fp
    }

    /** v37: Lenspilot কিবোর্ড ঘরে লেখা শেষ করলে — ইতিহাসে টুকে (AI যেন আবার "লিখুন" না বলে) পরের ধাপ চাওয়া। */
    private val kbListener = object : WorkflowContext.KeyboardListener {
        override fun onKeyboardTyped(text: String, field: String, submitted: Boolean) {
            val myRun = runId
            main.post {
                if (!active || myRun != runId) return@post
                val where = if (field.isNotBlank()) " (field: ${field.take(40)})" else ""
                val sent = if (submitted) " and pressed search/enter" else ""
                remember("Keyboard typed: \"${text.take(80)}\"$where$sent")
                lastFp = 0
                repeatKey = ""; repeatCount = 0; repeatImageTried = false
                main.postDelayed({
                    if (active && myRun == runId) trigger(manual = false)
                }, if (submitted) 1200L else 500L)
            }
        }
    }

    private var intentRetryUsed = false
    private var lastIntentSig = ""
    private var lastIntentAt = 0L
    private var retryError: String? = null
    private var retryPrev: String? = null

    // ------------------------------------------------------------------
    // জীবনচক্র
    // ------------------------------------------------------------------

    fun start(goal: String, system: String, extraUserInfo: List<UserInfoEntry>, extraImage: String? = null, withScreen: Boolean = true) {
        runId++
        main.removeCallbacksAndMessages(null)
        this.goal = goal
        this.system = system.ifBlank { "super_1_2" }
        history.clear()
        chatTurns.clear()
        busy = false; pendingChange = false; paused = false
        firstCall = true; lastFp = 0; seq = 0; waitStreak = 0; navStreak = 0; staleDrops = 0; forceImageNext = false
        repeatKey = ""; repeatCount = 0; repeatImageTried = false
        intentRetryUsed = false; lastIntentSig = ""; lastIntentAt = 0L
        retryError = null; retryPrev = null
        lastSay = ""
        shownTargets = emptyList()
        shownHighlights = emptyList()
        active = true
        sessionId = UUID.randomUUID().toString().replace("-", "")
        WorkflowContext.start(goal, extraUserInfo)
        WorkflowContext.setGuideSession(sessionId)
        WorkflowContext.keyboardListener = kbListener
        kbDeferScheduled = false
        ContextWindowStore.startSession(ctx, sessionId, goal)
        // v53.2: ছবিসহ প্রশ্নে প্রথম ধাপেই ছবি যায় — আগে ছবি ছাড়া কল হতো, ছবি আলাদা কলে ঢুকত
        screenBlocked = !withScreen
        queuedWithScreen = true
        if (withScreen) trigger(extraImage = extraImage, manual = false)
        else trigger(extraImage = extraImage, manual = true, withScreen = false)   // v81: স্ক্রিন-দেখা বন্ধ — প্রথম কলেও স্ক্রিন নয়
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
        queuedSaid = null; queuedImage = null
        suspended = false
    }

    /** স্ক্রিন বদলেছে (host-এর ডিবাউন্স/থিতু-হওয়া অপেক্ষার পর)। */
    fun onScreenChanged() {
        trigger(manual = false)
    }

    /**
     * এখনকার স্ক্রিন দেখে AI-কে "এরপর কী?" জিজ্ঞেস।
     * [manual] = ইউজার নিজে চেপেছে (বাবল/মাইক/মেসেজ) — থামা অবস্থা ভাঙে, ছাপ না বদলালেও কল যায়।
     */
    fun trigger(userSaid: String? = null, extraImage: String? = null, manual: Boolean = true, withScreen: Boolean = true) {
        if (!active) return
        if (suspended && !manual) return
        if (manual) paused = false else if (paused) return
        if (manual) screenBlocked = !withScreen else if (screenBlocked) return   // v81
        // v37: কিবোর্ড এখন ঘরে লিখছে/ভাবছে — তার মাঝের স্ক্রিন-বদলে AI-কে ডাকা নয়; লেখা শেষ হলে সে নিজে জানাবে
        if (!manual && WorkflowContext.keyboardBusy) {
            if (!kbDeferScheduled) {
                kbDeferScheduled = true
                val deferRun = runId
                main.postDelayed({
                    kbDeferScheduled = false
                    if (active && deferRun == runId) trigger(manual = false)
                }, 700L)
            }
            return
        }
        if (busy) {
            if (SystemClock.elapsedRealtime() - busySince > BUSY_TIMEOUT_MS) {
                busy = false
            } else {
                pendingChange = true
                if (manual && (!userSaid.isNullOrBlank() || extraImage != null)) { queuedSaid = userSaid; queuedImage = extraImage; queuedWithScreen = withScreen }
                if (manual) toast("Looking… please wait")
                return
            }
        }
        val sayIn = userSaid ?: queuedSaid
        val imgIn = extraImage ?: queuedImage
        queuedSaid = null; queuedImage = null
        val said = sayIn?.trim()?.takeIf { it.isNotEmpty() }
        if (said != null) {
            WorkflowContext.setUserAnswer(said)
            WorkflowContext.noteUserAnswered()
            remember("The user said: $said")
        }
        busy = true
        busySince = SystemClock.elapsedRealtime()
        val mySeq = ++seq
        val myRun = runId
        // v38: এখানে আর হাইলাইট মোছা হয় না। আগে এখানেই showThinking() চলত — স্ক্রিন আসলে না বদলালে কল বাতিল হতো (gather-এর skip),
        // কিন্তু হাইলাইট আগেই মুছে গিয়ে আর ফিরত না। এখন মোছা হয় শুধু gather নিশ্চিত হলে যে সত্যিই নতুন কল যাচ্ছে।
        val force = forceImageNext
        forceImageNext = false
        if (!withScreen) {
            // v81: স্ক্রিন-দেখা বন্ধ — নোড/স্ক্রিনশট কিছুই নেওয়া হয় না; AI শুধু ইউজারের কথা (ও সংযুক্তি) দেখে উত্তর দেয়
            val rs = ScreenMetrics.realSize(ctx)
            send(Screen(emptyList(), null, 0, null, rs[0], rs[1], noScreen = true), said, imgIn, false, mySeq, myRun)
            return
        }
        gather(
            forceImage = force, manual = manual,
            onSkip = { releaseBusy() },
            onFail = { msg -> fail(msg, mySeq, myRun) },
            onReady = { screen -> send(screen, said, imgIn, false, mySeq, myRun) }
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
        val rs = ScreenMetrics.realSize(ctx)
        val fp = snap?.fingerprint ?: host.fingerprintNow()
        // স্ক্রিন না বদলালে AI একই গাইডলাইন ধরে রাখে — নতুন কল লাগে না
        if (!manual && !forceImage && fp != 0 && fp == lastFp) { onSkip(); return }
        // ছাপ বদলেছে কিন্তু হাইলাইট-করা উপাদান একই জায়গায় একই অবস্থায় — গাইডলাইন একই, হাইলাইট থাকবে
        if (!manual && !forceImage && fp != 0 && highlightStillValid(snap)) { lastFp = fp; onSkip(); return }
        // হাইলাইটের জীবনকাল = এই এক নিয়ম: নতুন কল সত্যিই যাচ্ছে (এই লাইন পেরোলে) → পুরনো হাইলাইট মুহূর্তেই সরে "দেখছি…" আসে;
        // নতুন উত্তর এলে নতুন হাইলাইট। কল না গেলে (স্ক্রিন একই) হাইলাইট যেমন ছিল তেমনই থাকে — কোনো সেকেন্ড-টাইমার নেই।
        host.showThinking()
        val treeUsable = snap != null && !isMeaningless(snap.elements)
        if (treeUsable && !forceImage && !host.imageFirst && snap != null) {
            onReady(Screen(snap.elements, snap.meta, fp, null, rs[0], rs[1]))
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
                    onReady(Screen(snap.elements, snap.meta, fp, null, rs[0], rs[1]))
                else -> onFail("Cannot read the screen. Wait a moment and try again.")
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
            if (chatTurns.isNotEmpty()) {
                val ta = JSONArray()
                chatTurns.toList().forEach { ta.put(JSONObject().put("u", it.first).put("m", it.second)) }
                put("turns", ta)
            }
            put("first_call", firstCall)
            if (userSaid != null) put("user_said", userSaid)
            put("screen_width", screen.w)
            put("screen_height", screen.h)
            if (screen.elements.isNotEmpty()) put("elements", screen.elements.toAnalyzeScreenJson())
            screen.meta?.let { put("screen_meta", it) }
            if (shot != null) put("image_base64", shot.base64)
            if (screen.noScreen) put("no_screen", true)
            put("screen_source", when {
                screen.noScreen -> "none"
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
        shownTargets = emptyList()
        shownHighlights = emptyList()
        if (msg.contains("TOKEN_LIMIT")) {
            paused = true
            toast("Tokens used up. Open the app and watch an ad to get tokens.", long = true)
            host.onError("Tokens are used up")
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
        if (r.optBoolean("need_image", false) && !imageRetry && !screen.noScreen) {   // v81: স্ক্রিন-দেখা বন্ধ থাকলে ছবির জন্য গোপনে স্ক্রিনশট নেওয়া নয়
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

        // তাতেও না হলে ইউজারকে বলা; লুপ থেমে থাকে, ইউজার চাইলে (ট্রিগার চাপলে) আবার চালু
        if (r.optBoolean("give_up", false) || r.optBoolean("need_image", false)) {
            firstCall = false
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

        // v41: উত্তর আসতে যে ২-৫ সেকেন্ড লাগল তার মধ্যে স্ক্রিন বদলে গেলে (ইউজার নিজে এগিয়েছে, পপআপ এসেছে, অ্যাপ বদলেছে)
        // এই উত্তর পুরনো স্ক্রিনের। সেটা দেখালে নতুন স্ক্রিনের অন্য বাটনে হাইলাইট বসে (উল্টাপাল্টা হাইলাইট), পুরনো say পড়া হয়,
        // আর ইতিহাসে এমন "দেখিয়েছি" ঢোকে যা আসলে কখনো ঠিক জায়গায় দেখানো হয়নি (পরের কলে ভুল ধারণা)।
        // তাই বাদ দিয়ে এখনকার স্ক্রিন দেখে আবার জিজ্ঞেস; ইউজারের বার্তা/ছবি আবার পাঠানোর জন্য রাখা হয়।
        // v42: শুধু highlight/explain নয় — scroll/back/done/blocked-ও স্ক্রিনের উপর নির্ভর করে, তাই এগুলোও যাচাই।
        val needsFreshScreen = type == "highlight" || type == "explain" || type == "scroll" || type == "back" ||
            status == "done" || type == "done" || status == "blocked"
        if (needsFreshScreen && !screen.noScreen && isStaleResult(r, screen, type == "explain")) {
            pendingChange = false
            lastFp = 0
            shownTargets = emptyList()
            shownHighlights = emptyList()
            if (!userSaid.isNullOrBlank() || extraImage != null) { queuedSaid = userSaid; queuedImage = extraImage }
            if (staleDrops >= MAX_STALE_DROPS) {
                // v42: আগে এই সীমার পর পুরনো উত্তরটাই দেখানো হতো (উল্টাপাল্টা হাইলাইটের বাকি কারণ)। ভুল জায়গায় দেখানোর চেয়ে থামা ভালো।
                staleDrops = 0
                host.clearHighlights()
                paused = true
                present(emptyList(), STALE_ASK_SAY)
                return
            }
            staleDrops++
            host.showThinking()
            scheduleAutoCheck(350)   // স্ক্রিন থিতু হওয়ার জন্য একটু সময়; ইউজারের বার্তা/ছবি হারায় না
            return
        }
        staleDrops = 0
        firstCall = false   // v42: পুরনো-বাদ-যাওয়া উত্তরে firstCall বন্ধ হতো — প্রথম কলের "শেখাও/কাজ করো" প্রেক্ষাপট হারাত

        remember(historyLine(screen, note, say, actionTag(doObj, r)))
        WorkflowContext.setGuideStep(say, note)
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

        // v143: এই উত্তর সত্যিই ব্যবহৃত হচ্ছে (পুরনো-স্ক্রিনের বলে বাদ যায়নি) — চ্যাট-ইতিহাসে জমা
        r.optJSONObject("chat_turn")?.let { ct ->
            val u = ct.optString("u"); val m = ct.optString("m")
            if (u.isNotBlank() && m.isNotBlank()) chatTurns.add(u to m)
        }

        when (type) {
            "highlight" -> showHighlight(r, doObj, screen, say)
            "explain" -> showExplain(r, screen, say)
            "intent" -> runIntent(doObj, say, r, myRun)
            "scroll", "back" -> {
                intentRetryUsed = false
                repeatKey = ""
                navStreak++
                if (navStreak > MAX_NAV_STREAK) {
                    navStreak = 0
                    paused = true
                    lastFp = 0
                    present(emptyList(), NAV_ASK_SAY)
                } else {
                    // v142: "স্ক্রল করো" শুধু ইউজারকে বলা হতো, কেউ স্ক্রল করত না — ফলে নিচের জিনিস কখনো দেখাই হতো না।
                    // এখন অ্যাপ নিজেই স্ক্রল করে, একটু থেমে স্ক্রিন আবার পড়ে (AI নতুন স্ক্রিন দেখে সিদ্ধান্ত নেয়)।
                    val autoScrolled = type == "scroll" && (try { host.scrollScreen(doObj.optString("dir", "down")) } catch (e: Exception) { false })
                    present(emptyList(), say)
                    if (autoScrolled) {
                        pendingChange = false
                        lastFp = 0
                        scheduleAutoCheck(1000)
                    } else {
                        releasePending()
                    }
                }
            }
            "wait" -> {
                waitStreak++
                lastFp = 0   // একই স্ক্রিন হলেও আবার দেখতে হবে
                if (say.isNotBlank()) present(emptyList(), say) else host.showThinking()
                if (waitStreak > MAX_WAIT_STREAK) {
                    paused = true
                    present(emptyList(), say.ifBlank { "The screen is still loading. Tap the trigger again shortly." })
                } else {
                    scheduleAutoCheck(1600)
                }
            }
            "ask" -> {
                paused = true
                lastFp = 0
                present(emptyList(), say.ifBlank { "I need one piece of information. Please type or say it below." })
            }
            else -> {
                present(emptyList(), say)
                releasePending()
            }
        }
    }

    /**
     * v40 শেখানোর মোড: ইউজার অ্যাপ/স্ক্রিন কীভাবে ব্যবহার করে বুঝতে না পারলে AI একসঙ্গে অনেকগুলো জিনিস রঙিন চিপসহ
     * হাইলাইট করে বোঝায় (do.type="explain"), say-তে ব্যাখ্যা + "আর কী শিখতে চান?"। এরপর লুপ থামা থাকে —
     * ইউজার গাইড-চ্যাটে পরের প্রশ্ন করলে (ট্রিগার) তবেই আবার চলে; স্ক্রিন বদলালেও নিজে কিছু ঘটায় না।
     */
    private fun showExplain(r: JSONObject, screen: Screen, say: String) {
        intentRetryUsed = false
        navStreak = 0
        repeatKey = ""; repeatCount = 0; repeatImageTried = false
        val list = parseHighlights(r, screen)
        shownTargets = emptyList()
        shownHighlights = emptyList()
        paused = true
        lastFp = 0
        // স্ক্রিনের কার্ডে ছোট একটা লাইন (৭০০ অক্ষরের ব্যাখ্যা কার্ডে বসালে যে জিনিসগুলো বোঝাচ্ছি সেগুলোই ঢেকে যায়);
        // পুরো ব্যাখ্যা গাইড-চ্যাটে (ও ভয়েসে) যায়।
        val full = say.ifBlank { "What else would you like to learn? Type or say it in the chat below." }
        host.showCard(list, shortCaption(full))
        lastSay = full
        host.deliverSay(full, true)
        host.onUpdate(full, false)
        flushQueuedIfAny()
    }

    private fun shortCaption(say: String): String {
        val end = say.indexOfAny(charArrayOf('।', '?', '!', '।'))
        val cut = if (end in 0..110) end + 1 else minOf(say.length, 90)
        val head = say.substring(0, cut).trim()
        return if (cut < say.length) "$head …  (full text in 💬 chat)" else head
    }

    /** AI থেমে (প্রশ্ন/বাধা/শেখানো) থাকার সময় ইউজার যা পাঠিয়ে রেখেছিল তা এখন যায়। */
    private fun flushQueuedIfAny() {
        if (!active || (queuedSaid == null && queuedImage == null)) return
        val myRun = runId
        main.postDelayed({ if (active && myRun == runId && !busy) trigger(manual = true, withScreen = queuedWithScreen) }, 400)
    }

    private fun showHighlight(r: JSONObject, doObj: JSONObject, screen: Screen, say: String) {
        intentRetryUsed = false
        navStreak = 0
        val byId = screen.elements.associateBy { it.id }
        val list = parseHighlights(r, screen)
        // v93: হোম-স্ক্রিন/লঞ্চারে অ্যাপের আইকনে ট্যাপ দেখানোর বদলে সরাসরি অ্যাপ খোলা (intent) — AI আইকন দেখালেও ফোন নিজেই খোলে।
        if (tryOpenAppInsteadOfIcon(r, doObj, screen, list)) return
        present(list, say)
        shownHighlights = list
        shownCaption = say
        shownTargets = list.mapNotNull { h -> if (h.guess || h.elementId.isBlank()) null else byId[h.elementId] }
            .takeIf { it.size == list.size } ?: emptyList()
        shownPkg = screen.meta?.optString("pkg").orEmpty()
        shownPopup = screen.meta?.optBoolean("popup", false) ?: false

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

    // ------------------------------------------------------------------
    // v93: লঞ্চারের আইকন → সরাসরি অ্যাপ খোলা
    // ------------------------------------------------------------------

    /**
     * AI হোম-স্ক্রিনে একটা অ্যাপের আইকন (tap) দেখাতে চাইলে — আইকনের লেখা ইনস্টল-করা কোনো অ্যাপের নামের সাথে হুবহু মিললে —
     * ইউজারকে আইকন খুঁজে চাপতে না বলে ফোন নিজেই অ্যাপ খুলে দেয় (open_app intent)। না মিললে/অনিশ্চিত হলে আগের মতো হাইলাইট।
     */
    private fun tryOpenAppInsteadOfIcon(r: JSONObject, doObj: JSONObject, screen: Screen, list: List<Highlight>): Boolean {
        try {
            if (list.size != 1 || list[0].guess) return false
            if (doObj.optString("hint", "tap") != "tap") return false
            val pkg = screen.meta?.optString("pkg").orEmpty()
            if (pkg.isEmpty() || pkg !in IntentLauncher.homeLauncherPackages(ctx)) return false
            val el = screen.elements.firstOrNull { it.id == list[0].elementId } ?: return false
            if (el.label.isBlank() || el.flags.contains("unnamed") || el.popup) return false
            val app = IntentLauncher.findAppExact(ctx, el.label) ?: return false
            if (app.pkg == pkg || app.pkg == ctx.packageName) return false
            remember("[Opened:open_app] Opened ${app.label} directly (it was shown as an icon on the home screen)")
            val target = JSONObject().put("app_name", app.label).put("package", app.pkg)
            val intentDo = JSONObject().put("type", "intent").put("action", "open_app").put("target", target)
            runIntent(intentDo, "Opening ${app.label} for you…", r, runId)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenAppInsteadOfIcon failed", e)
            return false
        }
    }

    // ------------------------------------------------------------------
    // v93: ইউজার হাইলাইট-করা জিনিসে চাপ দিয়েছে — ধাপটা শেষ; একই বাটন আবার দেখানো নয়
    // ------------------------------------------------------------------

    /** host (Accessibility সার্ভিস) ইউজারের ক্লিক-ইভেন্টের জায়গা (স্ক্রিন-পিক্সেল) দেয়। */
    fun onUserClick(clicked: Rect) {
        if (!active || shownHighlights.isEmpty()) return
        val hit = shownHighlights.firstOrNull { h ->
            val b = h.bbox
            b.width() > 0 && b.height() > 0 &&
                (b.contains(clicked.centerX(), clicked.centerY()) || clicked.contains(b.centerX(), b.centerY()))
        } ?: return
        val what = hit.label.ifBlank { "the highlighted item" }.take(40)
        if (hit.actionHint == "type") {
            // লেখার ঘরে চাপ = শুধু ফোকাস; আসল কাজ লেখা (কিবোর্ড-লিসেনার সামলায়) — হাইলাইট থাকে, শুধু ইতিহাসে টোকা
            remember("The user tapped the text field «$what» (focused it) — now the writing is needed")
            return
        }
        remember("The user tapped «$what» (${hit.actionHint}) — this step is done. Do not point at it again unless the screen shows it did not work; show the next task.")
        shownTargets = emptyList()
        shownHighlights = emptyList()
        lastFp = 0
        repeatKey = ""; repeatCount = 0; repeatImageTried = false
        intentRetryUsed = false
        main.removeCallbacks(tapCheck)
        main.postDelayed(tapCheck, 650L)
    }

    /** নতুন উইন্ডো/অ্যাক্টিভিটি এসেছে — পুরনো হাইলাইট "এখনো বৈধ" ধরা চলবে না (হেডার/নিচের বারের একই বাটন নতুন স্ক্রিনেও থাকে)। */
    fun invalidateShown() {
        if (!active) return
        shownTargets = emptyList()
        shownHighlights = emptyList()
        lastFp = 0
    }

    val hasTrackedHighlight: Boolean get() = active && !busy && shownTargets.isNotEmpty() && shownHighlights.size == shownTargets.size

    /**
     * v93: স্ক্রল/কিবোর্ড/অ্যানিমেশনে হাইলাইট-করা উপাদান সরে গেলে বক্স নতুন জায়গায় নেওয়া (AI-কে না ডেকে — শুধু tree আবার পড়ে)।
     * আগে বক্স পুরনো পিক্সেলেই আঁকা থাকত — "হাইলাইট ঠিক জায়গায় পড়ে না"-র প্রধান কারণ।
     * উপাদান পাওয়া না গেলে বা একই নামের অনেকগুলোর মধ্যে নিশ্চিত হওয়া না গেলে ভুল জায়গায় বক্স রাখার চেয়ে মুছে দিয়ে নতুন কল করানো হয়।
     */
    fun reanchor() {
        if (!hasTrackedHighlight) return
        val snap = try { host.readTree() } catch (e: Exception) { null } ?: return
        if (snap.meta.optString("pkg") != shownPkg) return          // আসল স্ক্রিন-বদল — স্বাভাবিক পথ সামলাবে
        val rs = ScreenMetrics.realSize(ctx)
        val near = rs[1] * 12 / 100
        val newTargets = ArrayList<ScreenElement>(shownTargets.size)
        val newHighlights = ArrayList<Highlight>(shownTargets.size)
        var changed = false
        for (i in shownTargets.indices) {
            val t = shownTargets[i]
            val cands = snap.elements.filter { e -> e.type == t.type && (t.type == "input" || e.label == t.label) }
            val best = cands.minByOrNull { e ->
                val dx = (e.bbox.centerX() - t.bbox.centerX()).toLong()
                val dy = (e.bbox.centerY() - t.bbox.centerY()).toLong()
                dx * dx + dy * dy
            }
            val dist = if (best == null) Int.MAX_VALUE else kotlin.math.abs(best.bbox.centerY() - t.bbox.centerY())
            if (best == null || (cands.size > 1 && dist > near)) {
                // উপাদান নেই বা নিশ্চিত নই — পুরনো বক্স মুছে নতুন করে দেখা
                shownTargets = emptyList(); shownHighlights = emptyList(); lastFp = 0
                host.showCard(emptyList(), shownCaption)
                return
            }
            if (!sameBox(best.bbox, shownHighlights[i].bbox, 3)) changed = true
            newTargets += best
            newHighlights += shownHighlights[i].copy(bbox = Rect(best.bbox))
        }
        if (!changed) return
        shownTargets = newTargets
        shownHighlights = newHighlights
        host.showCard(newHighlights, shownCaption)
    }

    private fun parseHighlights(r: JSONObject, screen: Screen): List<Highlight> {
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
        return list
    }

    private fun runIntent(doObj: JSONObject, say: String, raw: JSONObject, myRun: Int) {
        val action = doObj.optString("action", "")
        val target = doObj.optJSONObject("target")
        if (action.isBlank() || target == null) {
            paused = true
            present(emptyList(), GIVE_UP_SAY)
            return
        }
        navStreak = 0
        val sig = "$action|$target"
        val now = SystemClock.elapsedRealtime()
        if (sig == lastIntentSig && now - lastIntentAt < 30_000L) {
            // একই intent আবার — আগেরবার চালানো হয়েছিল কিন্তু স্ক্রিন বদলায়নি (ফোন/অ্যান্ড্রয়েড ব্যাকগ্রাউন্ড থেকে খুলতে দেয়নি, বা অ্যাপ অন্য জায়গায় খুলেছে)।
            // আবার না চালিয়ে ত্রুটিসহ AI-কে জানাই: ছবি দেখে স্ক্রিন থেকেই এগোক।
            lastIntentSig = ""
            if (!intentRetryUsed) {
                intentRetryUsed = true
                retryError = "This intent was already run once but the screen did not change, so it cannot be run again. Look at the current screen and give a highlight."
                retryPrev = raw.optJSONObject("do")?.toString()
                lastFp = 0
                forceImageNext = true
                scheduleAutoCheck(100)
            } else {
                paused = true
                present(emptyList(), "I cannot open this myself. Please open it by hand, then tap the trigger.")
            }
            return
        }
        // v45: লক্ষ্য-অ্যাপ এখনই সামনে খোলা থাকলে আবার "খুলতে" যাই না (ইউজার বলছিল "আমি তো মেসেঞ্জারেই আছি")
        if (action == "open_app") {
            val curPkg = try { host.readTree()?.meta?.optString("pkg").orEmpty() } catch (e: Exception) { "" }
            val want = try { IntentLauncher.findApp(ctx, target)?.pkg.orEmpty() } catch (e: Exception) { "" }
            if (curPkg.isNotEmpty() && curPkg == want && !intentRetryUsed) {
                intentRetryUsed = true
                retryError = "That app is already open on the screen right now (the screen's app= is the one you wanted to open). Do not open it again. Look at the screen and give the next task."
                retryPrev = raw.optJSONObject("do")?.toString()
                lastFp = 0
                scheduleAutoCheck(100)
                return
            }
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
                // v45: Play Store নিজে খোলা বন্ধ — অ্যাপটা ইউজারের সামনেই খোলা থাকতে পারে, বা ফোনে অন্য নামে থাকতে পারে।
                // AI-কে ত্রুটিসহ আবার জিজ্ঞেস: স্ক্রিন দেখে সিদ্ধান্ত নিক। দ্বিতীয়বারও না পেলে ইউজারকে বলা (স্টোর না খুলে)।
                lastIntentSig = ""
                if (!intentRetryUsed) {
                    intentRetryUsed = true
                    val sim = if (res.similar.isNotEmpty()) " All apps installed on this phone: ${res.similar.joinToString(", ")}. If the app is in this list under another name or spelling, give open_app again with that exact name." else ""
                    retryError = "No installed app named \"${res.appName}\" was found by that name.$sim " +
                        "The app may already be open on the screen, or be installed under another name. Look at the current screen first: " +
                        "if the user is already in that app, do NOT open it again — go on with the next task. " +
                        "Never send the user to the Play Store or tell them to download anything unless they asked to install it."
                    retryPrev = raw.optJSONObject("do")?.toString()
                    lastFp = 0
                    forceImageNext = true
                    scheduleAutoCheck(100)
                } else {
                    paused = true
                    present(emptyList(), "I could not find ${res.appName.ifBlank { "this app" }} on your phone by that name. If it is installed, please open it yourself, then tap the trigger. If you want to install it, tell me and I will open the Play Store.")
                }
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
                    retryError = "This intent cannot be opened on this phone (reason: ${res.why}). Look at the screen and give a highlight."
                    retryPrev = raw.optJSONObject("do")?.toString()
                    lastFp = 0
                    scheduleAutoCheck(100)
                } else {
                    paused = true
                    present(emptyList(), "I could not open this myself. Please open it by hand, then tap the trigger.")
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
        val text = say.ifBlank { "The task is complete." }
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
        if (highlights.isEmpty()) { shownTargets = emptyList(); shownHighlights = emptyList() }
        host.showCard(highlights, say)
        if (say.isNotBlank()) {
            lastSay = say
            host.deliverSay(say, paused)
            host.onUpdate(say, false)
            if (paused) flushQueuedIfAny()
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

    /** v39: AI আসলে কী করতে বলেছিল — ইতিহাসে সত্যিকারের রেকর্ড (নোট শুধু AI-র নিজের কথা), যাতে পরের কলে ভুল ধারণা/পুনরাবৃত্তি কমে। */
    private fun actionTag(doObj: JSONObject, r: JSONObject): String {
        return when (doObj.optString("type", "wait")) {
            "highlight" -> {
                val hint = doObj.optString("hint", "tap")
                val label = r.optJSONArray("highlights")?.optJSONObject(0)?.optString("label", "").orEmpty().take(30)
                "Showed:" + hint + (if (label.isNotBlank()) " «" + label + "»" else "")
            }
            "explain" -> {
                val n = r.optJSONArray("highlights")?.length() ?: 0
                "Explained:" + n + " items"
            }
            "intent" -> "Opened:" + doObj.optString("action", "")
            "scroll" -> "Scrolled-by-app:" + doObj.optString("dir", "down") + " (if the next screen shows the same content, the end of the list was reached)"
            "back" -> "Said-back"
            "wait" -> "Wait"
            "ask" -> "Question"
            else -> ""
        }
    }

    private fun historyLine(screen: Screen, note: String, say: String, action: String = ""): String {
        val pkg = screen.meta?.optString("app")?.takeIf { it.isNotBlank() } ?: screen.meta?.optString("pkg").orEmpty()
        val title = screen.meta?.optString("title").orEmpty()
        val where = listOf(pkg, title).filter { it.isNotBlank() }.joinToString("/")
        // v42: আগে "$note | $say" জোড়া দিয়ে ২০০ অক্ষরে কাটা হতো — pkg/title + ট্যাগেই অর্ধেক শেষ, note-এর শেষ অংশ
        // (AI-র "এরপর কী খুঁজছি") কেটে যেত, আর say (ইউজারকে বলা কথা, note-এর পুনরাবৃত্তি) জায়গা খেত।
        // এখন AI-র নিজের note-ই পুরো যায় (সার্ভার note-ই ছোট রাখে); say শুধু note না থাকলে।
        val core = if (note.isNotBlank()) note else say
        val body = if (action.isNotBlank()) "[" + action + "] " + core else core
        return if (where.isNotBlank()) "$where: $body" else body
    }

    private fun remember(line: String) {
        if (line.isBlank()) return
        history.add(line)
        while (history.size > HISTORY_MAX) history.removeAt(0)
        ContextWindowStore.remember(ctx, sessionId, line.take(600))
    }

    private fun toast(msg: String, long: Boolean = false) {
        main.post { Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}
