package dev.soranerai.vpnhidenext

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.room.withTransaction
import dev.soranerai.vpnhidenext.db.AppDatabase
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
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ProtectionMode.VpnTargets) }

    val cachedApps by AppListCache.apps.collectAsState()
    val targets by TargetsCache.snapshot.collectAsState()
    val loading by TargetsCache.loading.collectAsState()

    // Unified states for each tab
    var vpnApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var portApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var originalVpn by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var originalPort by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var saving by remember { mutableStateOf(false) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    // Load/Sync apps when cache changes (only if not dirty)
    LaunchedEffect(cachedApps, targets) {
        val apps = cachedApps ?: return@LaunchedEffect
        val t = targets ?: return@LaunchedEffect
        val selfPkg = context.packageName

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
        }
    }

    val isVpnDirty = remember(vpnApps, originalVpn) { dirtyVpn(vpnApps, originalVpn) }
    val isMassDirty =
        remember(pendingMassRules, targets) {
            val original = targets?.massPortRules ?: emptyList()
            pendingMassRules != null && pendingMassRules != original
        }
    val isPortDirty =
        remember(portApps, originalPort, isMassDirty) {
            dirtyPort(portApps, originalPort) || isMassDirty
        }

    val anyDirty = isVpnDirty || isPortDirty

    LaunchedEffect(mode, isVpnDirty, isPortDirty) {
        val dirtyModes = mutableSetOf<ProtectionMode>()
        if (isVpnDirty) dirtyModes += ProtectionMode.VpnTargets
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
                    val header = context.getString(R.string.save_header_comment)
                    val selfPkg = context.packageName
                    val parts = mutableListOf<String>()

                    val db = AppDatabase.getInstance(context)
                    db.withTransaction {
                        val appDao = db.appDao()
                        val portRuleDao = db.portRuleDao()

                        if (isVpnDirty || isPortDirty) {
                            val vpnMap = vpnApps.associateBy { it.packageName to it.userId }
                            val portMap = portApps.associateBy { it.packageName to it.userId }
                            val allKeys = (vpnMap.keys + portMap.keys).distinct()

                            val protections =
                                allKeys.map { key ->
                                    val (pkg, userId) = key
                                    val vpnApp = vpnMap[key]
                                    val portApp = portMap[key]

                                    dev.soranerai.vpnhidenext.db.AppProtection(
                                        packageName = pkg,
                                        userId = userId,
                                        kmod = vpnApp?.kmod ?: false,
                                        lsposed = vpnApp?.lsposed ?: false,
                                        portHiding = portApp?.portHiding ?: false,
                                    )
                                }
                            appDao.insertAppProtections(protections)

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
                    }

                    if (isVpnDirty) {
                        val selfUid = context.applicationInfo.uid
                        val k = (vpnApps.filter { it.kmod }.map { it.uid } + selfUid).distinct().sorted()
                        val l = (vpnApps.filter { it.lsposed }.map { it.uid } + selfUid).distinct().sorted()
                        parts += buildVpnSaveCommand(header, k, l)
                    }
                    if (isPortDirty || isMassDirty) {
                        parts += buildPortSaveCommand(header, portApps, pendingMassRules ?: targets?.massPortRules ?: emptyList())
                    }

                    if (parts.isNotEmpty()) {
                        val (exitCode, _) = suExecAsync(parts.joinToString(" ; "))
                        if (exitCode == 0) {
                            DashboardCache.invalidate()
                            DiagnosticsCache.reset()
                            TargetsCache.refresh(scope, context)
                            originalVpn = vpnApps
                            originalPort = portApps
                            onSaved()
                        } else {
                            snackMessage = context.getString(R.string.save_failed_exit, exitCode)
                        }
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

// Helpers for command building (need to be accessible or moved to a central place)
private fun buildVpnSaveCommand(
    header: String,
    kmod: List<Int>,
    lsposed: List<Int>,
): String {
    val parts = mutableListOf<String>()
    parts += buildWriteTargetsCommand(KMOD_TARGETS, header, kmod)
    parts += buildWriteTargetsCommand(LSPOSED_TARGETS, header, lsposed)
    parts += buildKmodApplyCommand(kmod, targetType = "targets")
    parts += buildLsposedApplyCommand(lsposed)
    return parts.joinToString(" ; ")
}

private fun buildPortSaveCommand(
    header: String,
    apps: List<AppEntry>,
    massRules: List<PortRule>,
): String {
    val parts = mutableListOf<String>()

    // Observers file
    // Observers file
    val observersBody = StringBuilder(header).append("\n")
    apps.filter { it.portHiding }.forEach { app ->
        val entry = if (app.userId == 0) app.packageName else "${app.packageName}:${app.userId}"
        observersBody.append(entry).append("\n")
    }
    val b64Observers = Base64.encodeToString(observersBody.toString().toByteArray(), Base64.NO_WRAP)
    val observersDir = PORTS_OBSERVERS_FILE.substringBeforeLast('/')
    parts += "mkdir -p $observersDir ; echo '$b64Observers' | base64 -d > $PORTS_OBSERVERS_FILE && chmod 644 $PORTS_OBSERVERS_FILE"

    val ruleMap =
        apps.filter { it.portHiding }.associate { app ->
            app.uid to (app.portRules + massRules.filter { r -> r.enabled })
        }

    // Build rule persistence file body
    val rulesBody = StringBuilder(header).append("\n")
    ruleMap.forEach { (uid, rules) ->
        if (rules.isNotEmpty()) {
            rulesBody.append(uid)
            rules.forEach { rule ->
                val proto =
                    when (rule.protocol) {
                        PortProtocol.TCP -> 0
                        PortProtocol.UDP -> 1
                        PortProtocol.BOTH -> 2
                    }
                rulesBody.append(" ${rule.startPort}-${rule.endPort}:$proto")
            }
            rulesBody.append("\n")
        }
    }

    val b64Rules = Base64.encodeToString(rulesBody.toString().toByteArray(), Base64.NO_WRAP)
    val rulesDir = PORTS_RULES_FILE.substringBeforeLast('/')
    parts += "mkdir -p $rulesDir ; echo '$b64Rules' | base64 -d > $PORTS_RULES_FILE && chmod 644 $PORTS_RULES_FILE"

    parts += buildKmodPortRulesApplyCommand(ruleMap)

    return parts.joinToString(" ; ")
}
