package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable

internal data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val userIds: List<Int> = emptyList(),
    
    // Protection (VPN) flags
    val kmod: Boolean = false,
    val zygisk: Boolean = false,
    val lsposed: Boolean = false,
    
    // TUN Bypass
    val tunBypass: Boolean = false,
    
    // App Hiding
    val appHiding: Boolean = false,
    val appObserver: Boolean = false,
    
    // Port Hiding
    val portHiding: Boolean = false,
) {
    val anyProtection get() = kmod || zygisk || lsposed
    val anyHiding get() = appHiding || appObserver
}

internal enum class Layer { KMOD, ZYGISK, LSPOSED }

internal enum class AppSortOrder { NAME_ASC, NAME_DESC, SELECTED_FIRST }

internal data class InstalledModules(
    val kmod: Boolean,
    val kmodActive: Boolean,
    val zygisk: Boolean,
)
