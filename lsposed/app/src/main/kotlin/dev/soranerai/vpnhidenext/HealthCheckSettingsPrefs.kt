package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/**
 * Backing store for the "Protection health check" toggle in [SettingsScreen]'s
 * Experimental section. Mirrors [UpdateCheckSettingsPrefs] but drives
 * [HealthCheckScheduler] instead.
 */
internal suspend fun getHealthCheckEnabled(context: Context): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.healthCheckEnabled
}

internal suspend fun setHealthCheckEnabled(
    context: Context,
    enabled: Boolean,
) {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(healthCheckEnabled = enabled))
    if (enabled) HealthCheckScheduler.schedule(context) else HealthCheckScheduler.cancel(context)
}
