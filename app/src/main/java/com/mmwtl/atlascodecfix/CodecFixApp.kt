package com.mmwtl.atlascodecfix

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

class CodecFixApp : Application() {
    lateinit var prefs: CodecFixPrefs
        private set
    lateinit var adbClient: AdbClient
        private set
    lateinit var codecFixRepository: HevcCodecFixRepository
        private set
    lateinit var aotRepository: AotCompileRepository
        private set
    lateinit var errorNotifier: ErrorNotifier
        private set
    lateinit var manualApplyNotifier: ManualApplyNotifier
        private set

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
        errorNotifier = ErrorNotifier(this, prefs)
        manualApplyNotifier = ManualApplyNotifier(this)
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
