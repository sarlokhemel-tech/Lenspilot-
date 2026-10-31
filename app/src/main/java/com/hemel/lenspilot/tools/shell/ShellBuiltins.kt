package com.hemel.lenspilot.tools.shell

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** শুধু নেভিগেশনের বিল্টইন: cd, pwd, ls, cat, clear, help। */
internal object ShellBuiltins {

    val names: Set<String> = setOf("cd", "pwd", "ls", "cat", "clear", "help")

    private const val CAT_LIMIT = 256 * 1024

    val HELP_LINES: List<String> = listOf(
        "lenspilot shell — শুধু Git (JGit) + নেভিগেশন। সব কমান্ড workspace-এর ভেতরে চলে।",
        "",
        "নেভিগেশন:  cd [dir]   pwd   ls [-a] [-l] [path]   cat <file>   clear   help",
        "চেইন:      cmd1 ; cmd2     cmd1 && cmd2        (পাইপ | ও রিডাইরেক্ট > সমর্থিত নয়)",
        "",
        "git: init  clone  status  add  rm  mv  commit  push  pull  fetch  merge  rebase",
        "     branch  checkout  switch  restore  reset  revert  cherry-pick  stash  tag",
        "     log  show  diff  remote  config  clean  --version",
        "",
        "শুরুতে: git config --global user.name \"নাম\"  এবং  git config --global user.email \"মেইল\"",
        "GitHub: push/pull-এর আগে উপরের [GitHub] বাটনে লগইন করুন।",
        "Stop বাটন চলমান clone/fetch/push থামায়।"
    )

    fun run(
        name: String,
        args: List<String>,
        root: File,
        cwd: File,
        sink: ShellSink
    ): ExitResult {
        return when (name) {
            "cd" -> cd(args, root, cwd, sink)
            "pwd" -> {
                sink.out(ShellPaths.display(root, cwd))
                ExitResult(0, cwd)
            }
            "ls" -> ls(args, root, cwd, sink)
            "cat" -> cat(args, root, cwd, sink)
            "clear" -> {
                sink.clear()
                ExitResult(0, cwd)
            }
            "help" -> {
                for (l in HELP_LINES) sink.out(l)
                ExitResult(0, cwd)
            }
            else -> {
                sink.err("$name: command not found")
                ExitResult(127, cwd)
            }
        }
    }

    private fun cd(args: List<String>, root: File, cwd: File, sink: ShellSink): ExitResult {
        if (args.size > 1) {
            sink.err("cd: অনেক বেশি আর্গুমেন্ট")
            return ExitResult(1, cwd)
        }
        val target = if (args.isEmpty()) root else ShellPaths.resolve(root, cwd, args[0])
        if (target == null) {
            sink.err("cd: ${args[0]}: workspace-এর বাইরে যাওয়া যায় না")
            return ExitResult(1, cwd)
        }
        if (!target.isDirectory) {
            sink.err("cd: ${args[0]}: এমন ডিরেক্টরি নেই")
            return ExitResult(1, cwd)
        }
        return ExitResult(0, target)
    }

    private fun ls(args: List<String>, root: File, cwd: File, sink: ShellSink): ExitResult {
        var all = false
        var long = false
        val paths = ArrayList<String>()
        for (a in args) {
            if (a.length > 1 && a[0] == '-' && a[1] != '-') {
                for (ch in a.substring(1)) {
                    when (ch) {
                        'a' -> all = true
                        'l' -> long = true
                        '1' -> {}
                        else -> {
                            sink.err("ls: অজানা অপশন -$ch")
                            return ExitResult(2, cwd)
                        }
                    }
                }
            } else {
                paths.add(a)
            }
        }
        if (paths.isEmpty()) paths.add(".")
        var code = 0
        for (p in paths) {
            val target = ShellPaths.resolve(root, cwd, p)
            if (target == null) {
                sink.err("ls: $p: workspace-এর বাইরে")
                code = 1
                continue
            }
            if (!target.exists()) {
                sink.err("ls: $p: এমন ফাইল বা ডিরেক্টরি নেই")
                code = 1
                continue
            }
            if (paths.size > 1) sink.out("$p:")
            if (target.isFile) {
                sink.out(lsLine(target, target.name, long))
            } else {
                val kids = (target.listFiles() ?: emptyArray())
                    .filter { all || !it.name.startsWith(".") }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                for (k in kids) sink.out(lsLine(k, k.name, long))
            }
        }
        return ExitResult(code, cwd)
    }

    private fun lsLine(f: File, name: String, long: Boolean): String {
        val isLink = try {
            Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }
        val shown = if (f.isDirectory && !isLink) "$name/" else name
        if (!long) return shown
        val type = if (isLink) 'l' else if (f.isDirectory) 'd' else '-'
        val size = if (f.isDirectory) "-" else f.length().toString()
        val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
        return "$type ${size.padStart(9)} $date $shown"
    }

    private fun cat(args: List<String>, root: File, cwd: File, sink: ShellSink): ExitResult {
        if (args.isEmpty()) {
            sink.err("cat: ফাইলের নাম দিন")
            return ExitResult(1, cwd)
        }
        var code = 0
        for (a in args) {
            val f = ShellPaths.resolve(root, cwd, a)
            if (f == null) {
                sink.err("cat: $a: workspace-এর বাইরে")
                code = 1
                continue
            }
            if (!f.isFile) {
                sink.err("cat: $a: ফাইল নয় বা নেই")
                code = 1
                continue
            }
            try {
                val bytes = f.inputStream().use { ins ->
                    val buf = ByteArray(CAT_LIMIT)
                    var n = 0
                    while (n < CAT_LIMIT) {
                        val r = ins.read(buf, n, CAT_LIMIT - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                val head = minOf(bytes.size, 8000)
                var binary = false
                for (k in 0 until head) if (bytes[k].toInt() == 0) { binary = true; break }
                if (binary) {
                    sink.err("cat: $a: বাইনারি ফাইল (${f.length()} বাইট) — দেখানো হলো না")
                    code = 1
                    continue
                }
                val text = String(bytes, Charsets.UTF_8)
                for (l in text.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }) {
                    sink.out(l.trimEnd('\r'))
                }
                if (f.length() > CAT_LIMIT) sink.out("… (ফাইল বড়: প্রথম ${CAT_LIMIT / 1024} KB দেখানো হলো)")
            } catch (e: Exception) {
                sink.err("cat: $a: পড়া যায়নি (${e.message})")
                code = 1
            }
        }
        return ExitResult(code, cwd)
    }
}
