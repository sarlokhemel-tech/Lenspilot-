package com.hemel.lenspilot.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Guide Engine v2 — ক্লায়েন্ট-সাইড অবস্থা (সার্ভার stateless)।
 *
 * প্ল্যান (Plan JSON), ধাপ-সূচক, আগের ধাপের `expect`, "এ পর্যন্ত কী হয়েছে" নোট, miss/repair
 * গণনা আর হপ-মেমো রেফারেন্স এখানে থাকে। চারটা মোড (super_lite / super_1_2 / ela_1st / ela_4n) আর
 * দুটো লুপ (LenspilotAccessibilityService ও FallbackGuideService) একই ক্লাস ব্যবহার করে।
 *
 * মূল নিয়ম: সূচক এগোয় **শুধু সার্ভার `arrived != false` বলে "found" দিলে** — স্ক্রিন বদলালেই
 * "ইউজার ঠিক জিনিসটাই চেপেছে" ধরে নেওয়া হয় না। ইউজার অন্য কিছু চাপলে, পপআপ বা কীবোর্ড এলে সার্ভার
 * `arrived:false`/`wrong_screen` বলে আর সূচক সরে না।
 *
 * `nextIndex` = k = পরের কলে যে ধাপের জিনিস খুঁজতে বলা হবে (১-ভিত্তিক)।
 */
class GuideSession(planJson: String, val system: String) {

    enum class Outcome {
        /** ধাপের জিনিস হাইলাইট হয়েছে (বা আবার দেখানো হয়েছে) */
        SHOWN,
        /** এই স্ক্রিনে নেই — স্ক্রল/ব্যাক/মেনুর পরামর্শ দেখাও, ইউজার করলে আবার একই ধাপ */
        NOT_HERE,
        /** সার্ভার স্ক্রিনশট চেয়েছে — ছবি তুলে একই ধাপ আবার পাঠাও */
        NEED_IMAGE,
        /** ভুল স্ক্রিন, প্যাচ বসেছে, প্রথম প্যাচ-ধাপ স্ক্রিনে খোঁজার — সাথে সাথে আবার কল দাও */
        REPAIRED_RETRY,
        /** ভুল স্ক্রিন, প্যাচ বসেছে, প্রথম প্যাচ-ধাপ back/স্ক্রল (ইউজার করবে) — দেখাও ও স্ক্রিন বদলের অপেক্ষা */
        REPAIRED_WAIT,
        /** কাজ শেষ (success_signal প্রমাণিত) */
        DONE,
        /** সীমা-ব্রেক: ইউজারকে জিজ্ঞেস করো */
        STUCK,
        /** নিশ্চিত না / প্যাচ ছাড়া wrong_screen */
        UNSURE
    }

    data class Snapshot(
        val planJson: String,
        val nextIndex: Int,
        val prevExpect: String?,
        val progress: List<String>,
        val missCount: Int,
        val repairCount: Int,
        val memoRef: String?
    )

    private var plan: JSONObject = JSONObject(planJson)
    var nextIndex: Int = 1
        private set
    private var prevExpect: String? = null
    private val progress = ArrayList<String>()
    var missCount: Int = 0
        private set
    var repairCount: Int = 0
        private set
    private var memoRef: String? = null

    init {
        require((plan.optJSONArray("steps")?.length() ?: 0) > 0) { "plan has no steps" }
    }

    val stepCount: Int get() = plan.optJSONArray("steps")?.length() ?: 0

    private fun step(i: Int): JSONObject? = plan.optJSONArray("steps")?.optJSONObject(i - 1)

    /** ধাপ i-এর আগে-লেখা guide বাক্য (ইউজারকে দেখানোর জন্য) */
    fun guideTextOf(i: Int): String? = step(i)?.optString("guide")?.takeIf { it.isNotBlank() }

    fun currentGuideText(): String? = guideTextOf(nextIndex)

    /** ইউজার মাঝপথে কিছু বললে — পরের hop-এর "এ পর্যন্ত কী হয়েছে"-তে ঢোকে */
    fun noteUser(text: String) {
        if (text.isBlank()) return
        progress.add("ইউজার বলেছে: ${text.take(120)}")
        trimProgress()
    }

    private fun trimProgress() {
        while (progress.size > 4) progress.removeAt(0)
    }

    /** /api/analyze-screen বডিতে v2 ফিল্ড বসায়। পুরনো ফিল্ড অপরিবর্তিত থাকে। */
    fun fillBody(body: JSONObject) {
        body.put("plan", plan)
        body.put("step_index", nextIndex)
        prevExpect?.let { body.put("prev_expect", it) }
        if (progress.isNotEmpty()) body.put("progress_note", progress.joinToString("; ").takeLast(380))
        if (missCount > 0) body.put("miss_count", missCount)
        if (repairCount > 0) body.put("repair_count", repairCount)
        memoRef?.let { body.put("prev_memo_ref", it) }
        memoRef = null // একবারই পাঠানো হয়; সার্ভার যাচাই/বাতিল করে ফেলে
    }

    fun snapshot() = Snapshot(plan.toString(), nextIndex, prevExpect, progress.toList(), missCount, repairCount, memoRef)

    fun restore(s: Snapshot) {
        plan = JSONObject(s.planJson)
        nextIndex = s.nextIndex
        prevExpect = s.prevExpect
        progress.clear(); progress.addAll(s.progress)
        missCount = s.missCount
        repairCount = s.repairCount
        memoRef = s.memoRef
    }

    /** সার্ভারের result.guide প্রয়োগ করে অবস্থা হালনাগাদ করে; ক্লায়েন্ট কী করবে তা ফেরত দেয়। */
    fun onResult(guide: JSONObject): Outcome {
        val status = guide.optString("status", "unsure")
        val arrived: Boolean? = guide.opt("arrived") as? Boolean
        val m = guide.optString("memo_ref", "")
        memoRef = m.ifBlank { null }

        return when (status) {
            "found" -> {
                missCount = 0
                val pickStep = guide.optInt("pick_step", 0)
                // arrived != false ও pick_step == nextIndex  →  ধাপ k দেখানো হয়েছে, k+1-এ এগোও
                if (arrived != false && pickStep == nextIndex) {
                    if (nextIndex > 1) {
                        val prevGuide = guideTextOf(nextIndex - 1) ?: ""
                        progress.add("ধাপ ${nextIndex - 1} শেষ" + if (prevGuide.isNotBlank()) ": $prevGuide" else "")
                        trimProgress()
                    }
                    prevExpect = step(nextIndex)?.optString("expect")?.takeIf { it.isNotBlank() }
                        ?: "ধাপ $nextIndex করা হয়েছে"
                    nextIndex++
                }
                Outcome.SHOWN
            }
            "not_here" -> {
                missCount++
                if (guide.optBoolean("need_image", false)) Outcome.NEED_IMAGE else Outcome.NOT_HERE
            }
            "wrong_screen" -> {
                repairCount++
                val patch = guide.optJSONArray("plan_patch")
                if (patch != null && patch.length() > 0) applyPatch(patch) else Outcome.UNSURE
            }
            "done" -> Outcome.DONE
            "stuck" -> {
                missCount = 0
                repairCount = 0
                Outcome.STUCK
            }
            else -> {
                // "unsure"; গেট (ELA 4N অপরিবর্তনীয় ধাপ / শেষ-যাচাই) need_image দিলে ছবি তুলে আবার
                if (guide.optBoolean("need_image", false)) Outcome.NEED_IMAGE else Outcome.UNSURE
            }
        }
    }

    /** বাকি-পথের প্যাচ: এখনকার ধাপ k থেকে বাকি ধাপ বদলে যায়; আগের ধাপগুলো থাকে। */
    private fun applyPatch(patch: JSONArray): Outcome {
        val old = plan.optJSONArray("steps") ?: JSONArray()
        val merged = JSONArray()
        for (i in 0 until minOf(nextIndex - 1, old.length())) merged.put(old.getJSONObject(i))
        for (i in 0 until patch.length()) {
            val s = patch.optJSONObject(i) ?: continue
            val c = JSONObject(s.toString())
            c.put("i", merged.length() + 1)
            merged.put(c)
        }
        plan.put("steps", merged)
        plan.put("patched", true)
        prevExpect = null // প্যাচের প্রথম কল "first-like": আগের ধাপ যাচাই লাগবে না
        val first = merged.optJSONObject(nextIndex - 1) ?: return Outcome.UNSURE
        val act = first.optString("act", "tap")
        return if (act == "back" || act == "scroll") {
            // এটা ইউজার নিজে করবে (স্ক্রিনে খোঁজার কিছু নেই) — "দেখানো হয়েছে" ধরে এগোও,
            // পরের কল ধাপের expect দিয়ে অগ্রগতি যাচাই করবে।
            prevExpect = first.optString("expect").takeIf { it.isNotBlank() } ?: "ধাপ $nextIndex করা হয়েছে"
            nextIndex++
            Outcome.REPAIRED_WAIT
        } else {
            Outcome.REPAIRED_RETRY
        }
    }

    /** সার্ভারে পাঠানোর সংস্করণসহ বর্তমান প্ল্যান (সংরক্ষণ/ডিবাগের জন্য) */
    fun planJson(): String = plan.toString()
}
