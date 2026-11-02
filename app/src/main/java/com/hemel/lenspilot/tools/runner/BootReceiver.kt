package com.hemel.lenspilot.tools.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * রিবুট / অ্যাপ-আপডেট / সঠিক-অ্যালার্ম অনুমতি বদলের পর: নির্ধারিত সময়গুলো আবার arm ও সংরক্ষিত watcher ফেরানো।
 * অনুমতি-বদলের ঘটনায় (exact অনুমতি পেলে) WORK-এ থাকা সময়গুলো exact-এ উঠে যায়।
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { Scheduler.rearmAll(context) } catch (e: Exception) { }
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { Runner.rearm(context) } catch (e: Exception) { }
        }
    }
}
