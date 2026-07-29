package dev.soranerai.vpnhidenext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val BOOT_HEALTH_CHECK_WORK_NAME = "health_check_boot"

// The root daemon reloads the app-owned JSON policy after boot. The health
// check is delayed so module loading and daemon startup can settle first.
private const val BOOT_HEALTH_CHECK_DELAY_SEC = 60L

/**
 * Runs [HealthCheckWorker] once, shortly after boot, instead of waiting for
 * its next hourly periodic slot. The app has a startup gate that keeps the
 * splash screen up until root/kmod checks resolve, so a kmod that fails to
 * insmod is invisible until the user manually opens the app — this closes
 * the gap for exactly that case (kmod/hooks down right after *this* reboot).
 *
 * The periodic job in [HealthCheckScheduler] remains as a fallback: some
 * OEM skins (MIUI etc.) restrict boot receivers unless "autostart" is
 * granted, and it also catches failures that develop mid-session rather
 * than at boot.
 */
internal class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (getHealthCheckEnabled(appContext)) {
                    val request =
                        OneTimeWorkRequestBuilder<HealthCheckWorker>()
                            .setInitialDelay(BOOT_HEALTH_CHECK_DELAY_SEC, TimeUnit.SECONDS)
                            .setConstraints(Constraints.Builder().build())
                            .build()
                    WorkManager
                        .getInstance(appContext)
                        .enqueueUniqueWork(BOOT_HEALTH_CHECK_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
