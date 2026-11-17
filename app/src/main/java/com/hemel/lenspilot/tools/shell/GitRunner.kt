package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidConfigurationException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.errors.TransportException

/** `git <সাবকমান্ড>` ডিসপ্যাচার + ত্রুটি-বার্তা। */
internal object GitRunner {

    const val VERSION_LINE = "git version 5.13.3 (JGit 5.13.3.202401111512-r, Android shell)"

    private val USAGE: Map<String, String> = mapOf(
        "init" to "git init [-b <branch>] [<dir>]",
        "clone" to "git clone [--depth N] [-b <branch>] <url> [<dir>]",
        "status" to "git status [-s]",
        "add" to "git add <path>... | . | -A | -u",
        "rm" to "git rm [--cached] [-r] <path>...",
        "mv" to "git mv <source> <destination>",
        "commit" to "git commit [-a] [--amend] [--allow-empty] -m <message>",
        "push" to "git push [-u] [--force | --force-with-lease] [--tags] [--delete] [<remote> [<refspec>...]]",
        "pull" to "git pull [--rebase] [--ff-only] [<remote> [<branch>]]",
        "fetch" to "git fetch [--all] [--prune] [--tags] [--depth N] [<remote>]",
        "merge" to "git merge [--no-ff|--ff-only|--squash] [-m <message>] <branch> | --abort",
        "rebase" to "git rebase <upstream> [<branch>] | --continue | --abort | --skip",
        "branch" to "git branch [-a|-r] [-v] | <name> [<start>] | -d/-D <name> | -m [<old>] <new> | -u <remote/branch>",
        "checkout" to "git checkout [-f] <branch|commit> | -b <new> [<start>] | [<rev>] -- <path>",
        "switch" to "git switch [-f] <branch> | -c <new> [<start>] | --detach <commit>",
        "restore" to "git restore [--staged] [--worktree] [--source=<rev>] <path>...",
        "reset" to "git reset [--soft|--mixed|--hard] [<commit>] | [<commit>] -- <path>",
        "revert" to "git revert <commit>...",
        "cherry-pick" to "git cherry-pick [-n] <commit>...",
        "stash" to "git stash [push [-u] [-m <message>]] | list | pop | apply [stash@{n}] | drop [stash@{n}] | clear",
        "tag" to "git tag [-l [<pattern>]] | [-a] [-m <message>] [-f] <name> [<commit>] | -d <name>",
        "log" to "git log [--oneline] [--all] [-n N] [<rev>|<a>..<b>] [-- <path>]",
        "show" to "git show [<commit>|<rev>:<path>]",
        "diff" to "git diff [--cached] [--name-only|--name-status] [<rev> [<rev>]] [-- <path>]",
        "remote" to "git remote [-v] | add <name> <url> | remove <name> | set-url <name> <url> | get-url <name>",
        "config" to "git config [--global] <key> [<value>] | --get <key> | --unset <key> | --list",
        "clean" to "git clean (-n | -f) [-d] [-x] [<path>...]"
    )

    /** push-এ জোর (force) থাকলে অনুমোদন চাইতে হবে — না থাকলে null। */
    fun approvalFor(args: List<String>, workspace: String, line: String): ApprovalRequest? {
        // সেশন ৩: নিয়ম-তালিকা (RiskClassifier) ঠিক করে — push, ফোর্স, scratch-এর বাইরে মোছা, ইত্যাদি
        val seg = "git " + args.joinToString(" ")
        val v = com.hemel.lenspilot.tools.perm.RiskClassifier.classify(
            com.hemel.lenspilot.tools.perm.RiskInput("shell", "run", seg)
        )
        if (v.risk == com.hemel.lenspilot.tools.perm.Risk.LOW) return null
        return ApprovalRequest(
            workspace = workspace,
            command = ShellText.scrub(seg),
            action = "git " + (args.firstOrNull() ?: ""),
            detail = v.reason + "\n" + ShellText.scrub(line)
        )
    }

    fun execute(c: GitCtx, args: List<String>): Int =
        try {
            run(c, args)
        } catch (t: Throwable) {
            describe(c, t)
        }

    private fun run(c: GitCtx, args: List<String>): Int {
        if (args.isEmpty()) {
            c.out("usage: git <command> [<args>]   (see 'git help')")
            return 1
        }
        val sub = args[0]
        val rest = args.drop(1)
        return when (sub) {
            "--version", "version" -> { c.out(VERSION_LINE); 0 }
            "help", "--help", "-h" -> help(c, rest)
            "init" -> GitBasic.init(c, rest)
            "clone" -> GitBasic.clone(c, rest)
            "status" -> GitBasic.status(c, rest)
            "add" -> GitBasic.add(c, rest)
            "rm" -> GitBasic.rm(c, rest)
            "mv" -> GitBasic.mv(c, rest)
            "commit" -> GitBasic.commit(c, rest)
            "config" -> GitBasic.config(c, rest)
            "remote" -> GitBasic.remote(c, rest)
            "push" -> GitRemoteOps.push(c, rest)
            "pull" -> GitRemoteOps.pull(c, rest)
            "fetch" -> GitRemoteOps.fetch(c, rest)
            "merge" -> GitBranch.merge(c, rest)
            "rebase" -> GitBranch.rebase(c, rest)
            "branch" -> GitBranch.branch(c, rest)
            "checkout" -> GitBranch.checkout(c, rest, false)
            "switch" -> GitBranch.checkout(c, rest, true)
            "restore" -> GitBranch.restore(c, rest)
            "reset" -> GitHistory.reset(c, rest)
            "revert" -> GitHistory.revert(c, rest)
            "cherry-pick" -> GitHistory.cherryPick(c, rest)
            "stash" -> GitHistory.stash(c, rest)
            "tag" -> GitHistory.tag(c, rest)
            "log" -> GitHistory.log(c, rest)
            "show" -> GitHistory.show(c, rest)
            "diff" -> GitHistory.diff(c, rest)
            "clean" -> GitHistory.clean(c, rest)
            else -> c.fail("git: '$sub' is not supported in this shell — see 'git help'")
        }
    }

    private fun help(c: GitCtx, rest: List<String>): Int {
        val topic = rest.firstOrNull()
        if (topic == null) {
            for (l in ShellBuiltins.HELP_LINES) c.out(l)
            return 0
        }
        val u = USAGE[topic] ?: return c.fail("git help: '$topic' is not available in this shell")
        c.out("usage: $u")
        return 0
    }

    private fun describe(c: GitCtx, t: Throwable): Int {
        if (c.isCancelled()) {
            c.err("^C — cancelled")
            return 130
        }
        val msg = t.message ?: t.javaClass.simpleName
        when (t) {
            is CheckoutConflictException -> {
                c.err("error: local changes would be overwritten — commit or stash first")
                for (p in t.conflictingPaths) c.err("\t$p")
            }
            is NoHeadException -> c.err("fatal: no commits yet")
            is NotMergedException -> c.err("error: branch is not fully merged — use -D to force delete")
            is InvalidConfigurationException -> {
                c.err("fatal: $msg")
                c.err("hint: set the upstream — git branch -u origin/<branch>  or  git pull origin <branch>")
            }
            is TransportException -> {
                c.err("fatal: $msg")
                if (!c.auth.isLoggedIn()) {
                    c.err("hint: for a private repo you need to log in to GitHub — press the [GitHub] button above.")
                }
            }
            is LinkageError -> c.err("fatal: Android compatibility error (JGit): $msg")
            is OutOfMemoryError -> c.err("fatal: out of memory — for large repos, clone with --depth 1")
            else -> c.err("fatal: $msg")
        }
        return 1
    }
}
