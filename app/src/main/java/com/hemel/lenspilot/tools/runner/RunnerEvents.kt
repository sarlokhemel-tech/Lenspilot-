package com.hemel.lenspilot.tools.runner

import com.hemel.lenspilot.security.Redactor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** ToolsEvents-এ যা যা প্রকাশ হয় তার সাধারণ ধরন। */
sealed interface ToolsEvent

/** Evidence-এর একমাত্র পরিষ্কারক: ভল্টের মান মাস্ক + সর্বোচ্চ ৩০০০ অক্ষর। */
object Evidence {
    const val MAX_CHARS = 3000

    fun clean(raw: String, maxChars: Int = MAX_CHARS): String {
        val s = Redactor.redact(raw).trim()
        return if (s.length <= maxChars) s else s.take(maxChars - 1) + "…"
    }

    /** লগের ক্ষেত্রে শেষ অংশটাই কাজের — শুরু নয়। */
    fun tail(raw: String, maxChars: Int): String {
        val s = Redactor.redact(raw).trim()
        return if (s.length <= maxChars) s else "…" + s.takeLast(maxChars - 1)
    }
}

enum class RunnerKind(val wire: String) {
    DONE("done"), FAILED("failed"), STUCK("stuck"), NEEDS_YOU("needs_you")
}

/**
 * Runner-এর ঘটনা — LLM শুধু এগুলোতেই জাগে (অপেক্ষার সময় শূন্য টোকেন)।
 * evidence সবসময় redacted ও ≤ ৩০০০ অক্ষর (কনস্ট্রাক্টরেই নিশ্চিত হয়)।
 */
class RunnerEvent(
    val kind: RunnerKind,
    val sessionId: String?,
    val stepId: String,
    rawEvidence: String
) : ToolsEvent {
    val evidence: String = Evidence.clean(rawEvidence)
}

/** নির্ধারিত সময় এসে গেছে। [text] = literal অথবা অমীমাংসিত {{vault:…}} রেফারেন্স (রিজলভ-করা মান কখনো নয়)। */
class ScheduleFired(
    val id: String,
    val sessionId: String?,
    val action: String,
    val stepId: String?,
    val targetWorkspace: String?,
    rawText: String?,
    rawNote: String
) : ToolsEvent {
    val text: String? = rawText?.let { Evidence.clean(it, 1000) }
    val note: String = Evidence.clean(rawNote, 500)
}

/** সবার জন্য একটাই প্রবাহ। কালেক্টর না থাকলে ঘটনা হারাতে পারে, তাই বাড়তিভাবে steps.jsonl-এও লেখা হয়। */
object ToolsEvents {
    private val mutable = MutableSharedFlow<ToolsEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flow: SharedFlow<ToolsEvent> = mutable.asSharedFlow()

    fun publish(e: ToolsEvent) {
        mutable.tryEmit(e)
    }
}
