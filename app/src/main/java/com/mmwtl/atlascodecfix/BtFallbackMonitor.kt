package com.mmwtl.atlascodecfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Dynamically listens for G636 wakeup (`com.autolink.powerservice.infotainment.mode.changed`) and
 * CarPlay status (`com.autolink.carplay.carplaystatus`) broadcasts while the process is alive and
 * auto BT fallback is enabled. Only schedules or cancels [BtFallbackJobService]; never performs
 * ADB/root operations inside `onReceive`.
 */
class BtFallbackMonitor(
    private val app: CodecFixApp
) {
    private val lock = Any()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!app.prefs.autoBtFallbackEnabled) return
            val extras = intent.extras
            val event = BtFallbackEventParser.parse(
                action = intent.action,
                imMode = extractNumericExtra(extras, BtFallbackEventParser.EXTRA_IM_MODE),
                carplayStatus = extractNumericExtra(extras, BtFallbackEventParser.EXTRA_CARPLAY_STATUS)
            )
            when (event) {
                BtFallbackBroadcastEvent.SCREEN_OFF -> {
                    Log.d(TAG, "Received screen off (im_mode=0); resetting BT fallback cycle")
                    app.btFallbackLoopGuard.onScreenOff()
                    BtFallbackScheduler.cancel(app)
                }
                BtFallbackBroadcastEvent.SCREEN_WAKEUP -> {
                    Log.i(TAG, "Received screen wakeup (im_mode=6); scheduling BT fallback")
                    app.btFallbackLoopGuard.onWakeup()
                    BtFallbackScheduler.schedule(
                        app,
                        BtFallbackTrigger.WAKEUP,
                        resetRetries = true
                    )
                }
                BtFallbackBroadcastEvent.CARPLAY_CONNECTED -> {
                    Log.i(TAG, "Received CarPlay connected (status=1); cancelling pending BT fallback")
                    BtFallbackScheduler.cancel(app)
                }
                BtFallbackBroadcastEvent.CARPLAY_DISCONNECTED -> {
                    Log.i(TAG, "Received CarPlay disconnected/error (status=0); scheduling BT fallback")
                    BtFallbackScheduler.schedule(
                        app,
                        BtFallbackTrigger.CARPLAY_DISCONNECTED,
                        resetRetries = true
                    )
                }
                BtFallbackBroadcastEvent.IGNORED -> {
                    Log.d(TAG, "Ignoring BT fallback broadcast: action=${intent.action}")
                }
            }
        }
    }

    fun syncRegistration() {
        synchronized(lock) {
            val shouldRegister = app.prefs.autoBtFallbackEnabled
            if (shouldRegister && !registered) {
                val filter = IntentFilter().apply {
                    addAction(BtFallbackEventParser.ACTION_INFOTAINMENT_MODE_CHANGED)
                    addAction(BtFallbackEventParser.ACTION_CARPLAY_STATUS)
                }
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                registered = true
                Log.i(TAG, "Registered BT fallback broadcast monitor")
            } else if (!shouldRegister && registered) {
                runCatching { app.unregisterReceiver(receiver) }
                registered = false
                Log.i(TAG, "Unregistered BT fallback broadcast monitor")
            }
        }
    }

    private fun extractNumericExtra(extras: Bundle?, key: String): Int? {
        if (extras == null || !extras.containsKey(key)) return null
        @Suppress("DEPRECATION")
        val raw = extras.get(key) ?: return null
        return when (raw) {
            is Byte -> raw.toInt()
            is Int -> raw
            is Short -> raw.toInt()
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            else -> null
        }
    }

    private companion object {
        private const val TAG = "AtlasToolsBt"
    }
}
