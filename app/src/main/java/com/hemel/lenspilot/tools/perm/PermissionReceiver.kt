package com.hemel.lenspilot.tools.perm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * নোটিফিকেশনের [অনুমোদন]/[অস্বীকার] বাটন। PendingIntent immutable, রিসিভার exported=false।
 * প্রসেস মরে থাকলেও এই ব্রডকাস্টে প্রসেস জাগে; পেন্ডিং অনুরোধ আগে থেকে ফাইল থেকে ফেরানো হয় ([PermissionGate.init])।
 */
class PermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PermissionGate.ACTION_DECIDE) return
        val id = intent.getStringExtra(PermissionGate.EXTRA_ID) ?: return
        val approve = intent.getBooleanExtra(PermissionGate.EXTRA_APPROVE, false)
        PermissionGate.resolve(id, approve, false)
    }
}
