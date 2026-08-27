package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/** Persists the filesystem-hiding implementation selected in [SettingsScreen]. */
internal suspend fun getUseNoMountForFileHiding(context: Context): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.useNoMountForFileHiding
}

/**
 * Publishes the chosen implementation as part of the authoritative policy so
 * the root daemon can switch from SUSFS to NoMount on its next reload.
 */
internal suspend fun setUseNoMountForFileHiding(
    context: Context,
    enabled: Boolean,
): Boolean {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(useNoMountForFileHiding = enabled))
    if (DatabaseSync.sync(context)) return true
    dao.insertConfig(current)
    return false
}
