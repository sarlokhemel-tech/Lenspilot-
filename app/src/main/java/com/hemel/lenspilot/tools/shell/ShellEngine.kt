package com.hemel.lenspilot.tools.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File

/**
 * শেল ইঞ্জিন — UI-নির্ভর নয়। UI, Runner ও AI এজেন্ট সবাই এই run() ডাকে।
 *
 * বন্ধন:
 *  * cwd সবসময় [rootDir]-এর ভেতরে (canonical চেক, symlink দিয়ে বেরোনো যায় না)।
 *  * শুধু `git ...` ও নেভিগেশন বিল্টইন (cd pwd ls cat clear help); বাকি সব "command not found"।
 *  * পাইপ/রিডাইরেক্ট/& — স্পষ্ট "সমর্থিত নয়" বার্তা।
 */
class ShellEngine(
    rootDir: File,
    private val auth: GitAuthProvider,
    private val approval: ApprovalHook,
    private val workspaceLabel: String
) {
    val root: File = (if (!rootDir.exists()) rootDir.also { it.mkdirs() } else rootDir).let {
        ShellPaths.canonical(it) ?: it.absoluteFile
    }

    @Volatile
    private var cancelRequested = false

    /** Stop বাটন: চলমান clone/fetch/push-এর ProgressMonitor.isCancelled = true হয়; চেইনের বাকি অংশ বাদ যায়। */
    fun cancel() {
        cancelRequested = true
    }

    suspend fun run(line: String, cwd: File, sink: ShellSink): ExitResult {
        cancelRequested = false
        val job = currentCoroutineContext()[Job]
        val isCancelled: () -> Boolean = { cancelRequested || job?.isActive == false }

        val parsed = ShellParser.parse(line)
        val segments = when (parsed) {
            is ShellParser.Result.Error -> {
                sink.err("শেল: ${parsed.message}")
                return ExitResult(2, cwd)
            }
            is ShellParser.Result.Unsupported -> {
                sink.err("শেল: ${parsed.message}")
                return ExitResult(2, cwd)
            }
            is ShellParser.Result.Ok -> parsed.segments
        }

        var curCwd = if (ShellPaths.isInside(root, cwd)) (ShellPaths.canonical(cwd) ?: root) else root
        var last = ExitResult(0, curCwd)
        var prevJoin = ShellParser.Join.SEQ
        for (seg in segments) {
            val runIt = if (prevJoin == ShellParser.Join.AND) last.code == 0 else true
            if (runIt) {
                last = runOne(seg.words, curCwd, sink, line, isCancelled)
                curCwd = last.cwd
                if (last.cancelled) break
            }
            prevJoin = seg.join
        }
        return last
    }

    /**
     * প্লেসহোল্ডার থাকলে রিজলভ। ডিস্কে স্থায়ী হয় এমন সাবকমান্ডে (commit, tag, config, remote, clone…)
     * গোপন মান বসানো নিষেধ — শুধু push/pull/fetch। ব্যর্থ হলে null (বার্তা sink-এ)।
     */
    private fun resolveRefs(args: List<String>, sink: ShellSink): List<String>? {
        if (args.none { com.hemel.lenspilot.security.VaultRefs.hasRef(it) }) return args
        val sub = args.firstOrNull().orEmpty()
        if (sub != "push" && sub != "pull" && sub != "fetch") {
            sink.err("শেল: গোপন রেফারেন্স ({{vault:…}}) এই কমান্ডে চলবে না — মান ইতিহাস/কনফিগে থেকে যেত। শুধু push/pull/fetch-এ চলে।")
            return null
        }
        val app = com.hemel.lenspilot.security.SecurityHub.context()
        if (app == null) {
            sink.err("শেল: ভল্ট প্রস্তুত নয়।")
            return null
        }
        val out = ArrayList<String>()
        for (w in args) {
            val r = com.hemel.lenspilot.security.VaultRefs.resolve(app, w)
            if (!r.ok) {
                sink.err("শেল: ভল্টে নেই: ${r.missing}")
                return null
            }
            out.add(r.text ?: w)
        }
        return out
    }

    private suspend fun runOne(
        words: List<String>,
        cwd: File,
        sink: ShellSink,
        rawLine: String,
        isCancelled: () -> Boolean
    ): ExitResult {
        if (isCancelled()) {
            sink.err("^C")
            return ExitResult(130, cwd, true)
        }
        val cmd = words[0]
        val args = words.drop(1)
        if (cmd == "git") {
            val req = GitRunner.approvalFor(args, workspaceLabel, rawLine)
            if (req != null && !approval.approve(req)) {
                sink.err("অনুমোদন মেলেনি — কমান্ড চালানো হয়নি।")
                return ExitResult(1, cwd)
            }
            // সেশন ৩: {{vault:Title.field}} শেষ মুহূর্তে, শুধু এখানে রিজলভ হয়। মূল শব্দ/লাইন (প্লেসহোল্ডারসহ)
            // ইতিহাস ও স্ক্রলব্যাকে আগেই গেছে; রিজলভ-করা মান কোথাও লেখা হয় না।
            val runArgs = resolveRefs(args, sink) ?: return ExitResult(1, cwd)
            val ctx = GitCtx(root, cwd, sink, auth, isCancelled)
            val code = withContext(Dispatchers.IO) { GitRunner.execute(ctx, runArgs) }
            return ExitResult(code, cwd, cancelRequested)
        }
        if (cmd in ShellBuiltins.names) {
            // সেশন ৮: ls/cat/find ইত্যাদির ডিস্ক-কাজ মেইন থ্রেডে (runUser = Main.immediate) নয় — IO-তে
            return withContext(Dispatchers.IO) { ShellBuiltins.run(cmd, args, root, cwd, sink) }
        }
        sink.err("$cmd: command not found")
        return ExitResult(127, cwd)
    }
}
