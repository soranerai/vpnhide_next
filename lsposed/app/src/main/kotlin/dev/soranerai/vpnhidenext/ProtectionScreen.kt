package dev.soranerai.vpnhidenext

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.PolicyListMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    cancelTrigger: Int = 0,
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

    var originalListMode by remember { mutableStateOf(listMode) }
    var pendingMode by remember { mutableStateOf<PolicyListMode?>(null) }
    var modeResetPending by remember { mutableStateOf(false) }
    var showModeHelp by remember { mutableStateOf(false) }
    var pendingSystemApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var pendingSystemWarningMode by remember { mutableStateOf<PolicyListMode?>(null) }
    val warningPrefs =
        remember { context.getSharedPreferences("vpnhide_prefs", android.content.Context.MODE_PRIVATE) }

    fun systemWarningKey(mode: PolicyListMode): String =
        when (mode) {
            PolicyListMode.BLACKLIST -> "system_target_warning_blacklist_ack_v1"
            PolicyListMode.ALLOWLIST -> "system_target_warning_allowlist_ack_v1"
        }

    fun normalizeSystemOverrides(candidate: List<AppEntry>): List<AppEntry> {
        return candidate.map { it.withNormalizedSystemPolicy(listMode) }
    }

    fun stageApps(candidate: List<AppEntry>) {
        val normalized = normalizeSystemOverrides(candidate)
        val previous = apps.associateBy { it.packageName to it.userId }
        val risky =
            normalized.any { next ->
                val old = previous[next.packageName to next.userId] ?: return@any false
                isRiskySystemTransition(old, next, listMode)
            }
        if (risky && !warningPrefs.getBoolean(systemWarningKey(listMode), false)) {
            pendingSystemApps = normalized
            pendingSystemWarningMode = listMode
        } else {
            apps = normalized
        }
    }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    var sortedIds by remember { mutableStateOf<List<String>>(emptyList()) }

    fun resetOrder(source: List<AppEntry> = apps) {
        sortedIds =
            filterAndSortApps(
                source,
                listMode,
                searchQuery,
                showSystem,
                showRussianOnly,
                showOnlySelected,
                showOnlyWorkProfile,
                sortOrder,
            ).map { "${it.packageName}:${it.userId}" }
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
                        val coreSystemUid = app.isSystem && app.uid % 100000 < 10000
                        val explicit = key in t.systemPolicyExplicitApps && !coreSystemUid
                        val implicitSystemException =
                            listMode == PolicyListMode.ALLOWLIST && app.isSystem && !coreSystemUid && !explicit
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userId = app.userId,
                            uid = app.uid,
                            kmod =
                                if (implicitSystemException) {
                                    true
                                } else if (app.isSystem && !explicit) {
                                    false
                                } else {
                                    key in t.kmodTargets
                                },
                            lsposed =
                                if (implicitSystemException) {
                                    true
                                } else if (app.isSystem && !explicit) {
                                    false
                                } else {
                                    key in t.lsposedEntries
                                },
                            systemPolicyExplicit = explicit,
                            portHiding =
                                if (implicitSystemException) {
                                    true
                                } else if (app.isSystem && !explicit) {
                                    false
                                } else {
                                    key in t.portsObservers
                                },
                            portRules = t.portRules[key] ?: emptyList(),
                            kernelHookMask = t.kernelHookMasks[key],
                            javaHookMask = t.javaHookMasks[key],
                        )
                    }.sortedWith(
                        compareByDescending<AppEntry> { it.anyProtection || it.portHiding }.thenBy { it.label },
                    )
            originalApps = apps
            resetOrder(apps)
        }
    }

    // Rebuild the order when the presentation state changes. Do not include
    // `apps` here: row toggles are staged and must not reorder the list until
    // Save, while the displayed row values still update immediately.
    LaunchedEffect(searchQuery, showSystem, showRussianOnly, showOnlySelected, showOnlyWorkProfile, sortOrder, listMode) {
        if (apps.isNotEmpty()) resetOrder()
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
        val candidate =
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
        stageApps(candidate)
        snackMessage =
            if (addedCount > 0) {
                context.getString(R.string.bulk_protect_added, addedCount)
            } else {
                context.getString(R.string.bulk_protect_none_added)
            }
    }

    val dirty =
        remember(apps, originalApps, listMode, originalListMode, modeResetPending) {
            isDirty(apps, originalApps) || listMode != originalListMode || modeResetPending
        }

    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    LaunchedEffect(cancelTrigger) {
        if (cancelTrigger == 0 || !dirty) return@LaunchedEffect
        apps = originalApps
        onListModeChange(originalListMode)
        modeResetPending = false
        pendingMode = null
        pendingSystemApps = null
        pendingSystemWarningMode = null
        snackMessage = null
    }

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
                onUpdate = ::stageApps,
                sortedIds = sortedIds,
                onOpenAppSettings = onOpenAppSettings,
                onLockedSystemClick = {
                    snackMessage = context.getString(R.string.system_app_core_uid_locked)
                },
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
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.policy_help_title),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            val selectedCount =
                if (listMode == PolicyListMode.ALLOWLIST) {
                    apps.effectiveTargetUidCount(listMode)
                } else {
                    apps.effectiveTargetUidCount(listMode)
                }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { pendingMode = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                apps =
                                    apps.map {
                                        val eligibleSystem = it.isSystem && !it.isCoreSystemUid()
                                        it.copy(
                                            kmod = selected == PolicyListMode.ALLOWLIST && eligibleSystem,
                                            lsposed = selected == PolicyListMode.ALLOWLIST && eligibleSystem,
                                            systemPolicyExplicit = false,
                                            portHiding = selected == PolicyListMode.ALLOWLIST && eligibleSystem,
                                            portRules = emptyList(),
                                            kernelHookMask = null,
                                            javaHookMask = null,
                                        )
                                    }
                                onListModeChange(selected)
                                modeResetPending = true
                                pendingMode = null
                            },
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Text(stringResource(R.string.policy_mode_change_confirm))
                        }
                    }
                },
            )
        }

        pendingSystemApps?.let { candidate ->
            val warningMode = pendingSystemWarningMode ?: listMode
            AlertDialog(
                onDismissRequest = {
                    pendingSystemApps = null
                    pendingSystemWarningMode = null
                },
                title = { Text(stringResource(R.string.system_app_warning_title)) },
                text = { Text(stringResource(R.string.system_app_warning_message)) },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingSystemApps = null
                            pendingSystemWarningMode = null
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            warningPrefs.edit().putBoolean(systemWarningKey(warningMode), true).apply()
                            apps = candidate
                            pendingSystemApps = null
                            pendingSystemWarningMode = null
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text(stringResource(R.string.continue_action))
                    }
                },
            )
        }

        if (showModeHelp) {
            AlertDialog(
                onDismissRequest = { showModeHelp = false },
                title = { Text(stringResource(R.string.policy_help_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.policy_help_scope_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.policy_help_scope_intro),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ModeHelpSection(
                            title = stringResource(R.string.policy_help_blacklist_title),
                            text = stringResource(R.string.policy_help_blacklist),
                        )
                        ModeHelpSection(
                            title = stringResource(R.string.policy_help_allowlist_title),
                            text = stringResource(R.string.policy_help_allowlist),
                        )
                        ModeHelpSection(
                            title = stringResource(R.string.policy_help_layers_title),
                            text = stringResource(R.string.policy_help_layers),
                        )
                        ModeHelpSection(
                            title = stringResource(R.string.policy_help_ports_title),
                            text = stringResource(R.string.policy_help_ports),
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
                    val appDao = db.appDao()
                    val existingProtections = appDao.getAllAppProtectionSync()
                    val existingMap = existingProtections.associateBy { it.packageName to it.userId }
                    val protections =
                        apps.filter { it.uid.isEligiblePolicyUid() }.mapNotNull { stagedEntry ->
                            // Re-normalize at the commit boundary. UI state is
                            // staged asynchronously, so persistence must not
                            // trust an older explicitness marker.
                            val entry = stagedEntry.withNormalizedSystemPolicy(listMode)
                            val existing = existingMap[entry.packageName to entry.userId]
                            val kernelHookMask = if (modeResetPending) null else existing?.kernelHookMask
                            val javaHookMask = if (modeResetPending) null else existing?.javaHookMask
                            if (!entry.shouldPersistPolicy(listMode, kernelHookMask, javaHookMask)) {
                                return@mapNotNull null
                            }
                            AppProtection(
                                packageName = entry.packageName,
                                userId = entry.userId,
                                uid = entry.uid,
                                kmod = entry.kmod,
                                lsposed = entry.lsposed,
                                portHiding = entry.portHiding,
                                systemPolicyExplicit = entry.systemPolicyExplicit,
                                kernelHookMask = kernelHookMask,
                                javaHookMask = javaHookMask,
                            )
                        }.toMutableList()

                    // The manager is deliberately absent from the picker but
                    // remains an explicit record in the policy. Native self
                    // protection follows the list mode for both native and
                    // Framework layers: BLACKLIST explicitly includes
                    // VPNHide, while ALLOWLIST leaves it outside the exception
                    // list so it remains protected by exclude-mode matching.
                    // The manager is not shown in the picker, so its record
                    // must be upserted here. Do not make this conditional on
                    // a stale/missing DB row: replaceProtectionPolicy() would
                    // otherwise drop VPNHide itself from BLACKLIST targets.
                    val selfExisting = existingMap[selfPkg to 0]
                    protections.removeAll { it.packageName == selfPkg && it.userId == 0 }
                    protections +=
                        (selfExisting
                            ?: AppProtection(
                                packageName = selfPkg,
                                userId = 0,
                                uid = context.applicationInfo.uid,
                            )).copy(
                            uid = context.applicationInfo.uid,
                            kmod = listMode == PolicyListMode.BLACKLIST,
                            lsposed = listMode == PolicyListMode.BLACKLIST,
                            kernelHookMask = if (modeResetPending) null else selfExisting?.kernelHookMask,
                            javaHookMask = if (modeResetPending) null else selfExisting?.javaHookMask,
                        )

                    db.replaceProtectionPolicy(
                        listMode = listMode,
                        apps = protections,
                        resetRulesAndOverrides = modeResetPending,
                    )

                    val success =
                        dev.soranerai.vpnhidenext.db.DatabaseSync
                            .sync(context)
                    if (success) {
                        DashboardCache.refresh(scope, context, selfNeedsRestart)
                        DiagnosticsCache.reset()
                        DiagnosticsCache.run(scope, context)
                        TargetsCache.refreshAndWait(context)
                        originalApps = apps
                        originalListMode = listMode
                        modeResetPending = false
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

        val appListRefreshing by AppListCache.loading.collectAsState()
        val targetsRefreshing by TargetsCache.loading.collectAsState()
        UnifiedRefreshIndicator(
            visible = appListRefreshing || targetsRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun ModeHelpSection(
    title: String,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
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
        c.kmod != o.kmod || c.lsposed != o.lsposed || c.portHiding != o.portHiding ||
            c.systemPolicyExplicit != o.systemPolicyExplicit
    }
}
