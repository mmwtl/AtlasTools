package com.mmwtl.atlascodecfix

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

/**
 * Schedules unique background [BtFallbackJobService] work for Variant 1 Bluetooth auto-connect
 * recovery on boot, screen wakeup, or CarPlay disconnect/error.
 */
object BtFallbackScheduler {
    fun onBootEvent(context: Context, action: String?): Boolean {
        val app = context.applicationContext as CodecFixApp
        if (!app.prefs.autoBtFallbackEnabled) {
            cancel(context)
            return true
        }
        return when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                app.btFallbackLoopGuard.onBoot()
                schedule(context, BtFallbackTrigger.BOOT, resetRetries = true)
            }
            Intent.ACTION_USER_UNLOCKED -> ensureBootScheduled(context)
            else -> true
        }
    }

    fun ensureBootScheduled(context: Context): Boolean {
        val app = context.applicationContext as CodecFixApp
        if (!app.prefs.autoBtFallbackEnabled) {
            cancel(context)
            return true
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return true
        return schedule(context, BtFallbackTrigger.BOOT)
    }

    fun schedule(
        context: Context,
        trigger: BtFallbackTrigger,
        resetRetries: Boolean = false
    ): Boolean {
        val app = context.applicationContext as CodecFixApp
        if (!app.prefs.autoBtFallbackEnabled) {
            cancel(context)
            return true
        }
        if (!app.btFallbackLoopGuard.canSchedule(trigger)) {
            Log.d(TAG, "BT fallback schedule suppressed by loop guard: trigger=${trigger.wireName}")
            return true
        }
        if (resetRetries) app.prefs.btFallbackRetryCount = 0

        val extras = PersistableBundle().apply {
            putString(EXTRA_TRIGGER, trigger.wireName)
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, BtFallbackJobService::class.java)
        )
            .setMinimumLatency(trigger.delayMs.coerceAtLeast(0L))
            .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
            .setPersisted(false)
            .build()

        val scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        if (!scheduled) {
            Log.e(TAG, "Unable to schedule BT fallback job for trigger=${trigger.wireName}")
        } else {
            Log.i(TAG, "Scheduled BT fallback job: trigger=${trigger.wireName}, delayMs=${trigger.delayMs}")
        }
        return scheduled
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        val app = context.applicationContext as CodecFixApp
        app.prefs.btFallbackRetryCount = 0
    }

    internal const val EXTRA_TRIGGER = "bt_fallback_trigger"
    private const val TAG = "AtlasToolsBt"
    private const val JOB_ID = 0x41544254
    private const val RETRY_BACKOFF_MS = 15_000L
}
