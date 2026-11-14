package com.mmwtl.atlascodecfix

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodecFixViewModel(
    private val app: CodecFixApp
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CodecFixScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.adbClient.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
            }
        }
        if (app.prefs.adbEnabled) {
            launchOperation(text(R.string.adb_connecting)) {
                val connected = app.adbClient.reconnect()
                val connectionStatus = text(
                    if (connected) R.string.adb_connected else R.string.adb_connect_failed
                )
                if (!connected) {
                    notifyError(text(R.string.error_title_adb_connection), connectionStatus)
                    _state.update { it.copy(status = connectionStatus) }
                    return@launchOperation
                }

                _state.update { it.copy(status = text(R.string.checking_current_fix)) }
                refreshCurrentVariantLocked()
            }
        }
    }

    fun setAdbEnabled(enabled: Boolean) {
        if (_state.value.isBusy) return
        app.prefs.adbEnabled = enabled
        _state.update { it.copy(adbEnabled = enabled) }
        if (enabled) {
            if (app.prefs.autoWorkEnabled) {
                AutoApplyScheduler.schedule(app, resetRetries = true)
            }
            connectAdb()
        } else {
            AutoApplyScheduler.cancel(app)
            disconnectAdb()
        }
    }

    fun setAdbHost(text: String) {
        if (_state.value.isBusy) return
        val sanitized = text.trim().take(MAX_HOST_LENGTH)
        _state.update { it.copy(adbHostText = sanitized) }
        if (sanitized.isNotBlank()) {
            app.prefs.adbHost = sanitized
            if (_state.value.adbMode == AdbEndpointMode.TELNET &&
                app.prefs.lastTelnetPort != null
            ) {
                app.prefs.lastTelnetHost = sanitized
            }
        }
    }

    fun setAdbMode(mode: AdbEndpointMode) {
        if (_state.value.isBusy) return
        val current = _state.value
        val port = when (mode) {
            AdbEndpointMode.ATLAS -> AdbEndpoint.ATLAS_PORT
            AdbEndpointMode.PREFACE -> AdbEndpoint.PREFACE_PORT
            AdbEndpointMode.TELNET -> AdbEndpoint.TELNET_PORT
            AdbEndpointMode.CUSTOM -> current.adbPortText.toIntOrNull()
                ?.takeIf { it in 1..65_535 && it != AdbEndpoint.ATLAS_PORT && it != AdbEndpoint.PREFACE_PORT }
                ?: DEFAULT_CUSTOM_PORT
        }
        app.prefs.adbPort = port
        _state.update {
            it.copy(
                adbMode = mode,
                adbPortText = if (mode == AdbEndpointMode.TELNET) {
                    app.prefs.lastTelnetPort?.toString().orEmpty()
                } else {
                    port.toString()
                }
            )
        }
        if (current.adbMode != mode && app.prefs.adbEnabled) {
            launchOperation(text(R.string.adb_connecting)) {
                val connected = app.adbClient.reconnect()
                val status = text(if (connected) R.string.adb_connected else R.string.adb_connect_failed)
                if (!connected) notifyError(text(R.string.error_title_adb_connection), status)
                _state.update { it.copy(status = status) }
            }
        }
    }

    fun setAdbPort(text: String) {
        if (_state.value.isBusy) return
        val sanitized = text.filter(Char::isDigit).take(5)
        if (_state.value.adbMode == AdbEndpointMode.TELNET) {
            val telnetPort = sanitized.toIntOrNull()?.takeIf { it in 1..65_535 }
            _state.update { it.copy(adbPortText = sanitized) }
            app.prefs.lastTelnetPort = telnetPort
            if (telnetPort != null) app.prefs.lastTelnetHost = app.prefs.adbHost
            return
        }
        _state.update {
            it.copy(
                adbPortText = sanitized,
                adbMode = sanitized.toIntOrNull()?.let(AdbEndpoint::modeForPort)
                    ?: AdbEndpointMode.CUSTOM
            )
        }
        val port = sanitized.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        app.prefs.adbPort = port
    }

    fun connectAdb() {
        if (_state.value.isBusy) return
        val endpoint = _state.value
        val port = endpoint.adbPortText.toIntOrNull()
        val invalidTelnetPort = endpoint.adbMode == AdbEndpointMode.TELNET &&
            endpoint.adbPortText.isNotBlank() && (port == null || port !in 1..65_535)
        val invalidAdbEndpoint = endpoint.adbMode != AdbEndpointMode.TELNET &&
            (!AdbEndpoint.isValidHost(endpoint.adbHostText) || port == null || port !in 1..65_535)
        if (invalidTelnetPort || invalidAdbEndpoint) {
            _state.update { it.copy(status = text(R.string.adb_endpoint_invalid)) }
            return
        }

        launchOperation(text(R.string.adb_connecting)) {
            val connected = app.adbClient.reconnect()
            val status = text(if (connected) R.string.adb_connected else R.string.adb_connect_failed)
            if (!connected) notifyError(text(R.string.error_title_adb_connection), status)
            _state.update { it.copy(status = status) }
        }
    }

    fun disconnectAdb() {
        launchOperation(text(R.string.adb_disconnecting)) {
            app.adbClient.disconnect()
            _state.update { it.copy(status = text(R.string.adb_disconnected)) }
        }
    }

    fun selectVariant(variant: HevcCodecFixVariant) {
        if (_state.value.isBusy) return
        app.prefs.selectedVariant = variant
        var codecAutoApplyDisabled = false
        if (
            variant.experimental &&
            app.prefs.autoApplyCodecFix &&
            !app.prefs.skipCompatibilityCheck
        ) {
            app.prefs.autoApplyCodecFix = false
            cancelAutoApplyIfUnused()
            codecAutoApplyDisabled = true
        }
        _state.update {
            it.copy(
                selectedVariant = variant,
                autoApplyCodecFix = if (codecAutoApplyDisabled) false else it.autoApplyCodecFix,
                status = if (codecAutoApplyDisabled) {
                    text(R.string.auto_disabled_for_experimental, variant.title)
                } else {
                    it.status
                }
            )
        }
    }

    fun setAutoApplyCodecFix(enabled: Boolean) {
        launchOperation {
            val current = _state.value
            if (enabled && !current.adbEnabled) {
                _state.update {
                    it.copy(autoApplyCodecFix = false, status = text(R.string.enable_adb_first))
                }
                return@launchOperation
            }
            val targetVariant = current.effectiveAutoApplyVariant
            if (enabled && targetVariant.experimental && !current.skipCompatibilityCheck) {
                _state.update {
                    it.copy(
                        autoApplyCodecFix = false,
                        status = text(R.string.auto_requires_unsafe, targetVariant.title)
                    )
                }
                return@launchOperation
            }

            if (
                enabled &&
                targetVariant != HevcCodecFixVariant.DEFAULT &&
                !current.skipCompatibilityCheck
            ) {
                _state.update { it.copy(status = text(R.string.checking_compatibility)) }
                val compatibility = app.codecFixRepository.checkCompatibility()
                if (!compatibility.autoApplyAllowed) {
                    val status = compatibility.output.trim().takeLast(STATUS_TEXT_LIMIT)
                        .ifBlank { text(R.string.auto_unavailable) }
                    app.prefs.autoApplyCodecFix = false
                    notifyError(text(R.string.error_title_auto_unavailable), status)
                    _state.update {
                        it.copy(autoApplyCodecFix = false, status = status)
                    }
                    return@launchOperation
                }
            }

            app.prefs.autoApplyCodecFix = enabled
            app.prefs.autoApplyRetryCount = 0
            val scheduled = if (enabled) {
                AutoApplyScheduler.schedule(app, resetRetries = true)
            } else {
                cancelAutoApplyIfUnused()
                true
            }
            _state.update {
                it.copy(
                    autoApplyCodecFix = enabled,
                    status = if (enabled && scheduled) {
                        text(R.string.auto_enabled_for, it.effectiveAutoApplyVariant.title)
                    } else if (enabled) {
                        text(R.string.auto_apply_schedule_failed)
                    } else {
                        text(R.string.auto_codecfix_disabled)
                    }
                )
            }
            if (enabled && !scheduled) {
                notifyError(
                    text(R.string.app_name),
                    text(R.string.auto_apply_schedule_failed)
                )
            }
        }
    }

    fun setAutoApplyDelay(text: String) {
        if (_state.value.isBusy) return
        val sanitized = text.filter(Char::isDigit).take(4)
        _state.update { it.copy(autoApplyDelayText = sanitized) }
        val seconds = AutoApplyDelay.parse(sanitized) ?: return
        app.prefs.autoApplyDelaySeconds = seconds
    }

    fun setSkipCompatibilityCheck(enabled: Boolean) {
        if (_state.value.isBusy) return
        app.prefs.skipCompatibilityCheck = enabled
        var codecAutoApplyDisabled = false
        if (
            !enabled &&
            app.prefs.autoApplyCodecFix &&
            app.prefs.selectedVariant.experimental
        ) {
            app.prefs.autoApplyCodecFix = false
            cancelAutoApplyIfUnused()
            codecAutoApplyDisabled = true
        }
        _state.update {
            it.copy(
                skipCompatibilityCheck = enabled,
                autoApplyCodecFix = if (codecAutoApplyDisabled) false else it.autoApplyCodecFix,
                status = when {
                    codecAutoApplyDisabled -> text(R.string.unsafe_disabled_auto_disabled)
                    enabled -> text(R.string.unsafe_enabled)
                    else -> text(R.string.compatibility_enabled)
                }
            )
        }
    }

    fun onAotTabOpened() {
        val current = _state.value
        if (current.aotStatuses == null && current.adbEnabled && !current.isBusy) {
            refreshAotStatus()
        }
    }

    fun refreshAotStatus() {
        launchOperation(text(R.string.aot_checking)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }
            val result = app.aotRepository.queryStatus()
            val status = if (result.commandSuccess) {
                aotSummary(result.packages)
            } else {
                result.output.trim().takeLast(STATUS_TEXT_LIMIT)
                    .ifBlank { text(R.string.aot_status_check_failed) }
            }
            if (!result.commandSuccess) notifyError(text(R.string.error_title_aot), status)
            _state.update {
                it.copy(aotStatuses = result.packages, aotPackages = app.aotPackages(), status = status)
            }
        }
    }

    fun compileOutdatedAot() {
        launchOperation(text(R.string.aot_checking)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }
            val result = app.aotRepository.compileOutdated(aotProgressListener())
            showAotCompileResult(result)
        }
    }

    fun compileAotPackage(pkg: AotPackage) {
        launchOperation(text(R.string.aot_checking)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }
            val result = app.aotRepository.compilePackage(pkg, aotProgressListener())
            showAotCompileResult(result)
        }
    }

    fun setAutoAotCompile(enabled: Boolean) {
        if (_state.value.isBusy) return
        if (enabled && !_state.value.adbEnabled) {
            _state.update { it.copy(autoAotCompile = false, status = text(R.string.enable_adb_first)) }
            return
        }
        app.prefs.autoAotCompile = enabled
        app.prefs.autoApplyRetryCount = 0
        val scheduled = if (enabled) {
            AutoApplyScheduler.schedule(app, resetRetries = true)
        } else {
            cancelAutoApplyIfUnused()
            true
        }
        val status = when {
            enabled && scheduled -> text(R.string.auto_aot_enabled)
            enabled -> text(R.string.auto_apply_schedule_failed)
            else -> text(R.string.auto_aot_disabled)
        }
        if (!scheduled) notifyError(text(R.string.app_name), status)
        _state.update { it.copy(autoAotCompile = enabled, status = status) }
    }

    fun openAotPicker() {
        if (_state.value.aotPicker != null) return
        _state.update { it.copy(aotPicker = AotPickerState(selected = app.prefs.aotCustomPackages)) }
        viewModelScope.launch {
            val apps = runCatching { withContext(Dispatchers.IO) { app.installedApps() } }
                .getOrDefault(emptyList())
            _state.update { state -> state.copy(aotPicker = state.aotPicker?.copy(apps = apps)) }
        }
    }

    fun setAotPickerQuery(query: String) = updateAotPicker { it.copy(query = query.take(MAX_QUERY_LENGTH)) }

    fun setAotPickerShowSystem(show: Boolean) = updateAotPicker { it.copy(showSystem = show) }

    fun toggleAotPickerApp(packageName: String) = updateAotPicker {
        it.copy(selected = if (packageName in it.selected) it.selected - packageName else it.selected + packageName)
    }

    fun dismissAotPicker() = _state.update { it.copy(aotPicker = null) }

    fun confirmAotPicker() {
        val picker = _state.value.aotPicker ?: return
        val changed = picker.selected != app.prefs.aotCustomPackages
        app.prefs.aotCustomPackages = picker.selected
        _state.update {
            it.copy(
                aotPicker = null,
                aotPackages = app.aotPackages(),
                status = if (changed) text(R.string.aot_selection_saved, picker.selected.size) else it.status
            )
        }
        val current = _state.value
        if (changed && current.adbEnabled && !current.isBusy) refreshAotStatus()
    }

    private fun updateAotPicker(transform: (AotPickerState) -> AotPickerState) =
        _state.update { state -> state.copy(aotPicker = state.aotPicker?.let(transform)) }

    private fun aotProgressListener() = AotCompileRepository.ProgressListener { pkg, index, total ->
        _state.update {
            it.copy(status = text(R.string.aot_compiling, pkg.title, index, total))
        }
    }

    private fun showAotCompileResult(result: AotCompileResult) {
        val status = when {
            !result.statusSuccess -> result.statusOutput.trim().takeLast(STATUS_TEXT_LIMIT)
                .ifBlank { text(R.string.aot_status_check_failed) }
            result.requested.isEmpty() -> text(R.string.aot_nothing_to_compile)
            result.success -> text(
                R.string.aot_compiled,
                result.compiled.joinToString { it.title }
            )
            else -> buildList {
                if (result.compiled.isNotEmpty()) {
                    add(text(R.string.aot_compiled, result.compiled.joinToString { it.title }))
                }
                add(text(R.string.aot_compile_failed, result.failed.joinToString { it.title }))
                result.outcomes.lastOrNull { !it.success }?.output?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::add)
            }.joinToString("\n").takeLast(STATUS_TEXT_LIMIT)
        }
        if (!result.success) notifyError(text(R.string.error_title_aot), status)
        _state.update {
            it.copy(aotStatuses = result.statuses, aotPackages = app.aotPackages(), status = status)
        }
    }

    private fun aotSummary(statuses: List<AotPackageStatus>): String {
        val installed = statuses.count { it.state != AotCompileState.NOT_INSTALLED }
        val compiled = statuses.count { it.state == AotCompileState.COMPILED }
        return text(R.string.aot_summary, compiled, installed)
    }

    fun setErrorNotificationsEnabled(enabled: Boolean) {
        app.prefs.errorNotificationsEnabled = enabled
        _state.update {
            it.copy(
                errorNotificationsEnabled = enabled,
                status = if (enabled) {
                    text(R.string.notifications_enabled)
                } else {
                    text(R.string.notifications_disabled)
                }
            )
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        app.prefs.errorNotificationsEnabled = granted
        _state.update {
            it.copy(
                errorNotificationsEnabled = granted,
                status = if (granted) {
                    text(R.string.notifications_enabled)
                } else {
                    text(R.string.notification_permission_denied)
                }
            )
        }
    }

    fun refreshCurrentVariant() {
        launchOperation(text(R.string.checking_current_fix)) {
            refreshCurrentVariantLocked()
        }
    }

    fun requestApplySelectedVariant() {
        val current = _state.value
        if (current.isBusy) return
        if (!current.adbEnabled) {
            _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
            return
        }
        val variant = current.selectedVariant
        if (variant.experimental && !current.skipCompatibilityCheck) {
            _state.update {
                it.copy(
                    confirmation = ApplyConfirmation(variant, ConfirmationReason.EXPERIMENTAL),
                    status = text(R.string.experimental_warning_short, variant.title)
                )
            }
            return
        }
        applyVariant(
            variant = variant,
            allowRisky = current.skipCompatibilityCheck,
            allowExperimental = current.skipCompatibilityCheck
        )
    }

    fun confirmApply() {
        if (_state.value.isBusy) return
        val confirmation = _state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null) }
        applyVariant(
            variant = confirmation.variant,
            allowRisky = true,
            allowExperimental = true
        )
    }

    fun dismissApplyConfirmation() {
        if (_state.value.isBusy) return
        _state.update { it.copy(confirmation = null) }
    }

    fun runPreflightCheck() {
        launchOperation(text(R.string.running_preflight)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }

            val result = app.codecFixRepository.checkCompatibility()
            val report = result.output.trim().takeLast(PREFLIGHT_REPORT_LIMIT)
                .ifBlank { text(R.string.preflight_no_output) }
            if (!result.commandSuccess) {
                notifyError(text(R.string.error_title_profile_check), report)
            }
            _state.update {
                it.copy(
                    currentVariant = result.variant ?: it.currentVariant,
                    status = report
                )
            }
        }
    }

    fun runDiagnostics() {
        launchOperation(text(R.string.running_diagnostics)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }

            val result = app.codecFixRepository.collectDiagnostics()
            val report = result.output.trim().takeLast(DIAGNOSTICS_REPORT_LIMIT)
                .ifBlank { text(R.string.diagnostics_no_output) }
            if (!result.commandSuccess) {
                notifyError(text(R.string.error_title_diagnostics), report)
            }
            _state.update {
                it.copy(
                    currentVariant = result.variant ?: it.currentVariant,
                    status = report
                )
            }
        }
    }

    fun exportAnalysisBundle() {
        launchOperation(text(R.string.running_analysis_export)) {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launchOperation
            }

            val result = app.codecFixRepository.exportAnalysisBundle()
            val report = result.output.trim().takeLast(DIAGNOSTICS_REPORT_LIMIT)
                .ifBlank { text(R.string.analysis_export_no_output) }
            if (!result.commandSuccess) {
                notifyError(text(R.string.error_title_analysis_export), report)
            }
            _state.update {
                it.copy(
                    status = if (result.commandSuccess) {
                        text(R.string.analysis_export_succeeded, result.exportPath)
                    } else {
                        report
                    }
                )
            }
        }
    }

    fun loadAvailableCodecs() {
        if (_state.value.isCodecListLoading) return
        _state.update {
            it.copy(isCodecListLoading = true, codecListStatus = text(R.string.collecting_codecs))
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { collectAvailableCodecs() }
            }
            result
                .onSuccess { codecs ->
                    _state.update {
                        it.copy(
                            codecs = codecs,
                            isCodecListLoading = false,
                            isCodecListVisible = true,
                            codecListStatus = text(R.string.codecs_found, codecs.size)
                        )
                    }
                }
                .onFailure { t ->
                    val status = t.message ?: t.javaClass.simpleName
                    notifyError(text(R.string.error_title_codec_list), status)
                    _state.update { it.copy(isCodecListLoading = false, codecListStatus = status) }
                }
        }
    }

    fun hideAvailableCodecs() = _state.update { it.copy(isCodecListVisible = false) }

    fun setCodecHardwareFilter(enabled: Boolean) = _state.update { it.copy(showHardwareCodecs = enabled) }
    fun setCodecSoftwareFilter(enabled: Boolean) = _state.update { it.copy(showSoftwareCodecs = enabled) }
    fun setCodecAudioFilter(enabled: Boolean) = _state.update { it.copy(showAudioCodecs = enabled) }
    fun setCodecVideoFilter(enabled: Boolean) = _state.update { it.copy(showVideoCodecs = enabled) }

    private fun initialState(): CodecFixScreenState {
        val prefs = app.prefs
        return CodecFixScreenState(
            adbEnabled = prefs.adbEnabled,
            adbHostText = prefs.adbHost,
            adbPortText = if (prefs.adbPort == AdbEndpoint.TELNET_PORT) {
                prefs.lastTelnetPort?.toString().orEmpty()
            } else {
                prefs.adbPort.toString()
            },
            adbMode = AdbEndpoint.modeForPort(prefs.adbPort),
            autoApplyCodecFix = prefs.autoApplyCodecFix,
            autoApplyDelayText = prefs.autoApplyDelaySeconds.toString(),
            skipCompatibilityCheck = prefs.skipCompatibilityCheck,
            errorNotificationsEnabled = prefs.errorNotificationsEnabled,
            autoAotCompile = prefs.autoAotCompile,
            aotPackages = app.aotPackages(),
            selectedVariant = prefs.selectedVariant
        )
    }

    private fun cancelAutoApplyIfUnused() {
        if (!app.prefs.autoWorkEnabled) {
            AutoApplyScheduler.cancel(app)
        }
    }

    private fun launchOperation(
        status: String? = null,
        block: suspend () -> Unit
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = status ?: it.status) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    private suspend fun refreshCurrentVariantLocked() {
        val detected = app.codecFixRepository.detectCurrentVariant()
        val status = if (detected.commandSuccess) {
            text(
                R.string.current_variant,
                detected.variant?.title ?: text(R.string.variant_unknown)
            )
        } else {
            detected.output.trim().takeLast(STATUS_TEXT_LIMIT)
                .ifBlank { text(R.string.check_not_completed) }
        }
        if (!detected.commandSuccess) notifyError(text(R.string.error_title_fix_check), status)
        _state.update { it.copy(currentVariant = detected.variant, status = status) }
    }

    private fun applyVariant(
        variant: HevcCodecFixVariant,
        allowRisky: Boolean,
        allowExperimental: Boolean
    ) {
        launchOperation(text(R.string.applying_variant, variant.title)) {
            val result = app.codecFixRepository.applyVariant(
                variant = variant,
                allowRisky = allowRisky,
                skipCompatibilityCheck = app.prefs.skipCompatibilityCheck,
                allowExperimental = allowExperimental
            )

            if (!result.success &&
                !allowRisky &&
                result.compatibility?.status == HevcCodecFixCompatibilityStatus.RISKY
            ) {
                _state.update {
                    it.copy(
                        confirmation = ApplyConfirmation(variant, ConfirmationReason.RISKY),
                        status = result.compatibility.reason
                            ?: text(R.string.compatibility_not_confirmed)
                    )
                }
                return@launchOperation
            }

            if (result.success) app.prefs.selectedVariant = variant
            val status = if (result.success) {
                manualApplyStatus(variant)
            } else {
                listOf(
                    result.runOutput,
                    result.detectOutput,
                    if (result.restoredToDefault) {
                        text(R.string.automatic_restore_succeeded)
                    } else {
                        result.recoveryOutput
                    }
                )
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                    .trim()
                    .takeLast(STATUS_TEXT_LIMIT)
                    .ifBlank { text(R.string.apply_failed) }
            }

            if (!result.success) {
                notifyError(text(R.string.error_title_apply_failed, variant.title), status)
            }
            _state.update {
                it.copy(
                    currentVariant = result.detectedVariant,
                    status = status
                )
            }
            when {
                result.success -> app.manualApplyNotifier.notify(status)
                result.restoredToDefault -> app.manualApplyNotifier.notify(
                    text(R.string.manual_apply_rolled_back, variant.title)
                )
            }
        }
    }

    private fun manualApplyStatus(variant: HevcCodecFixVariant): String {
        return if (variant == HevcCodecFixVariant.DEFAULT) {
            text(R.string.manual_default_restored, variant.title)
        } else {
            text(R.string.manual_profile_applied, variant.title)
        }
    }

    private fun collectAvailableCodecs(): List<AvailableCodec> {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .map { info ->
                AvailableCodec(
                    name = info.name,
                    supportedTypes = info.supportedTypes.map(String::lowercase).sorted(),
                    isEncoder = info.isEncoder,
                    acceleration = info.accelerationKind()
                )
            }
            .sortedWith(
                compareBy<AvailableCodec> { it.primaryKind.sortOrder }
                    .thenBy { it.acceleration.sortOrder }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun notifyError(title: String, message: String) {
        app.errorNotifier.notify(title, message)
    }

    private fun text(@StringRes resource: Int, vararg arguments: Any): String {
        return app.getString(resource, *arguments)
    }

    class Factory(
        private val app: CodecFixApp
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CodecFixViewModel::class.java))
            return CodecFixViewModel(app) as T
        }
    }

    private companion object {
        private const val MAX_HOST_LENGTH = 253
        private const val DEFAULT_CUSTOM_PORT = 5556
        private const val PREFLIGHT_REPORT_LIMIT = 2_000
        private const val DIAGNOSTICS_REPORT_LIMIT = 6_000
        private const val STATUS_TEXT_LIMIT = 220
        private const val MAX_QUERY_LENGTH = 100
    }
}

data class CodecFixScreenState(
    val adbEnabled: Boolean = false,
    val adbHostText: String = "localhost",
    val adbPortText: String = "5555",
    val adbMode: AdbEndpointMode = AdbEndpointMode.ATLAS,
    val autoApplyCodecFix: Boolean = false,
    val autoApplyDelayText: String = AutoApplyDelay.DEFAULT_SECONDS.toString(),
    val skipCompatibilityCheck: Boolean = false,
    val errorNotificationsEnabled: Boolean = false,
    val selectedVariant: HevcCodecFixVariant = HevcCodecFixVariant.DEFAULT,
    val currentVariant: HevcCodecFixVariant? = null,
    val codecs: List<AvailableCodec> = emptyList(),
    val isCodecListLoading: Boolean = false,
    val isCodecListVisible: Boolean = false,
    val codecListStatus: String? = null,
    val showHardwareCodecs: Boolean = true,
    val showSoftwareCodecs: Boolean = true,
    val showAudioCodecs: Boolean = true,
    val showVideoCodecs: Boolean = true,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
    val isBusy: Boolean = false,
    val status: String? = null,
    val confirmation: ApplyConfirmation? = null,
    val autoAotCompile: Boolean = false,
    /** `null` until the AOT state has been read from the device. */
    val aotStatuses: List<AotPackageStatus>? = null,
    /** The catalog plus the user's own selection, in compile order. */
    val aotPackages: List<AotPackage> = AotCatalog.PACKAGES,
    /** Non-null while the AOT app picker is open. */
    val aotPicker: AotPickerState? = null
) {
    val effectiveAutoApplyVariant: HevcCodecFixVariant
        get() = selectedVariant

    val isAutoApplyDelayValid: Boolean
        get() = AutoApplyDelay.parse(autoApplyDelayText) != null
}

data class AotPickerState(
    /** `null` while the installed app list is loading. */
    val apps: List<InstalledApp>? = null,
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val showSystem: Boolean = false
) {
    val entries: List<InstalledApp>?
        get() = apps?.let { AotAppPicker.entries(it, selected, showSystem, query) }
}

data class ApplyConfirmation(
    val variant: HevcCodecFixVariant,
    val reason: ConfirmationReason
)

enum class ConfirmationReason {
    EXPERIMENTAL,
    RISKY
}

data class AvailableCodec(
    val name: String,
    val supportedTypes: List<String>,
    val isEncoder: Boolean,
    val acceleration: CodecAcceleration
) {
    val primaryKind: CodecMediaKind
        get() = when {
            supportedTypes.any { it.startsWith("video/") } -> CodecMediaKind.VIDEO
            supportedTypes.any { it.startsWith("audio/") } -> CodecMediaKind.AUDIO
            else -> CodecMediaKind.OTHER
        }
}

enum class CodecAcceleration(
    @param:StringRes val titleRes: Int,
    val sortOrder: Int
) {
    HARDWARE(R.string.codec_hardware, 0),
    SOFTWARE(R.string.codec_software, 1),
    UNKNOWN(R.string.codec_acceleration_unknown, 2)
}

enum class CodecMediaKind(
    @param:StringRes val titleRes: Int,
    val sortOrder: Int
) {
    VIDEO(R.string.codec_video, 0),
    AUDIO(R.string.codec_audio, 1),
    OTHER(R.string.codec_other, 2)
}

private fun MediaCodecInfo.accelerationKind(): CodecAcceleration {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        return when {
            isHardwareAccelerated -> CodecAcceleration.HARDWARE
            isSoftwareOnly -> CodecAcceleration.SOFTWARE
            else -> CodecAcceleration.UNKNOWN
        }
    }

    val lowerName = name.lowercase()
    return when {
        lowerName.startsWith("omx.google.") ||
            lowerName.startsWith("c2.android.") ||
            lowerName.startsWith("c2.google.") ||
            lowerName.startsWith("omx.ffmpeg.") -> CodecAcceleration.SOFTWARE
        lowerName.contains("qcom") ||
            lowerName.contains("qti") ||
            lowerName.contains("mtk") ||
            lowerName.contains("exynos") ||
            lowerName.contains("hisi") -> CodecAcceleration.HARDWARE
        else -> CodecAcceleration.UNKNOWN
    }
}
