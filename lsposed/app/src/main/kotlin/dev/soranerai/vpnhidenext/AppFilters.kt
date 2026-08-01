package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.db.PolicyListMode

/** Filter the staged rows rendered by the picker, not the last saved snapshot. */
internal fun AppEntry.isSelectedForPicker(): Boolean = anyProtection || portHiding

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
    val useSystemFilter = listMode == PolicyListMode.BLACKLIST && showSystem
    val useRussianFilter = listMode == PolicyListMode.BLACKLIST && showRussianOnly

    val filtered =
        apps.filter { app ->
            (useSystemFilter || !app.isSystem || app.isSelectedForPicker()) &&
                (!useRussianFilter || isRussianApp(app.packageName, app.label)) &&
                (!showOnlySelected || app.isSelectedForPicker()) &&
                (!showOnlyWorkProfile || app.userId != 0) &&
                (query.isEmpty() || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query))
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
    }
}
