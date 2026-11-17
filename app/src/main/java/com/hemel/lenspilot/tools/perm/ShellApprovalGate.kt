package com.hemel.lenspilot.tools.perm

import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.shell.ApprovalHook
import com.hemel.lenspilot.tools.shell.ApprovalRequest

/** ShellEngine-এর [ApprovalHook] — RiskClassifier দিয়ে মাপে, LOW না হলে PermissionGate-এ জিজ্ঞেস করে। */
object ShellApprovalGate : ApprovalHook {
    private const val TIMEOUT_SEC = 120

    override suspend fun approve(request: ApprovalRequest): Boolean {
        val v = RiskClassifier.classify(RiskInput("shell", "run", request.command))
        if (v.risk == Risk.LOW) return true
        val sid = if (request.workspace != "_global" && ToolsPaths.isValidId(request.workspace)) request.workspace else null
        val detail = (request.detail.ifEmpty { v.reason }) + "\n" + request.command
        return PermissionGate.request(v.risk, request.action, detail, TIMEOUT_SEC, sid) == Decision.APPROVED
    }
}
