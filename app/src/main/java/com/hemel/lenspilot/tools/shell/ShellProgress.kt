package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.lib.ProgressMonitor
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * clone/fetch/push-এর প্রগ্রেস → sink.progress (লাইভ লাইন)। ধাপ শেষ হলে এক লাইন স্থায়ীভাবে ছাপে।
 * isCancelled = Stop বাটন (বা কোরুটিন বাতিল) — JGit এটা দেখে কাজ থামায়।
 */
internal class SinkProgressMonitor(
    private val sink: ShellSink,
    private val cancelled: () -> Boolean
) : ProgressMonitor {

    private var title = ""
    private var total = 0
    private var done = 0
    private var lastEmit = 0L

    override fun start(totalTasks: Int) {}

    override fun beginTask(title: String?, totalWork: Int) {
        this.title = title ?: ""
        this.total = totalWork
        this.done = 0
        this.lastEmit = 0L
    }

    override fun update(completed: Int) {
        done += completed
        val now = System.currentTimeMillis()
        if (now - lastEmit < 120L) return
        lastEmit = now
        sink.progress(line())
    }

    override fun endTask() {
        if (title.isNotEmpty()) {
            if (total > 0) done = total
            sink.progress("")
            sink.out(line() + ", done.")
        }
        title = ""
    }

    override fun isCancelled(): Boolean = cancelled()

    private fun line(): String =
        if (total > 0) "$title: ${done * 100 / total}% ($done/$total)" else "$title: $done"
}

/** DiffCommand ইত্যাদির বাইট-আউটপুট → UTF-8 লাইনে ভেঙে sink-এ। */
internal class LineOutputStream(private val sink: ShellSink) : OutputStream() {
    private val buf = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (b == 10) emit() else buf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        for (k in off until off + len) write(b[k].toInt() and 0xff)
    }

    override fun flush() {}

    override fun close() {
        finish()
    }

    fun finish() {
        if (buf.size() > 0) emit()
    }

    private fun emit() {
        val s = String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
        buf.reset()
        sink.out(ShellText.scrub(s))
    }
}
