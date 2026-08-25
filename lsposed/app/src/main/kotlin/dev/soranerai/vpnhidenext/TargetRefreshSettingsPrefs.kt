package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

internal suspend fun getTargetRefreshEnabled(context: Context): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.targetRefreshEnabled
}

internal suspend fun setTargetRefreshEnabled(
    context: Context,
    enabled: Boolean,
) {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(targetRefreshEnabled = enabled))
    if (enabled) TargetRefreshScheduler.schedule(context) else TargetRefreshScheduler.cancel(context)
}
