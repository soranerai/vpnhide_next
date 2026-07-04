package dev.soranerai.vpnhidenext.db

import dev.soranerai.vpnhidenext.PortProtocol

internal data class DbMassPortRule(
    val id: Long = 0,
    val startPort: Int,
    val endPort: Int,
    internal val protocol: PortProtocol,
    internal val label: String = "",
    internal val enabled: Boolean = true,
)
