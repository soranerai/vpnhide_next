package dev.soranerai.vpnhidenext

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

internal enum class ProtectionMode { VpnTargets, TunBypass, AppHiding, PortHiding }

@Composable
internal fun ProtectionScreen(
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    sortOrder: AppSortOrder,
    onDirtyChange: (Boolean) -> Unit,
    saveTrigger: Int,
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
    var tunApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var hideApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var portApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    var dirtyVpn by remember { mutableStateOf(false) }
    var dirtyTun by remember { mutableStateOf(false) }
    var dirtyHide by remember { mutableStateOf(false) }
    var dirtyPort by remember { mutableStateOf(false) }

    var saving by remember { mutableStateOf(false) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    LaunchedEffect(Unit) {
        AppListCache.ensureLoaded(scope, context)
        TargetsCache.ensureLoaded(scope, context)
    }

    // Load/Sync apps when cache changes (only if not dirty)
    LaunchedEffect(cachedApps, targets) {
        val apps = cachedApps ?: return@LaunchedEffect
        val t = targets ?: return@LaunchedEffect
        val selfPkg = context.packageName

        if (!dirtyVpn) {
            vpnApps =
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            kmod = app.packageName in t.kmodTargets,
                            zygisk = app.packageName in t.zygiskTargets,
                            lsposed = app.packageName in t.lsposedTargets,
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.kmod || it.zygisk || it.lsposed }.thenBy { it.label })
        }

        if (!dirtyTun) {
            tunApps =
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            tunBypass = app.packageName in t.kmodDirectTargets,
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.tunBypass }.thenBy { it.label })
        }

        if (!dirtyHide) {
            val hidden = t.hiddenPkgs
            val observers = t.observerNames
            hideApps =
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        val rawHidden = app.packageName in hidden
                        val rawObserver = app.packageName in observers
                        // Conflict resolution: apps with both roles crash on startup.
                        // Treat as observer-only if both are set.
                        val (finalHidden, finalObserver) =
                            if (rawHidden && rawObserver) {
                                false to true
                            } else {
                                rawHidden to rawObserver
                            }
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            appHiding = finalHidden,
                            appObserver = finalObserver,
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.anyHiding }.thenBy { it.label })
        }

        if (!dirtyPort) {
            portApps =
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            portHiding = app.packageName in t.portsObservers,
                        )
                    }.sortedWith(compareByDescending<AppEntry> { it.portHiding }.thenBy { it.label })
        }
    }

    val anyDirty = dirtyVpn || dirtyTun || dirtyHide || dirtyPort
    LaunchedEffect(anyDirty) {
        onDirtyChange(anyDirty)
    }

    val counts =
        mapOf(
            ProtectionMode.VpnTargets to vpnApps.count { it.anyProtection },
            ProtectionMode.TunBypass to tunApps.count { it.tunBypass },
            ProtectionMode.AppHiding to hideApps.count { it.anyHiding },
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
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                vpnApps = newList
                                dirtyVpn = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ProtectionMode.TunBypass -> {
                        TunBypassScreen(
                            apps = tunApps,
                            searchQuery = searchQuery,
                            showSystem = showSystem,
                            showRussianOnly = showRussianOnly,
                            showOnlySelected = showOnlySelected,
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                tunApps = newList
                                dirtyTun = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ProtectionMode.AppHiding -> {
                        AppHidingScreen(
                            apps = hideApps,
                            searchQuery = searchQuery,
                            showSystem = showSystem,
                            showRussianOnly = showRussianOnly,
                            showOnlySelected = showOnlySelected,
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                hideApps = newList
                                dirtyHide = true
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
                            sortOrder = sortOrder,
                            onUpdate = { newList ->
                                portApps = newList
                                dirtyPort = true
                            },
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

                    if (dirtyVpn) {
                        val k = (vpnApps.filter { it.kmod }.map { it.packageName } + selfPkg).distinct().sorted()
                        val z = (vpnApps.filter { it.zygisk }.map { it.packageName } + selfPkg).distinct().sorted()
                        val l = (vpnApps.filter { it.lsposed }.map { it.packageName } + selfPkg).distinct().sorted()
                        parts += buildVpnSaveCommand(header, k, z, l)
                    }
                    if (dirtyTun) {
                        val k = (tunApps.filter { it.tunBypass }.map { it.packageName } + selfPkg).distinct().sorted()
                        parts += buildTunSaveCommand(header, k)
                    }
                    if (dirtyHide) {
                        val h = hideApps.filter { it.appHiding }.map { it.packageName }.sorted()
                        val o = hideApps.filter { it.appObserver }.map { it.packageName }.sorted()
                        parts += buildHideSaveCommand(header, h, o)
                    }
                    if (dirtyPort) {
                        val p = portApps.filter { it.portHiding }.map { it.packageName }.sorted()
                        parts += buildPortSaveCommand(header, p)
                    }

                    if (parts.isNotEmpty()) {
                        val (exitCode, _) = suExecAsync(parts.joinToString(" ; "))
                        if (exitCode == 0) {
                            DashboardCache.invalidate()
                            DiagnosticsCache.reset()
                            TargetsCache.refresh(scope, context)
                            dirtyVpn = false
                            dirtyTun = false
                            dirtyHide = false
                            dirtyPort = false
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
            ProtectionMode.TunBypass to R.string.mode_tun_bypass,
            ProtectionMode.AppHiding to R.string.mode_app_hiding,
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
    kmod: List<String>,
    zygisk: List<String>,
    lsposed: List<String>,
): String {
    val parts = mutableListOf<String>()
    parts += buildWriteTargetsCommand(KMOD_TARGETS, header, kmod)
    parts += buildWriteTargetsCommand(ZYGISK_TARGETS, header, zygisk)
    parts += buildWriteTargetsCommand(LSPOSED_TARGETS, header, lsposed)
    parts += "if [ -d $ZYGISK_MODULE_DIR ]; then cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>/dev/null; fi"
    parts += buildKmodApplyCommand(kmod)
    parts += buildLsposedApplyCommand(lsposed)
    return parts.joinToString(" ; ")
}

private fun buildTunSaveCommand(
    header: String,
    kmod: List<String>,
): String {
    val parts = mutableListOf<String>()
    parts += buildWriteTargetsCommand(KMOD_DIRECT_TARGETS, header, kmod)
    parts += buildKmodApplyCommand(kmod, isDirect = true)
    return parts.joinToString(" ; ")
}

private fun buildHideSaveCommand(
    header: String,
    hidden: List<String>,
    observers: List<String>,
): String {
    val parts = mutableListOf<String>()
    parts += buildWriteTargetsCommand(SS_HIDDEN_PKGS_FILE, header, hidden)

    // Resolve observer UIDs from package names
    if (observers.isEmpty()) {
        parts += "echo '$header' > $SS_OBSERVER_UIDS_FILE"
    } else {
        val uidsCmd = observers.joinToString(" ") { "pm list packages -U $it" }
        parts += "$uidsCmd | grep -oE 'uid:[0-9]+' | cut -d: -f2 | sort -u > $SS_OBSERVER_UIDS_FILE"
    }

    // No need to trigger kmod refresh here as App Hiding is handled by LSPosed
    return parts.joinToString(" ; ")
}

private fun buildPortSaveCommand(
    header: String,
    pkgs: List<String>,
): String {
    val parts = mutableListOf<String>()
    parts += buildWriteTargetsCommand(PORTS_OBSERVERS_FILE, header, pkgs)
    return parts.joinToString(" ; ")
}
