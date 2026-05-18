package dev.soranerai.vpnhidenext

import android.os.FileObserver
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * [XposedBridge.log] wrapper gated by a filesystem flag set from the app.
 * Used by LSPosed hooks running inside `system_server`, where we don't
 * have access to the app's SharedPreferences.
 *
 * Source of truth is [SS_DEBUG_LOGGING_FILE] — the app rewrites it when
 * the user toggles the setting. We read it on [install] and via an
 * inotify watcher so a flip takes effect without restarting system_server.
 *
 * Only per-request / hot-path logs should go through [i]. Hook install
 * failures and other one-time errors use [e], which always prints —
 * losing those would make diagnosing "hooks didn't attach" reports
 * impossible.
 */
internal object HookLog {
    @Volatile private var enabled: Boolean = false

    @Volatile private var watcher: FileObserver? = null

    fun install() {
        reload()
        if (watcher != null) return
        val observer =
            object : FileObserver(
                File("/data/system"),
                CREATE or CLOSE_WRITE or MOVED_TO or MODIFY,
            ) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path == "vpnhide_debug_logging") reload()
                }
            }
        watcher = observer
        observer.startWatching()
    }

    private fun reload() {
        enabled =
            try {
                File(SS_DEBUG_LOGGING_FILE).readText().trim() == "1"
            } catch (_: Throwable) {
                false
            }
    }

    fun i(msg: String) {
        if (enabled) XposedBridge.log(msg)
    }

    /** Always prints if debug logging is enabled — used for install failures and other diagnostics. */
    fun e(msg: String) {
        if (enabled) XposedBridge.log(msg)
    }
}
