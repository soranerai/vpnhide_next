package dev.soranerai.vpnhidenext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps authoritative policy UIDs current across install and reinstall. */
internal class PackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) {
            return
        }
        if (intent.action !in supportedActions) return

        val pendingResult = goAsync()
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiverScope.launch {
            try {
                TargetsCache.reload(context.applicationContext)
            } finally {
                pendingResult.finish()
                receiverScope.cancel()
            }
        }
    }

    private companion object {
        val supportedActions =
            setOf(
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
            )
    }
}
