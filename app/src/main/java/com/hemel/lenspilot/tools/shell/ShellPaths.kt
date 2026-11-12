package com.hemel.lenspilot.tools.shell

import java.io.File
import java.io.IOException

/**
 * শেলের পাথ-সুরক্ষা: cwd ও সব আর্গুমেন্ট সেশন-workspace-এর ভেতরে আটকানো।
 * canonical-path চেক হয় (symlink দিয়ে বেরোনো যায় না)।
 */
internal object ShellPaths {

    fun canonical(f: File): File? = try {
        f.canonicalFile
    } catch (e: IOException) {
        null
    }

    /** f কি root-এর ভেতরে (বা root নিজেই)? — canonical তুলনা। */
    fun isInside(root: File, f: File): Boolean {
        val rc = canonical(root) ?: return false
        val fc = canonical(f) ?: return false
        return fc.path == rc.path || fc.path.startsWith(rc.path + File.separator)
    }

    /**
     * ইউজারের দেওয়া পাথ → workspace-এর ভেতরের canonical File; বাইরে গেলে null।
     * "/x" ও "~/x" মানে workspace-রুট থেকে; "~" = রুট।
     */
    fun resolve(root: File, cwd: File, arg: String): File? {
        val f = when {
            arg == "~" || arg == "/" -> root
            arg.startsWith("~/") -> File(root, arg.substring(2))
            arg.startsWith("/") -> File(root, arg.trimStart('/'))
            else -> File(cwd, arg)
        }
        return if (isInside(root, f)) canonical(f) else null
    }

    /** প্রম্পটে দেখানোর পাথ: "~" বা "~/a/b"। */
    fun display(root: File, cwd: File): String {
        val rel = cwd.path.removePrefix(root.path)
        return if (rel.isEmpty()) "~" else "~" + rel.replace(File.separatorChar, '/')
    }

    /**
     * ইউজারের পাথ → রিপোর workTree-এর সাপেক্ষে '/'-দিয়ে-লেখা পাথ ("." = workTree নিজে)।
     * workspace-এর বাইরে গেলে null। (symlink-ডিরেক্টরি দিয়ে বেরোনোও আটকানো)
     */
    fun toRepoPath(root: File, workTree: File, cwd: File, arg: String): String? {
        val abs = if (arg.startsWith("/") || arg.startsWith("~")) resolveLexical(root, arg) else File(cwd, arg)
        return fileToRepoPath(root, workTree, abs)
    }

    /** পরম (absolute) File → রিপো-পাথ। */
    fun fileToRepoPath(root: File, workTree: File, file: File): String? {
        val abs = file.toPath().normalize().toFile()
        val parent = abs.parentFile ?: return null
        if (!isInside(root, parent) && !isInside(root, abs)) return null
        val wt = canonical(workTree) ?: return null
        val absC = File(canonical(parent) ?: return null, abs.name)
        val wtPath = wt.path
        if (absC.path == wtPath) return "."
        if (!absC.path.startsWith(wtPath + File.separator)) return null
        return absC.path.substring(wtPath.length + 1).replace(File.separatorChar, '/')
    }

    private fun resolveLexical(root: File, arg: String): File = when {
        arg == "~" || arg == "/" -> root
        arg.startsWith("~/") -> File(root, arg.substring(2))
        else -> File(root, arg.trimStart('/'))
    }

    fun hasGlob(s: String): Boolean = s.any { it == '*' || it == '?' }

    /** সরল glob (*, ?) — cwd-র নিচের ফাইলে মেলায় (.git বাদ)। রিপো-পাথের তালিকা ফেরত দেয়। */
    fun expandGlob(root: File, workTree: File, cwd: File, pattern: String): List<String> {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append("$")
        val re = Regex(sb.toString())
        val out = ArrayList<String>()
        var seen = 0
        val walk = cwd.walkTopDown().onEnter { it.name != ".git" }
        for (f in walk) {
            if (++seen > 20000) break
            if (!f.isFile) continue
            val rel = f.relativeTo(cwd).path.replace(File.separatorChar, '/')
            if (re.matches(rel)) {
                val rp = toRepoPath(root, workTree, cwd, rel) ?: continue
                out.add(rp)
            }
        }
        return out
    }
}
