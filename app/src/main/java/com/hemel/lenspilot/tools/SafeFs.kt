package com.hemel.lenspilot.tools

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * সেশন ৮ (হার্ডেনিং): symlink-নিরাপদ মোছা।
 * Kotlin-এর File.deleteRecursively() ফোল্ডার-symlink ফলো করে — ক্লোন-করা রিপোতে symlink
 * থাকলে workspace-এর বাইরের (বা অন্য সেশনের) ফাইল মুছে যেতে পারত। এখানে symlink নিজেকেই
 * মোছা হয়, তার ভেতরে ঢোকা হয় না।
 */
object SafeFs {
    private const val MAX_DEPTH = 64

    /** f (ফাইল/ফোল্ডার/symlink) মোছে। সব মুছলে true; আগে থেকে না থাকলেও true। */
    fun deleteTree(f: File): Boolean = del(f, 0)

    private fun del(f: File, depth: Int): Boolean {
        val p = f.toPath()
        return try {
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
            var ok = true
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                if (depth >= MAX_DEPTH) return false
                val kids = f.listFiles()
                if (kids != null) for (k in kids) if (!del(k, depth + 1)) ok = false
            }
            Files.deleteIfExists(p)
            ok
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
