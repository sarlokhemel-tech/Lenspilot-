package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.nio.file.Files

/** init, clone, status, add, rm, mv, commit, config, remote। */
internal object GitBasic {

    private fun isSsh(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("ssh://") || u.startsWith("git@") || (u.contains('@') && u.contains(':') && !u.contains("://"))
    }

    private const val SSH_MSG = "fatal: SSH URLs are not supported — use an HTTPS URL (e.g. https://github.com/user/repo.git)"

    // ------------------------------------------------------------------ init
    fun init(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, setOf("-b", "--initial-branch"), setOf("-q", "--quiet", "--bare"))
        if (c.badOpts(o)) return 129
        if (o.has("--bare")) return c.fail("fatal: --bare is not supported — only regular repositories in the workspace")
        val dirArg = o.pos.firstOrNull()
        val dir = if (dirArg == null) c.cwd else ShellPaths.resolve(c.root, c.cwd, dirArg)
            ?: return c.fail("fatal: '$dirArg' is outside the workspace")
        dir.mkdirs()
        val existed = File(dir, ".git").exists()
        val g = Git.init().setDirectory(dir).setInitialBranch(o.value("-b", "--initial-branch") ?: "main").call()
        g.close()
        if (!o.has("-q", "--quiet")) {
            val shown = ShellPaths.display(c.root, dir)
            c.out((if (existed) "Reinitialized existing Git repository in " else "Initialized empty Git repository in ") + "$shown/.git/")
        }
        return 0
    }

    // ------------------------------------------------------------------ clone
    fun clone(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, setOf("--depth", "-b", "--branch"), setOf("-q", "--quiet", "--single-branch", "--no-checkout"))
        if (c.badOpts(o)) return 129
        val url = o.pos.getOrNull(0) ?: return c.fail("usage: git clone [--depth N] [-b <branch>] <url> [<dir>]")
        if (isSsh(url)) return c.fail(SSH_MSG)
        val name = o.pos.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifEmpty { "repo" }
        val dir = ShellPaths.resolve(c.root, c.cwd, name) ?: return c.fail("fatal: '$name' is outside the workspace")
        if (dir.exists() && (dir.isFile || (dir.list()?.isNotEmpty() == true))) {
            return c.fail("fatal: destination path '$name' already exists and is not an empty directory")
        }
        val depth = o.value("--depth")?.toIntOrNull()
        if (o.value("--depth") != null && (depth == null || depth < 1)) return c.fail("fatal: --depth must be a positive number")
        val createdDir = !dir.exists()
        c.out("Cloning into '$name'...")
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(dir)
            .setProgressMonitor(c.monitor())
            .setTimeout(60)
            .setCloneAllBranches(!o.has("--single-branch") && depth == null)
        // JGit 5.13-এ shallow (--depth) নেই (6.3+) — পুরো ইতিহাস আসবে; --single-branch দিয়ে আকার কমানো যায়
        if (depth != null) c.err("warning: --depth is not supported in this JGit, doing a full clone (use --single-branch)")
        o.value("-b", "--branch")?.let { cmd.setBranch(it) }
        if (o.has("--no-checkout")) cmd.setNoCheckout(true)
        c.auth.credentials()?.let { cmd.setCredentialsProvider(it) }
        try {
            val g = cmd.call()
            g.close()
        } catch (t: Throwable) {
            if (createdDir) com.hemel.lenspilot.tools.SafeFs.deleteTree(dir)
            throw t
        }
        return 0
    }

    // ------------------------------------------------------------------ status
    fun status(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("-s", "--short", "-b", "--branch", "-sb"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val st = g.status().call()
            val head = repo.resolve(Constants.HEAD)
            val full = repo.fullBranch
            val onBranch = full != null && full.startsWith(Constants.R_HEADS)
            val branch = repo.branch
            if (o.has("-s", "--short")) {
                if (o.has("-b", "--branch")) c.out("## $branch")
                for (p in st.added) c.out("A  $p")
                for (p in st.changed) c.out("M  $p")
                for (p in st.removed) c.out("D  $p")
                for (p in st.modified) c.out(" M $p")
                for (p in st.missing) c.out(" D $p")
                for (p in st.conflicting) c.out("UU $p")
                for (p in st.untracked) c.out("?? $p")
                return 0
            }
            c.out(if (onBranch) "On branch $branch" else "HEAD detached at ${branch.take(7)}")
            if (onBranch) {
                try {
                    val t = BranchTrackingStatus.of(repo, branch)
                    if (t != null) {
                        val rn = shortRef(t.remoteTrackingBranch)
                        val ah = t.aheadCount
                        val bh = t.behindCount
                        if (ah == 0 && bh == 0) c.out("Your branch is up to date with '$rn'.")
                        else if (bh == 0) c.out("Your branch is ahead of '$rn' by $ah commit(s).")
                        else if (ah == 0) c.out("Your branch is behind '$rn' by $bh commit(s).")
                        else c.out("Your branch and '$rn' have diverged ($ah and $bh different commits).")
                    }
                } catch (e: Exception) {
                }
            }
            if (head == null) c.out("\nNo commits yet")
            val staged = st.added.isNotEmpty() || st.changed.isNotEmpty() || st.removed.isNotEmpty()
            if (staged) {
                c.out("\nChanges to be committed:")
                for (p in st.added.sorted()) c.out("\tnew file:   $p")
                for (p in st.changed.sorted()) c.out("\tmodified:   $p")
                for (p in st.removed.sorted()) c.out("\tdeleted:    $p")
            }
            if (st.conflicting.isNotEmpty()) {
                c.out("\nUnmerged paths:")
                for (p in st.conflicting.sorted()) c.out("\tboth modified:   $p")
            }
            if (st.modified.isNotEmpty() || st.missing.isNotEmpty()) {
                c.out("\nChanges not staged for commit:")
                for (p in st.modified.sorted()) c.out("\tmodified:   $p")
                for (p in st.missing.sorted()) c.out("\tdeleted:    $p")
            }
            if (st.untracked.isNotEmpty()) {
                c.out("\nUntracked files:")
                for (p in st.untracked.sorted()) c.out("\t$p")
            }
            if (st.isClean && st.untracked.isEmpty()) {
                c.out(if (head == null) "nothing to commit (create/copy files and use \"git add\")" else "\nnothing to commit, working tree clean")
            } else if (!staged && st.conflicting.isEmpty() && st.modified.isEmpty() && st.missing.isEmpty()) {
                c.out("\nnothing added to commit but untracked files present (use \"git add\")")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    private fun shortRef(name: String): String = org.eclipse.jgit.lib.Repository.shortenRefName(name)

    // ------------------------------------------------------------------ add
    fun add(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("-A", "--all", "-u", "--update", "-f", "--force", "-v", "--verbose", "-q"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val updateOnly = o.has("-u", "--update")
            val all = o.has("-A", "--all")
            val given = o.pos + o.afterDD
            if (given.isEmpty() && !all && !updateOnly) {
                return c.fail("No paths given — use 'git add .' or 'git add -A'")
            }
            val paths: List<String> = if (given.isEmpty()) {
                val here = c.repoPath(repo, ".") ?: return 1
                listOf(here)
            } else {
                c.repoPaths(repo, given) ?: return 1
            }
            val dc = repo.readDirCache()
            for (p in paths) {
                val exists = p == "." || File(repo.workTree, p).exists()
                val tracked = p == "." || dc.findEntry(p) >= 0 || dc.getEntriesWithin(p).isNotEmpty()
                if (!exists && !tracked) return c.fail("fatal: pathspec '$p' did not match any files")
            }
            for (p in paths) {
                if (!updateOnly) g.add().addFilepattern(p).call()
                g.add().addFilepattern(p).setUpdate(true).call()
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ rm
    fun rm(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--cached", "-r", "-f", "--force", "-q", "--quiet", "--ignore-unmatch"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val given = o.pos + o.afterDD
            if (given.isEmpty()) return c.fail("usage: git rm [--cached] [-r] <path>...")
            val paths = c.repoPaths(repo, given) ?: return 1
            val dc = repo.readDirCache()
            for (p in paths) {
                val isDir = File(repo.workTree, p).isDirectory
                val tracked = dc.findEntry(p) >= 0 || dc.getEntriesWithin(p).isNotEmpty()
                if (!tracked) {
                    if (o.has("--ignore-unmatch")) continue
                    return c.fail("fatal: pathspec '$p' did not match any tracked files")
                }
                if (isDir && !o.has("-r")) return c.fail("fatal: '$p' is a directory — not removing without -r")
            }
            for (p in paths) {
                g.rm().addFilepattern(p).setCached(o.has("--cached")).call()
                if (!o.has("-q", "--quiet")) c.out("rm '$p'")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ mv
    fun mv(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("-f", "-v", "--force", "--verbose"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val given = o.pos + o.afterDD
            if (given.size < 2) return c.fail("usage: git mv <source>... <destination>")
            val dstArg = given.last()
            val srcArgs = given.dropLast(1)
            val dstFile = ShellPaths.resolve(c.root, c.cwd, dstArg) ?: return c.fail("fatal: '$dstArg' is outside the workspace")
            val dstIsDir = dstFile.isDirectory
            if (srcArgs.size > 1 && !dstIsDir) return c.fail("fatal: destination must be a directory for multiple sources")
            val dc = repo.readDirCache()
            for (s in srcArgs) {
                val sp = c.repoPath(repo, s) ?: return 1
                val sf = File(repo.workTree, sp)
                if (!sf.exists()) return c.fail("fatal: '$s' does not exist")
                if (dc.findEntry(sp) < 0 && dc.getEntriesWithin(sp).isEmpty()) {
                    return c.fail("fatal: '$s' is not under version control")
                }
                val target = if (dstIsDir) File(dstFile, sf.name) else dstFile
                if (target.exists() && !o.has("-f", "--force")) return c.fail("fatal: destination '${target.name}' already exists (use -f to overwrite)")
                val parent = target.parentFile
                if (parent == null || !parent.isDirectory) return c.fail("fatal: destination directory does not exist")
                if (!ShellPaths.isInside(c.root, parent)) return c.fail("fatal: destination is outside the workspace")
                val dp = ShellPaths.fileToRepoPath(c.root, repo.workTree, target) ?: return c.fail("fatal: destination is outside the repository")
                if (target.exists()) com.hemel.lenspilot.tools.SafeFs.deleteTree(target)
                Files.move(sf.toPath(), target.toPath())
                g.rm().addFilepattern(sp).setCached(true).call()
                g.add().addFilepattern(dp).call()
                if (o.has("-v", "--verbose")) c.out("Renaming $sp to $dp")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ commit
    fun commit(c: GitCtx, a: List<String>): Int {
        val o = Opts(
            a, setOf("-m", "--message", "--author"),
            setOf("-a", "--all", "--amend", "--allow-empty", "--no-edit", "-q", "--quiet")
        )
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            if (!c.needIdentity(repo)) return 128
            val amend = o.has("--amend")
            val all = o.has("-a", "--all")
            val msgs = o.all("-m", "--message")
            val message: String = if (msgs.isEmpty()) {
                if (!amend) return c.fail("Provide a commit message: git commit -m \"message\" (this shell has no editor)")
                val head = repo.resolve(Constants.HEAD) ?: return c.fail("fatal: no commit to amend")
                c.commitOf(repo, head).fullMessage
            } else {
                msgs.joinToString("\n\n")
            }
            val allowEmpty = o.has("--allow-empty")
            if (!allowEmpty && !amend && repo.repositoryState == RepositoryState.SAFE) {
                val st = g.status().call()
                val staged = st.added.isNotEmpty() || st.changed.isNotEmpty() || st.removed.isNotEmpty()
                val unstagedTracked = st.modified.isNotEmpty() || st.missing.isNotEmpty()
                if (!staged && !(all && unstagedTracked)) {
                    c.out("nothing to commit" + if (unstagedTracked || st.untracked.isNotEmpty()) " (use git add or pass -a)" else ", working tree clean")
                    return 1
                }
            }
            val cmd = g.commit().setMessage(message).setAll(all).setAmend(amend)
            if (allowEmpty) cmd.setAllowEmpty(true)
            o.value("--author")?.let { au ->
                val m = Regex("^\\s*(.*?)\\s*<(.*)>\\s*$").find(au)
                    ?: return c.fail("fatal: --author format: \"Name <email>\"")
                cmd.setAuthor(m.groupValues[1], m.groupValues[2])
            }
            val rc = cmd.call()
            if (!o.has("-q", "--quiet")) {
                val br = repo.branch
                c.out("[$br ${c.shortId(rc)}] ${rc.shortMessage}")
            }
            return 0
        } finally {
            repo.close()
        }
    }

    // ------------------------------------------------------------------ config
    private fun splitKey(key: String): Triple<String, String?, String>? {
        val parts = key.split('.')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return null
        val sub = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString(".") else null
        return Triple(parts[0], sub, parts.last())
    }

    fun config(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("--global", "--local", "--list", "-l", "--get", "--unset"))
        if (c.badOpts(o)) return 129
        val repo = if (o.has("--global")) null else c.open2()
        try {
            val cfg: StoredConfig = if (repo != null) repo.config else SystemReader.getInstance().getUserConfig()
            if (o.has("--list", "-l")) {
                for (sec in cfg.sections.sorted()) {
                    for (n in cfg.getNames(sec, true).sorted()) {
                        for (v in cfg.getStringList(sec, null, n)) c.out("$sec.$n=$v")
                    }
                    for (sub in cfg.getSubsections(sec).sorted()) {
                        for (n in cfg.getNames(sec, sub, true).sorted()) {
                            for (v in cfg.getStringList(sec, sub, n)) c.out("$sec.$sub.$n=$v")
                        }
                    }
                }
                return 0
            }
            val key = o.pos.getOrNull(0) ?: return c.fail("usage: git config [--global] <key> [<value>] | --get <key> | --unset <key> | --list")
            val k = splitKey(key) ?: return c.fail("error: invalid key '$key' (format: section.name)")
            if (o.has("--unset")) {
                cfg.unset(k.first, k.second, k.third)
                cfg.save()
                return 0
            }
            val value = o.pos.getOrNull(1)
            if (value == null || o.has("--get")) {
                val v = cfg.getString(k.first, k.second, k.third) ?: return 1
                c.out(v)
                return 0
            }
            cfg.setString(k.first, k.second, k.third, value)
            cfg.save()
            return 0
        } finally {
            repo?.close()
        }
    }

    /** রিপো থাকলে খোলে, না থাকলে নীরবে null (config-এর জন্য — তখন গ্লোবাল কনফিগ)। */
    private fun GitCtx.open2(): org.eclipse.jgit.lib.Repository? {
        val b = org.eclipse.jgit.storage.file.FileRepositoryBuilder().findGitDir(cwd)
        val gd = b.gitDir
        if (gd == null || !ShellPaths.isInside(root, gd)) return null
        return b.build()
    }

    // ------------------------------------------------------------------ remote
    fun remote(c: GitCtx, a: List<String>): Int {
        val o = Opts(a, emptySet(), setOf("-v", "--verbose"))
        if (c.badOpts(o)) return 129
        val repo = c.open() ?: return 128
        try {
            val g = c.git(repo)
            val sub = o.pos.firstOrNull()
            val verbose = o.has("-v", "--verbose")
            if (sub == null) {
                for (r in g.remoteList().call().sortedBy { it.name }) {
                    if (verbose) {
                        for (u in r.getURIs()) c.out("${r.name}\t$u (fetch)")
                        val pushes = if (r.getPushURIs().isNotEmpty()) r.getPushURIs() else r.getURIs()
                        for (u in pushes) c.out("${r.name}\t$u (push)")
                    } else {
                        c.out(r.name)
                    }
                }
                return 0
            }
            when (sub) {
                "add" -> {
                    val name = o.pos.getOrNull(1)
                    val url = o.pos.getOrNull(2)
                    if (name == null || url == null) return c.fail("usage: git remote add <name> <url>")
                    if (isSsh(url)) return c.fail(SSH_MSG)
                    if (g.remoteList().call().any { it.name == name }) return c.fail("error: remote '$name' already exists")
                    g.remoteAdd().setName(name).setUri(URIish(url)).call()
                    return 0
                }
                "remove", "rm" -> {
                    val name = o.pos.getOrNull(1) ?: return c.fail("usage: git remote remove <name>")
                    if (g.remoteList().call().none { it.name == name }) return c.fail("error: no such remote: '$name'")
                    val cfg = repo.config
                    cfg.unsetSection("remote", name)
                    cfg.save()
                    return 0
                }
                "set-url" -> {
                    val name = o.pos.getOrNull(1)
                    val url = o.pos.getOrNull(2)
                    if (name == null || url == null) return c.fail("usage: git remote set-url <name> <new-url>")
                    if (isSsh(url)) return c.fail(SSH_MSG)
                    val cfg = repo.config
                    val rc = RemoteConfig(cfg, name)
                    if (rc.getURIs().isEmpty()) return c.fail("error: no such remote: '$name'")
                    for (u in ArrayList(rc.getURIs())) rc.removeURI(u)
                    rc.addURI(URIish(url))
                    rc.update(cfg)
                    cfg.save()
                    return 0
                }
                "get-url" -> {
                    val name = o.pos.getOrNull(1) ?: return c.fail("usage: git remote get-url <name>")
                    val u = repo.config.getString("remote", name, "url") ?: return c.fail("error: no such remote: '$name'")
                    c.out(u)
                    return 0
                }
                else -> return c.fail("git remote $sub: not available in JGit or not supported in this shell (supported: -v, add, remove, set-url, get-url)")
            }
        } finally {
            repo.close()
        }
    }
}
