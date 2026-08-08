package dev.soranerai.vpnhidenext.db

internal data class AppProtection(
    val packageName: String,
    val userId: Int = 0,
    val uid: Int = 0,
    val kmod: Boolean = false,
    val lsposed: Boolean = false,
    val portHiding: Boolean = false,
    // System packages are protected by default. This marker distinguishes an
    // intentional per-layer override from a legacy/default policy entry.
    val systemPolicyExplicit: Boolean = false,
    // null = no override, inherit the global hook mask
    val kernelHookMask: Long? = null,
    val javaHookMask: Long? = null,
)
