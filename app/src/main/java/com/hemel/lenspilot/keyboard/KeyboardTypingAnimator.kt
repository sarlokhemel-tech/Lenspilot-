package com.hemel.lenspilot.keyboard

import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Commits AI-generated text into the currently focused field a few
 * characters at a time, so it visibly "types" like a person rather than
 * pasting instantly — this is what makes the auto-type feature legible to
 * an elderly/first-time user watching their own screen ("look, it's
 * writing it for me") instead of the field just silently filling in.
 *
 * This is plain [InputConnection.commitText] — the same API any keyboard
 * app uses for every key press. No Accessibility API involved.
 *
 * v37: the connection is re-fetched on EVERY tick through [connection]
 * (the keyboard's live `currentInputConnection`). If the user moved to a
 * different field, the app restarted its input, or the keyboard was hidden
 * mid-typing, the connection object changes/goes null — the old code kept
 * writing into the stale one (text vanishing or landing in the wrong place).
 * Now typing stops cleanly and [onFinished] is told it did NOT complete,
 * so the guide is never told "typed X" when X only half landed.
 */
object KeyboardTypingAnimator {

    /** Roughly how many characters land per animation tick. Whole words at
     * once would look janky for Bangla conjuncts, but committing one
     * *code point* at a time is slow for long replies — a small chunk is
     * the middle ground. */
    private const val CHARS_PER_TICK = 2
    private const val TICK_DELAY_MS = 18L

    /** Never split a surrogate pair or a Bangla conjunct cluster
     * (consonant + hasanta + consonant, vowel signs, nukta, chandrabindu) in the middle of a commit. */
    private fun safeEnd(text: String, from: Int, want: Int): Int {
        var end = (from + want).coerceAtMost(text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end++
        while (end < text.length) {
            val c = text[end]
            val glue = c == '\u09CD' || c in '\u09BE'..'\u09CC' || c == '\u09BC' || c == '\u0981' ||
                c == '\u0982' || c == '\u0983' || c == '\u09D7' || text[end - 1] == '\u09CD' ||
                Character.isLowSurrogate(c)
            if (!glue) break
            end++
        }
        return end.coerceAtMost(text.length)
    }

    fun start(
        scope: CoroutineScope,
        connection: () -> InputConnection?,
        text: String,
        onFinished: (completed: Boolean) -> Unit = {}
    ): Job = scope.launch {
        val first = connection()
        if (first == null) { onFinished(false); return@launch }
        var index = 0
        var completed = false
        // Batch edits so autocomplete/spellcheck on the receiving app's
        // side doesn't re-run after every single chunk.
        first.beginBatchEdit()
        try {
            while (isActive && index < text.length) {
                val ic = connection()
                if (ic == null || ic !== first) break      // ঘর/ইনপুট বদলে গেছে — থামো
                val next = safeEnd(text, index, CHARS_PER_TICK)
                ic.commitText(text.substring(index, next), 1)
                index = next
                delay(TICK_DELAY_MS)
            }
            completed = isActive && index >= text.length
        } finally {
            // batch শেষ করা একই কানেকশনে; মরা কানেকশনে ব্যর্থ হলে ক্ষতি নেই
            runCatching { first.endBatchEdit() }
        }
        if (isActive) onFinished(completed)
    }
}
