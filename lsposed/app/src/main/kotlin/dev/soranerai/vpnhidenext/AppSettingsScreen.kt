package dev.soranerai.vpnhidenext

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig
import dev.soranerai.vpnhidenext.db.DbMassPortRule
import dev.soranerai.vpnhidenext.db.DbPortRule
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
    var selectedTab by remember { mutableStateOf(0) }

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
        modifier = modifier,
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.app_settings_tab_hooks), fontWeight = FontWeight.Bold) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.app_settings_tab_ports), fontWeight = FontWeight.Bold) },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (selectedTab == 0) HooksTab(app) else PortsTab(app)
            }
        }
    }
}

@Composable
private fun HooksTab(app: AppEntry?) {
    var subTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = subTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = { Text(stringResource(R.string.hook_testing_tab_kernel), fontWeight = FontWeight.Bold) },
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = { Text(stringResource(R.string.hook_testing_tab_framework), fontWeight = FontWeight.Bold) },
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            if (subTab == 0) {
                HookMaskSection(app = app, hooks = ALL_HOOKS, isKernel = true)
            } else {
                HookMaskSection(app = app, hooks = ALL_JAVA_HOOKS, isKernel = false)
            }
            val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(bottomNavPadding + 32.dp))
        }
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
        ElevatedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_hook_isolation_use_global),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.app_hook_isolation_use_global_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = !hasOverride,
                    enabled = !applying,
                    onCheckedChange = { useGlobal -> apply(!useGlobal, if (useGlobal) m else globalMask) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (error != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    HookMaskEditor(
        hooks = hooks,
        effectiveMask = effectiveMask,
        editable = editable,
        applying = applying,
        onMaskChange = { newMask -> apply(true, newMask) },
    )
}

@Composable
private fun HookMaskEditor(
    hooks: List<HookInfo>,
    effectiveMask: UInt,
    editable: Boolean,
    applying: Boolean,
    onMaskChange: (UInt) -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (applying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onMaskChange(0xFFFFFFFFu) },
                    enabled = editable && !applying && effectiveMask != 0xFFFFFFFFu,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ToggleOn, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hook_testing_btn_enable_all))
                }

                OutlinedButton(
                    onClick = { onMaskChange(0x0000u) },
                    enabled = editable && !applying && effectiveMask != 0x0000u,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
            ElevatedCard(
                shape = RoundedCornerShape(8.dp),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor =
                            if (isEnabled) {
                                MaterialTheme.colorScheme.surface
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
                        enabled = editable && !applying,
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

@Composable
private fun PortsTab(app: AppEntry?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<PortRule>>(emptyList()) }
    var massRules by remember { mutableStateOf<List<PortRule>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<PortRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val db = AppDatabase.getInstance(context)
        val mass = db.massPortRuleDao().getMassRulesSync().map { it.toPortRule() }
        massRules = mass
        rules =
            if (app != null) {
                db.portRuleDao().getRulesForAppSync(app.packageName, app.userId).map { it.toPortRule() }
            } else {
                mass
            }
        loaded = true
    }

    LaunchedEffect(app?.packageName, app?.userId) {
        withContext(Dispatchers.IO) { refresh() }
    }

    fun mutate(action: suspend (AppDatabase) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            action(db)
            DatabaseSync.sync(context)
            refresh()
        }
    }

    fun saveRule(newRule: PortRule) {
        mutate { db ->
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
        mutate { db -> if (app != null) db.portRuleDao().deleteRule(id) else db.massPortRuleDao().deleteMassRule(id) }
    }

    fun toggleRule(rule: PortRule) {
        saveRule(rule.copy(enabled = !rule.enabled))
    }

    val showEmpty = rules.isEmpty() && (app == null || massRules.isEmpty())

    Box(modifier = Modifier.fillMaxSize()) {
        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (app != null && !app.portHiding) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.app_settings_port_hiding_off_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                if (showEmpty) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
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
                        onEdit = { editingRule = it },
                        onDelete = ::deleteRule,
                        onToggle = ::toggleRule,
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
        }

        if (showAddDialog || editingRule != null) {
            PortRuleDialog(
                initialRule = editingRule,
                existingRules = rules,
                massRules = if (app != null) massRules else emptyList(),
                onDismiss = {
                    showAddDialog = false
                    editingRule = null
                },
                onConfirm = { newRule ->
                    saveRule(newRule)
                    showAddDialog = false
                    editingRule = null
                },
            )
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
                    color = MaterialTheme.colorScheme.primary,
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
    ElevatedCard(
        onClick = if (isReadOnly) ({}) else onEdit,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (isReadOnly) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (rule.label.isNotEmpty()) {
                    Text(
                        text = rule.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isReadOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
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
                    color = if (isReadOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
            }
            if (!isReadOnly) {
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
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
private fun PortRuleDialog(
    initialRule: PortRule? = null,
    existingRules: List<PortRule>,
    massRules: List<PortRule>,
    onDismiss: () -> Unit,
    onConfirm: (PortRule) -> Unit,
) {
    var label by remember { mutableStateOf(initialRule?.label ?: "") }
    var startPort by remember { mutableStateOf(initialRule?.startPort?.toString() ?: "") }
    var endPort by remember { mutableStateOf(initialRule?.endPort?.toString() ?: "") }
    var protocol by remember { mutableStateOf(initialRule?.protocol ?: PortProtocol.BOTH) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (initialRule == null) stringResource(R.string.ports_new_rule_title) else stringResource(R.string.ports_edit_rule_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.label_optional)) },
                    placeholder = { Text(stringResource(R.string.ports_label_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endPort,
                        onValueChange = { if (it.length <= 5) endPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.port_end)) },
                        placeholder = { Text(if (startPort.isEmpty()) "65535" else startPort) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    stringResource(R.string.ports_default_range_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.protocol), style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PortProtocol.values().forEachIndexed { index, p ->
                            val pLabel =
                                when (p) {
                                    PortProtocol.TCP -> "TCP"
                                    PortProtocol.UDP -> "UDP"
                                    PortProtocol.BOTH -> stringResource(R.string.protocol_both)
                                }
                            SegmentedButton(
                                selected = protocol == p,
                                onClick = { protocol = p },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PortProtocol.values().size),
                            ) {
                                Text(pLabel, fontSize = 10.sp)
                            }
                        }
                    }
                }

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

                if (violation.type != RuleViolationType.NONE) {
                    val msg =
                        when (violation.type) {
                            RuleViolationType.DUPLICATE -> stringResource(R.string.err_rule_exists)
                            RuleViolationType.REDUNDANT -> {
                                val target = violation.coveringRule
                                if (target?.label?.isNotEmpty() == true) {
                                    stringResource(R.string.err_rule_redundant, target.label)
                                } else {
                                    stringResource(R.string.err_rule_redundant, "${target?.startPort}-${target?.endPort}")
                                }
                            }
                            else -> ""
                        }
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    val rulesToRemove =
                        existingRules.filter { e ->
                            e.id != initialRule?.id &&
                                currentRule.startPort <= e.startPort && currentRule.endPort >= e.endPort &&
                                (protocol == PortProtocol.BOTH || protocol == e.protocol)
                        }
                    if (rulesToRemove.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ports_redundant_rules_removed_warning, rulesToRemove.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = violation.type == RuleViolationType.NONE,
                        onClick = {
                            onConfirm(
                                currentRule.copy(
                                    id = initialRule?.id ?: java.util.UUID.randomUUID().toString(),
                                    enabled = initialRule?.enabled ?: true,
                                ),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (initialRule == null) stringResource(R.string.add_rule) else stringResource(R.string.btn_save))
                    }
                }
            }
        }
    }
}
