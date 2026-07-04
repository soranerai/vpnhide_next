package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig

/**
 * Backing store for the "Experimental features" toggles/dropdown in
 * [SettingsScreen]. The VPN-app-hiding and self-hiding toggles are both
 * just friendly shortcuts for individual bits of the existing
 * [DbGlobalConfig.javaHookMask] — the same mask edited bit-by-bit on the
 * "Hook Isolation" screen ([HookTestingScreen]) — so flipping either one
 * here also updates the corresponding switch there and vice versa.
 */
internal suspend fun isJavaHookBitEnabled(
    context: Context,
    bitIndex: Int,
): Boolean {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return (config.javaHookMask and (1L shl bitIndex)) != 0L
}

/**
 * Flip a single bit of the global java hook mask and push it down to the
 * kernel module / LSPosed side via [DatabaseSync.sync]. Returns whether
 * the sync succeeded — callers should revert their optimistic UI state on
 * failure, same as [HookTestingScreen]'s per-hook switches.
 */
internal suspend fun setJavaHookBit(
    context: Context,
    bitIndex: Int,
    enabled: Boolean,
): Boolean {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    val bit = 1L shl bitIndex
    val newMask = if (enabled) current.javaHookMask or bit else current.javaHookMask and bit.inv()
    dao.insertConfig(current.copy(javaHookMask = newMask))
    return DatabaseSync.sync(context)
}

internal suspend fun getSimSpoofMode(context: Context): String {
    val config = AppDatabase.getInstance(context).globalConfigDao().getConfig() ?: DbGlobalConfig()
    return config.simSpoofMode
}

/** No kernel/LSPosed side to sync yet — this is a pure UI placeholder for now. */
internal suspend fun setSimSpoofMode(
    context: Context,
    mode: String,
) {
    val db = AppDatabase.getInstance(context)
    val dao = db.globalConfigDao()
    val current = dao.getConfig() ?: DbGlobalConfig()
    dao.insertConfig(current.copy(simSpoofMode = mode))
}
