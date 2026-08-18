package dev.soranerai.vpnhidenext

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.soranerai.vpnhidenext.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val HEALTH_CHECK_WORK_NAME = "health_check"
private const val HEALTH_NOTIFICATION_CHANNEL_ID = "protection_health"
private const val KMOD_DOWN_NOTIFICATION_ID = 2001
private const val LSPOSED_DOWN_NOTIFICATION_ID = 2002
private const val HEALTH_PREFS_NAME = "vpnhide_prefs"
private const val KEY_LAST_KMOD_DOWN_NOTIFIED_BOOT = "last_kmod_down_notified_boot_id"
private const val KEY_LAST_LSPOSED_DOWN_NOTIFIED_BOOT = "last_lsposed_down_notified_boot_id"

/**
 * Periodically checks whether protection actually came up this boot, for
 * apps the user has already configured — the dashboard only surfaces this
 * when opened, so a kmod that fails to insmod after an OTA/kernel update
 * (or LSPosed hooks that fail to install in system_server) can otherwise go
 * unnoticed while the user believes they're still protected.
 *
 * Deliberately only fires for a package/mechanism the user has actually
 * turned on (kmod/lsposed target count > 0) and that mechanism is
 * installed+enabled — never for "not installed" or "not configured", which
 * are already surfaced as dashboard issues and aren't regressions.
 *
 * De-duped per boot_id via SharedPreferences, same pattern [UpdateCheckWorker]
 * uses to only notify once per new app version.
 */
internal class HealthCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(applicationContext)
            val apps = db.appDao().getAllAppProtectionSync()
            val selfPkg = applicationContext.packageName
            val kmodTargetCount = apps.count { it.kmod && it.packageName != selfPkg }
            val lsposedTargetCount = apps.count { it.lsposed && it.packageName != selfPkg }
            if (kmodTargetCount == 0 && lsposedTargetCount == 0) return@withContext Result.success()

            val script =
                """
                echo "boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
                [ -f $kmodModuleDir/module.prop ] && echo "kmod_installed=1" || echo "kmod_installed=0"
                [ -c $DEV_NODE ] && echo "kmod_active=1" || echo "kmod_active=0"
                """.trimIndent()
            val (_, out) = suExecAsync(script)
            val props = parseHealthKeyValue(out)
            val bootId = props["boot_id"]?.trim().orEmpty()
            if (bootId.isBlank()) return@withContext Result.success()

            val prefs = applicationContext.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)

            if (kmodTargetCount > 0 && props["kmod_installed"] == "1" && props["kmod_active"] != "1") {
                notifyOncePerBoot(prefs, KEY_LAST_KMOD_DOWN_NOTIFIED_BOOT, bootId) {
                    showHealthNotification(
                        applicationContext,
                        KMOD_DOWN_NOTIFICATION_ID,
                        R.string.health_notification_kmod_title,
                        R.string.health_notification_kmod_text,
                    )
                }
            }

            if (lsposedTargetCount > 0 && props["kmod_active"] == "1") {
                val (_, hookStatusOut) = suExecAsync("$kmodCtl hook_status 2>/dev/null || true")
                val hookProps = parseHealthKeyValue(hookStatusOut)
                val hookBootId = hookProps["boot_id"]?.trim()
                val hooksActiveThisBoot = hookBootId != null && hookBootId == bootId
                if (!hooksActiveThisBoot) {
                    notifyOncePerBoot(prefs, KEY_LAST_LSPOSED_DOWN_NOTIFIED_BOOT, bootId) {
                        showHealthNotification(
                            applicationContext,
                            LSPOSED_DOWN_NOTIFICATION_ID,
                            R.string.health_notification_lsposed_title,
                            R.string.health_notification_lsposed_text,
                        )
                    }
                }
            }

            Result.success()
        }
}

private fun parseHealthKeyValue(text: String): Map<String, String> =
    text
        .lines()
        .mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

private inline fun notifyOncePerBoot(
    prefs: android.content.SharedPreferences,
    key: String,
    bootId: String,
    notify: () -> Unit,
) {
    if (prefs.getString(key, null) == bootId) return
    notify()
    prefs.edit().putString(key, bootId).apply()
}

private fun showHealthNotification(
    context: Context,
    notificationId: Int,
    titleRes: Int,
    textRes: Int,
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
                HEALTH_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.health_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val notification =
        NotificationCompat
            .Builder(context, HEALTH_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

    nm.notify(notificationId, notification)
}

/**
 * Schedules/cancels the periodic [HealthCheckWorker] job. Safe to call on
 * every app launch — `KEEP` makes re-scheduling a no-op if the job is
 * already queued with the same period.
 */
internal object HealthCheckScheduler {
    private val CHECK_INTERVAL = 1L to TimeUnit.HOURS

    suspend fun scheduleIfEnabled(context: Context) {
        val enabled = getHealthCheckEnabled(context)
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<HealthCheckWorker>(CHECK_INTERVAL.first, CHECK_INTERVAL.second)
                .setConstraints(Constraints.Builder().build())
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(HEALTH_CHECK_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HEALTH_CHECK_WORK_NAME)
    }
}
