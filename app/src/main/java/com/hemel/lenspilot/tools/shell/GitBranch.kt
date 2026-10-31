package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository

/** branch, checkout, switch, restore, merge, rebase। */
internal object GitBranch {

    private fun isDetached(repo: Repository): Boolean {
        val full = repo.fullBranch
        return full == null || !full.startsWith(Constants.R_HEADS)
    }

    // ------------------------------------------------------------------ branch
    fun branch(c: GitCtx, a: List<String>): Int {
        val o = Opts(
            a, setOf("-u", "--set-upstream-to"),
            setOf("-a", "--all", "-r", "--remotes", "-d", "-D", "-m", "-M", "-v", "-f", "--force", "--list", "--show-current")
        )
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (o.has("--show-current")) {
                if (!isDetached(repo)) c.out(repo.branch)
                return 0
            }
            val up = o.value("-u", "--set-upstream-to")
            if (up != null) {
                val b = o.pos.firstOrNull() ?: repo.branch
                val slash = up.indexOf('/')
                if (slash <= 0) return c.fail("fatal: upstream এর ফরম্যাট remote/branch (যেমন origin/main)")
                val cfg = repo.config
                cfg.setString("branch", b, "remote", up.substring(0, slash))
                cfg.setString("branch", b, "merge", Constants.R_HEADS + up.substring(slash + 1))
                cfg.save()
                c.out("branch '$b' এখন '$up' ট্র্যাক করে")
                return 0
            }
            if (o.has("-d", "-D")) {
                if (o.pos.isEmpty()) return c.fail("usage: git branch -d <নাম>...")
                val done = g.branchDelete().setBranchNames(*o.pos.toTypedArray()).setForce(o.has("-D")).call()
                for (d in done) c.out("Deleted branch ${Repository.shortenRefName(d)}.")
                return 0
            }
            if (o.has("-m", "-M")) {
                val (old, new) = when (o.pos.size) {
                    1 -> Pair(null, o.pos[0])
                    2 -> Pair(o.pos[0], o.pos[1])
                    else -> return c.fail("usage: git branch -m [<পুরনো>] <নতুন>")
                }
                val cmd = g.branchRename().setNewName(new)
                if (old != null) cmd.setOldName(old)
                cmd.call()
                return 0
            }
            val listing = o.pos.isEmpty() || o.has("--list")
            if (listing) {
                val mode = when {
                    o.has("-a", "--all") -> ListBranchCommand.ListMode.ALL
                    o.has("-r", "--remotes") -> ListBranchCommand.ListMode.REMOTE
                    else -> null
                }
                val cmd = g.branchList()
                if (mode != null) cmd.setListMode(mode)
                val refs = cmd.call().sortedBy { it.name }
                val cur = repo.fullBranch
                if (isDetached(repo) && mode != ListBranchCommand.ListMode.REMOTE) {
                    c.out("* (HEAD detached at ${repo.branch.take(7)})")
                }
                for (r in refs) {
                    val isRemote = r.name.startsWith(Constants.R_REMOTES)
                    val shown = if (isRemote) {
                        if (mode == ListBranchCommand.ListMode.REMOTE) r.name.removePrefix(Constants.R_REMOTES)
                        else "remotes/" + r.name.removePrefix(Constants.R_REMOTES)
                    } else {
                        Repository.shortenRefName(r.name)
                    }
                    val mark = if (r.name == cur) "* " else "  "
                    if (o.has("-v") && r.objectId != null) {
                        val msg = try { c.commitOf(repo, r.objectId).shortMessage } catch (e: Exception) { "" }
                        c.out("$mark$shown ${c.shortId(r.objectId)} $msg")
                    } else {
                        c.out(mark + shown)
                    }
                }
                return 0
            }
            val name = o.pos[0]
            if (repo.resolve(Constants.HEAD) == null) return c.fail("fatal: এখনো কোনো commit নেই — আগে একটা commit করুন")
            val cmd = g.branchCreate().setName(name)
            if (o.has("-f", "--force")) cmd.setForce(true)
            o.pos.getOrNull(1)?.let { cmd.setStartPoint(it) }
            cmd.call()
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ checkout / switch
    private fun conflictMsg(c: GitCtx, e: CheckoutConflictException): Int {
        c.err("error: আপনার লোকাল পরিবর্তন ওভাররাইট হয়ে যাবে:")
        for (p in e.conflictingPaths) c.err("\t$p")
        c.err("আগে commit বা stash করুন (অথবা -f দিয়ে পরিবর্তন ফেলে দিন)।")
        return 1
    }

    fun checkout(c: GitCtx, a: List<String>, isSwitch: Boolean): Int {
        val o = Opts(
            a, setOf("-b", "-c"),
            setOf("-f", "--force", "-q", "--quiet", "--detach", "-d", "-t", "--track", "--ours", "--theirs", "-B", "-C", "--orphan")
        )
        if (c.badOpts(o)) return 129
        if (o.has("--ours", "--theirs", "-B", "-C", "--orphan")) {
            return c.fail("এই অপশন (--ours/--theirs/-B/-C/--orphan) JGit-এ নেই")
        }
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val force = o.has("-f", "--force")
            val newName = o.value("-b", "-c")
            val args = o.pos

            // ---- পাথ মোড (checkout [<rev>] -- <paths>)
            val pathMode = !isSwitch && newName == null && (o.hadDD ||
                (args.size == 1 && repo.findRef(args[0]) == null && repo.resolve(args[0]) == null && !o.has("--detach")))
            if (pathMode) {
                var treeish: String? = null
                val paths = ArrayList<String>(o.afterDD)
                if (o.hadDD) {
                    treeish = args.firstOrNull()
                } else {
                    paths.addAll(args)
                }
                if (paths.isEmpty()) return c.fail("fatal: কোন পাথ restore হবে?")
                val rp = c.repoPaths(repo, paths) ?: return 1
                val cmd = g.checkout()
                if (treeish != null) cmd.setStartPoint(treeish)
                var all = false
                for (p in rp) { if (p == ".") all = true else cmd.addPath(p) }
                if (all) cmd.setAllPaths(true)
                cmd.call()
                return 0
            }
            if (isSwitch && o.hadDD) return c.fail("fatal: switch-এ পাথ চলে না")

            if (force) g.reset().setMode(ResetCommand.ResetType.HARD).call()

            val quiet = o.has("-q", "--quiet")
            try {
                if (newName != null) {
                    if (repo.resolve(Constants.HEAD) == null) return c.fail("fatal: এখনো কোনো commit নেই — নতুন branch বানানো যাবে না")
                    val cmd = g.checkout().setCreateBranch(true).setName(newName)
                    args.firstOrNull()?.let { cmd.setStartPoint(it) }
                    cmd.call()
                    if (!quiet) c.out("Switched to a new branch '$newName'")
                    return 0
                }
                val target = args.firstOrNull() ?: return c.fail("fatal: কোন branch/commit? (usage: git ${if (isSwitch) "switch" else "checkout"} <নাম>)")
                if (args.size > 1) return c.fail("fatal: একটাই branch/commit দিন")
                val localRef = repo.findRef(Constants.R_HEADS + target)
                if (localRef == null && repo.resolve(target) == null) {
                    val matches = remoteMatches(repo, target)
                    if (matches.size == 1) {
                        g.checkout().setCreateBranch(true).setName(target).setStartPoint(matches[0])
                            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
                        if (!quiet) c.out("branch '$target' '${matches[0].removePrefix(Constants.R_REMOTES)}' ট্র্যাক করছে; Switched to a new branch '$target'")
                        return 0
                    }
                    if (matches.size > 1) return c.fail("error: '$target' একাধিক remote-এ আছে — 'git checkout -b $target <remote>/$target' দিন")
                    return c.fail("error: '$target' নামে কোনো branch/commit নেই")
                }
                g.checkout().setName(target).call()
                if (!quiet) {
                    if (isDetached(repo)) c.out("Note: '$target' — detached HEAD অবস্থায় আছেন (${repo.branch.take(7)})")
                    else c.out("Switched to branch '${repo.branch}'")
                }
                return 0
            } catch (e: CheckoutConflictException) {
                return conflictMsg(c, e)
            }
        } finally {
            repo.close()
        }
    }

    /** refs/remotes/<remote>/<name> এর তালিকা (DWIM checkout-এর জন্য)। */
    private fun remoteMatches(repo: Repository, name: String): List<String> {
        val out = ArrayList<String>()
        for (r in repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES)) {
            val rest = r.name.removePrefix(Constants.R_REMOTES)
            if (rest.substringAfter('/', "") == name) out.add(r.name)
        }
        return out
    }

    // ------------------------------------------------------------------ restore
    fun restore(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, setOf("-s", "--source"), setOf("-S", "--staged", "-W", "--worktree", "-q"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val given = o.pos + o.afterDD
            if (given.isEmpty()) return c.fail("fatal: কোন পাথ restore হবে? (git restore [--staged] <পাথ>)")
            val rp = c.repoPaths(repo, given) ?: return 1
            val src = o.value("-s", "--source")
            if (src != null && repo.resolve(src) == null) return c.fail("fatal: অজানা source '$src'")
            val staged = o.has("-S", "--staged")
            val worktree = o.has("-W", "--worktree") || !staged
            try {
                if (staged) {
                    val ref = src ?: Constants.HEAD
                    var all = false
                    val cmd = g.reset().setRef(ref)
                    for (p in rp) { if (p == ".") all = true else cmd.addPath(p) }
                    if (all) g.reset().setMode(ResetCommand.ResetType.MIXED).setRef(ref).call() else cmd.call()
                }
                if (worktree) {
                    val cmd = g.checkout()
                    if (src != null) cmd.setStartPoint(src)
                    var all = false
                    for (p in rp) { if (p == ".") all = true else cmd.addPath(p) }
                    if (all) cmd.setAllPaths(true)
                    cmd.call()
                }
            } catch (e: CheckoutConflictException) {
                return conflictMsg(c, e)
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ merge
    fun printMerge(c: GitCtx, res: MergeResult): Int {
        return when (res.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> { c.out("Already up to date."); 0 }
            MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED -> {
                c.out("Fast-forward"); 0
            }
            MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_SQUASHED -> { c.out("Merge made."); 0 }
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED, MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED -> {
                c.out("Merge হয়েছে, কিন্তু commit করা হয়নি — 'git commit' দিন।"); 0
            }
            MergeResult.MergeStatus.CONFLICTING -> {
                val conf = res.conflicts
                if (conf != null) for (k in conf.keys) c.err("CONFLICT (content): Merge conflict in $k")
                c.err("Automatic merge failed; কনফ্লিক্ট ঠিক করে 'git add' + 'git commit' করুন (বাতিল: git merge --abort)।")
                1
            }
            else -> {
                c.err("error: merge সম্পূর্ণ হয়নি (${res.mergeStatus}) — লোকাল পরিবর্তন আগে commit/stash করুন")
                val f = res.failingPaths
                if (f != null) for (k in f.keys) c.err("  $k")
                1
            }
        }
    }

    fun merge(c: GitCtx, a: List<String>): Int {
        val o = Opts(
            a, setOf("-m", "--message"),
            setOf("--no-ff", "--ff", "--ff-only", "--squash", "--no-commit", "--abort", "-q", "--quiet")
        )
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (o.has("--abort")) {
                if (repo.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
                    return c.fail("fatal: বাতিল করার মতো কোনো merge চলছে না")
                }
                g.reset().setMode(ResetCommand.ResetType.HARD).call()
                c.out("merge বাতিল (HEAD-এ ফেরা হয়েছে)")
                return 0
            }
            val target = o.pos.firstOrNull() ?: return c.fail("usage: git merge <branch|commit>")
            val id = repo.resolve(target) ?: return c.fail("merge: '$target' — merge করার মতো কিছু নয়")
            if (!c.needIdentity(repo)) return 128
            val cmd = g.merge().include(target, id)
            when {
                o.has("--no-ff") -> cmd.setFastForward(MergeCommand.FastForwardMode.NO_FF)
                o.has("--ff-only") -> cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                else -> cmd.setFastForward(MergeCommand.FastForwardMode.FF)
            }
            if (o.has("--squash")) cmd.setSquash(true)
            if (o.has("--no-commit")) cmd.setCommit(false)
            o.value("-m", "--message")?.let { cmd.setMessage(it) }
            return try {
                printMerge(c, cmd.call())
            } catch (e: CheckoutConflictException) {
                conflictMsg(c, e)
            }
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ rebase
    fun printRebase(c: GitCtx, res: RebaseResult): Int {
        return when (res.status) {
            RebaseResult.Status.OK -> { c.out("Successfully rebased."); 0 }
            RebaseResult.Status.UP_TO_DATE -> { c.out("Current branch is up to date."); 0 }
            RebaseResult.Status.FAST_FORWARD -> { c.out("Fast-forwarded."); 0 }
            RebaseResult.Status.ABORTED -> { c.out("rebase বাতিল।"); 0 }
            RebaseResult.Status.NOTHING_TO_COMMIT -> { c.out("কোনো পরিবর্তন নেই — 'git rebase --skip' দিন।"); 1 }
            RebaseResult.Status.CONFLICTS, RebaseResult.Status.STOPPED -> {
                val conf = res.conflicts
                if (conf != null) for (k in conf) c.err("CONFLICT: $k")
                c.err("ঠিক করে 'git add' তারপর 'git rebase --continue' (বাতিল: --abort)।")
                1
            }
            RebaseResult.Status.UNCOMMITTED_CHANGES -> {
                c.err("error: rebase করা যাচ্ছে না — অ-commit করা পরিবর্তন আছে (commit/stash করুন)")
                1
            }
            else -> {
                c.err("error: rebase ব্যর্থ (${res.status})")
                1
            }
        }
    }

    fun rebase(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--continue", "--abort", "--skip", "-i", "--interactive", "-q", "--quiet"))
        if (c.badOpts(o)) return 129
        if (o.has("-i", "--interactive")) return c.fail("git rebase -i: JGit-এ এডিটর-ভিত্তিক interactive rebase সমর্থিত নয় — non-interactive rebase ব্যবহার করুন")
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val op = when {
                o.has("--continue") -> RebaseCommand.Operation.CONTINUE
                o.has("--abort") -> RebaseCommand.Operation.ABORT
                o.has("--skip") -> RebaseCommand.Operation.SKIP
                else -> RebaseCommand.Operation.BEGIN
            }
            if (!c.needIdentity(repo)) return 128
            val cmd = g.rebase().setOperation(op)
            if (op == RebaseCommand.Operation.BEGIN) {
                val upstream = o.pos.firstOrNull() ?: return c.fail("usage: git rebase <upstream> [<branch>]")
                if (repo.resolve(upstream) == null) return c.fail("fatal: অজানা revision '$upstream'")
                o.pos.getOrNull(1)?.let { br ->
                    g.checkout().setName(br).call()
                }
                cmd.setUpstream(upstream)
            }
            return try {
                printRebase(c, cmd.call())
            } catch (e: CheckoutConflictException) {
                conflictMsg(c, e)
            }
        } finally {
            repo.close()
        }
    }
}
