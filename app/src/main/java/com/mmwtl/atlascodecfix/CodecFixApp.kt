package com.mmwtl.atlascodecfix

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CodecFixApp : Application() {
    lateinit var prefs: CodecFixPrefs
        private set
    lateinit var adbClient: AdbClient
        private set
    lateinit var codecFixRepository: HevcCodecFixRepository
        private set
    lateinit var aotRepository: AotCompileRepository
        private set
    lateinit var btFallbackRepository: BtFallbackRepository
        private set
    val btFallbackLoopGuard = BtFallbackLoopGuard()
    lateinit var btFallbackMonitor: BtFallbackMonitor
        private set
    lateinit var errorNotifier: ErrorNotifier
        private set
    lateinit var manualApplyNotifier: ManualApplyNotifier
        private set

    private val _btFallbackStatus = MutableStateFlow<BtFallbackStatus?>(null)
    val btFallbackStatus: StateFlow<BtFallbackStatus?> = _btFallbackStatus.asStateFlow()

    private val _btFallbackLastRun = MutableStateFlow<BtFallbackLastRun?>(null)
    val btFallbackLastRun: StateFlow<BtFallbackLastRun?> = _btFallbackLastRun.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        prefs = CodecFixPrefs(this)
        migrateAotSelection()
        adbClient = AdbClient(this, prefs)
        codecFixRepository = HevcCodecFixRepository(this, adbClient)
        aotRepository = AotCompileRepository(
            adbClient,
            packages = ::aotPackages,
            isDebuggable = ::isDebuggablePackage
        )
        btFallbackRepository = BtFallbackRepository(adbClient)
        btFallbackMonitor = BtFallbackMonitor(this)
        errorNotifier = ErrorNotifier(this, prefs)
        manualApplyNotifier = ManualApplyNotifier(this)
        _btFallbackLastRun.value = prefs.btFallbackLastRun
        btFallbackMonitor.syncRegistration()
    }

    fun updateBtFallbackStatus(status: BtFallbackStatus) {
        _btFallbackStatus.value = status
    }

    fun recordBtFallbackResult(result: BtFallbackRunResult) {
        result.latestStatus?.let { _btFallbackStatus.value = it }
        val detail = when (result.outcome) {
            BtFallbackOutcome.APPLIED -> buildList {
                result.beforeStatus?.carplayDisconnectAddress?.let { mac ->
                    add(getString(R.string.bt_fallback_detail_cleared, mac))
                }
                if (result.reorderedDeviceAddress != null) {
                    add(getString(R.string.bt_fallback_detail_reordered))
                }
            }.joinToString(" · ")
            BtFallbackOutcome.FAILED -> result.output.trim().takeLast(180)
            else -> ""
        }
        recordBtFallbackLastRun(
            BtFallbackLastRun(
                timestampMs = result.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                trigger = result.trigger,
                outcome = result.outcome,
                detail = detail
            )
        )
    }

    fun recordBtFallbackLastRun(lastRun: BtFallbackLastRun) {
        prefs.btFallbackLastRun = lastRun
        _btFallbackLastRun.value = lastRun
    }

    private fun migrateAotSelection() {
        if (prefs.aotCatalogMigrated) return
        prefs.aotCustomPackages = AotCatalog.migrateSelection(prefs.aotCustomPackages) {
            applicationInfo(it) != null
        }
        prefs.aotCatalogMigrated = true
    }

    /** The catalog plus the user's own selection, labelled from the package manager. */
    fun aotPackages(): List<AotPackage> = AotCatalog.withCustom(
        prefs.aotCustomPackages.map { name -> AotPackage(name, applicationLabel(name) ?: name) }
    )

    /** Every installed application except AtlasTools itself; needs `QUERY_ALL_PACKAGES`. */
    fun installedApps(): List<InstalledApp> {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getInstalledApplications(0)
        }
        return apps.asSequence()
            .filter { it.packageName != packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                    isSystem = info.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) ==
                        ApplicationInfo.FLAG_SYSTEM
                )
            }
            .toList()
    }

    private fun applicationLabel(packageName: String): String? =
        applicationInfo(packageName)?.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank)

    private fun isDebuggablePackage(packageName: String): Boolean {
        val info = applicationInfo(packageName) ?: return false
        return info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
