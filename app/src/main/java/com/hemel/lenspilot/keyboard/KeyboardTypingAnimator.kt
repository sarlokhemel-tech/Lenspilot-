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
 */
object KeyboardTypingAnimator {

    /** Roughly how many characters land per animation tick. Whole words at
     * once would look janky for Bangla conjuncts, but committing one
     * *code point* at a time is slow for long replies — a small chunk is
     * the middle ground. */
    private const val CHARS_PER_TICK = 2
    private const val TICK_DELAY_MS = 18L

    fun start(
        scope: CoroutineScope,
        inputConnection: InputConnection,
        text: String,
        onDone: () -> Unit = {}
    ): Job = scope.launch {
        var index = 0
        // Batch edits so autocomplete/spellcheck on the receiving app's
        // side doesn't re-run after every single chunk.
        inputConnection.beginBatchEdit()
        try {
            while (isActive && index < text.length) {
                val next = (index + CHARS_PER_TICK).coerceAtMost(text.length)
                inputConnection.commitText(text.substring(index, next), 1)
                index = next
                delay(TICK_DELAY_MS)
            }
        } finally {
            inputConnection.endBatchEdit()
        }
        if (isActive) onDone()
    }
}
