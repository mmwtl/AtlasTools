package com.mmwtl.atlascodecfix

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class BtFallbackRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun autoFallbackClearsLockReordersMainDevicesRestartsBluetoothServiceAndVerifies() = runBlocking {
        val device = FakeBtDevice(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            deviceAddress = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66"
        )
        val repository = BtFallbackRepository(device, nowMs = { 123_456L })

        val result = repository.runFallback(BtFallbackTrigger.WAKEUP)

        assertEquals(BtFallbackOutcome.APPLIED, result.outcome)
        assertEquals(BtFallbackTrigger.WAKEUP, result.trigger)
        assertEquals(123_456L, result.timestampMs)
        assertEquals("11:22:33:44:55:66,AA:BB:CC:DD:EE:FF", result.reorderedDeviceAddress)
        assertFalse(result.retryable)
        assertNull(result.afterStatus?.carplayDisconnectAddress)
        assertEquals("11:22:33:44:55:66,AA:BB:CC:DD:EE:FF", result.afterStatus?.deviceAddressList)

        val applyCmd = device.commands[1]
        assertTrue(applyCmd.contains("ROOT_PREFIX='su root'"))
        assertTrue(applyCmd.contains("settings put secure key_carplay_disconnect_address"))
        assertTrue(applyCmd.contains("settings put system key_device_address"))
        assertTrue(applyCmd.contains("am force-stop com.autolink.bluetoothservice"))
        assertTrue(applyCmd.contains("am startservice -n com.autolink.bluetoothservice/.BluetoothService"))
        assertFalse(applyCmd.contains("/vendor"))
    }

    @Test
    fun autoFallbackSkipsWithoutRunningRootCommandsWhenCarPlayActiveOrAlreadyConnectedOrUnblocked() =
        runBlocking {
            val activeCarplay = FakeBtDevice(
                carplayDisconnect = "AA:BB:CC:DD:EE:FF",
                carplayStatus = "1"
            )
            val r1 = BtFallbackRepository(activeCarplay).runFallback(BtFallbackTrigger.BOOT)
            assertEquals(BtFallbackOutcome.SKIPPED_CARPLAY_ACTIVE, r1.outcome)
            assertFalse(r1.retryable)
            assertEquals(1, activeCarplay.commands.size)

            val btConnected = FakeBtDevice(
                carplayDisconnect = "AA:BB:CC:DD:EE:FF",
                connectionState = "STATE_CONNECTED"
            )
            val r2 = BtFallbackRepository(btConnected).runFallback(BtFallbackTrigger.WAKEUP)
            assertEquals(BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED, r2.outcome)
            assertEquals(1, btConnected.commands.size)

            val notBlocked = FakeBtDevice(carplayDisconnect = "")
            val r3 = BtFallbackRepository(notBlocked)
                .runFallback(BtFallbackTrigger.CARPLAY_DISCONNECTED)
            assertEquals(BtFallbackOutcome.SKIPPED_NOT_BLOCKED, r3.outcome)
            assertEquals(1, notBlocked.commands.size)
        }

    @Test
    fun manualFallbackStillRestartsWhenLockAlreadyEmptyWithoutRewritingDeviceAddress() = runBlocking {
        val device = FakeBtDevice(
            carplayDisconnect = "",
            deviceAddress = "11:22:33:44:55:66"
        )

        val result = BtFallbackRepository(device).runFallback(BtFallbackTrigger.MANUAL)

        assertEquals(BtFallbackOutcome.APPLIED, result.outcome)
        assertNull(result.reorderedDeviceAddress)
        assertEquals(3, device.commands.size)
        assertFalse(device.commands[1].contains("settings put system key_device_address"))
    }

    @Test
    fun applyFailureAndVerificationMismatchReturnFailed() = runBlocking {
        val applyFailedDevice = FakeBtDevice(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            applyExitCode = 1,
            applyStdout = "Error: Not found; no service started."
        )
        val failedApply = BtFallbackRepository(applyFailedDevice)
            .runFallback(BtFallbackTrigger.MANUAL)
        assertEquals(BtFallbackOutcome.FAILED, failedApply.outcome)
        assertFalse(failedApply.retryable)

        val timeoutDevice = FakeBtDevice(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            applyFailure = AdbCommandFailureKind.TIMEOUT
        )
        val timedOut = BtFallbackRepository(timeoutDevice).runFallback(BtFallbackTrigger.BOOT)
        assertEquals(BtFallbackOutcome.FAILED, timedOut.outcome)
        assertTrue(timedOut.retryable)

        val stickyLockDevice = FakeBtDevice(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            clearTakesEffect = false
        )
        val mismatch = BtFallbackRepository(stickyLockDevice).runFallback(BtFallbackTrigger.WAKEUP)
        assertEquals(BtFallbackOutcome.FAILED, mismatch.outcome)
        assertFalse(mismatch.retryable)
    }

    @Test
    fun applyCommandChecksAmStartserviceErrorTextAndUsesSafePosixSh() {
        val cmd = BtFallbackRepository.buildApplyCommand("11:22:33:44:55:66,AA:BB:CC:DD:EE:FF")

        assertTrue(cmd.contains("am force-stop com.autolink.bluetoothservice || exit \$?"))
        assertTrue(cmd.contains("am startservice -n com.autolink.bluetoothservice/.BluetoothService"))
        assertTrue(cmd.contains("*Error:*|*Exception:*"))
        assertFalse(cmd.contains("bash"))
        assertFalse(cmd.contains("/vendor"))
    }

    @Test
    fun btFallbackSharesProcessWideLockWithAotAndCodecRepositories() = runBlocking {
        val device = FakeBtDevice(
            carplayDisconnect = "AA:BB:CC:DD:EE:FF",
            commandDelayMs = 20L
        )
        val bt = BtFallbackRepository(device)
        val aot = AotCompileRepository(device, listOf(AotPackage("com.geely.atlaslauncher", "Launcher")))
        val codec = HevcCodecFixRepository(MinimalAssets(temporaryFolder.newFolder("assets")), device)

        listOf(
            async { bt.runFallback(BtFallbackTrigger.MANUAL) },
            async { codec.detectCurrentVariant() },
            async { aot.queryStatus() }
        ).awaitAll()

        assertEquals(1, device.maxConcurrent.get())
    }

    private class FakeBtDevice(
        private var carplayDisconnect: String = "",
        private var deviceAddress: String = "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66",
        private val carplayStatus: String = "0",
        private val adapterState: String = "ON",
        private val connectionState: String = "STATE_DISCONNECTED",
        private val applyExitCode: Int = 0,
        private val applyStdout: String = "Starting service: Intent { cmp=com.autolink.bluetoothservice/.BluetoothService }",
        private val applyFailure: AdbCommandFailureKind? = null,
        private val clearTakesEffect: Boolean = true,
        private val commandDelayMs: Long = 0L
    ) : AdbCommandExecutor {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun execute(command: String, timeoutMs: Long): AdbCommandResult {
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            try {
                commands += command
                if (commandDelayMs > 0L) delay(commandDelayMs)
                return when {
                    command.contains(BtFallbackStatusParser.CARPLAY_DISCONNECT_START) -> {
                        val snapshot = BtFallbackStatusParserTest.buildSnapshot(
                            carplayDisconnect = carplayDisconnect,
                            deviceAddress = deviceAddress,
                            carplayStatus = carplayStatus,
                            dumpsys = """
                                Bluetooth Status
                                  enabled: ${adapterState == "ON"}
                                  state: $adapterState
                                AdapterProperties
                                  ConnectionState: $connectionState
                                  State: $adapterState
                            """.trimIndent()
                        )
                        AdbCommandResult(stdout = snapshot, exitCode = 0)
                    }
                    command.contains("am force-stop com.autolink.bluetoothservice") -> {
                        applyFailure?.let {
                            return AdbCommandResult.failure(it, "simulated $it")
                        }
                        if (applyExitCode == 0 && clearTakesEffect) {
                            carplayDisconnect = ""
                            val reordered = Regex(
                                """settings put system key_device_address '([^']+)'"""
                            ).find(command.replace("'\"'\"'", "'"))?.groupValues?.getOrNull(1)
                            if (reordered != null) {
                                deviceAddress = reordered
                            }
                        }
                        AdbCommandResult(stdout = applyStdout, exitCode = applyExitCode)
                    }
                    command.startsWith("dumpsys package dexopt") -> {
                        val out = "Dexopt state:\n${AotDexoptStatusParser.DUMP_EXIT_MARKER}0\n${AotDexoptStatusParser.END_MARKER}"
                        AdbCommandResult(stdout = out, exitCode = 0)
                    }
                    else -> AdbCommandResult(stdout = "variant:msmnile", exitCode = 0)
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class MinimalAssets(private val root: File) : HevcAssetSource {
        override fun stage(variant: HevcCodecFixVariant): File = root
        override fun stageScript(assetPath: String): File =
            File(root, assetPath).apply { writeText("echo variant:msmnile") }
        override fun readText(assetPath: String): String = ""
        override fun getString(resource: Int, vararg arguments: Any): String = "text"
    }
}
