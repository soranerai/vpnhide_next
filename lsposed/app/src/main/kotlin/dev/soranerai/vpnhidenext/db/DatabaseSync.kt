package dev.soranerai.vpnhidenext.db

import android.content.Context
import dev.soranerai.vpnhidenext.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Applies the complete declarative policy through the single backend entrypoint. */
internal object DatabaseSync {
    suspend fun sync(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val configFile = AppDatabase.policyConfigFile(context)
            if (!configFile.isFile) return@withContext false

            val statsBucketSeconds =
                StatsRetentionPeriod.fromConfigValue(
                    AppDatabase.getInstance(context).globalConfigDao().getConfig()?.statsRetentionPeriod
                        ?: "30m",
                ).bucketSeconds
            val selfUid = context.applicationInfo.uid
            val quotedConfig = shellQuote(configFile.absolutePath)
            val command =
                "$kmodCtl load $quotedConfig $selfUid" +
                    " && $kmodCtl stats_window $statsBucketSeconds"

            val (exitCode, _) = suExec(command)
            exitCode == 0
        }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
