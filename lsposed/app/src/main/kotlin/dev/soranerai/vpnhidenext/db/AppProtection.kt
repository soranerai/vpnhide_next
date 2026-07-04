package dev.soranerai.vpnhidenext.db

internal data class AppProtection(
    val packageName: String,
    val userId: Int = 0,
    val uid: Int = 0,
    val kmod: Boolean = false,
    val lsposed: Boolean = false,
    val portHiding: Boolean = false,
)
