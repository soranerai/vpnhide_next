package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/**
 * Backing store for the "Auto-test without VPN" toggle in [SettingsScreen]'s
 * Experimental section. Read directly by [DiagnosticsCache] before it
 * considers raising [SelfTestVpnCoordinator] — no WorkManager scheduling
 * involved here, unlike the update-check/health-check toggles.
 */
internal suspend fun getSelfTestVpnEnabled(context: Context): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.selfTestVpnEnabled
}

internal suspend fun setSelfTestVpnEnabled(
    context: Context,
    enabled: Boolean,
) {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(selfTestVpnEnabled = enabled))
}
