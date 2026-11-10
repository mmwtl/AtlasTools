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
        adbClient = AdbClient(this, prefs)
        codecFixRepository = HevcCodecFixRepository(this, adbClient)
        aotRepository = AotCompileRepository(adbClient, isDebuggable = ::isDebuggablePackage)
        errorNotifier = ErrorNotifier(this, prefs)
        manualApplyNotifier = ManualApplyNotifier(this)
    }

    /** Requires the catalog packages in the manifest `<queries>` block on Android 11+. */
    private fun isDebuggablePackage(packageName: String): Boolean {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
