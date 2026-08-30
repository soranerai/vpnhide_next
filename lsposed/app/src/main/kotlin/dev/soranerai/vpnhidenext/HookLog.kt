package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.hooks.core.XposedBridge

/**
 * Logging wrapper for hooks running in system_server.
 *
 * The hook process must not probe the app's private directory or leave a
 * shared policy/debug file in /data/system. Until an explicit access-controlled
 * logging bridge exists, hot-path logging remains disabled in this process;
 * install errors are still emitted through [e] when enabled by a future bridge.
 */
internal object HookLog {
    @Volatile private var enabled: Boolean = false

    fun install() {
        enabled = false
    }

    internal fun isEnabled(): Boolean = enabled

    fun i(msg: String) {
        if (enabled) XposedBridge.log(msg)
    }

    fun e(msg: String) {
        XposedBridge.log(msg)
    }
}
