package dev.soranerai.vpnhidenext.db

// Bit 5 already implements "hide VPN apps from target apps" (PackageManagerHook +
// ConnectivityHook). Bit 7 ("hide own package from PackageManager") is a new,
// opt-in experimental hook — new installs should NOT get it enabled by
// default, unlike bits 0-6 which default on.
internal const val JAVA_HOOK_BIT_HIDE_VPN_APPS = 5
internal const val JAVA_HOOK_BIT_SELF_HIDE = 7
internal const val DEFAULT_JAVA_HOOK_MASK = 0xFFFFFFFFL and (1L shl JAVA_HOOK_BIT_SELF_HIDE).inv()

internal enum class PolicyListMode {
    BLACKLIST,
    ALLOWLIST,
}

internal data class DbGlobalConfig(
    val id: String = "default",
    val listMode: PolicyListMode = PolicyListMode.BLACKLIST,
    val kernelHookMask: Long = 0xFFFFFFFFL,
    val javaHookMask: Long = DEFAULT_JAVA_HOOK_MASK,
    val debugLogging: Int = 0,
    val updateCheckEnabled: Boolean = true,
    val healthCheckEnabled: Boolean = true,
    val targetRefreshEnabled: Boolean = true,
    val selfTestVpnEnabled: Boolean = true,
    val useNoMountForFileHiding: Boolean = false,
)

internal interface GlobalConfigDao {
    suspend fun getConfig(): DbGlobalConfig?

    suspend fun insertConfig(config: DbGlobalConfig)
}
