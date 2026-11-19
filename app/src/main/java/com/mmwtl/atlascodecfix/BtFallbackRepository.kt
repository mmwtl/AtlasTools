package com.mmwtl.atlascodecfix

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Diagnostics and Variant 1 Bluetooth auto-connect fallback after wireless CarPlay on G636:
 * reads `key_carplay_disconnect_address`, `key_device_address`, `sys.autolink.carplay.status`,
 * and Bluetooth adapter/connection state; when safe, clears the stuck CarPlay lock, moves the
 * CarPlay MAC to the end of `key_device_address` if another main device is saved, and restarts
 * `com.autolink.bluetoothservice` so stock `ConnectionPolicy` runs auto-connect.
 *
 * All sequences share [DeviceOperationLock] with Codec Fix and AOT, use finite command timeouts,
 * follow `ADB transport -> su root sh -c -> command` for writes/restarts, and never touch `/vendor`.
 */
class BtFallbackRepository(
    private val adb: AdbCommandExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun queryStatus(): BtFallbackStatus = withContext(Dispatchers.IO) {
        DeviceOperationLock.mutex.withLock { queryStatusLocked() }
    }

    suspend fun runFallback(trigger: BtFallbackTrigger): BtFallbackRunResult =
        withContext(Dispatchers.IO) {
            DeviceOperationLock.mutex.withLock {
                val before = queryStatusLocked()
                val manual = trigger == BtFallbackTrigger.MANUAL
                val decision = BtFallbackDecision.evaluate(before, manual)
                if (decision != null) {
                    val retryable = decision == BtFallbackOutcome.FAILED && !before.commandSuccess
                    return@withLock BtFallbackRunResult(
                        outcome = decision,
                        trigger = trigger,
                        beforeStatus = before,
                        output = before.output,
                        retryable = retryable,
                        timestampMs = nowMs()
                    )
                }

                val reordered = BtDeviceAddressPolicy.moveToEndIfNeeded(
                    deviceAddressList = before.deviceAddressList,
                    carplayMac = before.carplayDisconnectAddress
                )
                val applyResult = executeCatching(buildApplyCommand(reordered), APPLY_TIMEOUT_MS)
                if (!applyResult.succeeded) {
                    return@withLock BtFallbackRunResult(
                        outcome = BtFallbackOutcome.FAILED,
                        trigger = trigger,
                        beforeStatus = before,
                        reorderedDeviceAddress = reordered,
                        output = applyResult.displayOutput,
                        retryable = applyResult.failure != null,
                        timestampMs = nowMs()
                    )
                }

                val after = queryStatusLocked()
                val verified = after.commandSuccess && !after.isCarplayLockPresent
                val combinedOutput = listOf(applyResult.displayOutput, after.output)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                BtFallbackRunResult(
                    outcome = if (verified) BtFallbackOutcome.APPLIED else BtFallbackOutcome.FAILED,
                    trigger = trigger,
                    beforeStatus = before,
                    afterStatus = after,
                    reorderedDeviceAddress = reordered,
                    output = combinedOutput,
                    retryable = !after.commandSuccess,
                    timestampMs = nowMs()
                )
            }
        }

    private suspend fun queryStatusLocked(): BtFallbackStatus {
        val result = executeCatching(buildStatusCommand(), STATUS_TIMEOUT_MS)
        if (!result.succeeded) {
            return BtFallbackStatus(
                carplayDisconnectAddress = null,
                deviceAddressList = null,
                carplayStatusProp = null,
                isConnectingProp = null,
                btAdapterState = BtAdapterState.UNKNOWN,
                btConnectionState = BtConnectionState.UNKNOWN,
                bluetoothServiceRunning = false,
                output = result.displayOutput,
                commandSuccess = false
            )
        }
        return BtFallbackStatusParser.parse(result.stdout, commandExitSucceeded = true)
    }

    private suspend fun executeCatching(command: String, timeoutMs: Long): AdbCommandResult {
        return try {
            adb.execute(command, timeoutMs)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "ADB command invocation failed", t)
            AdbCommandResult.failure(
                AdbCommandFailureKind.TRANSPORT,
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    internal companion object {
        private const val TAG = "AtlasToolsBt"
        internal const val STATUS_TIMEOUT_MS = 15_000L
        internal const val APPLY_TIMEOUT_MS = 20_000L
        internal const val BT_SERVICE_PACKAGE = "com.autolink.bluetoothservice"
        internal const val BT_SERVICE_COMPONENT = "$BT_SERVICE_PACKAGE/.BluetoothService"
        internal const val KEY_CARPLAY_DISCONNECT_ADDRESS = "key_carplay_disconnect_address"
        internal const val KEY_DEVICE_ADDRESS = "key_device_address"
        internal const val PROP_CARPLAY_STATUS = "sys.autolink.carplay.status"
        internal const val PROP_IS_CONNECTING = "sys.autolink.is_connecting"

        internal fun buildStatusCommand(): String = listOf(
            "echo ${BtFallbackStatusParser.CARPLAY_DISCONNECT_START}",
            "settings get secure $KEY_CARPLAY_DISCONNECT_ADDRESS 2>&1",
            "echo \"${BtFallbackStatusParser.CARPLAY_DISCONNECT_EXIT}\$?\"",
            "echo ${BtFallbackStatusParser.DEVICE_ADDRESS_START}",
            "settings get system $KEY_DEVICE_ADDRESS 2>&1",
            "echo \"${BtFallbackStatusParser.DEVICE_ADDRESS_EXIT}\$?\"",
            "echo ${BtFallbackStatusParser.CARPLAY_STATUS_START}",
            "getprop $PROP_CARPLAY_STATUS 2>&1",
            "echo ${BtFallbackStatusParser.IS_CONNECTING_START}",
            "getprop $PROP_IS_CONNECTING 2>&1",
            "echo ${BtFallbackStatusParser.GLOBAL_ON_START}",
            "settings get global bluetooth_on 2>&1",
            "echo ${BtFallbackStatusParser.SERVICE_PID_START}",
            "pidof $BT_SERVICE_PACKAGE 2>/dev/null || true",
            "echo ${BtFallbackStatusParser.DUMPSYS_START}",
            "(dumpsys bluetooth_manager 2>&1 | grep -E '^(Bluetooth Status|AdapterProperties|  enabled:|  state:|  State:|  ConnectionState:)' || true)",
            "echo ${BtFallbackStatusParser.END_MARKER}"
        ).joinToString("; ")

        internal fun buildApplyCommand(reorderedDeviceAddress: String?): String {
            val steps = mutableListOf(
                "settings put secure $KEY_CARPLAY_DISCONNECT_ADDRESS '' || exit \$?"
            )
            if (!reorderedDeviceAddress.isNullOrBlank()) {
                steps += "settings put system $KEY_DEVICE_ADDRESS ${reorderedDeviceAddress.shellQuote()} || exit \$?"
            }
            // Use force-stop rather than stopservice: ConnectionPolicy.stop() in onDestroy()
            // commits an empty in-memory saveDevices list to key_device_address after boot, and
            // ConnectionManager keeps a static singleton with closed proxies across restarts.
            steps += "am force-stop $BT_SERVICE_PACKAGE || exit \$?"
            steps += "sleep 1"
            steps += "START_OUT=\$(am startservice -n $BT_SERVICE_COMPONENT 2>&1)"
            steps += "START_RC=\$?"
            steps += "printf '%s\\n' \"\$START_OUT\""
            steps += "if [ \"\$START_RC\" -ne 0 ]; then exit \"\$START_RC\"; fi"
            steps += "case \"\$START_OUT\" in *Error:*|*Exception:*) exit 1 ;; esac"
            return buildRootShellCommand(steps.joinToString("\n"))
        }

        internal fun buildRootShellCommand(script: String): String {
            return "ROOT_PREFIX='su root'; " +
                "if [ \"\$(id -u 2>/dev/null)\" = '0' ]; then ROOT_PREFIX=''; fi; " +
                "\$ROOT_PREFIX sh -c ${script.shellQuote()}"
        }

        private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
    }
}
