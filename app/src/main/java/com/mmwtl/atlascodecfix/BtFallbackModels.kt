package com.mmwtl.atlascodecfix

import java.util.Locale

enum class BtAdapterState {
    ON,
    OFF,
    TURNING_ON,
    TURNING_OFF,
    UNKNOWN
}

enum class BtConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    UNKNOWN
}

enum class BtFallbackTrigger(
    val wireName: String,
    val delayMs: Long
) {
    MANUAL("manual", 0L),
    BOOT("boot", 15_000L),
    WAKEUP("wakeup", 14_000L),
    CARPLAY_DISCONNECTED("carplay_disconnected", 2_000L);

    companion object {
        fun fromWireName(value: String?): BtFallbackTrigger? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

enum class BtFallbackOutcome(val wireName: String) {
    APPLIED("applied"),
    SKIPPED_CARPLAY_ACTIVE("skipped_carplay_active"),
    SKIPPED_BT_DISABLED("skipped_bt_disabled"),
    SKIPPED_BT_ALREADY_CONNECTED("skipped_bt_already_connected"),
    SKIPPED_NOT_BLOCKED("skipped_not_blocked"),
    SKIPPED_LOOP_GUARD("skipped_loop_guard"),
    FAILED("failed");

    companion object {
        fun fromWireName(value: String?): BtFallbackOutcome? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

data class BtFallbackStatus(
    val carplayDisconnectAddress: String?,
    val deviceAddressList: String?,
    val carplayStatusProp: String?,
    val isConnectingProp: Boolean?,
    val btAdapterState: BtAdapterState,
    val btConnectionState: BtConnectionState,
    val bluetoothServiceRunning: Boolean,
    val output: String,
    val commandSuccess: Boolean
) {
    val isCarplayActive: Boolean
        get() = carplayStatusProp == "1"

    val isCarplayLockPresent: Boolean
        get() = !carplayDisconnectAddress.isNullOrBlank()
}

data class BtFallbackRunResult(
    val outcome: BtFallbackOutcome,
    val trigger: BtFallbackTrigger,
    val beforeStatus: BtFallbackStatus? = null,
    val afterStatus: BtFallbackStatus? = null,
    val reorderedDeviceAddress: String? = null,
    val output: String = "",
    val retryable: Boolean = false,
    val timestampMs: Long = 0L
) {
    val latestStatus: BtFallbackStatus?
        get() = afterStatus ?: beforeStatus

    val succeeded: Boolean
        get() = outcome != BtFallbackOutcome.FAILED
}

data class BtFallbackLastRun(
    val timestampMs: Long,
    val trigger: BtFallbackTrigger,
    val outcome: BtFallbackOutcome,
    val detail: String = ""
)

/**
 * Pure parser for the framed shell snapshot produced by [BtFallbackRepository.buildStatusCommand].
 */
object BtFallbackStatusParser {
    internal const val CARPLAY_DISCONNECT_START = "__BT_CARPLAY_DISCONNECT_START__"
    internal const val CARPLAY_DISCONNECT_EXIT = "__BT_CARPLAY_DISCONNECT_EXIT__"
    internal const val DEVICE_ADDRESS_START = "__BT_DEVICE_ADDRESS_START__"
    internal const val DEVICE_ADDRESS_EXIT = "__BT_DEVICE_ADDRESS_EXIT__"
    internal const val CARPLAY_STATUS_START = "__BT_CARPLAY_STATUS_START__"
    internal const val IS_CONNECTING_START = "__BT_IS_CONNECTING_START__"
    internal const val GLOBAL_ON_START = "__BT_GLOBAL_ON_START__"
    internal const val SERVICE_PID_START = "__BT_SERVICE_PID_START__"
    internal const val DUMPSYS_START = "__BT_DUMPSYS_START__"
    internal const val END_MARKER = "__BT_STATUS_END__"

    fun hasEndMarker(output: String): Boolean =
        output.lineSequence().any { it.trim() == END_MARKER }

    fun parse(output: String, commandExitSucceeded: Boolean): BtFallbackStatus {
        val complete = commandExitSucceeded && hasEndMarker(output)
        if (!complete) {
            return BtFallbackStatus(
                carplayDisconnectAddress = null,
                deviceAddressList = null,
                carplayStatusProp = null,
                isConnectingProp = null,
                btAdapterState = BtAdapterState.UNKNOWN,
                btConnectionState = BtConnectionState.UNKNOWN,
                bluetoothServiceRunning = false,
                output = output,
                commandSuccess = false
            )
        }

        val carplaySection = section(output, CARPLAY_DISCONNECT_START, DEVICE_ADDRESS_START)
        val (carplayRaw, carplayExit) = splitExitMarker(carplaySection, CARPLAY_DISCONNECT_EXIT)

        val deviceSection = section(output, DEVICE_ADDRESS_START, CARPLAY_STATUS_START)
        val (deviceRaw, deviceExit) = splitExitMarker(deviceSection, DEVICE_ADDRESS_EXIT)

        val carplayPropRaw = section(output, CARPLAY_STATUS_START, IS_CONNECTING_START).trim()
        val isConnectingRaw = section(output, IS_CONNECTING_START, GLOBAL_ON_START).trim()
        val globalOnRaw = section(output, GLOBAL_ON_START, SERVICE_PID_START).trim()
        val servicePidRaw = section(output, SERVICE_PID_START, DUMPSYS_START).trim()
        val dumpsysRaw = section(output, DUMPSYS_START, END_MARKER)

        val settingsSucceeded = carplayExit == 0 && deviceExit == 0
        val adapterState = parseAdapterState(dumpsysRaw, globalOnRaw)
        val connectionState = parseConnectionState(dumpsysRaw, adapterState)
        val serviceRunning = servicePidRaw.split(Regex("""\s+"""))
            .any { token -> token.isNotEmpty() && token.all(Char::isDigit) }

        return BtFallbackStatus(
            carplayDisconnectAddress = normalizeSettingValue(carplayRaw),
            deviceAddressList = normalizeSettingValue(deviceRaw),
            carplayStatusProp = carplayPropRaw.takeIf(String::isNotEmpty),
            isConnectingProp = when (isConnectingRaw.lowercase(Locale.ROOT)) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            },
            btAdapterState = adapterState,
            btConnectionState = connectionState,
            bluetoothServiceRunning = serviceRunning,
            output = output,
            commandSuccess = settingsSucceeded
        )
    }

    private fun section(output: String, startMarker: String, endMarker: String): String {
        val lines = output.lines()
        val startIdx = lines.indexOfFirst { it.trim() == startMarker }
        if (startIdx < 0) return ""
        val endIdx = lines.indexOfFirst { it.trim() == endMarker }
        if (endIdx <= startIdx) return ""
        return lines.subList(startIdx + 1, endIdx).joinToString("\n")
    }

    private fun splitExitMarker(section: String, exitPrefix: String): Pair<String, Int?> {
        var exitCode: Int? = null
        val content = mutableListOf<String>()
        for (line in section.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(exitPrefix)) {
                exitCode = trimmed.removePrefix(exitPrefix).trim().toIntOrNull()
            } else {
                content += line
            }
        }
        return content.joinToString("\n").trim() to exitCode
    }

    private fun normalizeSettingValue(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
        return trimmed
    }

    private fun parseAdapterState(dumpsys: String, globalBluetoothOn: String): BtAdapterState {
        var managerState: BtAdapterState? = null
        var managerEnabled: Boolean? = null
        var inAdapterProperties = false

        for (line in dumpsys.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "AdapterProperties" -> inAdapterProperties = true
                trimmed == "Bluetooth Status" -> inAdapterProperties = false
                inAdapterProperties && trimmed.startsWith("State:") -> {
                    parseStateToken(trimmed.removePrefix("State:").trim())?.let { return it }
                }
                !inAdapterProperties && trimmed.startsWith("state:") -> {
                    managerState = parseStateToken(trimmed.removePrefix("state:").trim())
                }
                !inAdapterProperties && trimmed.startsWith("enabled:") -> {
                    managerEnabled = when (trimmed.removePrefix("enabled:").trim().lowercase(Locale.ROOT)) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                }
            }
        }

        if (managerState != null) return managerState
        if (managerEnabled != null) {
            return if (managerEnabled) BtAdapterState.ON else BtAdapterState.OFF
        }
        return when (globalBluetoothOn.trim()) {
            "1" -> BtAdapterState.ON
            "0" -> BtAdapterState.OFF
            else -> BtAdapterState.UNKNOWN
        }
    }

    private fun parseStateToken(token: String): BtAdapterState? = when (token.uppercase(Locale.ROOT)) {
        "ON", "STATE_ON" -> BtAdapterState.ON
        "OFF", "STATE_OFF" -> BtAdapterState.OFF
        "TURNING_ON", "STATE_TURNING_ON" -> BtAdapterState.TURNING_ON
        "TURNING_OFF", "STATE_TURNING_OFF" -> BtAdapterState.TURNING_OFF
        else -> null
    }

    private fun parseConnectionState(
        dumpsys: String,
        adapterState: BtAdapterState
    ): BtConnectionState {
        for (line in dumpsys.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("ConnectionState:")) {
                val value = trimmed.removePrefix("ConnectionState:").trim().uppercase(Locale.ROOT)
                return when (value) {
                    "STATE_CONNECTED", "CONNECTED" -> BtConnectionState.CONNECTED
                    "STATE_CONNECTING", "CONNECTING" -> BtConnectionState.CONNECTING
                    "STATE_DISCONNECTING", "DISCONNECTING" -> BtConnectionState.DISCONNECTING
                    "STATE_DISCONNECTED", "DISCONNECTED" -> BtConnectionState.DISCONNECTED
                    else -> BtConnectionState.UNKNOWN
                }
            }
        }
        return if (adapterState == BtAdapterState.OFF || adapterState == BtAdapterState.TURNING_OFF) {
            BtConnectionState.DISCONNECTED
        } else {
            BtConnectionState.UNKNOWN
        }
    }
}

/**
 * Pure helper for reordering `Settings.System["key_device_address"]` so an unreachable CarPlay
 * iPhone MAC at index 0 does not make `ConnectionPolicy` stall for 30 seconds before trying the
 * saved Android device.
 */
object BtDeviceAddressPolicy {
    private val MAC_REGEX = Regex("""^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$""")

    fun isValidMac(value: String?): Boolean =
        value != null && MAC_REGEX.matches(value.trim())

    fun parseMacList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (part in raw.split(',')) {
            val trimmed = part.trim().uppercase(Locale.ROOT)
            if (isValidMac(trimmed) && seen.add(trimmed)) {
                result += trimmed
            }
        }
        return result
    }

    /**
     * Returns a reordered comma-separated MAC list with [carplayMac] moved to the end, or `null`
     * when no change is needed (invalid MAC, fewer than 2 saved devices, MAC absent, or already
     * last).
     */
    fun moveToEndIfNeeded(deviceAddressList: String?, carplayMac: String?): String? {
        val target = carplayMac?.trim()?.uppercase(Locale.ROOT)
        if (!isValidMac(target)) return null
        val devices = parseMacList(deviceAddressList)
        if (devices.size < 2) return null
        val index = devices.indexOf(target)
        if (index < 0 || index == devices.lastIndex) return null

        val reordered = buildList(devices.size) {
            devices.forEachIndexed { idx, mac ->
                if (idx != index) add(mac)
            }
            add(devices[index])
        }
        return reordered.joinToString(",")
    }
}

/** Pure safety decision for whether a BT fallback attempt should execute. */
object BtFallbackDecision {
    /**
     * Returns `null` when recovery should proceed, or a non-null [BtFallbackOutcome] explaining
     * why recovery was skipped or failed before executing any write/restart command.
     */
    fun evaluate(status: BtFallbackStatus, manual: Boolean): BtFallbackOutcome? {
        if (!status.commandSuccess) return BtFallbackOutcome.FAILED
        if (status.isCarplayActive) return BtFallbackOutcome.SKIPPED_CARPLAY_ACTIVE
        if (status.btAdapterState != BtAdapterState.ON) return BtFallbackOutcome.SKIPPED_BT_DISABLED
        if (status.btConnectionState == BtConnectionState.CONNECTED) {
            return BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED
        }
        if (!manual) {
            if (status.btConnectionState == BtConnectionState.UNKNOWN) {
                return BtFallbackOutcome.FAILED
            }
            if (status.btConnectionState == BtConnectionState.CONNECTING ||
                status.isConnectingProp == true
            ) {
                return BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED
            }
            if (!status.isCarplayLockPresent) {
                return BtFallbackOutcome.SKIPPED_NOT_BLOCKED
            }
        }
        return null
    }
}

enum class BtFallbackBroadcastEvent {
    SCREEN_OFF,
    SCREEN_WAKEUP,
    CARPLAY_CONNECTED,
    CARPLAY_DISCONNECTED,
    IGNORED
}

object BtFallbackEventParser {
    const val ACTION_INFOTAINMENT_MODE_CHANGED =
        "com.autolink.powerservice.infotainment.mode.changed"
    const val EXTRA_IM_MODE = "im_mode"
    const val IM_MODE_SCREEN_OFF = 0
    const val IM_MODE_SCREEN_ON = 6

    const val ACTION_CARPLAY_STATUS = "com.autolink.carplay.carplaystatus"
    const val EXTRA_CARPLAY_STATUS = "status"
    const val CARPLAY_STATUS_DISCONNECTED = 0
    const val CARPLAY_STATUS_CONNECTED = 1

    fun parse(
        action: String?,
        imMode: Int?,
        carplayStatus: Int?
    ): BtFallbackBroadcastEvent = when (action) {
        ACTION_INFOTAINMENT_MODE_CHANGED -> when (imMode) {
            IM_MODE_SCREEN_OFF -> BtFallbackBroadcastEvent.SCREEN_OFF
            IM_MODE_SCREEN_ON -> BtFallbackBroadcastEvent.SCREEN_WAKEUP
            else -> BtFallbackBroadcastEvent.IGNORED
        }
        ACTION_CARPLAY_STATUS -> when (carplayStatus) {
            CARPLAY_STATUS_CONNECTED -> BtFallbackBroadcastEvent.CARPLAY_CONNECTED
            CARPLAY_STATUS_DISCONNECTED -> BtFallbackBroadcastEvent.CARPLAY_DISCONNECTED
            else -> BtFallbackBroadcastEvent.IGNORED
        }
        else -> BtFallbackBroadcastEvent.IGNORED
    }
}

/**
 * Prevents restart loops across repeated wakeups or duplicate `carplaystatus=0` broadcasts.
 * Caps automatic recovery to at most [MAX_APPLIES_PER_CYCLE] successful apply and
 * [MAX_EXECUTIONS_PER_CYCLE] total non-skipped attempts per boot/wakeup cycle, plus a global
 * [COOLDOWN_AFTER_APPLY_MS] cooldown after any successful apply.
 */
class BtFallbackLoopGuard(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var appliedInCycle = 0
    private var attemptsInCycle = 0
    private var lastApplyAtMs = NEVER
    private var lastWakeResetAtMs = NEVER
    private var lastCarplayDisconnectScheduleAtMs = NEVER
    private var screenWasOff = false

    @Synchronized
    fun onBoot() {
        appliedInCycle = 0
        attemptsInCycle = 0
        lastApplyAtMs = NEVER
        lastWakeResetAtMs = nowMs()
        lastCarplayDisconnectScheduleAtMs = NEVER
        screenWasOff = false
    }

    @Synchronized
    fun onScreenOff() {
        screenWasOff = true
        appliedInCycle = 0
        attemptsInCycle = 0
        lastCarplayDisconnectScheduleAtMs = NEVER
    }

    @Synchronized
    fun onWakeup() {
        val now = nowMs()
        if (screenWasOff || lastWakeResetAtMs == NEVER || now - lastWakeResetAtMs >= MIN_WAKE_CYCLE_GAP_MS) {
            appliedInCycle = 0
            attemptsInCycle = 0
            lastWakeResetAtMs = now
            lastCarplayDisconnectScheduleAtMs = NEVER
            screenWasOff = false
        }
    }

    @Synchronized
    fun resetForDisable() {
        appliedInCycle = 0
        attemptsInCycle = 0
        lastApplyAtMs = NEVER
        lastWakeResetAtMs = NEVER
        lastCarplayDisconnectScheduleAtMs = NEVER
        screenWasOff = false
    }

    @Synchronized
    fun canSchedule(trigger: BtFallbackTrigger): Boolean {
        if (trigger == BtFallbackTrigger.MANUAL) return true
        val now = nowMs()
        if (!canExecuteLocked(now)) return false
        if (trigger == BtFallbackTrigger.CARPLAY_DISCONNECTED) {
            if (lastCarplayDisconnectScheduleAtMs != NEVER &&
                now - lastCarplayDisconnectScheduleAtMs < CARPLAY_DISCONNECT_DEBOUNCE_MS
            ) {
                return false
            }
            lastCarplayDisconnectScheduleAtMs = now
        }
        return true
    }

    @Synchronized
    fun canExecuteNow(): Boolean = canExecuteLocked(nowMs())

    @Synchronized
    fun recordExecution(outcome: BtFallbackOutcome) {
        when (outcome) {
            BtFallbackOutcome.APPLIED -> {
                appliedInCycle++
                attemptsInCycle++
                lastApplyAtMs = nowMs()
            }
            BtFallbackOutcome.FAILED -> {
                attemptsInCycle++
            }
            BtFallbackOutcome.SKIPPED_CARPLAY_ACTIVE,
            BtFallbackOutcome.SKIPPED_BT_DISABLED,
            BtFallbackOutcome.SKIPPED_BT_ALREADY_CONNECTED,
            BtFallbackOutcome.SKIPPED_NOT_BLOCKED,
            BtFallbackOutcome.SKIPPED_LOOP_GUARD -> Unit
        }
    }

    private fun canExecuteLocked(now: Long): Boolean {
        if (appliedInCycle >= MAX_APPLIES_PER_CYCLE) return false
        if (attemptsInCycle >= MAX_EXECUTIONS_PER_CYCLE) return false
        if (lastApplyAtMs != NEVER && now - lastApplyAtMs < COOLDOWN_AFTER_APPLY_MS) return false
        return true
    }

    companion object {
        const val MAX_APPLIES_PER_CYCLE = 1
        const val MAX_EXECUTIONS_PER_CYCLE = 2
        const val COOLDOWN_AFTER_APPLY_MS = 60_000L
        const val MIN_WAKE_CYCLE_GAP_MS = 30_000L
        const val CARPLAY_DISCONNECT_DEBOUNCE_MS = 5_000L
        private const val NEVER = -1L
    }
}

internal object BtFallbackRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun nextRetryCount(currentRetryCount: Int): Int? {
        val next = currentRetryCount.coerceAtLeast(0) + 1
        return next.takeIf { it < MAX_ATTEMPTS }
    }
}
