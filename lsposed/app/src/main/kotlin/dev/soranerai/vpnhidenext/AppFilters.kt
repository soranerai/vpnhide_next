package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.PolicyListMode

/** Filter the staged rows rendered by the picker, not the last saved snapshot. */
internal fun AppEntry.isSelectedForPicker(): Boolean = anyProtection || portHiding

internal fun AppEntry.isAutomaticallySelectedSystem(listMode: PolicyListMode): Boolean =
    listMode == PolicyListMode.ALLOWLIST && isSystem && !systemPolicyExplicit

internal fun List<AppEntry>.manualSelectionCount(listMode: PolicyListMode): Int =
    count { it.isSelectedForPicker() && !it.isAutomaticallySelectedSystem(listMode) }

internal fun AppEntry.withNormalizedSystemPolicy(
    listMode: PolicyListMode,
): AppEntry {
    if (!isSystem || isCoreSystemUid()) return this
    // Policy describes intended behavior, not current module availability.
    // Persist the kernel exception now so installing/re-enabling kmod later
    // cannot silently invert the default for system applications.
    val safeKmod = listMode == PolicyListMode.ALLOWLIST
    val safeFramework = listMode == PolicyListMode.ALLOWLIST
    val isSafe = kmod == safeKmod && lsposed == safeFramework && portHiding == safeFramework
    return copy(systemPolicyExplicit = !isSafe)
}

internal fun isRiskySystemTransition(
    old: AppEntry,
    next: AppEntry,
    listMode: PolicyListMode,
): Boolean {
    if (!next.isSystem || next.isCoreSystemUid()) return false
    return when (listMode) {
        PolicyListMode.BLACKLIST -> {
            (!old.kmod && next.kmod) ||
                (!old.lsposed && next.lsposed) ||
                (!old.portHiding && next.portHiding)
        }

        PolicyListMode.ALLOWLIST -> {
            (old.kmod && !next.kmod) ||
                (old.lsposed && !next.lsposed) ||
                (old.portHiding && !next.portHiding)
        }
    }
}

internal fun AppEntry.shouldPersistPolicy(
    listMode: PolicyListMode,
    kernelHookMask: Long?,
    javaHookMask: Long?,
): Boolean {
    val hasExtraPolicy = kernelHookMask != null || javaHookMask != null || portRules.isNotEmpty()
    if (listMode == PolicyListMode.BLACKLIST && isSystem && !systemPolicyExplicit) return hasExtraPolicy
    return kmod || lsposed || portHiding || systemPolicyExplicit || hasExtraPolicy
}

internal fun missingSystemPolicyDefaults(
    listMode: PolicyListMode,
    configured: List<AppProtection>,
    systemPackages: Set<Pair<String, Int>>,
    selfPackage: String,
): List<AppProtection> {
    if (listMode != PolicyListMode.ALLOWLIST) return emptyList()
    val configuredUids = configured.map { it.uid }.toMutableSet()
    return buildList {
        for ((packageName, uid) in systemPackages.sortedWith(compareBy({ it.second }, { it.first }))) {
            if (uid % 100000 < 10000 || uid in configuredUids || packageName == selfPackage) continue
            add(
                AppProtection(
                    packageName = packageName,
                    userId = uid / 100000,
                    uid = uid,
                    kmod = true,
                    lsposed = true,
                    portHiding = true,
                ),
            )
            configuredUids.add(uid)
        }
    }
}

internal fun filterAndSortApps(
    apps: List<AppEntry>,
    listMode: PolicyListMode,
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    showOnlyWorkProfile: Boolean,
    sortOrder: AppSortOrder,
): List<AppEntry> {
    val query = searchQuery.trim().lowercase()
    val useRussianFilter = listMode == PolicyListMode.BLACKLIST && showRussianOnly

    val filtered =
        apps.filter { app ->
            systemAppIsVisible(app, listMode, showSystem) &&
                (!useRussianFilter || isRussianApp(app.packageName, app.label)) &&
                (!showOnlySelected || app.isSelectedForPicker()) &&
                (!showOnlyWorkProfile || app.userId != 0) &&
                app.matchesSearch(query)
        }

    return when (sortOrder) {
        AppSortOrder.NAME_ASC -> {
            filtered.sortedBy { it.label.lowercase() }
        }

        AppSortOrder.NAME_DESC -> {
            filtered.sortedByDescending { it.label.lowercase() }
        }

        AppSortOrder.SELECTED_FIRST -> {
            filtered.sortedWith(
                compareByDescending<AppEntry> { it.isSelectedForPicker() }.thenBy { it.label.lowercase() },
            )
        }

        AppSortOrder.UNSELECTED_FIRST -> {
            filtered.sortedWith(
                compareBy<AppEntry> { it.isSelectedForPicker() }.thenBy { it.label.lowercase() },
            )
        }
    }
}

private fun AppEntry.matchesSearch(query: String): Boolean =
    query.isEmpty() ||
        label.lowercase().contains(query) ||
        packageName.lowercase().contains(query) ||
        uid.toString().contains(query)

private fun systemAppIsVisible(
    app: AppEntry,
    listMode: PolicyListMode,
    showSystem: Boolean,
): Boolean {
    if (!app.isSystem || showSystem) return true
    return when (listMode) {
        PolicyListMode.BLACKLIST -> app.isSelectedForPicker()

        // Implicit system exceptions are selected in allowlist, but must not
        // flood the normal app list. Keep only intentionally overridden rows
        // visible after the user turns the system-app filter back off.
        PolicyListMode.ALLOWLIST -> app.systemPolicyExplicit
    }
}
