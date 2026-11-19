package com.mmwtl.atlascodecfix

import android.content.Context
import androidx.core.content.edit

class CodecFixPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("codec_fix_prefs", Context.MODE_PRIVATE)

    var adbEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADB_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ADB_ENABLED, value) }

    var adbPort: Int
        get() = prefs.getInt(KEY_ADB_PORT, AdbEndpoint.ATLAS_PORT)
            .takeIf(AdbEndpoint::isValidPort)
            ?: AdbEndpoint.ATLAS_PORT
        set(value) = prefs.edit {
            putInt(
                KEY_ADB_PORT,
                if (AdbEndpoint.isValidPort(value)) value else AdbEndpoint.ATLAS_PORT
            )
        }

    var adbHost: String
        get() = prefs.getString(KEY_ADB_HOST, DEFAULT_ADB_HOST)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_ADB_HOST
        set(value) = prefs.edit { putString(KEY_ADB_HOST, value.trim().ifEmpty { DEFAULT_ADB_HOST }) }

    var autoApplyCodecFix: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_APPLY, value) }

    var autoApplyDelaySeconds: Int
        get() = AutoApplyDelay.normalize(
            prefs.getInt(KEY_AUTO_APPLY_DELAY_SECONDS, AutoApplyDelay.DEFAULT_SECONDS)
        )
        set(value) = prefs.edit {
            putInt(KEY_AUTO_APPLY_DELAY_SECONDS, AutoApplyDelay.normalize(value))
        }

    var autoAotCompile: Boolean
        get() = prefs.getBoolean(KEY_AUTO_AOT_COMPILE, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_AOT_COMPILE, value) }

    /** Package names the user added to the AOT list in addition to [AotCatalog]. */
    var aotCustomPackages: Set<String>
        get() = prefs.getStringSet(KEY_AOT_CUSTOM_PACKAGES, null).orEmpty()
            .filterTo(sortedSetOf()) { AotPackage.isValidPackageName(it) && !AotCatalog.isCatalogPackage(it) }
        set(value) = prefs.edit {
            putStringSet(
                KEY_AOT_CUSTOM_PACKAGES,
                value.filterTo(HashSet()) {
                    AotPackage.isValidPackageName(it) && !AotCatalog.isCatalogPackage(it)
                }
            )
        }

    internal var aotCatalogMigrated: Boolean
        get() = prefs.getBoolean(KEY_AOT_CATALOG_MIGRATED, false)
        set(value) = prefs.edit { putBoolean(KEY_AOT_CATALOG_MIGRATED, value) }

    /** True when the background job has any work: ADB is on and at least one auto action is. */
    val autoWorkEnabled: Boolean
        get() = adbEnabled && (autoApplyCodecFix || autoAotCompile)

    var autoBtFallback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BT_FALLBACK, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_BT_FALLBACK, value) }

    val autoBtFallbackEnabled: Boolean
        get() = adbEnabled && autoBtFallback

    internal var btFallbackRetryCount: Int
        get() = prefs.getInt(KEY_BT_FALLBACK_RETRY_COUNT, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_BT_FALLBACK_RETRY_COUNT, value.coerceAtLeast(0)) }

    var btFallbackLastRun: BtFallbackLastRun?
        get() {
            val timestamp = prefs.getLong(KEY_BT_FALLBACK_LAST_TS, 0L)
            val trigger = BtFallbackTrigger.fromWireName(
                prefs.getString(KEY_BT_FALLBACK_LAST_TRIGGER, null)
            )
            val outcome = BtFallbackOutcome.fromWireName(
                prefs.getString(KEY_BT_FALLBACK_LAST_OUTCOME, null)
            )
            if (timestamp <= 0L || trigger == null || outcome == null) return null
            return BtFallbackLastRun(
                timestampMs = timestamp,
                trigger = trigger,
                outcome = outcome,
                detail = prefs.getString(KEY_BT_FALLBACK_LAST_DETAIL, null).orEmpty()
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_BT_FALLBACK_LAST_TS)
                remove(KEY_BT_FALLBACK_LAST_TRIGGER)
                remove(KEY_BT_FALLBACK_LAST_OUTCOME)
                remove(KEY_BT_FALLBACK_LAST_DETAIL)
            } else {
                putLong(KEY_BT_FALLBACK_LAST_TS, value.timestampMs)
                putString(KEY_BT_FALLBACK_LAST_TRIGGER, value.trigger.wireName)
                putString(KEY_BT_FALLBACK_LAST_OUTCOME, value.outcome.wireName)
                putString(KEY_BT_FALLBACK_LAST_DETAIL, value.detail.take(MAX_BT_DETAIL_CHARS))
            }
        }

    var skipCompatibilityCheck: Boolean
        get() = prefs.getBoolean(KEY_SKIP_COMPATIBILITY_CHECK, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_COMPATIBILITY_CHECK, value) }

    var errorNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ERROR_NOTIFICATIONS_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ERROR_NOTIFICATIONS_ENABLED, value) }

    var selectedVariant: HevcCodecFixVariant
        get() = HevcCodecFixVariant.fromArgument(prefs.getString(KEY_SELECTED_VARIANT, null))
            ?: HevcCodecFixVariant.DEFAULT
        set(value) = prefs.edit { putString(KEY_SELECTED_VARIANT, value.argument) }

    internal var autoApplyRetryCount: Int
        get() = prefs.getInt(KEY_AUTO_APPLY_RETRY_COUNT, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_AUTO_APPLY_RETRY_COUNT, value.coerceAtLeast(0)) }

    internal var lastTelnetHost: String?
        get() = prefs.getString(KEY_LAST_TELNET_HOST, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_LAST_TELNET_HOST)
            else putString(KEY_LAST_TELNET_HOST, value.trim())
        }

    internal var lastTelnetPort: Int?
        get() = prefs.getInt(KEY_LAST_TELNET_PORT, 0).takeIf { it in 1..65_535 }
        set(value) = prefs.edit {
            if (value == null || value !in 1..65_535) remove(KEY_LAST_TELNET_PORT)
            else putInt(KEY_LAST_TELNET_PORT, value)
        }

    private companion object {
        private const val KEY_ADB_ENABLED = "adb_enabled"
        private const val KEY_ADB_HOST = "adb_host"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_AUTO_APPLY = "auto_apply"
        private const val KEY_AUTO_AOT_COMPILE = "auto_aot_compile"
        private const val KEY_AOT_CUSTOM_PACKAGES = "aot_custom_packages"
        private const val KEY_AOT_CATALOG_MIGRATED = "aot_catalog_migrated"
        private const val KEY_AUTO_APPLY_DELAY_SECONDS = "auto_apply_delay_seconds"
        private const val KEY_SKIP_COMPATIBILITY_CHECK = "skip_compatibility_check"
        private const val KEY_ERROR_NOTIFICATIONS_ENABLED = "error_notifications_enabled"
        private const val KEY_SELECTED_VARIANT = "selected_variant"
        private const val KEY_AUTO_APPLY_RETRY_COUNT = "auto_apply_retry_count"
        private const val KEY_AUTO_BT_FALLBACK = "auto_bt_fallback"
        private const val KEY_BT_FALLBACK_RETRY_COUNT = "bt_fallback_retry_count"
        private const val KEY_BT_FALLBACK_LAST_TS = "bt_fallback_last_ts"
        private const val KEY_BT_FALLBACK_LAST_TRIGGER = "bt_fallback_last_trigger"
        private const val KEY_BT_FALLBACK_LAST_OUTCOME = "bt_fallback_last_outcome"
        private const val KEY_BT_FALLBACK_LAST_DETAIL = "bt_fallback_last_detail"
        private const val MAX_BT_DETAIL_CHARS = 240
        private const val KEY_LAST_TELNET_HOST = "last_telnet_host"
        private const val KEY_LAST_TELNET_PORT = "last_telnet_port"
        private const val DEFAULT_ADB_HOST = "localhost"
    }
}
