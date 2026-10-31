package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/** git কমান্ডের অপশন-পার্সার: কোটেশন আগেই খোলা শব্দ থেকে ফ্ল্যাগ/মান/পজিশনাল আলাদা করে। */
internal class Opts(args: List<String>, valueOpts: Set<String>, boolOpts: Set<String>) {
    val flags = HashSet<String>()
    private val vals = HashMap<String, MutableList<String>>()
    val pos = ArrayList<String>()
    val afterDD = ArrayList<String>()
    var hadDD = false
    val unknown = ArrayList<String>()
    var missingValue: String? = null

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (hadDD) {
                afterDD.add(a)
                i++
                continue
            }
            if (a == "--") {
                hadDD = true
            } else if (a.startsWith("--")) {
                val eq = a.indexOf('=')
                val name = if (eq > 0) a.substring(0, eq) else a
                if (name in valueOpts) {
                    val v: String
                    if (eq > 0) {
                        v = a.substring(eq + 1)
                    } else if (i + 1 < args.size) {
                        i++
                        v = args[i]
                    } else {
                        missingValue = name
                        v = ""
                    }
                    vals.getOrPut(name) { ArrayList() }.add(v)
                } else if (name in boolOpts && eq < 0) {
                    flags.add(name)
                } else {
                    unknown.add(a)
                }
            } else if (a.length > 1 && a[0] == '-' && a.substring(1).all { it.isDigit() } && "-n" in valueOpts) {
                vals.getOrPut("-n") { ArrayList() }.add(a.substring(1))
            } else if (a.length > 1 && a[0] == '-') {
                var k = 1
                while (k < a.length) {
                    val o = "-" + a[k]
                    if (o in valueOpts) {
                        val rest = a.substring(k + 1)
                        val v: String
                        if (rest.isNotEmpty()) {
                            v = rest
                        } else if (i + 1 < args.size) {
                            i++
                            v = args[i]
                        } else {
                            missingValue = o
                            v = ""
                        }
                        vals.getOrPut(o) { ArrayList() }.add(v)
                        break
                    } else if (o in boolOpts) {
                        flags.add(o)
                    } else {
                        unknown.add(o)
                    }
                    k++
                }
            } else {
                pos.add(a)
            }
            i++
        }
    }

    fun has(vararg names: String): Boolean = names.any { it in flags }

    fun value(vararg names: String): String? {
        for (n in names) {
            val l = vals[n]
            if (l != null && l.isNotEmpty()) return l.last()
        }
        return null
    }

    fun all(vararg names: String): List<String> {
        val out = ArrayList<String>()
        for (n in names) vals[n]?.let { out.addAll(it) }
        return out
    }
}

/** একটা git কমান্ড চালানোর পরিবেশ। ব্লকিং — IO থ্রেডে চলে। */
internal class GitCtx(
    val root: File,
    val cwd: File,
    private val sink: ShellSink,
    val auth: GitAuthProvider,
    private val cancelledFn: () -> Boolean
) {
    fun out(s: String) {
        for (l in s.split('\n')) sink.out(ShellText.scrub(l))
    }

    fun err(s: String) {
        for (l in s.split('\n')) sink.err(ShellText.scrub(l))
    }

    /** ত্রুটি ছাপিয়ে exit-কোড 1। */
    fun fail(s: String): Int {
        err(s)
        return 1
    }

    fun isCancelled(): Boolean = cancelledFn()

    fun monitor(): SinkProgressMonitor = SinkProgressMonitor(sink, cancelledFn)

    /** অপশন ভুল হলে বার্তা ছাপে; true = থামতে হবে। */
    fun badOpts(o: Opts): Boolean {
        if (o.unknown.isNotEmpty()) {
            err("error: অজানা বা অসমর্থিত অপশন: ${o.unknown.joinToString(" ")}")
            return true
        }
        val mv = o.missingValue
        if (mv != null) {
            err("error: অপশন $mv-এর মান দিন")
            return true
        }
        return false
    }

    /** cwd থেকে ওপরে workspace-রুট পর্যন্ত রিপো খোঁজে। নেই হলে বার্তা ছেপে null। */
    fun open(): Repository? {
        val b = FileRepositoryBuilder().findGitDir(cwd)
        val gd = b.gitDir
        if (gd == null || !ShellPaths.isInside(root, gd)) {
            err("fatal: এটা git রিপোজিটরি নয় (workspace-এর ভেতরে .git পাওয়া যায়নি) — আগে 'git init' বা 'git clone'")
            return null
        }
        return b.build()
    }

    /** ইউজারের পাথ → রিপো-পাথ; workspace-এর বাইরে হলে বার্তা ছেপে null। */
    fun repoPath(repo: Repository, arg: String): String? {
        val wt = repo.workTree
        val p = ShellPaths.toRepoPath(root, wt, cwd, arg)
        if (p == null) err("fatal: '$arg' রিপোজিটরি/workspace-এর বাইরে")
        return p
    }

    /** পাথ-আর্গুমেন্ট (glob সহ) → রিপো-পাথের তালিকা। null = ত্রুটি (বার্তা ছাপা হয়েছে)। */
    fun repoPaths(repo: Repository, args: List<String>): List<String>? {
        val out = ArrayList<String>()
        for (a in args) {
            if (ShellPaths.hasGlob(a)) {
                val m = ShellPaths.expandGlob(root, repo.workTree, cwd, a)
                if (m.isEmpty()) {
                    err("fatal: pathspec '$a' কোনো ফাইলের সাথে মেলেনি")
                    return null
                }
                out.addAll(m)
            } else {
                out.add(repoPath(repo, a) ?: return null)
            }
        }
        return out
    }

    fun commitOf(repo: Repository, id: ObjectId): RevCommit =
        RevWalk(repo).use { it.parseCommit(id) }

    fun shortId(id: ObjectId): String = id.name().substring(0, 7)

    /** user.name ও user.email সেট আছে কিনা — না থাকলে বার্তা ছেপে false। */
    fun needIdentity(repo: Repository): Boolean {
        val cfg = repo.config
        val n = cfg.getString("user", null, "name")
        val e = cfg.getString("user", null, "email")
        if (n.isNullOrBlank() || e.isNullOrBlank()) {
            err("*** আপনি কে তা জানা নেই। আগে চালান:")
            err("  git config --global user.name \"আপনার নাম\"")
            err("  git config --global user.email \"you@example.com\"")
            return false
        }
        return true
    }

    /** remote-এর URL (নাম না থাকলে আর্গুমেন্টটাই URL ধরা হয়)। */
    fun remoteUrl(repo: Repository, remote: String): String =
        repo.config.getString("remote", remote, "url") ?: remote

    fun isGithub(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("https://github.com/") || u.startsWith("http://github.com/")
    }

    /** GitHub-remote-এ লগইন লাগে অথচ নেই → ব্যবহারের নির্দেশ ছাপে, ডায়ালগ খোলার অনুরোধ করে; true = থামতে হবে। */
    fun loginRequired(url: String): Boolean {
        if (!isGithub(url) || auth.isLoggedIn()) return false
        err("fatal: GitHub-এ লগইন করা নেই।")
        err("উপরের [GitHub] বাটনে চাপ দিন → কোড দেখাবে → GitHub খুলে কোড বসান → আবার কমান্ডটি চালান।")
        auth.requestLogin()
        return true
    }

    fun git(repo: Repository): Git = Git.wrap(repo)
}
