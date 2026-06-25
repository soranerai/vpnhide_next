package dev.soranerai.vpnhidenext

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbIfacePrefix
import dev.soranerai.vpnhidenext.db.DbMassPortRule

internal enum class ProtectionMode { VpnTargets, PortHiding }

@Composable
internal fun ProtectionScreen(
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    showOnlyWorkProfile: Boolean,
    sortOrder: AppSortOrder,
    onStateChange: (ProtectionMode, Set<ProtectionMode>) -> Unit,
    onAppPortConfig: (AppEntry) -> Unit,
    updatedApp: AppEntry?,
    saveTrigger: Int,
    pendingMassRules: List<PortRule>?,
    pendingIfacePrefixes: List<String>?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ProtectionMode.VpnTargets) }

    val cachedApps by AppListCache.apps.collectAsState()
    val targets by TargetsCache.snapshot.collectAsState()

    // Unified states for each tab
    var vpnApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var portApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var originalVpn by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var originalPort by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var saving by remember { mutableStateOf(false) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val vpnListState = rememberLazyListState()
    val portListState = rememberLazyListState()

    var vpnSortedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var portSortedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    // Explicit order reset: computes stable display ID lists from the latest targets snapshot.
    // MUST be declared before any LaunchedEffect that calls it.
    //
    // When to call:
    //   • First data load (sortedIds still empty)        → called from LaunchedEffect(cachedApps, targets)
    //   • Filter / search / sortOrder changes             → called from LaunchedEffect(filters…)
    //   • Pull-to-refresh (refreshTrigger++)              → called from LaunchedEffect(filters…) via trigger
    //   • After successful save                           → called explicitly in save block
    //
    // When NOT to call:
    //   • Toggle (vpnApps/portApps change) → sortedIds stays the same, remember(apps, sortedIds)
    //     re-maps content with the same order, no jump occurs.
    fun resetOrder() {
        val apps = cachedApps ?: return
        val t = targets ?: return
        val q = searchQuery.trim().lowercase()
        val selfPkg = context.packageName

        vpnSortedIds =
            apps
                .filter { it.packageName != selfPkg }
                .filter { app ->
                    (
                        showSystem || !app.isSystem || (app.packageName to app.userId) in t.kmodTargets ||
                            (app.packageName to app.userId) in t.lsposedTargets
                    ) &&
                        (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                        (
                            !showOnlySelected || (app.packageName to app.userId) in t.kmodTargets ||
                                (app.packageName to app.userId) in t.lsposedTargets
                        ) &&
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
                                compareByDescending<AppSummary> {
                                    (it.packageName to it.userId) in
                                        t.kmodTargets ||
                                        (it.packageName to it.userId) in t.lsposedTargets
                                }.thenBy { it.label.lowercase() },
                            )
                        }
                    }
                }.map { "${it.packageName}:${it.userId}" }

        portSortedIds =
            apps
                .filter { it.packageName != selfPkg }
                .filter { app ->
                    (showSystem || !app.isSystem || (app.packageName to app.userId) in t.portsObservers) &&
                        (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                        (!showOnlySelected || (app.packageName to app.userId) in t.portsObservers) &&
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
                                compareByDescending<AppSummary> {
                                    (it.packageName to it.userId) in
                                        t.portsObservers
                                }.thenBy { it.label.lowercase() },
                            )
                        }
                    }
                }.map { "${it.packageName}:${it.userId}" }
    }

    // Load/Sync apps when cache changes (only if not dirty)
    LaunchedEffect(cachedApps, targets) {
        val apps = cachedApps ?: return@LaunchedEffect
        val t = targets ?: return@LaunchedEffect
        val selfPkg = context.packageName
        var rebuilt = false

        if (vpnApps.isEmpty() || !dirtyVpn(vpnApps, originalVpn)) {
            vpnApps =
                apps
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
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.kmod || it.lsposed }.thenBy { it.label })
            originalVpn = vpnApps
            rebuilt = true
        }

        if (portApps.isEmpty() || !dirtyPort(portApps, originalPort)) {
            portApps =
                apps
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
                            portHiding = key in t.portsObservers,
                            portRules = t.portRules[key] ?: emptyList(),
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.portHiding }.thenBy { it.label })
            originalPort = portApps
            rebuilt = true
        }

        // Refresh order whenever we actually rebuilt the lists from new targets data.
        // Covers both first-load and pull-to-refresh.
        // NOT called on toggle because toggle changes vpnApps via onUpdate (dirty check above),
        // so rebuilt stays false and order is preserved.
        if (rebuilt || vpnSortedIds.isEmpty() || portSortedIds.isEmpty()) {
            resetOrder()
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

    val isVpnDirty = remember(vpnApps, originalVpn) { dirtyVpn(vpnApps, originalVpn) }
    val isMassDirty =
        remember(pendingMassRules, targets) {
            val original = targets?.massPortRules ?: emptyList()
            pendingMassRules != null && pendingMassRules != original
        }
    val isIfaceDirty =
        remember(pendingIfacePrefixes, targets) {
            val original = targets?.ifacePrefixes ?: emptyList()
            pendingIfacePrefixes != null && pendingIfacePrefixes != original
        }
    val isPortDirty =
        remember(portApps, originalPort, isMassDirty) {
            dirtyPort(portApps, originalPort) || isMassDirty
        }

    val anyDirty = isVpnDirty || isPortDirty || isIfaceDirty

    LaunchedEffect(mode, isVpnDirty, isPortDirty, isIfaceDirty) {
        val dirtyModes = mutableSetOf<ProtectionMode>()
        if (isVpnDirty || isIfaceDirty) dirtyModes += ProtectionMode.VpnTargets
        if (isPortDirty) dirtyModes += ProtectionMode.PortHiding
        onStateChange(mode, dirtyModes)
    }
    val counts =
        mapOf(
            ProtectionMode.VpnTargets to vpnApps.count { it.anyProtection },
            ProtectionMode.PortHiding to portApps.count { it.portHiding },
        )

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ProtectionModeSwitcher(
                mode = mode,
                counts = counts,
                onModeChange = { mode = it },
            )

            Box(modifier = Modifier.weight(1f)) {
                when (mode) {
                    ProtectionMode.VpnTargets -> {
                        AppPickerScreen(
                            apps = vpnApps,
                            searchQuery = searchQuery,
                            showSystem = showSystem,
                            showRussianOnly = showRussianOnly,
                            showOnlySelected = showOnlySelected,
                            showOnlyWorkProfile = showOnlyWorkProfile,
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                vpnApps = newList
                            },
                            sortedIds = vpnSortedIds,
                            onRefresh = onRefresh,
                            listState = vpnListState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ProtectionMode.PortHiding -> {
                        PortsHidingScreen(
                            apps = portApps,
                            searchQuery = searchQuery,
                            showSystem = showSystem,
                            showRussianOnly = showRussianOnly,
                            showOnlySelected = showOnlySelected,
                            showOnlyWorkProfile = showOnlyWorkProfile,
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                portApps = newList
                            },
                            onConfigClick = onAppPortConfig,
                            sortedIds = portSortedIds,
                            onRefresh = onRefresh,
                            listState = portListState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Unified Saving Effect
        LaunchedEffect(saveTrigger) {
            if (saveTrigger > 0 && !saving && anyDirty) {
                saving = true
            }
        }

        // Unified Saving Effect
        if (saving) {
            LaunchedEffect(Unit) {
                try {
                    val selfPkg = context.packageName

                    val db = AppDatabase.getInstance(context)
                    db.withTransaction {
                        val appDao = db.appDao()
                        val portRuleDao = db.portRuleDao()

                        if (isVpnDirty || isPortDirty) {
                            val vpnMap = vpnApps.associateBy { it.packageName to it.userId }
                            val portMap = portApps.associateBy { it.packageName to it.userId }
                            val allKeys = (vpnMap.keys + portMap.keys).distinct()

                            val protections =
                                allKeys.mapNotNull { key ->
                                    val (pkg, userId) = key
                                    val vpnApp = vpnMap[key]
                                    val portApp = portMap[key]

                                    val kmod = vpnApp?.kmod ?: false
                                    val lsposed = vpnApp?.lsposed ?: false
                                    val portHiding = portApp?.portHiding ?: false

                                    if (!kmod && !lsposed && !portHiding) return@mapNotNull null

                                    dev.soranerai.vpnhidenext.db.AppProtection(
                                        packageName = pkg,
                                        userId = userId,
                                        uid = vpnApp?.uid ?: portApp?.uid ?: 0,
                                        kmod = kmod,
                                        lsposed = lsposed,
                                        portHiding = portHiding,
                                    )
                                }
                            appDao.insertAppProtections(protections)

                            val existingProtections = appDao.getAllAppProtectionSync()
                            val keysToKeep = protections.map { it.packageName to it.userId }.toSet()
                            for (existing in existingProtections) {
                                val key = existing.packageName to existing.userId
                                if (existing.packageName != selfPkg && key !in keysToKeep) {
                                    appDao.deleteAppProtection(existing)
                                }
                            }

                            if (isPortDirty) {
                                val originalPortMap = originalPort.associateBy { it.packageName to it.userId }
                                for (key in allKeys) {
                                    val (pkg, userId) = key
                                    val portApp = portMap[key] ?: continue
                                    val orig = originalPortMap[key]

                                    // Only update if rules changed
                                    if (orig == null || portApp.portRules != orig.portRules || portApp.portHiding != orig.portHiding) {
                                        portRuleDao.deleteRulesForApp(pkg, userId)
                                        if (portApp.portRules.isNotEmpty()) {
                                            portRuleDao.insertRules(
                                                portApp.portRules.map { rule ->
                                                    dev.soranerai.vpnhidenext.db.DbPortRule(
                                                        packageName = pkg,
                                                        userId = userId,
                                                        startPort = rule.startPort,
                                                        endPort = rule.endPort,
                                                        protocol = rule.protocol,
                                                        label = rule.label,
                                                        enabled = rule.enabled,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (isMassDirty && pendingMassRules != null) {
                            val massDao = db.massPortRuleDao()
                            massDao.deleteAllMassRules()
                            massDao.insertMassRules(
                                pendingMassRules.map { rule ->
                                    dev.soranerai.vpnhidenext.db.DbMassPortRule(
                                        startPort = rule.startPort,
                                        endPort = rule.endPort,
                                        protocol = rule.protocol,
                                        label = rule.label,
                                        enabled = rule.enabled,
                                    )
                                },
                            )
                        }

                        if (isIfaceDirty && pendingIfacePrefixes != null) {
                            val ifaceDao = db.ifacePrefixDao()
                            ifaceDao.deleteAllPrefixes()
                            ifaceDao.insertPrefixes(
                                pendingIfacePrefixes.map { DbIfacePrefix(it) },
                            )
                        }
                    }

                    val success =
                        dev.soranerai.vpnhidenext.db.DatabaseSync
                            .sync(context)
                    if (success) {
                        DashboardCache.invalidate()
                        DiagnosticsCache.reset()
                        TargetsCache.refresh(scope, context)
                        originalVpn = vpnApps
                        originalPort = portApps
                        // Re-sort after save to reflect new selection state (jump once, but after save is done)
                        resetOrder()
                        onSaved()
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

    LaunchedEffect(updatedApp) {
        updatedApp?.let { app ->
            portApps =
                portApps.map {
                    if (it.packageName == app.packageName && it.userId == app.userId) app else it
                }
        }
    }
}

private fun dirtyVpn(
    current: List<AppEntry>,
    original: List<AppEntry>,
): Boolean {
    if (current.size != original.size) return true
    return current.any { c ->
        val o = original.find { it.packageName == c.packageName && it.userId == c.userId } ?: return@any true
        c.kmod != o.kmod || c.lsposed != o.lsposed
    }
}

private fun dirtyPort(
    current: List<AppEntry>,
    original: List<AppEntry>,
): Boolean {
    if (current.size != original.size) return true
    return current.any { c ->
        val o = original.find { it.packageName == c.packageName && it.userId == c.userId } ?: return@any true
        c.portHiding != o.portHiding || c.portRules != o.portRules
    }
}

@Composable
private fun ProtectionModeSwitcher(
    mode: ProtectionMode,
    counts: Map<ProtectionMode, Int>,
    onModeChange: (ProtectionMode) -> Unit,
) {
    val options =
        listOf(
            ProtectionMode.VpnTargets to R.string.mode_vpn_targets,
            ProtectionMode.PortHiding to R.string.mode_port_hiding,
        )
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (m, labelRes) ->
            val count = counts[m] ?: 0
            SegmentedButton(
                selected = m == mode,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (m == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
