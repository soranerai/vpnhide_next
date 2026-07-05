package dev.soranerai.vpnhidenext

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection

@Composable
internal fun ProtectionScreen(
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

    val dirty = remember(apps, originalApps) { isDirty(apps, originalApps) }

    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    Box(modifier = modifier.fillMaxSize()) {
        AppPickerScreen(
            apps = apps,
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
            modifier = Modifier.fillMaxSize(),
        )

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
                            if (existing.packageName != selfPkg && key !in keysToKeep) {
                                appDao.deleteAppProtection(existing)
                            }
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
