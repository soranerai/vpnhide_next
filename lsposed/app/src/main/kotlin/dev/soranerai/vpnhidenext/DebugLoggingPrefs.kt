package dev.soranerai.vpnhidenext

import android.content.Context
import android.util.Log
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/**
 * Persisted "debug logging" preference and its propagation to the
 * out-of-process logging sinks:
 *
 *  - App Kotlin code → [VpnHideLog.enabled] (volatile)
 *  - system_server LSPosed hooks → [SS_DEBUG_LOGGING_FILE] (inotify-
 *    watched; flip takes effect immediately for already-running apps)
 *  - Kernel module → [KMOD_CTL] (stealthy IOCTL; per-boot
 *    only, re-seeded from [SS_DEBUG_LOGGING_FILE] by `kmod/module/
 *    service.sh` at boot so the persistent toggle survives reboots
 *    even when the app isn't opened)
 */
internal const val SS_DEBUG_LOGGING_FILE = "/data/system/vpnhide_debug_logging"

/** Default is OFF — stealth-first matches the project's anti-detection stance. */
internal suspend fun isEnabledInPrefs(context: Context): Boolean {
    val db = AppDatabase.getInstance(context)
    return db.globalConfigDao().getConfig()?.debugLogging == 1
}

/**
 * Flip the persisted preference and propagate it to every sink. Runs
 * SU commands, so callers should invoke from a background dispatcher.
 * Use this for the user-facing toggle in Diagnostics.
 */
internal suspend fun setDebugLoggingEnabled(
    context: Context,
    enabled: Boolean,
) {
    Log.d("VpnHide", "setDebugLoggingEnabled: enabled=$enabled")
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(debugLogging = if (enabled) 1 else 0))
    applyDebugLoggingRuntime(enabled)
}

/**
 * Push [enabled] to the runtime sinks only, without touching
 * SharedPreferences. Used by diagnostic capture paths (Collect debug
 * log button + [LogcatRecorder]) that temporarily force-enable logging
 * for the duration of a capture and then restore the user's persisted
 * choice — without this, the user-facing toggle would visually flip
 * under the user while they collected a bug report.
 */
internal fun applyDebugLoggingRuntime(enabled: Boolean) {
    VpnHideLog.enabled = enabled
    writeDebugFlagFiles(enabled)
}

private fun writeDebugFlagFiles(enabled: Boolean) {
    val value = if (enabled) "1" else "0"
    suExec(
        "[ -c $DEV_NODE ] && $KMOD_CTL debug $value; " +
            "true",
    )
}
