package dev.soranerai.vpnhidenext

import android.graphics.drawable.Drawable

internal data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val userId: Int = 0,
    val uid: Int = 0,
    // Protection (VPN) flags
    val kmod: Boolean = false,
    val lsposed: Boolean = false,
    // Port Hiding
    val portHiding: Boolean = false,
    val portRules: List<PortRule> = emptyList(),
) {
    val anyProtection get() = kmod || lsposed
}

internal enum class PortProtocol { TCP, UDP, BOTH }

internal data class PortRule(
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val startPort: Int,
    val endPort: Int = startPort,
    val protocol: PortProtocol = PortProtocol.BOTH,
    val label: String = "",
    val enabled: Boolean = true,
)

internal enum class Layer { KMOD, LSPOSED }

internal enum class AppSortOrder { NAME_ASC, NAME_DESC, SELECTED_FIRST }

internal data class InstalledModules(
    val kmod: Boolean,
    val kmodActive: Boolean,
)
