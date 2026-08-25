package dev.soranerai.vpnhidenext

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TARGET_REFRESH_WORK_NAME = "target_refresh"

/**
 * Periodic safety net for the app-owned policy. Package broadcasts normally
 * refresh immediately, while this worker repairs missed broadcasts, stale
 * UIDs after restore/reinstall, and materialized allowlist system entries.
 */
internal class TargetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (!getTargetRefreshEnabled(applicationContext)) return@withContext Result.success()
            runCatching { TargetsCache.reload(applicationContext) }
                .fold(
                    onSuccess = { Result.success() },
                    onFailure = { Result.retry() },
                )
        }
}

internal object TargetRefreshScheduler {
    private val REFRESH_INTERVAL = 6L to TimeUnit.HOURS

    suspend fun scheduleIfEnabled(context: Context) {
        if (getTargetRefreshEnabled(context)) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<TargetRefreshWorker>(REFRESH_INTERVAL.first, REFRESH_INTERVAL.second)
                .setConstraints(Constraints.Builder().build())
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TARGET_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TARGET_REFRESH_WORK_NAME)
    }
}
