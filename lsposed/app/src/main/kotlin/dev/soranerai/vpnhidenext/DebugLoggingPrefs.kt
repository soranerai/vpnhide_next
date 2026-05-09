package dev.soranerai.vpnhidenext

import android.content.Context

/**
 * Persisted "debug logging" preference and its propagation to the
 * out-of-process logging sinks:
 *
 *  - App Kotlin code → [VpnHideLog.enabled] (volatile)
 *  - system_server LSPosed hooks → [SS_DEBUG_LOGGING_FILE] (inotify-
 *    watched; flip takes effect immediately for already-running apps)
 *  - Zygisk module → [ZYGISK_DEBUG_LOGGING_FILE] (read when the module
 *    is injected into a forked app, so target apps need to be restarted
 *    before a flip takes effect for them — identical to targets.txt)
 *  - Kernel module → [KMOD_CTL] (stealthy IOCTL; per-boot
 *    only, re-seeded from [SS_DEBUG_LOGGING_FILE] by `kmod/module/
 *    service.sh` at boot so the persistent toggle survives reboots
 *    even when the app isn't opened)
 */
private const val PREFS_NAME = "vpnhide_prefs"
private const val KEY_DEBUG_LOGGING = "debug_logging"

internal const val SS_DEBUG_LOGGING_FILE = "/data/system/vpnhide_debug_logging"

// Canonical persistent location — survives zygisk module reinstall.
// `zygisk/module/service.sh` copies this into the module dir below at
// every boot, so a reinstall doesn't lose the user's preference.
internal const val ZYGISK_DEBUG_LOGGING_PERSIST = "/data/adb/vpnhide_zygisk/debug_logging"

// Module-dir mirror — what `zygisk/src/lib.rs::apply_debug_logging_flag`
// reads via the `get_module_dir()` fd at every fork. Wiped on module
// reinstall (KSU/Magisk replaces the whole tree from the zip).
internal const val ZYGISK_DEBUG_LOGGING_FILE = "/data/adb/modules/vpnhide_zygisk/debug_logging"
internal const val ZYGISK_PERSIST_DIR = "/data/adb/vpnhide_zygisk"

/** Default is OFF — stealth-first matches the project's anti-detection stance. */
internal fun isEnabledInPrefs(context: Context): Boolean =
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_DEBUG_LOGGING, false)

/**
 * Flip the persisted preference and propagate it to every sink. Runs
 * SU commands, so callers should invoke from a background dispatcher.
 * Use this for the user-facing toggle in Diagnostics.
 */
internal fun setDebugLoggingEnabled(
    context: Context,
    enabled: Boolean,
) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DEBUG_LOGGING, enabled)
        .apply()
    applyDebugLoggingRuntime(enabled)
}

/**
 * Push [enabled] to the runtime sinks only, without touching
 * SharedPreferences. Used by diagnostic capture paths (Collect debug
 * log button + [LogcatRecorder]) that temporarily force-enable logging
 * for the duration of a capture and then restore the user's persisted
 * choice — without this, the user-facing toggle would visually flip
 * under the user while they collected a bug report.
 */
internal fun applyDebugLoggingRuntime(enabled: Boolean) {
    VpnHideLog.enabled = enabled
    writeDebugFlagFiles(enabled)
}

private fun writeDebugFlagFiles(enabled: Boolean) {
    val value = if (enabled) "1" else "0"
    // Independent writes batched into one su invocation to keep the
    // toggle UI snappy — each round-trip is ~50-100ms from Kotlin.
    // Sinks fail silently when the corresponding component isn't
    // installed / loaded (zygisk module dir absent, kmod /proc node
    // absent), which is the desired behavior: the flag has no target.
    //
    //   1. system_server hook file: /data/system, labelled
    //      system_data_file so system_server (and nothing else) can
    //      read it. `chcon || true` so devices without chcon still
    //      end up with a working file at the kernel-default label.
    //   2. Zygisk persistent file: survives module reinstall.
    //      service.sh copies it into the module dir at every boot.
    //   3. Zygisk module-dir mirror: what the .so actually reads via
    //      get_module_dir() fd on every fork. Wiped on module
    //      reinstall — MainActivity re-propagates from prefs as a
    //      safety-net for "reinstall mid-session, no reboot yet".
    //   4. Kernel module /proc toggle: in-kernel volatile, per-boot.
    //      service.sh re-seeds it at every boot from SS_DEBUG_LOGGING_FILE,
    //      so a persistent ON survives reboots without the app needing
    //      to open.
    suExec(
        "echo '$value' > $SS_DEBUG_LOGGING_FILE" +
            " && chmod 644 $SS_DEBUG_LOGGING_FILE" +
            " && chcon u:object_r:system_data_file:s0 $SS_DEBUG_LOGGING_FILE 2>/dev/null; " +
            "[ -d $ZYGISK_PERSIST_DIR ] && {" +
            " echo '$value' > $ZYGISK_DEBUG_LOGGING_PERSIST" +
            " && chmod 644 $ZYGISK_DEBUG_LOGGING_PERSIST 2>/dev/null; }; " +
            "[ -d $ZYGISK_MODULE_DIR ] &&" +
            " echo '$value' > $ZYGISK_DEBUG_LOGGING_FILE" +
            " && chmod 644 $ZYGISK_DEBUG_LOGGING_FILE 2>/dev/null; " +
            "[ -c $DEV_NODE ] && $KMOD_CTL debug $value; " +
            "true",
    )
}
