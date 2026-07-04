package dev.soranerai.vpnhidenext.db

import dev.soranerai.vpnhidenext.PortProtocol

internal data class DbPortRule(
    val id: Long = 0,
    val packageName: String,
    val userId: Int = 0,
    val startPort: Int,
    val endPort: Int,
    internal val protocol: PortProtocol,
    val label: String = "",
    val enabled: Boolean = true,
)
