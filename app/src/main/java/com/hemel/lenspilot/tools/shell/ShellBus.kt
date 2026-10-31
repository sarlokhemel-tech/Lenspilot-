package com.hemel.lenspilot.tools.shell

import android.content.Context
import com.hemel.lenspilot.tools.ToolsPaths
import org.eclipse.jgit.transport.CredentialsProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * শেলের কেন্দ্রীয় সেতু — Activity, Runner ও AI এজেন্ট একই সেশনে একই ইঞ্জিনে কথা বলে।
 * ধাপ ৩-এ [outputFilter] (redactor) ও [approvalHook] (PermissionGate) এখানেই বসবে।
 */
object ShellBus {
    @Volatile
    var outputFilter: OutputFilter = object : OutputFilter {
        // সেশন ৩: শেলের সব আউটপুট স্ক্রিনে/স্ক্রলব্যাকে যাওয়ার আগে ভল্টের মান মাস্ক হয়
        override fun filter(text: String): String = com.hemel.lenspilot.security.Redactor.redact(text)
    }

    @Volatile
    var approvalHook: ApprovalHook = com.hemel.lenspilot.tools.perm.ShellApprovalGate

    /** UI খোলা না থাকলে লগইন দরকার হলে কে জানবে — Runner এটা সেট করতে পারে। */
    @Volatile
    var onLoginNeeded: ((String) -> Unit)? = null

    private const val GLOBAL = "_global"
    private val sessions = ConcurrentHashMap<String, ShellSession>()

    /** sessionId null হলে গ্লোবাল workspace। অবৈধ id হলে IllegalArgumentException। */
    fun session(ctx: Context, sessionId: String?): ShellSession {
        val key = sessionId ?: GLOBAL
        sessions[key]?.let { return it }
        return synchronized(this) {
            sessions[key]?.let { return it }
            val app = ctx.applicationContext
            GitEnv.init(app)
            val dir = if (sessionId == null) {
                ToolsPaths.globalWorkspace(app)
            } else {
                require(ToolsPaths.isValidId(sessionId)) { "অবৈধ sessionId" }
                ToolsPaths.workspaceDir(app, sessionId).also { it.mkdirs() }
            }
            val holder = arrayOfNulls<ShellSession>(1)
            val auth = object : GitAuthProvider {
                override fun isLoggedIn(): Boolean = GitHubAuth.isLoggedIn(app)
                override fun credentials(): CredentialsProvider? = GitHubAuth.credentialsProvider(app)
                override fun requestLogin() {
                    holder[0]?.requestLogin()
                }
            }
            val engine = ShellEngine(dir, auth, DelegatingApproval, key)
            val s = ShellSession(key, engine, app)
            holder[0] = s
            sessions[key] = s
            s
        }
    }

    fun get(sessionId: String?): ShellSession? = sessions[sessionId ?: GLOBAL]

    /**
     * AI/Runner-এর জন্য: কমান্ড প্রম্পটে অক্ষর-অক্ষর করে টাইপ হয় (~25ms), তারপর লাইভ আউটপুটসহ চলে।
     * চলার সময় ইউজারের ইনপুট লক ("ELORA কাজ করছে")। UI না থাকলে অ্যানিমেশন ছাড়াই চলে।
     */
    suspend fun typeAndRun(ctx: Context, sessionId: String?, cmd: String): ExitResult =
        session(ctx, sessionId).runAi(cmd)

    private object DelegatingApproval : ApprovalHook {
        override suspend fun approve(request: ApprovalRequest): Boolean = approvalHook.approve(request)
    }
}
