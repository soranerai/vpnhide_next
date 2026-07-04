package dev.soranerai.vpnhidenext

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.AppProtection
import dev.soranerai.vpnhidenext.db.DatabaseSync
import dev.soranerai.vpnhidenext.db.DbGlobalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppHookIsolationScreen(
    app: AppEntry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableStateOf(0) }

    var kernelWriteJob by remember { mutableStateOf<Job?>(null) }
    var javaWriteJob by remember { mutableStateOf<Job?>(null) }

    var globalKernelMask by remember { mutableStateOf(0xFFFFFFFFu) }
    var globalJavaMask by remember { mutableStateOf(0xFFFFFFFFu) }

    var hasKernelOverride by remember { mutableStateOf(false) }
    var kernelMask by remember { mutableStateOf<UInt?>(null) }
    var kernelError by remember { mutableStateOf<String?>(null) }
    var applyingKernel by remember { mutableStateOf(false) }

    var hasJavaOverride by remember { mutableStateOf(false) }
    var javaMask by remember { mutableStateOf<UInt?>(null) }
    var javaError by remember { mutableStateOf<String?>(null) }
    var applyingJava by remember { mutableStateOf(false) }

    val errKernelSet = stringResource(R.string.hook_testing_kernel_err_set)
    val errFrameworkSet = stringResource(R.string.hook_testing_framework_err_set)

    fun refreshFromDb() {
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val config = db.globalConfigDao().getConfig() ?: DbGlobalConfig()
            val proto = db.appDao().getAppProtection(app.packageName, app.userId)
            withContext(Dispatchers.Main) {
                globalKernelMask = config.kernelHookMask.toUInt()
                globalJavaMask = config.javaHookMask.toUInt()
                hasKernelOverride = proto?.kernelHookMask != null
                hasJavaOverride = proto?.javaHookMask != null
                kernelMask = proto?.kernelHookMask?.toUInt() ?: globalKernelMask
                javaMask = proto?.javaHookMask?.toUInt() ?: globalJavaMask
                kernelError = null
                javaError = null
            }
        }
    }

    LaunchedEffect(app.packageName, app.userId) { refreshFromDb() }

    fun applyKernel(
        newHasOverride: Boolean,
        newMask: UInt,
    ) {
        val oldHasOverride = hasKernelOverride
        val oldMask = kernelMask
        hasKernelOverride = newHasOverride
        kernelMask = newMask
        kernelError = null
        applyingKernel = true
        kernelWriteJob?.cancel()
        kernelWriteJob =
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                val appDao = db.appDao()
                val existing =
                    appDao.getAppProtection(app.packageName, app.userId)
                        ?: AppProtection(packageName = app.packageName, userId = app.userId, uid = app.uid)
                appDao.insertAppProtection(
                    existing.copy(kernelHookMask = if (newHasOverride) newMask.toLong() else null),
                )
                val success = DatabaseSync.sync(context)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        hasKernelOverride = oldHasOverride
                        kernelMask = oldMask
                        kernelError = errKernelSet
                    }
                    applyingKernel = false
                }
            }
    }

    fun applyJava(
        newHasOverride: Boolean,
        newMask: UInt,
    ) {
        val oldHasOverride = hasJavaOverride
        val oldMask = javaMask
        hasJavaOverride = newHasOverride
        javaMask = newMask
        javaError = null
        applyingJava = true
        javaWriteJob?.cancel()
        javaWriteJob =
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                val appDao = db.appDao()
                val existing =
                    appDao.getAppProtection(app.packageName, app.userId)
                        ?: AppProtection(packageName = app.packageName, userId = app.userId, uid = app.uid)
                appDao.insertAppProtection(
                    existing.copy(javaHookMask = if (newHasOverride) newMask.toLong() else null),
                )
                val success = DatabaseSync.sync(context)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        hasJavaOverride = oldHasOverride
                        javaMask = oldMask
                        javaError = errFrameworkSet
                    }
                    applyingJava = false
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_hook_isolation_title)) },
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.icon?.let {
                    Image(
                        bitmap = it.toBitmap(40, 40).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.hook_testing_tab_kernel), fontWeight = FontWeight.Bold) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
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

                if (selectedTabIndex == 0) {
                    kernelMask?.let { mask ->
                        HookIsolationSection(
                            hooks = ALL_HOOKS,
                            globalMask = globalKernelMask,
                            hasOverride = hasKernelOverride,
                            mask = mask,
                            applying = applyingKernel,
                            errorMessage = kernelError,
                            onUseGlobalChange = { useGlobal ->
                                applyKernel(!useGlobal, if (useGlobal) mask else globalKernelMask)
                            },
                            onMaskChange = { newMask -> applyKernel(true, newMask) },
                        )
                    }
                } else {
                    javaMask?.let { mask ->
                        HookIsolationSection(
                            hooks = ALL_JAVA_HOOKS,
                            globalMask = globalJavaMask,
                            hasOverride = hasJavaOverride,
                            mask = mask,
                            applying = applyingJava,
                            errorMessage = javaError,
                            onUseGlobalChange = { useGlobal ->
                                applyJava(!useGlobal, if (useGlobal) mask else globalJavaMask)
                            },
                            onMaskChange = { newMask -> applyJava(true, newMask) },
                        )
                    }
                }

                val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 32.dp))
            }
        }
    }
}

@Composable
private fun HookIsolationSection(
    hooks: List<HookInfo>,
    globalMask: UInt,
    hasOverride: Boolean,
    mask: UInt,
    applying: Boolean,
    errorMessage: String?,
    onUseGlobalChange: (Boolean) -> Unit,
    onMaskChange: (UInt) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                onCheckedChange = { useGlobal -> onUseGlobalChange(useGlobal) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    if (errorMessage != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    val effectiveMask = if (hasOverride) mask else globalMask

    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
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
                    enabled = !applying && (!hasOverride || effectiveMask != 0xFFFFFFFFu),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ToggleOn, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hook_testing_btn_enable_all))
                }

                OutlinedButton(
                    onClick = { onMaskChange(0x0000u) },
                    enabled = !applying && (!hasOverride || effectiveMask != 0x0000u),
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
                            color =
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (isEnabled) 0.8f else 0.4f,
                                ),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Switch(
                        checked = isEnabled,
                        enabled = hasOverride && !applying,
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
