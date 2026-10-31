package com.hemel.lenspilot.tools.shell

import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File

/** কমান্ড শেষের ফল। cwd = কমান্ড চলার পরের (cd করলে বদলানো) ডিরেক্টরি। */
data class ExitResult(val code: Int, val cwd: File, val cancelled: Boolean = false) {
    val ok: Boolean get() = code == 0
}

/**
 * ইঞ্জিনের আউটপুট যেখানে যায় (UI, Runner বা AI এজেন্ট — সবাই একই ইন্টারফেস)।
 * সব মেথড যেকোনো থ্রেড থেকে ডাকা হতে পারে — বাস্তবায়ন থ্রেড-সেফ হতে হবে।
 * out/err: ঠিক এক লাইন (শেষে নতুন-লাইন নেই)।
 */
interface ShellSink {
    fun out(text: String)
    fun err(text: String)

    /** লাইভ প্রগ্রেস লাইন (জায়গায় বদলে যায়, স্ক্রলব্যাকে জমে না)। খালি টেক্সট = লাইন মুছে ফেলা। */
    fun progress(text: String)

    /** স্ক্রিন পরিষ্কার (clear কমান্ড)। */
    fun clear()
}

/** আউটপুট দেখানোর/বেরোনোর আগে এর ভেতর দিয়ে যায়। এখন identity; ধাপ ৩-এ redactor বসবে। */
interface OutputFilter {
    fun filter(text: String): String

    companion object {
        val Identity: OutputFilter = object : OutputFilter {
            override fun filter(text: String): String = text
        }
    }
}

/** উচ্চ-ঝুঁকির কমান্ডের অনুমোদন চাওয়া (ধাপ ৩-এ PermissionGate এটা বাস্তবায়ন করবে)। */
data class ApprovalRequest(
    val workspace: String,
    val command: String,
    val action: String,
    val detail: String
)

interface ApprovalHook {
    /** true = চালাতে দাও। টাইমআউট/অস্বীকার = false। */
    suspend fun approve(request: ApprovalRequest): Boolean
}

/** এখনকার ডিফল্ট: সবসময় allow (ধাপ ৩-এ PermissionGate বসলে বদলাবে)। */
object AllowAllApproval : ApprovalHook {
    override suspend fun approve(request: ApprovalRequest): Boolean = true
}

/** Git-এর লগইন তথ্য। টোকেন কখনো প্লেইন টেক্সটে বেরোয় না — শুধু CredentialsProvider-এর ভেতর দিয়ে JGit-এ যায়। */
interface GitAuthProvider {
    fun isLoggedIn(): Boolean
    fun credentials(): CredentialsProvider?

    /** লগইন ডায়ালগ খোলার অনুরোধ (UI থাকলে খোলে)। */
    fun requestLogin()
}

/** URL-এ বসানো ইউজার/টোকেন (https://user:tok@host) আউটপুট থেকে মুছে ফেলা। */
internal object ShellText {
    private val URL_CREDS = Regex("(https?://)[^/@\\s]+@")

    fun scrub(s: String): String = URL_CREDS.replace(s, "\$1***@")
}
