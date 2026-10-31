package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** log, show, diff, tag, stash, reset, revert, cherry-pick, clean। */
internal object GitHistory {

    private fun dateOf(rc: RevCommit): String {
        val id = rc.authorIdent
        val f = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US)
        f.timeZone = id.getTimeZone() ?: TimeZone.getDefault()
        return f.format(id.getWhen())
    }

    private fun printCommitFull(c: GitCtx, rc: RevCommit) {
        c.out("commit ${rc.name()}")
        val a = rc.authorIdent
        c.out("Author: ${a.name} <${a.emailAddress}>")
        c.out("Date:   ${dateOf(rc)}")
        c.out("")
        for (l in rc.fullMessage.trimEnd().lines()) c.out("    $l")
    }

    // ------------------------------------------------------------------ log
    fun log(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, setOf("-n", "--max-count"), setOf("--oneline", "--all"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val nGiven = o.value("-n", "--max-count")?.toIntOrNull()
            if (o.value("-n", "--max-count") != null && (nGiven == null || nGiven < 1)) return c.fail("fatal: -n ধনাত্মক সংখ্যা চায়")
            val cap = nGiven ?: 100
            val cmd = g.log().setMaxCount(cap + 1)
            val paths = ArrayList<String>(o.afterDD)
            for (p in o.pos) {
                if (p.contains("..")) {
                    val parts = p.split("..", limit = 2)
                    val since = repo.resolve(parts[0]) ?: return c.fail("fatal: অজানা revision '${parts[0]}'")
                    val until = repo.resolve(parts[1].ifEmpty { "HEAD" }) ?: return c.fail("fatal: অজানা revision '${parts[1]}'")
                    cmd.addRange(since, until)
                } else {
                    val id = repo.resolve(p)
                    if (id != null && !o.hadDD) cmd.add(id) else paths.add(p)
                }
            }
            if (o.has("--all")) cmd.all()
            if (paths.isNotEmpty()) {
                val rp = c.repoPaths(repo, paths) ?: return 1
                for (p in rp) if (p != ".") cmd.addPath(p)
            }
            val oneline = o.has("--oneline")
            var count = 0
            try {
                for (rc in cmd.call()) {
                    if (count >= cap) {
                        if (nGiven == null) c.out("… (শুধু প্রথম $cap টি দেখানো হলো; বেশি দেখতে -n সংখ্যা দিন)")
                        break
                    }
                    if (oneline) {
                        c.out("${c.shortId(rc)} ${rc.shortMessage}")
                    } else {
                        if (count > 0) c.out("")
                        printCommitFull(c, rc)
                    }
                    count++
                }
            } catch (e: NoHeadException) {
                return c.fail("fatal: বর্তমান branch '${repo.branch}'-এ এখনো কোনো commit নেই")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ diff helpers
    private fun treeIter(repo: Repository, id: ObjectId): AbstractTreeIterator {
        val p = CanonicalTreeParser()
        repo.newObjectReader().use { reader -> p.reset(reader, id) }
        return p
    }

    private fun treeOf(repo: Repository, rev: String): ObjectId? = repo.resolve("$rev^{tree}")

    /** দুই tree (বা index/worktree) এর diff ছাপে। */
    private fun runDiff(
        c: GitCtx, repo: Repository, oldTree: ObjectId?, newTree: ObjectId?, cached: Boolean,
        paths: List<String>, nameOnly: Boolean, nameStatus: Boolean
    ): Int {
        val g = c.git(repo)
        val los = LineOutputStream(ShellSinkAdapter(c))
        val cmd = g.diff().setOutputStream(los)
        if (oldTree != null) cmd.setOldTree(treeIter(repo, oldTree))
        if (newTree != null) cmd.setNewTree(treeIter(repo, newTree))
        if (cached) cmd.setCached(true)
        val real = paths.filter { it != "." }
        if (real.isNotEmpty()) cmd.setPathFilter(PathFilterGroup.createFromStrings(real))
        val short = nameOnly || nameStatus
        if (short) cmd.setShowNameAndStatusOnly(true)
        val entries = cmd.call()
        los.finish()
        if (short) {
            for (e in entries) {
                val path = if (e.changeType == DiffEntry.ChangeType.DELETE) e.oldPath else e.newPath
                if (nameOnly) c.out(path) else c.out("${letter(e.changeType)}\t$path")
            }
        }
        return 0
    }

    private fun letter(t: DiffEntry.ChangeType): String = when (t) {
        DiffEntry.ChangeType.ADD -> "A"
        DiffEntry.ChangeType.DELETE -> "D"
        DiffEntry.ChangeType.RENAME -> "R"
        DiffEntry.ChangeType.COPY -> "C"
        else -> "M"
    }

    // ------------------------------------------------------------------ diff
    fun diff(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--cached", "--staged", "--name-only", "--name-status"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val revs = ArrayList<String>()
            val paths = ArrayList<String>(o.afterDD)
            for (p in o.pos) {
                if (!o.hadDD && paths.isEmpty() && (p.contains("..") || repo.resolve(p) != null)) revs.add(p) else paths.add(p)
            }
            val rp = if (paths.isEmpty()) emptyList() else (c.repoPaths(repo, paths) ?: return 1)
            val cached = o.has("--cached", "--staged")
            var oldT: ObjectId? = null
            var newT: ObjectId? = null
            if (revs.size == 1 && revs[0].contains("..")) {
                val parts = revs[0].split("..", limit = 2)
                oldT = treeOf(repo, parts[0]) ?: return c.fail("fatal: অজানা revision '${parts[0]}'")
                newT = treeOf(repo, parts[1].ifEmpty { "HEAD" }) ?: return c.fail("fatal: অজানা revision '${parts[1]}'")
            } else if (revs.size == 1) {
                oldT = treeOf(repo, revs[0]) ?: return c.fail("fatal: অজানা revision '${revs[0]}'")
            } else if (revs.size == 2) {
                oldT = treeOf(repo, revs[0]) ?: return c.fail("fatal: অজানা revision '${revs[0]}'")
                newT = treeOf(repo, revs[1]) ?: return c.fail("fatal: অজানা revision '${revs[1]}'")
            } else if (revs.size > 2) {
                return c.fail("fatal: সর্বোচ্চ দুটো revision দিন")
            }
            return runDiff(c, repo, oldT, newT, cached, rp, o.has("--name-only"), o.has("--name-status"))
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ show
    fun show(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--name-only", "--name-status", "-s", "--no-patch"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val spec = o.pos.firstOrNull() ?: "HEAD"
            val id = repo.resolve(spec) ?: return c.fail("fatal: অজানা revision '$spec'")
            val commit = try {
                c.commitOf(repo, id)
            } catch (e: Exception) {
                null
            }
            if (commit == null) {
                // ব্লব হলে লেখা দেখাই
                val loader = repo.open(id)
                if (loader.type != Constants.OBJ_BLOB) return c.fail("fatal: show শুধু commit ও ফাইল (rev:path) দেখায়")
                if (loader.size > 256 * 1024) return c.fail("ফাইল বড় (${loader.size} বাইট) — দেখানো হলো না")
                val bytes = loader.bytes
                if (bytes.any { it.toInt() == 0 }) return c.fail("বাইনারি ফাইল — দেখানো হলো না")
                for (l in String(bytes, Charsets.UTF_8).lines()) c.out(l.trimEnd('\r'))
                return 0
            }
            printCommitFull(c, commit)
            if (o.has("-s", "--no-patch")) return 0
            c.out("")
            val parentTree = if (commit.parentCount > 0) {
                RevWalk(repo).use { it.parseCommit(commit.getParent(0).id).tree.id }
            } else {
                null
            }
            return runDiff(c, repo, parentTree ?: emptyTree(repo), commit.tree.id, false,
                emptyList(), o.has("--name-only"), o.has("--name-status"))
        } finally {
            repo.close()
        }
    }

    /** খালি tree-এর আইডি (root commit-এর diff-এর জন্য)। */
    private fun emptyTree(repo: Repository): ObjectId {
        val ins = repo.newObjectInserter()
        try {
            val id = ins.insert(Constants.OBJ_TREE, ByteArray(0))
            ins.flush()
            return id
        } finally {
            ins.close()
        }
    }

    // ------------------------------------------------------------------ tag
    fun tag(c: GitCtx, a: List<String>): Int {
        val o = Opts(
            a, setOf("-m", "--message"),
            setOf("-a", "--annotate", "-l", "--list", "-f", "--force", "-d", "--delete")
        )
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (o.has("-d", "--delete")) {
                if (o.pos.isEmpty()) return c.fail("usage: git tag -d <নাম>...")
                val done = g.tagDelete().setTags(*o.pos.toTypedArray()).call()
                for (d in done) c.out("Deleted tag '${Repository.shortenRefName(d)}'")
                return 0
            }
            val msgs = o.all("-m", "--message")
            if (o.pos.isEmpty() || o.has("-l", "--list")) {
                val pattern = o.pos.firstOrNull()
                val re = pattern?.let { p ->
                    Regex("^" + p.split('*').joinToString(".*") { Regex.escape(it) } + "$")
                }
                for (n in g.tagList().call().map { Repository.shortenRefName(it.name) }.sorted()) {
                    if (re == null || re.matches(n)) c.out(n)
                }
                return 0
            }
            val name = o.pos[0]
            val annotated = o.has("-a", "--annotate") || msgs.isNotEmpty()
            if (annotated && msgs.isEmpty()) return c.fail("বার্তা দিন: git tag -a $name -m \"বার্তা\" (এডিটর নেই)")
            if (annotated && !c.needIdentity(repo)) return 128
            val target = o.pos.getOrNull(1) ?: "HEAD"
            val id = repo.resolve(target) ?: return c.fail("fatal: '$target' বৈধ ref নয়")
            val obj = RevWalk(repo).use { it.parseAny(id) }
            val cmd = g.tag().setName(name).setObjectId(obj).setAnnotated(annotated)
            if (annotated) cmd.setMessage(msgs.joinToString("\n\n"))
            if (o.has("-f", "--force")) cmd.setForceUpdate(true)
            cmd.call()
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ stash
    private fun stashIndex(arg: String?): Int? {
        if (arg == null) return 0
        val m = Regex("^stash@\\{(\\d+)\\}$").find(arg)
        if (m != null) return m.groupValues[1].toInt()
        return arg.toIntOrNull()
    }

    fun stash(c: GitCtx, a: List<String>): Int {
        val first = a.firstOrNull()
        val subs = setOf("push", "save", "list", "pop", "apply", "drop", "clear", "show", "branch")
        val sub = if (first != null && first in subs) first else "push"
        val rest = if (first != null && first in subs) a.drop(1) else a
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            when (sub) {
                "push", "save" -> {
                    val o = Opts(rest, setOf("-m", "--message"), setOf("-u", "--include-untracked", "-q", "--quiet"))
                    if (c.badOpts(o)) return 129
                    if (!c.needIdentity(repo)) return 128
                    val cmd = g.stashCreate().setIncludeUntracked(o.has("-u", "--include-untracked"))
                    o.value("-m", "--message")?.let { cmd.setWorkingDirectoryMessage(it) }
                    val r = cmd.call()
                    if (r == null) {
                        c.out("No local changes to save")
                        return 0
                    }
                    c.out("Saved working directory and index state: ${r.shortMessage}")
                    return 0
                }
                "list" -> {
                    var i = 0
                    for (s in g.stashList().call()) {
                        c.out("stash@{$i}: ${s.shortMessage}")
                        i++
                    }
                    return 0
                }
                "apply", "pop", "drop" -> {
                    val idx = stashIndex(rest.firstOrNull()) ?: return c.fail("fatal: '${rest.firstOrNull()}' বৈধ stash নয় (যেমন stash@{0})")
                    val n = g.stashList().call().size
                    if (n == 0) return c.fail("error: কোনো stash নেই")
                    if (idx >= n) return c.fail("error: stash@{$idx} নেই (মোট $n টি)")
                    if (sub != "drop") g.stashApply().setStashRef("stash@{$idx}").call()
                    if (sub != "apply") {
                        g.stashDrop().setStashRef(idx).call()
                        c.out("Dropped stash@{$idx}")
                    }
                    return 0
                }
                "clear" -> {
                    val n = g.stashList().call().size
                    for (k in 0 until n) g.stashDrop().setStashRef(0).call()
                    return 0
                }
                else -> return c.fail("git stash $sub: JGit-এ নেই — সমর্থিত: push, pop, list, apply, drop, clear")
            }
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ reset
    fun reset(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--soft", "--mixed", "--hard", "-q", "--quiet"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            var rev: String? = null
            val paths = ArrayList<String>(o.afterDD)
            for ((i, p) in o.pos.withIndex()) {
                if (i == 0 && !o.hadDD && repo.resolve(p) != null) rev = p else paths.add(p)
            }
            val modes = listOf("--soft", "--mixed", "--hard").filter { it in o.flags }
            if (modes.size > 1) return c.fail("fatal: একসাথে একাধিক reset মোড চলে না")
            if (paths.isNotEmpty()) {
                if (modes.isNotEmpty() && modes[0] != "--mixed") return c.fail("fatal: পাথ দিয়ে ${modes[0]} reset করা যায় না")
                val rp = c.repoPaths(repo, paths) ?: return 1
                val cmd = g.reset().setRef(rev ?: Constants.HEAD)
                var all = false
                for (p in rp) { if (p == ".") all = true else cmd.addPath(p) }
                if (all) {
                    g.reset().setMode(ResetCommand.ResetType.MIXED).setRef(rev ?: Constants.HEAD).call()
                } else {
                    cmd.call()
                }
                return 0
            }
            val mode = when (modes.firstOrNull()) {
                "--soft" -> ResetCommand.ResetType.SOFT
                "--hard" -> ResetCommand.ResetType.HARD
                else -> ResetCommand.ResetType.MIXED
            }
            val ref = g.reset().setMode(mode).setRef(rev ?: Constants.HEAD).call()
            if (mode == ResetCommand.ResetType.HARD && !o.has("-q", "--quiet")) {
                val oid = ref.objectId
                if (oid != null) c.out("HEAD is now at ${c.shortId(oid)} ${c.commitOf(repo, oid).shortMessage}")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ revert
    fun revert(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--abort", "--continue", "-n", "--no-commit", "--no-edit"))
        if (c.badOpts(o)) return 129
        if (o.has("--abort", "--continue", "-n", "--no-commit")) {
            return c.fail("git revert: --abort/--continue/--no-commit JGit-এ নেই — বাতিল করতে 'git reset --hard' ব্যবহার করুন")
        }
        if (o.pos.isEmpty()) return c.fail("usage: git revert <commit>...")
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (!c.needIdentity(repo)) return 128
            for (p in o.pos) {
                val id = repo.resolve(p) ?: return c.fail("fatal: অজানা revision '$p'")
                val cmd = g.revert().include(id)
                val r = cmd.call()
                if (r == null) {
                    c.err("error: '$p' revert করা যায়নি — কনফ্লিক্ট:")
                    for (u in cmd.unmergedPaths) c.err("  CONFLICT: $u")
                    c.err("ঠিক করে 'git add' + 'git commit' করুন, অথবা 'git reset --hard'")
                    return 1
                }
                c.out("[${repo.branch} ${c.shortId(r)}] ${r.shortMessage}")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ cherry-pick
    fun cherryPick(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--abort", "--continue", "-n", "--no-commit"))
        if (c.badOpts(o)) return 129
        if (o.has("--abort", "--continue")) {
            return c.fail("git cherry-pick: --abort/--continue JGit-এ নেই — বাতিল করতে 'git reset --hard' ব্যবহার করুন")
        }
        if (o.pos.isEmpty()) return c.fail("usage: git cherry-pick [-n] <commit>...")
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (!c.needIdentity(repo)) return 128
            val cmd = g.cherryPick()
            for (p in o.pos) {
                if (p.contains("..")) return c.fail("fatal: range (a..b) সমর্থিত নয় — commit আলাদা করে দিন")
                val id = repo.resolve(p) ?: return c.fail("fatal: অজানা revision '$p'")
                cmd.include(id)
            }
            if (o.has("-n", "--no-commit")) cmd.setNoCommit(true)
            val res = cmd.call()
            return when (res.status) {
                org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK -> {
                    val h = res.newHead
                    if (h != null) c.out("[${repo.branch} ${c.shortId(h)}] ${h.shortMessage}")
                    0
                }
                org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING -> {
                    c.err("error: cherry-pick-এ কনফ্লিক্ট — ঠিক করে 'git add' + 'git commit' করুন, অথবা 'git reset --hard'")
                    1
                }
                else -> {
                    c.err("error: cherry-pick ব্যর্থ — লোকাল পরিবর্তন আগে commit/stash করুন")
                    for (k in res.failingPaths.keys) c.err("  $k")
                    1
                }
            }
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ clean
    fun clean(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("-f", "--force", "-d", "-n", "--dry-run", "-x", "-q", "--quiet"))
        if (c.badOpts(o)) return 129
        val dry = o.has("-n", "--dry-run")
        if (!dry && !o.has("-f", "--force")) return c.fail("fatal: clean-এর জন্য -f (মুছতে) বা -n (শুধু দেখতে) দিন")
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val cmd = g.clean().setDryRun(dry).setCleanDirectories(o.has("-d"))
            if (o.has("-x")) cmd.setIgnore(false)
            val given = o.pos + o.afterDD
            if (given.isNotEmpty()) {
                val rp = c.repoPaths(repo, given) ?: return 1
                cmd.setPaths(rp.filter { it != "." }.toSet())
            }
            val removed = cmd.call()
            for (r in removed.sorted()) c.out((if (dry) "Would remove " else "Removing ") + r)
            return 0
        } finally {
            repo.close()
        }
    }
}

/** GitCtx-কে ShellSink হিসেবে দেখানোর পাতলা মোড়ক (diff আউটপুটের জন্য)। */
internal class ShellSinkAdapter(private val c: GitCtx) : ShellSink {
    override fun out(text: String) = c.out(text)
    override fun err(text: String) = c.err(text)
    override fun progress(text: String) {}
    override fun clear() {}
}
