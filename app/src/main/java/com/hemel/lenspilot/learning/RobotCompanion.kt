package com.hemel.lenspilot.learning

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.hemel.lenspilot.R
import kotlin.math.ceil
import kotlin.math.min

/**
 * The Lenspilot robot companion of Learning Mode ("স্ক্রিনকে জীবন্ত করে তোলা").
 *
 * It lives in the right pane of the lesson screen, in the same slot as the picture panel:
 * whenever NO picture is on the stage it is shown — floating, blinking, changing mood and
 * turning its side — with a speech bubble above its head that shows, sentence by sentence and
 * word by word, exactly the words the TTS voice is saying right now. The moment a picture
 * arrives it slides away; when the picture is done it comes back.
 *
 * Art: res/drawable-nodpi/robot_<mood>_<side>.webp — 9 moods (+ "blink") x 3 sides.
 */
class RobotCompanion(
    private val stage: View,
    private val bubbleText: TextView,
    private val bubbleTail: View,
    private val robot: ImageView
) {
    private val dp = stage.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var shown = false
    private var mood = "happy"
    private var side = "front"
    private var moodCounter = 0
    private var sideCounter = 0

    private var shownChunk = -1
    private var chunks: List<String> = emptyList()
    private var chunkEnd: List<Float> = emptyList()   // cumulative end fraction (0..1) per chunk

    /** Called with the exact words currently shown in the bubble ("" = nothing). The lesson screen uses it
     * to show the same words as a subtitle while the robot is hidden behind a picture — so spoken
     * words are never invisible. */
    var onSpeech: ((String) -> Unit)? = null

    private var floatAnim: ObjectAnimator? = null
    private var swayAnim: ObjectAnimator? = null
    private var released = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (released) return
            if (shown && mood != "blink") {
                robot.setImageResource(res("blink", side))
                handler.postDelayed({ if (!released) robot.setImageResource(res(mood, side)) }, 130L)
            }
            handler.postDelayed(this, 2600L + (Math.random() * 2600).toLong())
        }
    }

    init {
        stage.visibility = View.GONE
        applyPose()
        setBubble("")
    }

    // ---------------------------------------------------------------- show / hide
    fun isShown(): Boolean = shown

    fun currentText(): String = bubbleText.text?.toString().orEmpty()

    fun show() {
        // BUGFIX: আগে `shown == true` হলেই ফিরে যেত — hide()-এর অ্যানিমেশন বাতিল/ওভারল্যাপ হলে রোবটের
        // view GONE/অদৃশ্য থেকেও shown=true থেকে যেত, ফলে রোবট আর ফিরত না (কথা চলত, বাবল/ছবি কিছুই না)।
        if (released) return
        if (shown && stage.visibility == View.VISIBLE) return
        shown = true
        stage.animate().cancel()
        stage.alpha = 0f
        stage.translationX = 40f * dp
        stage.visibility = View.VISIBLE
        stage.animate().alpha(1f).translationX(0f).setDuration(260L)
            .setInterpolator(OvershootInterpolator(0.8f)).start()
        startIdle()
    }

    fun hide() {
        if (!shown) return
        shown = false
        stage.animate().cancel()
        stage.animate().alpha(0f).translationX(40f * dp).setDuration(220L).withEndAction {
            if (!shown) {
                stage.visibility = View.GONE
                stage.alpha = 1f
                stage.translationX = 0f
                stopIdle()
            }
        }.start()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        stopIdle()
        stage.animate().cancel()
    }

    // ---------------------------------------------------------------- what it says / how it feels
    /** A new beat starts: pick the mood + side, and prepare the bubble for [narration] (the exact
     * TTS text). [moodHint] is the lesson planner's own "mood" for this beat, if it gave one. */
    fun onSegment(narration: String, moodHint: String?) {
        val text = narration.trim() // খালি হলে (বোর্ডে লেখার ধাপ) বাবল ফাঁকা থাকে
        mood = pickMood(moodHint, text)
        side = pickSide()
        applyPose()
        pop()
        prepareChunks(text)
        setProgress(0)
    }

    /** Waiting for the server (planning / searching a picture / making the voice). */
    fun thinking(text: String) {
        if (mood != "thinking") {
            mood = "thinking"
            applyPose()
        }
        chunks = listOf(text)
        chunkEnd = listOf(1f)
        shownChunk = 0
        setBubble(text)
    }

    /** Idle line (before any lesson). */
    fun greet(text: String) {
        mood = "happy"
        side = "front"
        applyPose()
        chunks = listOf(text)
        chunkEnd = listOf(1f)
        setBubble(text)
    }

    /** [pct] 0..100 = how far the audio of this beat has played. */
    fun setProgress(pct: Int) {
        if (chunks.isEmpty()) { setBubble(""); return }
        val f = pct.coerceIn(0, 100) / 100f
        var idx = chunkEnd.indexOfFirst { f <= it }
        if (idx < 0) idx = chunks.size - 1
        val start = if (idx == 0) 0f else chunkEnd[idx - 1]
        val span = (chunkEnd[idx] - start).coerceAtLeast(0.0001f)
        val within = ((f - start) / span).coerceIn(0f, 1f)
        val words = chunks[idx].split(" ").filter { it.isNotEmpty() }
        val n = if (pct >= 100) words.size else
            min(words.size, ceil(words.size * min(1f, within * 1.3f)).toInt().coerceAtLeast(1))
        if (idx != shownChunk) {
            // নতুন "পাতা": আগের লেখা সরে গিয়ে নতুন লেখা ভেসে ওঠে — বাবলে জায়গা না ধরলেও আটকায় না
            shownChunk = idx
            bubbleText.animate().cancel()
            bubbleText.alpha = 0f
            bubbleText.animate().alpha(1f).setDuration(160L).start()
        }
        setBubble(words.take(n).joinToString(" "))
    }

    // ---------------------------------------------------------------- internals
    private fun setBubble(t: String) {
        if (bubbleText.text.toString() != t) bubbleText.text = t
        val vis = if (t.isBlank()) View.INVISIBLE else View.VISIBLE
        bubbleText.visibility = vis
        bubbleTail.visibility = vis
        onSpeech?.invoke(t)
    }

    private fun prepareChunks(text: String) {
        val out = mutableListOf<String>()
        for (sentence in text.split(Regex("(?<=[।?!.])\\s+"))) {
            var s = sentence.trim()
            if (s.isEmpty()) continue
            while (s.length > 64) {
                var cut = s.lastIndexOf(' ', 56)
                val comma = s.indexOfAny(charArrayOf(',', ';'), 24)
                if (comma in 24..62) cut = comma + 1
                if (cut < 16) cut = 56
                out.add(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
            }
            if (s.isNotEmpty()) out.add(s)
        }
        chunks = out
        shownChunk = -1
        val total = out.sumOf { it.length }.coerceAtLeast(1).toFloat()
        var acc = 0f
        chunkEnd = out.map { acc += it.length / total; acc }
    }

    private fun pickMood(hint: String?, text: String): String {
        val h = hint?.trim()?.lowercase()
        if (h != null && MOODS.contains(h)) return h
        return when {
            text.contains("দুঃখিত") || text.contains("ভুল") -> "sorry"
            text.contains("?") -> "curious"
            text.contains("!") || text.contains("দারুণ") || text.contains("অসাধারণ") -> "excited"
            text.contains("আশ্চর্য") || text.contains("অবাক") -> "surprised"
            else -> CYCLE[moodCounter++ % CYCLE.size]
        }
    }

    private fun pickSide(): String = SIDE_CYCLE[sideCounter++ % SIDE_CYCLE.size]

    private fun res(m: String, s: String): Int = ART["${m}_$s"] ?: R.drawable.robot_happy_front

    private fun applyPose() {
        robot.setImageResource(res(mood, side))
    }

    private fun pop() {
        robot.animate().cancel()
        robot.scaleX = 0.92f
        robot.scaleY = 0.92f
        robot.animate().scaleX(1f).scaleY(1f).setDuration(240L)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun startIdle() {
        stopIdle()
        floatAnim = ObjectAnimator.ofFloat(robot, View.TRANSLATION_Y, -5f * dp, 5f * dp).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        swayAnim = ObjectAnimator.ofFloat(robot, View.ROTATION, -1.8f, 1.8f).apply {
            duration = 2300L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, 2200L)
    }

    private fun stopIdle() {
        floatAnim?.cancel(); floatAnim = null
        swayAnim?.cancel(); swayAnim = null
        handler.removeCallbacks(blinkRunnable)
        robot.translationY = 0f
        robot.rotation = 0f
    }

    companion object {
        private val MOODS = setOf("happy", "excited", "thinking", "curious", "surprised",
            "explaining", "wink", "calm", "sorry")
        private val CYCLE = listOf("explaining", "happy", "calm", "explaining", "wink", "thinking", "explaining", "happy")
        private val SIDE_CYCLE = listOf("front", "right", "front", "left", "right", "front", "left")

        private val ART: Map<String, Int> = mapOf(
            "happy_front" to R.drawable.robot_happy_front,
            "happy_left" to R.drawable.robot_happy_left,
            "happy_right" to R.drawable.robot_happy_right,
            "excited_front" to R.drawable.robot_excited_front,
            "excited_left" to R.drawable.robot_excited_left,
            "excited_right" to R.drawable.robot_excited_right,
            "thinking_front" to R.drawable.robot_thinking_front,
            "thinking_left" to R.drawable.robot_thinking_left,
            "thinking_right" to R.drawable.robot_thinking_right,
            "curious_front" to R.drawable.robot_curious_front,
            "curious_left" to R.drawable.robot_curious_left,
            "curious_right" to R.drawable.robot_curious_right,
            "surprised_front" to R.drawable.robot_surprised_front,
            "surprised_left" to R.drawable.robot_surprised_left,
            "surprised_right" to R.drawable.robot_surprised_right,
            "explaining_front" to R.drawable.robot_explaining_front,
            "explaining_left" to R.drawable.robot_explaining_left,
            "explaining_right" to R.drawable.robot_explaining_right,
            "wink_front" to R.drawable.robot_wink_front,
            "wink_left" to R.drawable.robot_wink_left,
            "wink_right" to R.drawable.robot_wink_right,
            "calm_front" to R.drawable.robot_calm_front,
            "calm_left" to R.drawable.robot_calm_left,
            "calm_right" to R.drawable.robot_calm_right,
            "sorry_front" to R.drawable.robot_sorry_front,
            "sorry_left" to R.drawable.robot_sorry_left,
            "sorry_right" to R.drawable.robot_sorry_right,
            "blink_front" to R.drawable.robot_blink_front,
            "blink_left" to R.drawable.robot_blink_left,
            "blink_right" to R.drawable.robot_blink_right
        )
    }
}
