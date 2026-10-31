package com.hemel.lenspilot.tools.runner

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager ফলব্যাক — exact অ্যালার্মের অনুমতি না থাকলে এটাই সময়মতো (কিছুটা দেরিতে হতে পারে) জাগায়। */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = inputData.getString(Scheduler.EXTRA_ID) ?: return Result.failure()
        return try {
            Scheduler.onFired(applicationContext, id)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
