package com.hemel.lenspilot.tools.shell

import android.content.Context
import org.eclipse.jgit.storage.file.WindowCacheConfig
import java.io.File

/**
 * Android-এ JGit-কে নিরাপদে চালু করা — প্রথম ব্যবহারের আগে একবার।
 *
 *  * user.home ও XDG_CONFIG_HOME অ্যাপের নিজস্ব ফোল্ডারে: ইউজার-গিট-কনফিগ (--global) এখানেই পড়া/লেখা হয়।
 *  * সিস্টেম git কনফিগ পড়ার চেষ্টা নেই: Android-এ git বাইনারি নেই, তাই JGit-এর সিস্টেম-কনফিগ খালি থাকে।
 *    (এই কারণে কাস্টম SystemReader লাগেনি — ভুল সাবক্লাসে বিল্ড ভাঙার ঝুঁকি এড়ানো হলো।)
 *  * মেমরি সীমা: বড় ফাইল স্ট্রিম করে পড়া হয় যাতে OOM না হয়।
 *
 * NOTE: JGit XDG_CONFIG_HOME পড়ে System.getenv() থেকে (প্রোপার্টি নয়); পাওয়া না গেলে $user.home/.config নেয় —
 * দুটোই অ্যাপ-প্রাইভেট ফোল্ডারে পড়ে, তাই প্রোপার্টি সেট রাখা নিরীহ ও যথেষ্ট।
 */
object GitEnv {
    @Volatile
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val home = homeDir(ctx)
        System.setProperty("user.home", home.absolutePath)
        System.setProperty("XDG_CONFIG_HOME", File(home, ".config").apply { mkdirs() }.absolutePath)
        try {
            val cfg = WindowCacheConfig()
            cfg.setPackedGitLimit(16L * 1024 * 1024)
            cfg.setDeltaBaseCacheLimit(4 * 1024 * 1024)
            cfg.setStreamFileThreshold(16 * 1024 * 1024)
            cfg.install()
        } catch (t: Throwable) {
            android.util.Log.w("GitEnv", "WindowCache কনফিগ হয়নি", t)
        }
        ready = true
    }

    fun homeDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "tools/githome").apply { mkdirs() }
}
