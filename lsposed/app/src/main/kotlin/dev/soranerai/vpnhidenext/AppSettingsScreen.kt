package dev.soranerai.vpnhidenext

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig
import dev.soranerai.vpnhidenext.db.DbMassPortRule
import dev.soranerai.vpnhidenext.db.DbPortRule
import dev.soranerai.vpnhidenext.ui.theme.TelBlue
import dev.soranerai.vpnhidenext.ui.theme.TelGreen
import dev.soranerai.vpnhidenext.ui.theme.TelOrange
import dev.soranerai.vpnhidenext.ui.theme.TelPink
import dev.soranerai.vpnhidenext.ui.theme.TelPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One screen for both scopes: [app] == null edits the global hook masks /
 * mass port rules; a non-null [app] edits that app's per-app hook mask
 * overrides / local port rules. Replaces the previously-separate
 * HookTestingScreen (global hooks), AppHookIsolationScreen (per-app hooks),
 * BulkRulesScreen (global ports) and PortRulesScreen (per-app ports).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsScreen(
    app: AppEntry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { 3 }

    var rules by remember { mutableStateOf<List<PortRule>>(emptyList()) }
    var massRules by remember { mutableStateOf<List<PortRule>>(emptyList()) }
    var rulesLoaded by remember { mutableStateOf(false) }

    // Identity and visibility are tracked separately: while the predictive-back
    // gesture is held (or the exit animation is running), ruleScreenIdentity
    // must keep pointing at the rule being edited (or null for "add new") so
    // the screen doesn't blank out or flash to "add" mode mid-gesture — it's
    // only ever overwritten by the next open, never reset on close.
    var ruleScreenIdentity by remember { mutableStateOf<PortRule?>(null) }
    var ruleScreenOpen by remember { mutableStateOf(false) }

    suspend fun refreshRules() {
        val db = AppDatabase.getInstance(context)
        val mass = db.massPortRuleDao().getMassRulesSync().map { it.toPortRule() }
        massRules = mass
        rules =
            if (app != null) {
                db.portRuleDao().getRulesForAppSync(app.packageName, app.userId).map { it.toPortRule() }
            } else {
                mass
            }
        rulesLoaded = true
    }

    LaunchedEffect(app?.packageName, app?.userId) {
        withContext(Dispatchers.IO) { refreshRules() }
    }

    fun mutateRules(action: suspend (AppDatabase) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            action(db)
            DatabaseSync.sync(context)
            refreshRules()
        }
    }

    fun saveRule(newRule: PortRule) {
        mutateRules { db ->
            if (app != null) {
                db.portRuleDao().insertRule(
                    DbPortRule(
                        id = newRule.id.toLongOrNull() ?: 0L,
                        packageName = app.packageName,
                        userId = app.userId,
                        startPort = newRule.startPort,
                        endPort = newRule.endPort,
                        protocol = newRule.protocol,
                        label = newRule.label,
                        enabled = newRule.enabled,
                    ),
                )
            } else {
                db.massPortRuleDao().insertMassRule(
                    DbMassPortRule(
                        id = newRule.id.toLongOrNull() ?: 0L,
                        startPort = newRule.startPort,
                        endPort = newRule.endPort,
                        protocol = newRule.protocol,
                        label = newRule.label,
                        enabled = newRule.enabled,
                    ),
                )
            }
        }
    }

    fun deleteRule(rule: PortRule) {
        val id = rule.id.toLongOrNull() ?: return
        mutateRules { db -> if (app != null) db.portRuleDao().deleteRule(id) else db.massPortRuleDao().deleteMassRule(id) }
    }

    fun toggleRule(rule: PortRule) {
        saveRule(rule.copy(enabled = !rule.enabled))
    }

    fun openAddRule() {
        ruleScreenIdentity = null
        ruleScreenOpen = true
    }

    fun openEditRule(rule: PortRule) {
        ruleScreenIdentity = rule
        ruleScreenOpen = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (app != null) {
                            Column {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(stringResource(R.string.app_settings_title_global))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .padding(top = innerPadding.calculateTopPadding())
                        .fillMaxSize(),
            ) {
                // beyondViewportPageCount keeps all 3 pages composed at once (there
                // are only 3 total) so switching pages — by swipe or by tapping
                // the pill below — never disposes/reloads a page's state, which
                // was the source of a visible blank-then-reload flicker.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 2,
                ) { page ->
                    when (page) {
                        0 -> {
                            HookPage(app = app, hooks = ALL_HOOKS, isKernel = true, accent = TelBlue)
                        }

                        1 -> {
                            HookPage(app = app, hooks = ALL_JAVA_HOOKS, isKernel = false, accent = TelPurple)
                        }

                        else -> {
                            PortsTab(
                                app = app,
                                rules = rules,
                                massRules = massRules,
                                loaded = rulesLoaded,
                                onEditRule = ::openEditRule,
                                onDeleteRule = ::deleteRule,
                                onToggleRule = ::toggleRule,
                            )
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 20.dp)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    PillTabSelector(
                        // Single-letter labels reuse the N/F/P convention already
                        // used for the per-app protection chips in AppRows.kt
                        // (kernel/framework/port) — full words ("Framework")
                        // don't fit three-across without wrapping.
                        tabs =
                            listOf(
                                PillTab(Icons.Filled.Memory, "N", accent = TelBlue),
                                PillTab(Icons.Filled.Code, "F", accent = TelPurple),
                                PillTab(Icons.Filled.Dns, "P", accent = TelPink),
                            ),
                        selectedIndex = pagerState.currentPage,
                        onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier.width(220.dp),
                        height = 60.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                        tonalElevation = 12.dp,
                        shadowElevation = 8.dp,
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = pagerState.currentPage == 2,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier.align(Alignment.Center).offset(x = 152.dp),
                    ) {
                        FloatingActionButton(
                            onClick = { openAddRule() },
                            containerColor = TelPink,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.size(60.dp),
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    }
                }
            }
        }

        // Rendered as a sibling of the Scaffold above, not nested inside its
        // content Box — that content is pushed down by innerPadding for this
        // screen's own top bar, so a rule screen nested in there would render
        // its own TopAppBar starting below this screen's bar (looking like a
        // second stacked header) instead of covering the whole screen.
        PredictiveBackOverlay(
            visible = ruleScreenOpen,
            onBack = { ruleScreenOpen = false },
            modifier = Modifier.fillMaxSize(),
        ) {
            PortRuleScreen(
                initialRule = ruleScreenIdentity,
                existingRules = rules,
                massRules = if (app != null) massRules else emptyList(),
                onBack = { ruleScreenOpen = false },
                onConfirm = { newRule ->
                    saveRule(newRule)
                    ruleScreenOpen = false
                },
            )
        }
    }
}

@Composable
private fun HookPage(
    app: AppEntry?,
    hooks: List<HookInfo>,
    isKernel: Boolean,
    accent: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        HookMaskSection(app = app, hooks = hooks, isKernel = isKernel, accent = accent)
        val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Spacer(Modifier.height(bottomNavPadding + 100.dp))
    }
}

/**
 * Drives both the global editor (app == null, always "overriding" its own
 * value) and the per-app editor (app != null, has an explicit "use global"
 * switch) through the same read/write/render path — the two only differ in
 * where the mask is persisted.
 */
@Composable
private fun HookMaskSection(
    app: AppEntry?,
    hooks: List<HookInfo>,
    isKernel: Boolean,
    accent: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var writeJob by remember { mutableStateOf<Job?>(null) }

    var globalMask by remember { mutableStateOf(0xFFFFFFFFu) }
    var hasOverride by remember { mutableStateOf(app == null) }
    var mask by remember { mutableStateOf<UInt?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }

    val errorText =
        stringResource(
            if (isKernel) R.string.hook_testing_kernel_err_set else R.string.hook_testing_framework_err_set,
        )

    suspend fun refresh() {
        val db = AppDatabase.getInstance(context)
        val config = db.globalConfigDao().getConfig() ?: DbGlobalConfig()
        val gMask = (if (isKernel) config.kernelHookMask else config.javaHookMask).toUInt()
        if (app == null) {
            globalMask = gMask
            hasOverride = true
            mask = gMask
        } else {
            val proto = db.appDao().getAppProtection(app.packageName, app.userId)
            val override = if (isKernel) proto?.kernelHookMask else proto?.javaHookMask
            globalMask = gMask
            hasOverride = override != null
            mask = override?.toUInt() ?: gMask
        }
        error = null
    }

    LaunchedEffect(app?.packageName, app?.userId, isKernel) { refresh() }

    fun apply(
        newHasOverride: Boolean,
        newMask: UInt,
    ) {
        val oldHasOverride = hasOverride
        val oldMask = mask
        hasOverride = newHasOverride
        mask = newMask
        error = null
        applying = true
        writeJob?.cancel()
        writeJob =
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                val success =
                    if (app == null) {
                        val dao = db.globalConfigDao()
                        val cur = dao.getConfig() ?: DbGlobalConfig()
                        dao.insertConfig(
                            if (isKernel) cur.copy(kernelHookMask = newMask.toLong()) else cur.copy(javaHookMask = newMask.toLong()),
                        )
                        DatabaseSync.sync(context)
                    } else {
                        val appDao = db.appDao()
                        val existing =
                            appDao.getAppProtection(app.packageName, app.userId)
                                ?: AppProtection(packageName = app.packageName, userId = app.userId, uid = app.uid)
                        appDao.insertAppProtection(
                            if (isKernel) {
                                existing.copy(kernelHookMask = if (newHasOverride) newMask.toLong() else null)
                            } else {
                                existing.copy(javaHookMask = if (newHasOverride) newMask.toLong() else null)
                            },
                        )
                        DatabaseSync.sync(context)
                    }
                withContext(Dispatchers.Main) {
                    if (!success) {
                        hasOverride = oldHasOverride
                        mask = oldMask
                        error = errorText
                    }
                    applying = false
                }
            }
    }

    val m = mask ?: return
    val editable = app == null || hasOverride
    val effectiveMask = if (editable) m else globalMask

    if (app != null) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.app_hook_isolation_use_global),
                subtitle = stringResource(R.string.app_hook_isolation_use_global_desc),
                checked = !hasOverride,
                onCheckedChange = { useGlobal -> apply(!useGlobal, if (useGlobal) m else globalMask) },
                icon = Icons.Filled.Public,
                iconTint = accent,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (error != null) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    HookMaskEditor(
        hooks = hooks,
        effectiveMask = effectiveMask,
        editable = editable,
        applying = applying,
        accent = accent,
        // Presets are per-app only — the global mask already has its own
        // Min/Standard/Max control (ProtectionLevelCard on the Dashboard),
        // so showing them again here too would be a redundant, confusing
        // second control for the same global value.
        showPresets = isKernel && app != null,
        onMaskChange = { newMask -> apply(true, newMask) },
    )
}

@Composable
private fun HookMaskEditor(
    hooks: List<HookInfo>,
    effectiveMask: UInt,
    editable: Boolean,
    applying: Boolean,
    accent: Color,
    showPresets: Boolean,
    onMaskChange: (UInt) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "0x${effectiveMask.toString(16).uppercase()} ($effectiveMask)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = applying,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = accent)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Only the kernel mask maps to the Мин/Сред/Макс tiers shown on
            // the Dashboard (PROTECTION_MASK_MIN/AVG/MAX) — the Java mask has
            // no such tiering, its hooks are flat reflection-based checks
            // with no meaningful coverage/perf tradeoff between them.
            if (showPresets) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets =
                        listOf(
                            Triple(PROTECTION_MASK_MIN, R.string.protection_level_min, TelBlue),
                            Triple(PROTECTION_MASK_AVG, R.string.protection_level_avg, TelGreen),
                            Triple(PROTECTION_MASK_MAX, R.string.protection_level_max, TelOrange),
                        )
                    for ((presetMask, labelRes, presetColor) in presets) {
                        val selected = effectiveMask == presetMask
                        OutlinedButton(
                            onClick = { onMaskChange(presetMask) },
                            enabled = editable && !selected,
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) presetColor.copy(alpha = 0.15f) else Color.Transparent,
                                    contentColor = presetColor,
                                ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(labelRes), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onMaskChange(0xFFFFFFFFu) },
                    enabled = editable && effectiveMask != 0xFFFFFFFFu,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ToggleOn, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hook_testing_btn_enable_all))
                }

                OutlinedButton(
                    onClick = { onMaskChange(0x0000u) },
                    enabled = editable && effectiveMask != 0x0000u,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ToggleOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hook_testing_btn_disable_all))
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (hook in hooks) {
            val isEnabled = hook.allIndices.all { idx -> (effectiveMask and (1u shl idx)) != 0u }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (isEnabled) {
                                accent.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.hook_testing_idx_format, hook.index)) },
                                colors =
                                    SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = accent.copy(alpha = if (isEnabled) 0.15f else 0.06f),
                                        labelColor = if (isEnabled) accent else accent.copy(alpha = 0.5f),
                                    ),
                                border = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = stringResource(hook.nameRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (isEnabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(hook.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isEnabled) 0.8f else 0.4f),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Switch(
                        checked = isEnabled,
                        enabled = editable,
                        onCheckedChange = { checked ->
                            var newMask = effectiveMask
                            for (idx in hook.allIndices) {
                                newMask =
                                    if (checked) {
                                        newMask or (1u shl idx)
                                    } else {
                                        newMask and (1u shl idx).inv()
                                    }
                            }
                            onMaskChange(newMask)
                        },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = accent,
                                checkedBorderColor = accent,
                            ),
                    )
                }
            }
        }
    }
}

private fun DbPortRule.toPortRule() =
    PortRule(id = id.toString(), startPort = startPort, endPort = endPort, protocol = protocol, label = label, enabled = enabled)

private fun DbMassPortRule.toPortRule() =
    PortRule(id = id.toString(), startPort = startPort, endPort = endPort, protocol = protocol, label = label, enabled = enabled)

/** Small tinted icon+text banner — shared by the port-hiding-off hint and the rule dialog's warnings. */
@Composable
private fun InlineHintBanner(
    text: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Purely presentational — rule data/mutations live in [AppSettingsScreen] so the rule editor can overlay the whole tab bar. */
@Composable
private fun PortsTab(
    app: AppEntry?,
    rules: List<PortRule>,
    massRules: List<PortRule>,
    loaded: Boolean,
    onEditRule: (PortRule) -> Unit,
    onDeleteRule: (PortRule) -> Unit,
    onToggleRule: (PortRule) -> Unit,
) {
    val showEmpty = rules.isEmpty() && (app == null || massRules.isEmpty())

    Box(modifier = Modifier.fillMaxSize()) {
        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (app != null && !app.portHiding) {
                    InlineHintBanner(
                        text = stringResource(R.string.app_settings_port_hiding_off_hint),
                        icon = Icons.Default.WarningAmber,
                        tint = TelOrange,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }

                if (showEmpty) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = TelPink.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.no_rules),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumnPortRules(
                        app = app,
                        rules = rules,
                        massRules = massRules,
                        onEdit = onEditRule,
                        onDelete = onDeleteRule,
                        onToggle = onToggleRule,
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyColumnPortRules(
    app: AppEntry?,
    rules: List<PortRule>,
    massRules: List<PortRule>,
    onEdit: (PortRule) -> Unit,
    onDelete: (PortRule) -> Unit,
    onToggle: (PortRule) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (app != null && massRules.isNotEmpty()) {
            item(key = "mass_header") {
                Text(
                    stringResource(R.string.ports_mass_rules_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(massRules, key = { "mass_${it.id}" }) { rule ->
                PortRuleCard(rule = rule, isReadOnly = true, onEdit = {}, onDelete = {}, onToggle = {})
            }
            item(key = "local_header") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    stringResource(R.string.ports_local_rules_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        items(rules, key = { it.id }) { rule ->
            PortRuleCard(
                rule = rule,
                onEdit = { onEdit(rule) },
                onDelete = { onDelete(rule) },
                onToggle = { onToggle(rule) },
            )
        }

        item(key = "spacer_bottom") {
            val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(bottomNavPadding + 100.dp))
        }
    }
}

@Composable
private fun PortRuleCard(
    rule: PortRule,
    isReadOnly: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent = if (isReadOnly) MaterialTheme.colorScheme.secondary else TelPink
    ElevatedCard(
        onClick = if (isReadOnly) ({}) else onEdit,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (isReadOnly) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    } else {
                        TelPink.copy(alpha = 0.08f)
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon = Icons.Default.Dns, tint = accent)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (rule.label.isNotEmpty()) {
                    Text(
                        text = rule.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
                Text(
                    text =
                        if (rule.startPort == rule.endPort) {
                            stringResource(R.string.port_rule_port_format, rule.startPort)
                        } else {
                            stringResource(R.string.port_rule_range_format, rule.startPort, rule.endPort)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isReadOnly) MaterialTheme.colorScheme.secondary else androidx.compose.ui.graphics.Color.Unspecified,
                )
                val protoLabel =
                    when (rule.protocol) {
                        PortProtocol.TCP -> "TCP"
                        PortProtocol.UDP -> "UDP"
                        PortProtocol.BOTH -> stringResource(R.string.protocol_both)
                    }
                Text(
                    text = stringResource(R.string.port_rule_protocol_format, protoLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
            if (!isReadOnly) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            checkedBorderColor = accent,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Text(stringResource(R.string.bulk_btn).uppercase(), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortRuleScreen(
    initialRule: PortRule?,
    existingRules: List<PortRule>,
    massRules: List<PortRule>,
    onBack: () -> Unit,
    onConfirm: (PortRule) -> Unit,
) {
    var label by remember { mutableStateOf(initialRule?.label ?: "") }
    var startPort by remember { mutableStateOf(initialRule?.startPort?.toString() ?: "") }
    var endPort by remember { mutableStateOf(initialRule?.endPort?.toString() ?: "") }
    var protocol by remember { mutableStateOf(initialRule?.protocol ?: PortProtocol.BOTH) }

    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TelPink,
            focusedLabelColor = TelPink,
            cursorColor = TelPink,
        )

    val currentRule =
        PortRule(
            id = initialRule?.id ?: "",
            startPort = startPort.toIntOrNull() ?: (endPort.toIntOrNull() ?: 1),
            endPort = endPort.toIntOrNull() ?: (startPort.toIntOrNull() ?: 65535),
            protocol = protocol,
            label = label,
            enabled = true,
        )

    val violationLocal = validateRule(currentRule, existingRules)
    val violationMass = validateRule(currentRule, massRules)
    val violation = if (violationMass.type != RuleViolationType.NONE) violationMass else violationLocal

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initialRule == null) {
                            stringResource(R.string.ports_new_rule_title)
                        } else {
                            stringResource(R.string.ports_edit_rule_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        bottomBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = violation.type == RuleViolationType.NONE,
                    onClick = {
                        onConfirm(
                            currentRule.copy(
                                id =
                                    initialRule?.id ?: java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                enabled = initialRule?.enabled ?: true,
                            ),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelPink, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (initialRule == null) stringResource(R.string.add_rule) else stringResource(R.string.btn_save))
                }
            }
        },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.label_optional)) },
                placeholder = { Text(stringResource(R.string.ports_label_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = startPort,
                    onValueChange = { if (it.length <= 5) startPort = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.port_start)) },
                    placeholder = { Text("1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endPort,
                    onValueChange = { if (it.length <= 5) endPort = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.port_end)) },
                    placeholder = { Text(if (startPort.isEmpty()) "65535" else startPort) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                stringResource(R.string.ports_default_range_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.protocol),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                val protocolLabels =
                    mapOf(
                        PortProtocol.TCP to "TCP",
                        PortProtocol.UDP to "UDP",
                        PortProtocol.BOTH to stringResource(R.string.protocol_both),
                    )
                PillTabSelector(
                    tabs =
                        PortProtocol.values().map { p ->
                            PillTab(icon = null, label = protocolLabels.getValue(p), accent = TelPink)
                        },
                    selectedIndex = PortProtocol.values().indexOf(protocol),
                    onSelect = { index -> protocol = PortProtocol.values()[index] },
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
            }

            if (violation.type != RuleViolationType.NONE) {
                val msg =
                    when (violation.type) {
                        RuleViolationType.DUPLICATE -> {
                            stringResource(R.string.err_rule_exists)
                        }

                        RuleViolationType.REDUNDANT -> {
                            val target = violation.coveringRule
                            if (target?.label?.isNotEmpty() == true) {
                                stringResource(R.string.err_rule_redundant, target.label)
                            } else {
                                stringResource(R.string.err_rule_redundant, "${target?.startPort}-${target?.endPort}")
                            }
                        }

                        else -> {
                            ""
                        }
                    }
                InlineHintBanner(text = msg, icon = Icons.Filled.ErrorOutline, tint = MaterialTheme.colorScheme.error)
            } else {
                val rulesToRemove =
                    existingRules.filter { e ->
                        e.id != initialRule?.id &&
                            currentRule.startPort <= e.startPort && currentRule.endPort >= e.endPort &&
                            (protocol == PortProtocol.BOTH || protocol == e.protocol)
                    }
                if (rulesToRemove.isNotEmpty()) {
                    InlineHintBanner(
                        text = stringResource(R.string.ports_redundant_rules_removed_warning, rulesToRemove.size),
                        icon = Icons.Filled.Info,
                        tint = TelPink,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
