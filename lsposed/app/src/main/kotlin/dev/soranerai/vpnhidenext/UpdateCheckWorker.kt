package dev.soranerai.vpnhidenext

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val UPDATE_CHECK_WORK_NAME = "update_check"
private const val UPDATE_NOTIFICATION_CHANNEL_ID = "update_available"
private const val UPDATE_NOTIFICATION_ID = 1001
private const val UPDATE_PREFS_NAME = "vpnhide_prefs"
private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_update_version"

/**
 * Periodically polls GitHub Releases (via [checkForUpdate]) from a
 * WorkManager job so a new release surfaces as a notification even if the
 * user hasn't opened the app in a while — [UpdateCheckCache] only checks
 * while the app is in the foreground.
 *
 * De-duped by [KEY_LAST_NOTIFIED_VERSION] so a release doesn't re-notify
 * on every periodic run, only once per new version.
 */
internal class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (!getUpdateCheckEnabled(applicationContext)) return@withContext Result.success()
            val info = checkForUpdate(BuildConfig.VERSION_NAME) ?: return@withContext Result.success()

            val prefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
            if (lastNotified == info.latestVersion) return@withContext Result.success()

            showUpdateNotification(applicationContext, info)
            prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, info.latestVersion).apply()
            Result.success()
        }
}

private fun showUpdateNotification(
    context: Context,
    info: UpdateInfo,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(
            NotificationChannel(
                UPDATE_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
    val pendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val notification =
        NotificationCompat
            .Builder(context, UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, info.latestVersion))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

    nm.notify(UPDATE_NOTIFICATION_ID, notification)
}

/**
 * Schedules/cancels the periodic [UpdateCheckWorker] job. Safe to call on
 * every app launch — `KEEP` makes re-scheduling a no-op if the job is
 * already queued with the same period.
 */
internal object UpdateCheckScheduler {
    private val CHECK_INTERVAL = 12L to TimeUnit.HOURS

    suspend fun scheduleIfEnabled(context: Context) {
        val enabled = getUpdateCheckEnabled(context)
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_INTERVAL.first, CHECK_INTERVAL.second)
                .setConstraints(constraints)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(UPDATE_CHECK_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UPDATE_CHECK_WORK_NAME)
    }
}
