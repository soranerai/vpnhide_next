package dev.soranerai.vpnhidenext

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

internal enum class StatsRetentionPeriod(
    val configValue: String,
    val bucketSeconds: Int,
) {
    THIRTY_MIN("30m", 60),
    ONE_HOUR("1h", 120),
    SIX_HOURS("6h", 720),
    TWENTY_FOUR_HOURS("24h", 2880),
    UNLIMITED("unlimited", 31_536_000),
    ;

    companion object {
        fun fromConfigValue(value: String): StatsRetentionPeriod = entries.find { it.configValue == value } ?: THIRTY_MIN
    }
}

internal suspend fun getStatsRetentionPeriod(context: Context): StatsRetentionPeriod {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return StatsRetentionPeriod.fromConfigValue(config.statsRetentionPeriod)
}

internal suspend fun setStatsRetentionPeriod(
    context: Context,
    period: StatsRetentionPeriod,
): Boolean {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(statsRetentionPeriod = period.configValue))
    return DatabaseSync.sync(context)
}

@Composable
internal fun StatsRetentionPeriod.displayLabel(): String =
    stringResource(
        when (this) {
            StatsRetentionPeriod.THIRTY_MIN -> R.string.stats_period_30m
            StatsRetentionPeriod.ONE_HOUR -> R.string.stats_period_1h
            StatsRetentionPeriod.SIX_HOURS -> R.string.stats_period_6h
            StatsRetentionPeriod.TWENTY_FOUR_HOURS -> R.string.stats_period_24h
            StatsRetentionPeriod.UNLIMITED -> R.string.stats_period_unlimited
        },
    )
