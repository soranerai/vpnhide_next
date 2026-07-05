package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/**
 * Backing store for the "Check for updates in background" toggle in
 * [SettingsScreen]'s Experimental section. Purely app-local — unlike the
 * hook-mask toggles next to it, this never goes through [DatabaseSync],
 * since WorkManager scheduling has nothing to do with the kernel/LSPosed
 * side.
 */
internal suspend fun getUpdateCheckEnabled(context: Context): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.updateCheckEnabled
}

internal suspend fun setUpdateCheckEnabled(
    context: Context,
    enabled: Boolean,
) {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(updateCheckEnabled = enabled))
    if (enabled) UpdateCheckScheduler.schedule(context) else UpdateCheckScheduler.cancel(context)
}
