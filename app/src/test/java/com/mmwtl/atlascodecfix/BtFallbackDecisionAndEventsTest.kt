package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtFallbackDecisionAndEventsTest {
    private fun status(
        carplayDisconnect: String? = "AA:BB:CC:DD:EE:FF",
        carplayStatus: String? = "0",
        isConnecting: Boolean? = false,
        adapterState: BtAdapterState = BtAdapterState.ON,
        connectionState: BtConnectionState = BtConnectionState.DISCONNECTED,
        commandSuccess: Boolean = true
    ) = BtFallbackStatus(
        carplayDisconnectAddress = carplayDisconnect,
        deviceAddressList = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
        carplayStatusProp = carplayStatus,
        isConnectingProp = isConnecting,
        btAdapterState = adapterState,
        btConnectionState = connectionState,
        bluetoothServiceRunning = true,
        output = "",
        commandSuccess = commandSuccess
    )

    @Test
    fun autoModeRunsOnlyWhenSafeAndBlocked() {
        assertNull(BtFallbackDecision.evaluate(status(), manual = false))
        assertEquals(
            BtFallbackOutcome.SKIPPED_CARPLAY_ACTIVE,
            BtFallbackDecision.evaluate(status(carplayStatus = "1"), manual = false)
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_DISABLED,
            BtFallbackDecision.evaluate(status(adapterState = BtAdapterState.OFF), manual = false)
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED,
            BtFallbackDecision.evaluate(
                status(connectionState = BtConnectionState.CONNECTED),
                manual = false
            )
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED,
            BtFallbackDecision.evaluate(
                status(connectionState = BtConnectionState.CONNECTING),
                manual = false
            )
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED,
            BtFallbackDecision.evaluate(status(isConnecting = true), manual = false)
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_NOT_BLOCKED,
            BtFallbackDecision.evaluate(status(carplayDisconnect = null), manual = false)
        )
        assertEquals(
            BtFallbackOutcome.FAILED,
            BtFallbackDecision.evaluate(
                status(connectionState = BtConnectionState.UNKNOWN),
                manual = false
            )
        )
        assertEquals(
            BtFallbackOutcome.FAILED,
            BtFallbackDecision.evaluate(status(commandSuccess = false), manual = false)
        )
    }

    @Test
    fun manualModeAllowsTriggeringWhenLockAlreadyEmptyButStillProtectsActiveSessions() {
        assertNull(BtFallbackDecision.evaluate(status(carplayDisconnect = null), manual = true))
        assertEquals(
            BtFallbackOutcome.SKIPPED_CARPLAY_ACTIVE,
            BtFallbackDecision.evaluate(status(carplayStatus = "1"), manual = true)
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_DISABLED,
            BtFallbackDecision.evaluate(status(adapterState = BtAdapterState.OFF), manual = true)
        )
        assertEquals(
            BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED,
            BtFallbackDecision.evaluate(
                status(connectionState = BtConnectionState.CONNECTED),
                manual = true
            )
        )
    }

    @Test
    fun eventParserHandlesKnownBroadcastsAndRejectsUnexpectedActions() {
        assertEquals(
            BtFallbackBroadcastEvent.SCREEN_OFF,
            BtFallbackEventParser.parse(
                BtFallbackEventParser.ACTION_INFOTAINMENT_MODE_CHANGED,
                imMode = 0,
                carplayStatus = null
            )
        )
        assertEquals(
            BtFallbackBroadcastEvent.SCREEN_WAKEUP,
            BtFallbackEventParser.parse(
                BtFallbackEventParser.ACTION_INFOTAINMENT_MODE_CHANGED,
                imMode = 6,
                carplayStatus = null
            )
        )
        assertEquals(
            BtFallbackBroadcastEvent.IGNORED,
            BtFallbackEventParser.parse(
                BtFallbackEventParser.ACTION_INFOTAINMENT_MODE_CHANGED,
                imMode = 3,
                carplayStatus = null
            )
        )
        assertEquals(
            BtFallbackBroadcastEvent.CARPLAY_CONNECTED,
            BtFallbackEventParser.parse(
                BtFallbackEventParser.ACTION_CARPLAY_STATUS,
                imMode = null,
                carplayStatus = 1
            )
        )
        assertEquals(
            BtFallbackBroadcastEvent.CARPLAY_DISCONNECTED,
            BtFallbackEventParser.parse(
                BtFallbackEventParser.ACTION_CARPLAY_STATUS,
                imMode = null,
                carplayStatus = 0
            )
        )
        assertEquals(
            BtFallbackBroadcastEvent.IGNORED,
            BtFallbackEventParser.parse(
                "com.example.UNEXPECTED",
                imMode = 6,
                carplayStatus = 0
            )
        )
    }

    @Test
    fun loopGuardPreventsMultipleAppliesInOneCycleAndEnforcesCooldown() {
        var now = 1_000L
        val guard = BtFallbackLoopGuard { now }

        guard.onBoot()
        assertTrue(guard.canSchedule(BtFallbackTrigger.BOOT))
        assertTrue(guard.canExecuteNow())

        guard.recordExecution(BtFallbackOutcome.APPLIED)

        // Same boot cycle: blocked immediately.
        assertFalse(guard.canExecuteNow())
        assertFalse(guard.canSchedule(BtFallbackTrigger.CARPLAY_DISCONNECTED))

        // Screen off + wakeup within 60s cooldown: still blocked by global cooldown!
        now += 10_000L
        guard.onScreenOff()
        now += 5_000L
        guard.onWakeup()
        assertFalse(guard.canSchedule(BtFallbackTrigger.WAKEUP))

        // Once 60s cooldown has elapsed on the new wake cycle, allowed again.
        now += 50_000L
        assertTrue(guard.canSchedule(BtFallbackTrigger.WAKEUP))
        assertTrue(guard.canExecuteNow())
    }

    @Test
    fun loopGuardDebouncesRapidCarplayDisconnectAndCapsFailuresPerCycle() {
        var now = 10_000L
        val guard = BtFallbackLoopGuard { now }

        guard.onWakeup()
        assertTrue(guard.canSchedule(BtFallbackTrigger.CARPLAY_DISCONNECTED))
        // Rapid duplicate 1s later is debounced.
        now += 1_000L
        assertFalse(guard.canSchedule(BtFallbackTrigger.CARPLAY_DISCONNECTED))
        // After debounce window (5s), allowed again if nothing executed yet.
        now += 5_000L
        assertTrue(guard.canSchedule(BtFallbackTrigger.CARPLAY_DISCONNECTED))

        // Skipped runs do not consume failure budget.
        guard.recordExecution(BtFallbackOutcome.SKIPPED_NOT_BLOCKED)
        assertTrue(guard.canExecuteNow())

        // Two failed executions exhaust the cycle budget.
        guard.recordExecution(BtFallbackOutcome.FAILED)
        assertTrue(guard.canExecuteNow())
        guard.recordExecution(BtFallbackOutcome.FAILED)
        assertFalse(guard.canExecuteNow())

        // Duplicate wakeup within 30s without screen-off does not reset the cycle.
        now += 5_000L
        guard.onWakeup()
        assertFalse(guard.canExecuteNow())

        // Screen-off resets the cycle for the next wakeup.
        guard.onScreenOff()
        guard.onWakeup()
        assertTrue(guard.canExecuteNow())
    }

    @Test
    fun retryPolicyIsBounded() {
        assertEquals(1, BtFallbackRetryPolicy.nextRetryCount(0))
        assertEquals(2, BtFallbackRetryPolicy.nextRetryCount(1))
        assertNull(BtFallbackRetryPolicy.nextRetryCount(2))
        assertEquals(1, BtFallbackRetryPolicy.nextRetryCount(-5))
    }
}
