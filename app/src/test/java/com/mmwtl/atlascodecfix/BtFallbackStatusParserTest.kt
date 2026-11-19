package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtFallbackStatusParserTest {
    @Test
    fun parsesBlockedG636Snapshot() {
        val raw = buildSnapshot(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            deviceAddress = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
            carplayStatus = "0",
            isConnecting = "false",
            globalOn = "1",
            servicePid = "4123",
            dumpsys = """
                Bluetooth Status
                  enabled: true
                  state: ON
                AdapterProperties
                  ConnectionState: STATE_DISCONNECTED
                  State: ON
            """.trimIndent()
        )

        val status = BtFallbackStatusParser.parse(raw, commandExitSucceeded = true)

        assertTrue(status.commandSuccess)
        assertEquals("AA:BB:CC:DD:EE:FF", status.carplayDisconnectAddress)
        assertEquals("AA:BB:CC:DD:EE:FF,11:22:33:44:55:66", status.deviceAddressList)
        assertEquals("0", status.carplayStatusProp)
        assertEquals(false, status.isConnectingProp)
        assertEquals(BtAdapterState.ON, status.btAdapterState)
        assertEquals(BtConnectionState.DISCONNECTED, status.btConnectionState)
        assertTrue(status.bluetoothServiceRunning)
        assertFalse(status.isCarplayActive)
        assertTrue(status.isCarplayLockPresent)
    }

    @Test
    fun treatsNullAndBlankSettingsAsEmpty() {
        val raw = buildSnapshot(
            carplayDisconnect = "null",
            deviceAddress = "",
            carplayStatus = "",
            isConnecting = "",
            globalOn = "1",
            servicePid = "",
            dumpsys = """
                Bluetooth Status
                  enabled: true
                  state: ON
                AdapterProperties
                  ConnectionState: STATE_CONNECTED
                  State: ON
            """.trimIndent()
        )

        val status = BtFallbackStatusParser.parse(raw, commandExitSucceeded = true)

        assertTrue(status.commandSuccess)
        assertNull(status.carplayDisconnectAddress)
        assertNull(status.deviceAddressList)
        assertNull(status.carplayStatusProp)
        assertNull(status.isConnectingProp)
        assertEquals(BtAdapterState.ON, status.btAdapterState)
        assertEquals(BtConnectionState.CONNECTED, status.btConnectionState)
        assertFalse(status.bluetoothServiceRunning)
        assertFalse(status.isCarplayLockPresent)
    }

    @Test
    fun parsesActiveCarPlayAndBluetoothOff() {
        val raw = buildSnapshot(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            carplayStatus = "1",
            isConnecting = "true",
            globalOn = "0",
            servicePid = "998",
            dumpsys = """
                Bluetooth Status
                  enabled: false
                  state: OFF
            """.trimIndent()
        )

        val status = BtFallbackStatusParser.parse(raw, commandExitSucceeded = true)

        assertTrue(status.commandSuccess)
        assertTrue(status.isCarplayActive)
        assertEquals(true, status.isConnectingProp)
        assertEquals(BtAdapterState.OFF, status.btAdapterState)
        assertEquals(BtConnectionState.DISCONNECTED, status.btConnectionState)
    }

    @Test
    fun fallsBackToGlobalBluetoothOnWhenDumpsysIsEmpty() {
        val raw = buildSnapshot(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            deviceAddress = "11:22:33:44:55:66",
            carplayStatus = "0",
            isConnecting = "",
            globalOn = "1",
            servicePid = "1024",
            dumpsys = ""
        )

        val status = BtFallbackStatusParser.parse(raw, commandExitSucceeded = true)

        assertTrue(status.commandSuccess)
        assertEquals(BtAdapterState.ON, status.btAdapterState)
        assertEquals(BtConnectionState.UNKNOWN, status.btConnectionState)
    }

    @Test
    fun missingEndMarkerMarksCommandFailed() {
        val truncated = "${BtFallbackStatusParser.CARPLAY_DISCONNECT_START}\nAA:BB:CC:DD:EE:FF"

        val status = BtFallbackStatusParser.parse(truncated, commandExitSucceeded = true)

        assertFalse(status.commandSuccess)
        assertEquals(BtAdapterState.UNKNOWN, status.btAdapterState)
        assertEquals(BtConnectionState.UNKNOWN, status.btConnectionState)
    }

    @Test
    fun nonZeroSettingsExitMarksCommandFailed() {
        val raw = buildSnapshot(
            carplayDisconnect = "SecurityException",
            carplayExit = 255,
            deviceAddress = "11:22:33:44:55:66",
            carplayStatus = "0",
            isConnecting = "false",
            globalOn = "1",
            servicePid = "1234",
            dumpsys = """
                AdapterProperties
                  ConnectionState: STATE_DISCONNECTED
                  State: ON
            """.trimIndent()
        )

        val status = BtFallbackStatusParser.parse(raw, commandExitSucceeded = true)

        assertFalse(status.commandSuccess)
    }

    companion object {
        fun buildSnapshot(
            carplayDisconnect: String = "",
            carplayExit: Int = 0,
            deviceAddress: String = "",
            deviceExit: Int = 0,
            carplayStatus: String = "0",
            isConnecting: String = "false",
            globalOn: String = "1",
            servicePid: String = "1234",
            dumpsys: String = """
                Bluetooth Status
                  enabled: true
                  state: ON
                AdapterProperties
                  ConnectionState: STATE_DISCONNECTED
                  State: ON
            """.trimIndent()
        ): String = buildString {
            appendLine(BtFallbackStatusParser.CARPLAY_DISCONNECT_START)
            appendLine(carplayDisconnect)
            appendLine("${BtFallbackStatusParser.CARPLAY_DISCONNECT_EXIT}$carplayExit")
            appendLine(BtFallbackStatusParser.DEVICE_ADDRESS_START)
            appendLine(deviceAddress)
            appendLine("${BtFallbackStatusParser.DEVICE_ADDRESS_EXIT}$deviceExit")
            appendLine(BtFallbackStatusParser.CARPLAY_STATUS_START)
            appendLine(carplayStatus)
            appendLine(BtFallbackStatusParser.IS_CONNECTING_START)
            appendLine(isConnecting)
            appendLine(BtFallbackStatusParser.GLOBAL_ON_START)
            appendLine(globalOn)
            appendLine(BtFallbackStatusParser.SERVICE_PID_START)
            appendLine(servicePid)
            appendLine(BtFallbackStatusParser.DUMPSYS_START)
            appendLine(dumpsys)
            appendLine(BtFallbackStatusParser.END_MARKER)
        }
    }
}
