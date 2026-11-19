package com.mmwtl.atlascodecfix

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BtFallbackJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runGuard = AutoApplyRunGuard()
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runToken = runGuard.start()
        val trigger = BtFallbackTrigger.fromWireName(
            params.extras.getString(BtFallbackScheduler.EXTRA_TRIGGER)
        ) ?: BtFallbackTrigger.BOOT
        runningJob?.cancel()
        runningJob = scope.launch {
            val shouldRetry = try {
                performBtFallback(runToken, trigger)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "BT fallback job failed", t)
                if (runGuard.isCurrent(runToken)) {
                    retryOrStop(trigger, t.message ?: t.javaClass.simpleName)
                } else {
                    false
                }
            }
            if (runGuard.isCurrent(runToken)) {
                jobFinished(params, shouldRetry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runGuard.invalidate()
        runningJob?.cancel()
        runningJob = null
        val prefs = currentApp().prefs
        if (!prefs.autoBtFallbackEnabled) {
            prefs.btFallbackRetryCount = 0
            return false
        }
        val nextAttempt = BtFallbackRetryPolicy.nextRetryCount(prefs.btFallbackRetryCount)
        return if (nextAttempt == null) {
            prefs.btFallbackRetryCount = 0
            false
        } else {
            prefs.btFallbackRetryCount = nextAttempt
            true
        }
    }

    override fun onDestroy() {
        runGuard.invalidate()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun performBtFallback(
        runToken: Long,
        trigger: BtFallbackTrigger
    ): Boolean {
        val app = currentApp()
        val prefs = app.prefs
        if (!prefs.autoBtFallbackEnabled) {
            prefs.btFallbackRetryCount = 0
            return false
        }
        if (!app.btFallbackLoopGuard.canExecuteNow()) {
            Log.d(TAG, "BT fallback skipped by loop guard: trigger=${trigger.wireName}")
            prefs.btFallbackRetryCount = 0
            return false
        }

        if (!app.adbClient.connectForAutoApply()) {
            if (!runGuard.isCurrent(runToken)) return false
            val message = (app.adbClient.connectionState.value as? AdbConnectionState.Error)
                ?.message
                ?: getString(R.string.bt_fallback_connect_failed)
            return retryOrStop(trigger, message)
        }

        val result = app.btFallbackRepository.runFallback(trigger)
        if (!runGuard.isCurrent(runToken)) return false

        app.btFallbackLoopGuard.recordExecution(result.outcome)
        app.recordBtFallbackResult(result)

        if (result.retryable) {
            val detail = result.output.trim().takeLast(ERROR_TEXT_LIMIT)
                .ifBlank { getString(R.string.bt_fallback_outcome_failed) }
            return retryOrStop(trigger, detail)
        }

        prefs.btFallbackRetryCount = 0
        if (result.outcome == BtFallbackOutcome.FAILED) {
            val message = result.output.trim().takeLast(ERROR_TEXT_LIMIT)
                .ifBlank { getString(R.string.bt_fallback_outcome_failed) }
            Log.w(TAG, "BT fallback failed (${trigger.wireName}): $message")
            app.errorNotifier.notify(getString(R.string.error_title_bt_fallback), message)
        } else {
            Log.i(TAG, "BT fallback finished (${trigger.wireName}): ${result.outcome.wireName}")
        }
        return false
    }

    private fun retryOrStop(trigger: BtFallbackTrigger, message: String): Boolean {
        val app = currentApp()
        val nextAttempt = BtFallbackRetryPolicy.nextRetryCount(app.prefs.btFallbackRetryCount)
        if (nextAttempt == null) {
            app.prefs.btFallbackRetryCount = 0
            app.recordBtFallbackLastRun(
                BtFallbackLastRun(
                    timestampMs = System.currentTimeMillis(),
                    trigger = trigger,
                    outcome = BtFallbackOutcome.FAILED,
                    detail = message.takeLast(ERROR_TEXT_LIMIT)
                )
            )
            val finalMessage = getString(
                R.string.bt_fallback_auto_stopped,
                BtFallbackRetryPolicy.MAX_ATTEMPTS,
                message
            )
            Log.e(TAG, finalMessage)
            app.errorNotifier.notify(getString(R.string.error_title_bt_fallback), finalMessage)
            return false
        }

        app.prefs.btFallbackRetryCount = nextAttempt
        Log.w(TAG, "BT fallback retry $nextAttempt/${BtFallbackRetryPolicy.MAX_ATTEMPTS}: $message")
        return true
    }

    private fun currentApp(): CodecFixApp = application as CodecFixApp

    private companion object {
        private const val TAG = "AtlasToolsBt"
        private const val ERROR_TEXT_LIMIT = 220
    }
}
