package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtDeviceAddressPolicyTest {
    @Test
    fun movesCarplayMacFromFirstToLastWhenAnotherDeviceIsSaved() {
        val reordered = BtDeviceAddressPolicy.moveToEndIfNeeded(
            deviceAddressList = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
            carplayMac = "aa:bb:cc:dd:ee:ff"
        )

        assertEquals("11:22:33:44:55:66,AA:BB:CC:DD:EE:FF", reordered)
    }

    @Test
    fun doesNothingWhenCarplayMacIsAlreadyLast() {
        assertNull(
            BtDeviceAddressPolicy.moveToEndIfNeeded(
                deviceAddressList = "11:22:33:44:55:66,AA:BB:CC:DD:EE:FF",
                carplayMac = "AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun neverDeletesSingleSavedDevice() {
        assertNull(
            BtDeviceAddressPolicy.moveToEndIfNeeded(
                deviceAddressList = "AA:BB:CC:DD:EE:FF",
                carplayMac = "AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun doesNothingWhenCarplayMacIsAbsentOrInvalid() {
        assertNull(
            BtDeviceAddressPolicy.moveToEndIfNeeded(
                deviceAddressList = "11:22:33:44:55:66,22:33:44:55:66:77",
                carplayMac = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertNull(
            BtDeviceAddressPolicy.moveToEndIfNeeded(
                deviceAddressList = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
                carplayMac = "not-a-mac"
            )
        )
        assertNull(
            BtDeviceAddressPolicy.moveToEndIfNeeded(
                deviceAddressList = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
                carplayMac = null
            )
        )
    }

    @Test
    fun deduplicatesAndPreservesOrderAcrossThreeDevices() {
        val reordered = BtDeviceAddressPolicy.moveToEndIfNeeded(
            deviceAddressList = "aa:bb:cc:dd:ee:ff, 11:22:33:44:55:66, aa:bb:cc:dd:ee:ff, 22:33:44:55:66:77",
            carplayMac = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("11:22:33:44:55:66,22:33:44:55:66:77,AA:BB:CC:DD:EE:FF", reordered)
    }

    @Test
    fun validatesMacFormatStrictly() {
        assertTrue(BtDeviceAddressPolicy.isValidMac("01:23:45:67:89:AB"))
        assertTrue(BtDeviceAddressPolicy.isValidMac("ab:cd:ef:00:11:22"))
        assertFalse(BtDeviceAddressPolicy.isValidMac("01:23:45:67:89"))
        assertFalse(BtDeviceAddressPolicy.isValidMac("01:23:45:67:89:GG"))
        assertFalse(BtDeviceAddressPolicy.isValidMac("01:23:45:67:89:AB;rm -rf /"))
    }
}
