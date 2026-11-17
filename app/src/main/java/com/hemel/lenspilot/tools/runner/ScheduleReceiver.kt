package com.hemel.lenspilot.tools.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager-এর exact অ্যালার্ম এখানে আসে (immutable PendingIntent, exported=false)। */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        try { Scheduler.onFired(context, id) } catch (e: Exception) { }
    }
}
