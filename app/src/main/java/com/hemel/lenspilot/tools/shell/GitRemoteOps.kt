package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt

/** push, fetch, pull — নেটওয়ার্ক কমান্ড (প্রগ্রেস + Stop সহ)। */
internal object GitRemoteOps {

    private fun short7(id: org.eclipse.jgit.lib.ObjectId?): String = id?.name()?.take(7) ?: "0000000"

    fun printFetch(c: GitCtx, res: FetchResult?) {
        if (res == null) return
        for (ru in res.trackingRefUpdates) {
            val rem = Repository.shortenRefName(ru.remoteName)
            val loc = Repository.shortenRefName(ru.localName)
            when (ru.result) {
                RefUpdate.Result.NEW -> {
                    val kind = if (ru.remoteName.startsWith(Constants.R_TAGS)) "new tag   " else "new branch"
                    c.out(" * [$kind]  $rem -> $loc")
                }
                RefUpdate.Result.FAST_FORWARD -> c.out("   ${short7(ru.oldObjectId)}..${short7(ru.newObjectId)}  $rem -> $loc")
                RefUpdate.Result.FORCED -> c.out(" + ${short7(ru.oldObjectId)}...${short7(ru.newObjectId)}  $rem -> $loc (forced update)")
                RefUpdate.Result.NO_CHANGE -> {}
                else -> c.out(" ! [${ru.result}]  $rem -> $loc")
            }
        }
    }

    // ------------------------------------------------------------------ fetch
    fun fetch(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, setOf("--depth"), setOf("--all", "--prune", "-p", "--tags", "-t", "-q", "--quiet"))
        if (c.badOpts(o)) return 129
        val depth = o.value("--depth")?.toIntOrNull()
        if (o.value("--depth") != null && (depth == null || depth < 1)) return c.fail("fatal: --depth ধনাত্মক সংখ্যা হতে হবে")
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val remotes: List<String> = if (o.has("--all")) {
                g.remoteList().call().map { it.name }
            } else {
                listOf(o.pos.firstOrNull() ?: repo.config.getString("branch", repo.branch, "remote") ?: "origin")
            }
            if (remotes.isEmpty()) return c.fail("fatal: কোনো remote নেই — 'git remote add origin <url>'")
            for (r in remotes) {
                val url = repo.config.getString("remote", r, "url") ?: return c.fail("fatal: remote '$r' নেই — 'git remote add $r <url>'")
                c.out("From ${ShellText.scrub(url)}")
                val cmd: FetchCommand = g.fetch().setRemote(r).setProgressMonitor(c.monitor()).setTimeout(60)
                cmd.setRemoveDeletedRefs(o.has("--prune", "-p"))
                if (o.has("--tags", "-t")) cmd.setTagOpt(TagOpt.FETCH_TAGS)
                if (depth != null) c.err("সতর্কতা: --depth এই JGit-এ সমর্থিত নয়, পুরো fetch হচ্ছে")
                c.auth.credentials()?.let { cmd.setCredentialsProvider(it) }
                printFetch(c, cmd.call())
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ pull
    fun pull(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--rebase", "-r", "--no-rebase", "--ff-only", "--no-ff", "-q", "--quiet"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val full = repo.fullBranch
            if (full == null || !full.startsWith(Constants.R_HEADS)) return c.fail("fatal: detached HEAD-এ pull চলে না — আগে একটা branch-এ যান")
            val remote = o.pos.firstOrNull() ?: repo.config.getString("branch", repo.branch, "remote") ?: "origin"
            val url = repo.config.getString("remote", remote, "url") ?: return c.fail("fatal: remote '$remote' নেই — 'git remote add $remote <url>'")
            if (c.loginRequired(url)) return 1
            val cmd = g.pull().setProgressMonitor(c.monitor()).setTimeout(60)
            if (o.pos.isNotEmpty()) cmd.setRemote(remote)
            o.pos.getOrNull(1)?.let { cmd.setRemoteBranchName(it) }
            if (o.has("--rebase", "-r")) cmd.setRebase(true)
            if (o.has("--ff-only")) cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            if (o.has("--no-ff")) cmd.setFastForward(MergeCommand.FastForwardMode.NO_FF)
            c.auth.credentials()?.let { cmd.setCredentialsProvider(it) }
            val res = cmd.call()
            printFetch(c, res.fetchResult)
            val rb = res.rebaseResult
            val mg = res.mergeResult
            return when {
                rb != null -> GitBranch.printRebase(c, rb)
                mg != null -> GitBranch.printMerge(c, mg)
                else -> if (res.isSuccessful) 0 else 1
            }
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ push
    /** "a", "a:b", "+a:b" → পূর্ণ নামের RefSpec। */
    private fun normalizeSpec(repo: Repository, raw: String): RefSpec {
        val plus = raw.startsWith("+")
        val body = raw.removePrefix("+")
        val src = body.substringBefore(':')
        val dst = if (body.contains(':')) body.substringAfter(':') else src
        val fullSrc = if (src.isEmpty()) "" else (repo.findRef(src)?.name ?: src)
        val fullDst = if (dst.startsWith("refs/")) dst else Constants.R_HEADS + dst
        return RefSpec((if (plus) "+" else "") + fullSrc + ":" + fullDst)
    }

    fun push(c: GitCtx, a0: List<String>): Int {
        val a = a0.map { if (it.startsWith("--force-with-lease=")) "--force-with-lease" else it }
        val o = Opts(
            a, emptySet(),
            setOf("-f", "--force", "--force-with-lease", "--tags", "--all", "-u", "--set-upstream", "-d", "--delete", "-n", "--dry-run", "-q", "--quiet")
        )
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val full = repo.fullBranch
            val onBranch = full != null && full.startsWith(Constants.R_HEADS)
            val branch = repo.branch
            val remote = o.pos.firstOrNull() ?: repo.config.getString("branch", branch, "remote") ?: "origin"
            val url = repo.config.getString("remote", remote, "url")
                ?: return c.fail("fatal: remote '$remote' নেই — 'git remote add $remote <url>'")
            if (c.loginRequired(url)) return 1

            val specs = ArrayList<RefSpec>()
            val given = o.pos.drop(1)
            if (o.has("-d", "--delete")) {
                if (given.isEmpty()) return c.fail("usage: git push --delete <remote> <branch>")
                for (b in given) specs.add(RefSpec(":" + (if (b.startsWith("refs/")) b else Constants.R_HEADS + b)))
            } else if (given.isNotEmpty()) {
                for (r in given) specs.add(normalizeSpec(repo, r))
            } else if (!o.has("--all") && !o.has("--tags")) {
                if (!onBranch) return c.fail("fatal: detached HEAD — কোন branch push হবে তা দিন")
                if (repo.resolve(Constants.HEAD) == null) return c.fail("error: push করার মতো কোনো commit নেই")
                val merge = repo.config.getString("branch", branch, "merge")
                specs.add(RefSpec("${Constants.R_HEADS}$branch:${merge ?: (Constants.R_HEADS + branch)}"))
            }

            val lease = o.has("--force-with-lease")
            val force = o.has("-f", "--force") || lease || specs.any { it.isForceUpdate }

            if (lease) {
                val ls = g.lsRemote().setRemote(remote).setHeads(true)
                c.auth.credentials()?.let { ls.setCredentialsProvider(it) }
                val remoteRefs = ls.call()
                for (sp in specs) {
                    val dst = sp.destination ?: continue
                    if (!dst.startsWith(Constants.R_HEADS)) continue
                    val remoteId = remoteRefs.firstOrNull { it.name == dst }?.objectId
                    val tracking = repo.exactRef(Constants.R_REMOTES + remote + "/" + dst.removePrefix(Constants.R_HEADS))?.objectId
                    if (remoteId != tracking) {
                        c.err(" ! [rejected] $dst (stale info — remote-এ অন্য কেউ পরিবর্তন করেছে; আগে 'git fetch')")
                        return 1
                    }
                }
            }

            val cmd = g.push().setRemote(remote).setProgressMonitor(c.monitor()).setTimeout(60)
            if (specs.isNotEmpty()) cmd.setRefSpecs(specs)
            if (o.has("--all")) cmd.setPushAll()
            if (o.has("--tags")) cmd.setPushTags()
            if (force) cmd.setForce(true)
            if (o.has("-n", "--dry-run")) cmd.setDryRun(true)
            c.auth.credentials()?.let { cmd.setCredentialsProvider(it) }

            var code = 0
            for (pr in cmd.call()) {
                c.out("To ${ShellText.scrub(pr.getURI().toString())}")
                val msgs = pr.messages
                if (!msgs.isNullOrBlank()) for (l in msgs.trim().lines()) c.out("remote: $l")
                for (ru in pr.remoteUpdates) {
                    val src = ru.srcRef ?: "(delete)"
                    val line = "$src -> ${ru.remoteName}"
                    when (ru.status) {
                        RemoteRefUpdate.Status.OK -> c.out(" * [ok] $line")
                        RemoteRefUpdate.Status.UP_TO_DATE -> c.out(" = [up to date] $line")
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                            c.err(" ! [rejected] $line (non-fast-forward) — আগে 'git pull'")
                            code = 1
                        }
                        else -> {
                            c.err(" ! [${ru.status}] $line ${ru.message ?: ""}".trimEnd())
                            code = 1
                        }
                    }
                }
            }
            if (code == 0 && o.has("-u", "--set-upstream") && onBranch) {
                val cfg = repo.config
                cfg.setString("branch", branch, "remote", remote)
                cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch)
                cfg.save()
                c.out("branch '$branch' এখন '$remote/$branch' ট্র্যাক করে")
            }
            return code
        } finally {
            repo.close()
        }
    }
}
