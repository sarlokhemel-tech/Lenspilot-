package com.hemel.lenspilot.tools.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class ShellLine(val text: String, val kind: Int) {
    companion object {
        const val OUT = 0
        const val ERR = 1
        const val PROMPT = 2
        const val INFO = 3
    }
}

/**
 * এক সেশনের শেল-অবস্থা (স্ক্রলব্যাক, cwd, ইতিহাস, চলার অবস্থা)। Activity ঘুরলেও টিকে থাকে।
 * Listener-এর সব কলব্যাক main থ্রেডে আসে। আউটপুট [ShellBus.outputFilter]-এর ভেতর দিয়ে যায়।
 */
class ShellSession internal constructor(
    val id: String,
    internal val engine: ShellEngine,
    private val appCtx: Context
) {
    interface Listener {
        fun onLines(lines: List<ShellLine>)
        fun onClear()
        fun onProgress(text: String?)
        fun onPrompt(prompt: String)
        fun onTyping(text: String?)
        fun onState(running: Boolean, aiWorking: Boolean)
        fun onLoginRequested()
    }

    class Snapshot(
        val lines: List<ShellLine>,
        val prompt: String,
        val progress: String?,
        val typing: String?,
        val running: Boolean,
        val aiWorking: Boolean
    )

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val aiMutex = Mutex()

    private val scrollback = ArrayDeque<ShellLine>()
    private val pending = ArrayList<ShellLine>()
    private var flushScheduled = false
    private val history = ArrayList<String>()
    private var listener: Listener? = null

    @Volatile
    var cwd: File = engine.root
        private set

    private var running = false
    private var aiWorking = false
    private var progressText: String? = null
    private var typingText: String? = null
    private var progressDirty = false

    val root: File get() = engine.root

    /** Runner-এর জন্য: স্ক্রলব্যাকের শেষ [maxLines] লাইন (আউটপুট আগেই Redactor-এর ভেতর দিয়ে গেছে)। */
    fun tailText(maxLines: Int): String = synchronized(lock) {
        scrollback.toList().takeLast(maxLines).joinToString("\n") { it.text }
    }

    fun prompt(): String = "lenspilot:" + ShellPaths.display(engine.root, cwd) + "$"

    // ------------------------------------------------------------ listener
    fun attach(l: Listener): Snapshot = synchronized(lock) {
        listener = l
        Snapshot(ArrayList(scrollback), prompt(), progressText, typingText, running, aiWorking)
    }

    fun detach(l: Listener) {
        synchronized(lock) { if (listener === l) listener = null }
    }

    fun hasListener(): Boolean = synchronized(lock) { listener != null }

    fun historySnapshot(): List<String> = synchronized(lock) { ArrayList(history) }

    fun isBusy(): Boolean = synchronized(lock) { running || aiWorking }

    // ------------------------------------------------------------ sink
    internal val sink: ShellSink = object : ShellSink {
        override fun out(text: String) = append(ShellLine.OUT, text)
        override fun err(text: String) = append(ShellLine.ERR, text)
        override fun progress(text: String) {
            synchronized(lock) {
                progressText = if (text.isEmpty()) null else ShellBus.outputFilter.filter(text)
                progressDirty = true
            }
            scheduleFlush()
        }

        override fun clear() {
            synchronized(lock) {
                scrollback.clear()
                pending.clear()
            }
            main.post { synchronized(lock) { listener }?.onClear() }
        }
    }

    private fun append(kind: Int, raw: String) {
        val line = ShellLine(ShellBus.outputFilter.filter(raw), kind)
        synchronized(lock) {
            scrollback.addLast(line)
            while (scrollback.size > MAX_LINES) scrollback.removeFirst()
            pending.add(line)
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        main.postDelayed({ flush() }, 40L)
    }

    private fun flush() {
        val batch: List<ShellLine>
        val l: Listener?
        val prog: String?
        val sendProg: Boolean
        synchronized(lock) {
            flushScheduled = false
            batch = ArrayList(pending)
            pending.clear()
            l = listener
            prog = progressText
            sendProg = progressDirty
            progressDirty = false
        }
        if (l == null) return
        if (batch.isNotEmpty()) l.onLines(batch)
        if (sendProg) l.onProgress(prog)
    }

    // ------------------------------------------------------------ state
    private fun notifyState() {
        val l: Listener?
        val r: Boolean
        val a: Boolean
        synchronized(lock) { l = listener; r = running; a = aiWorking }
        main.post { l?.onState(r, a) }
    }

    private fun notifyPrompt() {
        val p = prompt()
        val l = synchronized(lock) { listener }
        main.post { l?.onPrompt(p) }
    }

    private fun setTyping(t: String?) {
        val l: Listener?
        synchronized(lock) { typingText = t; l = listener }
        // এজেন্ট Default থ্রেডে চলে — listener-এর কলব্যাক সবসময় main থ্রেডে (নইলে EditText.setText ক্র্যাশ)
        if (l != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) l.onTyping(t) else main.post { l.onTyping(t) }
        }
    }

    internal fun requestLogin() {
        val l = synchronized(lock) { listener }
        main.post { l?.onLoginRequested() }
        ShellBus.onLoginNeeded?.invoke(id)
    }

    /** Stop বাটন। */
    fun stop() {
        engine.cancel()
    }

    // ------------------------------------------------------------ run
    /** ইউজারের টাইপ করা কমান্ড। ব্যস্ত থাকলে false। */
    fun runUser(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        synchronized(lock) {
            if (running || aiWorking) return false
            running = true
        }
        notifyState()
        scope.launch { execute(cmd) }
        return true
    }

    /** AI/Runner-এর কমান্ড: ইনপুট লক + টাইপিং অ্যানিমেশন (~25ms/অক্ষর) + লাইভ আউটপুট। */
    suspend fun runAi(cmd: String): ExitResult {
        return aiMutex.withLock {
            synchronized(lock) { aiWorking = true }
            notifyState()
            try {
                animateTyping(cmd)
                execute(cmd)
            } finally {
                setTyping(null)
                synchronized(lock) { aiWorking = false }
                notifyState()
            }
        }
    }

    private suspend fun animateTyping(cmd: String) {
        if (!hasListener() || cmd.isEmpty()) return
        // মোট অ্যানিমেশন ~৬ সেকেন্ডের বেশি নয়: লম্বা কমান্ডে প্রতি অক্ষরের বিরতি কমে।
        val per = minOf(25L, maxOf(2L, 6000L / cmd.length))
        for (i in 1..cmd.length) {
            setTyping(cmd.substring(0, i))
            delay(per)
        }
        setTyping(null)
    }

    private suspend fun execute(cmd: String): ExitResult {
        synchronized(lock) {
            if (history.isEmpty() || history.last() != cmd) history.add(cmd)
            while (history.size > 200) history.removeAt(0)
            running = true
        }
        notifyState()
        append(ShellLine.PROMPT, prompt() + " " + cmd)
        var res = ExitResult(1, cwd)
        try {
            res = engine.run(cmd, cwd, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            sink.err("শেল ত্রুটি: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            sink.progress("")
            synchronized(lock) { running = false }
        }
        cwd = res.cwd
        notifyPrompt()
        notifyState()
        return res
    }

    companion object {
        private const val MAX_LINES = 3000
    }
}
