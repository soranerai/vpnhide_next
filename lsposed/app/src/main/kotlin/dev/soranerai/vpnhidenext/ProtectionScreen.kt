package dev.soranerai.vpnhidenext

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.DbGlobalConfig
import dev.soranerai.vpnhidenext.db.PolicyListMode

@Composable
internal fun ProtectionScreen(
    listMode: PolicyListMode,
    onListModeChange: (PolicyListMode) -> Unit,
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    showOnlyWorkProfile: Boolean,
    sortOrder: AppSortOrder,
    onDirtyChange: (Boolean) -> Unit,
    onOpenAppSettings: (AppEntry) -> Unit,
    selfNeedsRestart: Boolean,
    saveTrigger: Int,
    modifier: Modifier = Modifier,
    bulkProtectTrigger: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cachedApps by AppListCache.apps.collectAsState()
    val targets by TargetsCache.snapshot.collectAsState()

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var originalApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var saving by remember { mutableStateOf(false) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var sortedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var originalListMode by remember { mutableStateOf(listMode) }
    var pendingMode by remember { mutableStateOf<PolicyListMode?>(null) }
    var showModeHelp by remember { mutableStateOf(false) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    // Explicit order reset: computes a stable display ID list from the latest targets snapshot.
    // MUST be declared before any LaunchedEffect that calls it.
    //
    // When to call:
    //   • First data load (sortedIds still empty)        → called from LaunchedEffect(cachedApps, targets)
    //   • Filter / search / sortOrder changes             → called from LaunchedEffect(filters…)
    //   • Pull-to-refresh (refreshTrigger++)              → called from LaunchedEffect(filters…) via trigger
    //   • After successful save                           → called explicitly in save block
    //
    // When NOT to call:
    //   • Toggle (apps changes) → sortedIds stays the same, remember(apps, sortedIds)
    //     re-maps content with the same order, no jump occurs.
    fun resetOrder() {
        val allApps = cachedApps ?: return
        val t = targets ?: return
        val q = searchQuery.trim().lowercase()
        val selfPkg = context.packageName

        fun isProtected(app: AppSummary): Boolean {
            val key = app.packageName to app.userId
            return key in t.kmodTargets || key in t.lsposedTargets || key in t.portsObservers
        }

        sortedIds =
            allApps
                .filter { it.packageName != selfPkg }
                .filter { app ->
                    (showSystem || !app.isSystem || isProtected(app)) &&
                        (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                        (!showOnlySelected || isProtected(app)) &&
                        (!showOnlyWorkProfile || app.userId != 0) &&
                        (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
                }.let { list ->
                    when (sortOrder) {
                        AppSortOrder.NAME_ASC -> {
                            list.sortedBy { it.label.lowercase() }
                        }

                        AppSortOrder.NAME_DESC -> {
                            list.sortedByDescending { it.label.lowercase() }
                        }

                        AppSortOrder.SELECTED_FIRST -> {
                            list.sortedWith(
                                compareByDescending<AppSummary> { isProtected(it) }.thenBy { it.label.lowercase() },
                            )
                        }
                    }
                }.map { "${it.packageName}:${it.userId}" }
    }

    // Load/Sync apps when cache changes (only if not dirty)
    LaunchedEffect(cachedApps, targets) {
        val allApps = cachedApps ?: return@LaunchedEffect
        val t = targets ?: return@LaunchedEffect
        val selfPkg = context.packageName

        if (apps.isEmpty() || !isDirty(apps, originalApps)) {
            apps =
                allApps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        val key = app.packageName to app.userId
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userId = app.userId,
                            uid = app.uid,
                            kmod = key in t.kmodTargets,
                            lsposed = key in t.lsposedTargets,
                            portHiding = key in t.portsObservers,
                            portRules = t.portRules[key] ?: emptyList(),
                            kernelHookMask = t.kernelHookMasks[key],
                            javaHookMask = t.javaHookMasks[key],
                        )
                    }.sortedWith(
                        compareByDescending<AppEntry> { it.anyProtection || it.portHiding }.thenBy { it.label },
                    )
            originalApps = apps

            if (sortedIds.isEmpty()) {
                resetOrder()
            }
        }
    }

    // Stable Re-sorting logic: only run when filters, search, sortOrder, or manual refresh change
    LaunchedEffect(searchQuery, showSystem, showRussianOnly, showOnlySelected, showOnlyWorkProfile, sortOrder, refreshTrigger) {
        if (targets != null) {
            resetOrder()
        }
    }

    val onRefresh = {
        TargetsCache.refresh(scope, context)
        refreshTrigger++
        Unit
    }

    // Bulk "protect all shown" action from the filter menu — only ever adds
    // protection for whichever apps are currently filtered/visible
    // (sortedIds), never removes it, so it's safe to fire repeatedly.
    // Just stages the change into `apps`, same as a per-row toggle — Save
    // still needs to be tapped to persist it.
    LaunchedEffect(bulkProtectTrigger) {
        if (bulkProtectTrigger == 0) return@LaunchedEffect
        val kmodInstalled = targets?.kmodModuleInstalled == true
        val visibleKeys = sortedIds.toSet()
        var addedCount = 0
        apps =
            apps.map { entry ->
                val key = "${entry.packageName}:${entry.userId}"
                if (key !in visibleKeys) return@map entry
                val alreadyFull = entry.lsposed && entry.portHiding && (entry.kmod || !kmodInstalled)
                if (alreadyFull) return@map entry
                addedCount++
                entry.copy(
                    kmod = kmodInstalled || entry.kmod,
                    lsposed = true,
                    portHiding = true,
                )
            }
        snackMessage =
            if (addedCount > 0) {
                context.getString(R.string.bulk_protect_added, addedCount)
            } else {
                context.getString(R.string.bulk_protect_none_added)
            }
    }

    val dirty =
        remember(apps, originalApps, listMode, originalListMode) {
            isDirty(apps, originalApps) || listMode != originalListMode
        }

    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppPickerScreen(
                apps = apps,
                listMode = listMode,
                searchQuery = searchQuery,
                showSystem = showSystem,
                showRussianOnly = showRussianOnly,
                showOnlySelected = showOnlySelected,
                showOnlyWorkProfile = showOnlyWorkProfile,
                sortOrder = sortOrder,
                onUpdate = { newList -> apps = newList },
                sortedIds = sortedIds,
                onRefresh = onRefresh,
                onOpenAppSettings = onOpenAppSettings,
                listState = listState,
                topContentPadding = 114.dp,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillTabSelector(
                    tabs =
                        listOf(
                            PillTab(Icons.Default.VisibilityOff, stringResource(R.string.policy_mode_blacklist)),
                            PillTab(Icons.Default.Visibility, stringResource(R.string.policy_mode_allowlist)),
                        ),
                    selectedIndex = if (listMode == PolicyListMode.BLACKLIST) 0 else 1,
                    onSelect = { selected ->
                        val selectedMode = if (selected == 0) PolicyListMode.BLACKLIST else PolicyListMode.ALLOWLIST
                        if (selectedMode != listMode) pendingMode = selectedMode
                    },
                    modifier = Modifier.weight(1f),
                    height = 56.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                    tonalElevation = 12.dp,
                    shadowElevation = 8.dp,
                )
                FilledIconButton(
                    onClick = { showModeHelp = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = stringResource(R.string.policy_help_title),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            val selectedCount = apps.count { it.anyProtection || it.portHiding }
            val summaryBackgroundColor =
                if (isSystemInDarkTheme()) Color(0xFF1B5E20) else Color(0xFFE8F5E9)
            val summaryContentColor =
                if (isSystemInDarkTheme()) Color.White else Color(0xFF1B5E20)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = summaryBackgroundColor,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            if (listMode == PolicyListMode.ALLOWLIST) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                        contentDescription = null,
                        tint = summaryContentColor,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text =
                            stringResource(
                                if (listMode == PolicyListMode.ALLOWLIST) {
                                    R.string.policy_mode_allowlist_summary
                                } else {
                                    R.string.policy_mode_blacklist_summary
                                },
                                selectedCount,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryContentColor,
                    )
                }
            }
        }

        pendingMode?.let { selected ->
            AlertDialog(
                onDismissRequest = { pendingMode = null },
                title = { Text(stringResource(R.string.policy_mode_change_title)) },
                text = {
                    Text(
                        stringResource(
                            if (selected == PolicyListMode.ALLOWLIST) {
                                R.string.policy_mode_allowlist_warning
                            } else {
                                R.string.policy_mode_blacklist_warning
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onListModeChange(selected)
                        pendingMode = null
                    }) {
                        Text(stringResource(R.string.policy_mode_change_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMode = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        if (showModeHelp) {
            AlertDialog(
                onDismissRequest = { showModeHelp = false },
                title = { Text(stringResource(R.string.policy_help_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(
                                if (listMode == PolicyListMode.ALLOWLIST) {
                                    R.string.policy_help_allowlist
                                } else {
                                    R.string.policy_help_blacklist
                                },
                            ),
                        )
                        Text(
                            stringResource(R.string.policy_help_layers),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.policy_help_ports),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModeHelp = false }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        LaunchedEffect(saveTrigger) {
            if (saveTrigger > 0 && !saving && dirty) {
                saving = true
            }
        }

        if (saving) {
            LaunchedEffect(Unit) {
                try {
                    val selfPkg = context.packageName
                    val db = AppDatabase.getInstance(context)
                    db.withTransaction {
                        val appDao = db.appDao()
                        val globalDao = db.globalConfigDao()
                        val currentGlobal = globalDao.getConfig() ?: DbGlobalConfig()
                        globalDao.insertConfig(currentGlobal.copy(listMode = listMode))
                        // Hook-mask overrides are now edited live from AppSettingsScreen, not
                        // staged in this list — read the current DB state so this save can't
                        // clobber them with the stale snapshot captured when the tab loaded.
                        val existingProtections = appDao.getAllAppProtectionSync()
                        val existingMap = existingProtections.associateBy { it.packageName to it.userId }
                        val appsMap = apps.associateBy { it.packageName to it.userId }

                        val protections =
                            appsMap.keys.mapNotNull { key ->
                                val (pkg, userId) = key
                                val entry = appsMap.getValue(key)
                                val existing = existingMap[key]
                                val kernelHookMask = existing?.kernelHookMask
                                val javaHookMask = existing?.javaHookMask

                                if (!entry.kmod && !entry.lsposed && !entry.portHiding &&
                                    kernelHookMask == null && javaHookMask == null
                                ) {
                                    return@mapNotNull null
                                }

                                AppProtection(
                                    packageName = pkg,
                                    userId = userId,
                                    uid = entry.uid,
                                    kmod = entry.kmod,
                                    lsposed = entry.lsposed,
                                    portHiding = entry.portHiding,
                                    kernelHookMask = kernelHookMask,
                                    javaHookMask = javaHookMask,
                                )
                            }
                        appDao.insertAppProtections(protections)

                        val keysToKeep = protections.map { it.packageName to it.userId }.toSet()
                        for (existing in existingProtections) {
                            val key = existing.packageName to existing.userId
                            if (existing.packageName == selfPkg || key in keysToKeep) continue
                            appDao.deleteAppProtection(existing)
                        }
                    }

                    val success =
                        dev.soranerai.vpnhidenext.db.DatabaseSync
                            .sync(context)
                    if (success) {
                        DashboardCache.refresh(scope, context, selfNeedsRestart)
                        DiagnosticsCache.reset()
                        DiagnosticsCache.run(scope, context)
                        TargetsCache.refresh(scope, context)
                        originalApps = apps
                        originalListMode = listMode
                        // Re-sort after save to reflect new selection state (jump once, but after save is done)
                        resetOrder()
                    } else {
                        snackMessage = context.getString(R.string.save_failed_exit, -1)
                    }
                } catch (e: Exception) {
                    snackMessage = e.message
                }
                saving = false
            }
        }
    }
}

private fun isDirty(
    current: List<AppEntry>,
    original: List<AppEntry>,
): Boolean {
    if (current.size != original.size) return true
    return current.any { c ->
        val o = original.find { it.packageName == c.packageName && it.userId == c.userId } ?: return@any true
        c.kmod != o.kmod || c.lsposed != o.lsposed || c.portHiding != o.portHiding
    }
}
