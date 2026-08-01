package dev.soranerai.vpnhidenext

import android.content.Context
import android.util.Log
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/*
 * Persisted "debug logging" preference and its propagation to the
 * out-of-process logging sinks:
 *
 *  - App Kotlin code → [VpnHideLog.enabled] (volatile)
 *  - Kernel module → the persisted JSON policy, applied through `load`
 *  - LSPosed hooks → process-local logging only; system_server does not
 *    probe app-private or shared filesystem state
 */

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
    DatabaseSync.sync(context)
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
}
