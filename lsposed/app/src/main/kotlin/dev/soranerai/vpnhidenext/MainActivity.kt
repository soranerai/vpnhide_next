package dev.soranerai.vpnhidenext

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbMassPortRule
import dev.soranerai.vpnhidenext.ui.theme.VpnHideTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val splashReady = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !splashReady.get() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Load the user's debug-logging preference on a background dispatcher
        // and propagate the persisted flag to the on-disk sinks.
        lifecycleScope.launch(Dispatchers.IO) {
            VpnHideLog.init(applicationContext)
            applyDebugLoggingRuntime(VpnHideLog.enabled)
        }
        setContent { VpnHideApp(onReady = { splashReady.set(true) }) }
    }
}

private sealed class RootState {
    data class Granted(
        val startup: StartupResult,
    ) : RootState()

    data object Denied : RootState()

    data object KmodMissing : RootState()

    data object Migrating : RootState()
}

@Composable
fun VpnHideApp(onReady: () -> Unit = {}) {
    VpnHideTheme {
        var rootState by remember { mutableStateOf<RootState?>(null) }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            val hasLegacyDb = deContext.getDatabasePath("vpnhide_database").exists()
            if (hasLegacyDb) {
                rootState = RootState.Migrating
                withContext(Dispatchers.IO) {
                    AppDatabase.performMigrationIfRequired(context)
                }
            }
            val res =
                withContext(Dispatchers.IO) {
                    performStartupOptimized()
                }
            rootState =
                when {
                    !res.rootGranted -> RootState.Denied
                    !res.kmodActive -> RootState.KmodMissing
                    else -> RootState.Granted(res)
                }
        }

        when (rootState) {
            // splash holds until root check completes
            null -> {
                Unit
            }

            RootState.Migrating -> {
                LaunchedEffect(Unit) { onReady() }
                MigrationScreen()
            }

            RootState.Denied -> {
                // Drop splash — RootDeniedScreen has no async prerequisites.
                LaunchedEffect(Unit) { onReady() }
                RootDeniedScreen()
            }

            RootState.KmodMissing -> {
                LaunchedEffect(Unit) { onReady() }
                KmodMissingScreen()
            }

            is RootState.Granted -> {
                MainScreen(startup = (rootState as RootState.Granted).startup, onReady = onReady)
            }
        }
    }
}

private enum class Tab { Dashboard, Protection, Diagnostics }

private data class RefreshContext(
    val loading: Boolean,
    val onRefresh: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    startup: StartupResult,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var selfNeedsRestart by remember { mutableStateOf<Boolean?>(startup.addedToTargets) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }
    var showRussianOnly by remember { mutableStateOf(false) }
    var showOnlySelected by remember { mutableStateOf(false) }
    var showOnlyWorkProfile by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(AppSortOrder.SELECTED_FIRST) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var isProtectionDirty by remember { mutableStateOf(false) }
    var currentProtectionMode by remember { mutableStateOf(ProtectionMode.VpnTargets) }
    var dirtyProtectionModes by remember { mutableStateOf(setOf<ProtectionMode>()) }
    var saveTrigger by remember { mutableStateOf(0) }
    var editingAppRules by remember { mutableStateOf<AppEntry?>(null) }
    var showBulkRules by remember { mutableStateOf(false) }
    var localMassRules by remember { mutableStateOf<List<PortRule>?>(null) }
    var rulesUpdatedApp by remember { mutableStateOf<AppEntry?>(null) }
    var showIfacePrefixes by remember { mutableStateOf(false) }
    var localIfacePrefixes by remember { mutableStateOf<List<String>?>(null) }
    var showHookTesting by remember { mutableStateOf(false) }
    val userNames by AppListCache.userNames.collectAsState()
    val refreshRestart = selfNeedsRestart ?: false
    val searchFocusRequester = remember { FocusRequester() }

    // Kick off both Protection caches lazily — only when the user
    // navigates to Protection. Moved out of here to reduce startup jank.

    // Pre-warm Dashboard (needed for first frame) and Diagnostics (needed
    // when user switches to Diagnostics tab) as soon as selfNeedsRestart
    // is resolved. Dashboard prewarm here — not in DashboardScreen's own
    // LaunchedEffect — so it runs while the splash is still held, not
    // only after MainScreen has rendered.
    LaunchedEffect(selfNeedsRestart) {
        val r = selfNeedsRestart ?: return@LaunchedEffect
        DashboardCache.ensureLoaded(scope, context, r)
        if (!r) {
            DiagnosticsCache.run(scope, context)
            AppListCache.ensureLoaded(scope, context)
            TargetsCache.ensureLoaded(scope, context)
        }
    }

    // Hold the splash screen only until the Root / Startup check completes.
    // Dashboard data will pop in lazily once its suExec finishes.
    val uiReady = selfNeedsRestart != null
    LaunchedEffect(uiReady) {
        if (uiReady) onReady()
    }

    // Kick the update check once (silently) on first launch, and again
    // on ON_RESUME if it's been a while. Listener lives as long as
    // MainScreen is composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    UpdateCheckCache.ensureFresh(scope, BuildConfig.VERSION_NAME)
                    if (AppListCache.apps.value != null) {
                        AppListCache.refresh(scope, context)
                        TargetsCache.refresh(scope, context)
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Protection) {
            searchActive = false
            searchQuery = ""
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (searchActive && currentTab == Tab.Protection) {
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = {},
                                expanded = false,
                                onExpandedChange = {},
                                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                                leadingIcon = {
                                    IconButton(onClick = {
                                        searchActive = false
                                        searchQuery = ""
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    }
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = null)
                                        }
                                    }
                                },
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                    ) {}
                } else {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        actions = {
                            RefreshActionIcon(
                                currentTab = currentTab,
                                refreshRestart = refreshRestart,
                                scope = scope,
                                context = context,
                            )
                            if (currentTab == Tab.Protection) {
                                IconButton(onClick = { searchActive = true }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                    )
                                }
                                Box {
                                    val anyFilterActive =
                                        showSystem || showRussianOnly || showOnlySelected || showOnlyWorkProfile ||
                                            sortOrder != AppSortOrder.NAME_ASC
                                    if (anyFilterActive) {
                                        FilledIconButton(onClick = { showFilterMenu = true }) {
                                            Icon(
                                                Icons.Default.FilterList,
                                                contentDescription = null,
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = { showFilterMenu = true }) {
                                            Icon(
                                                Icons.Default.FilterList,
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showFilterMenu,
                                        onDismissRequest = { showFilterMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.filter_show_system)) },
                                            onClick = { showSystem = !showSystem },
                                            leadingIcon = {
                                                Checkbox(
                                                    checked = showSystem,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.filter_russian_only)) },
                                            onClick = { showRussianOnly = !showRussianOnly },
                                            leadingIcon = {
                                                Checkbox(
                                                    checked = showRussianOnly,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.filter_only_selected)) },
                                            onClick = { showOnlySelected = !showOnlySelected },
                                            leadingIcon = {
                                                Checkbox(
                                                    checked = showOnlySelected,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                        if (userNames.size > 1) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.filter_only_work_profile)) },
                                                onClick = { showOnlyWorkProfile = !showOnlyWorkProfile },
                                                leadingIcon = {
                                                    Checkbox(
                                                        checked = showOnlyWorkProfile,
                                                        onCheckedChange = null,
                                                    )
                                                },
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sort_name_asc)) },
                                            onClick = {
                                                sortOrder = AppSortOrder.NAME_ASC
                                                showFilterMenu = false
                                            },
                                            leadingIcon = {
                                                RadioButton(
                                                    selected = sortOrder == AppSortOrder.NAME_ASC,
                                                    onClick = null,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sort_name_desc)) },
                                            onClick = {
                                                sortOrder = AppSortOrder.NAME_DESC
                                                showFilterMenu = false
                                            },
                                            leadingIcon = {
                                                RadioButton(
                                                    selected = sortOrder == AppSortOrder.NAME_DESC,
                                                    onClick = null,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sort_selected_first)) },
                                            onClick = {
                                                sortOrder = AppSortOrder.SELECTED_FIRST
                                                showFilterMenu = false
                                            },
                                            leadingIcon = {
                                                RadioButton(
                                                    selected = sortOrder == AppSortOrder.SELECTED_FIRST,
                                                    onClick = null,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            val restart = selfNeedsRestart
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .padding(top = innerPadding.calculateTopPadding()),
                ) {
                    // Initial root check / startup loader
                    TopProgressBar(visible = restart == null)

                    // Tab-switch loaders (localized collection to prevent Scaffold recomposition)
                    TabLoadingBar()

                    if (restart != null) {
                        when (currentTab) {
                            Tab.Dashboard -> {
                                DashboardScreen(
                                    selfNeedsRestart = restart,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            Tab.Protection -> {
                                ProtectionScreen(
                                    searchQuery = searchQuery,
                                    showSystem = showSystem,
                                    showRussianOnly = showRussianOnly,
                                    showOnlySelected = showOnlySelected,
                                    showOnlyWorkProfile = showOnlyWorkProfile,
                                    sortOrder = sortOrder,
                                    onStateChange = { mode, dirtyModes ->
                                        currentProtectionMode = mode
                                        dirtyProtectionModes = dirtyModes
                                        isProtectionDirty = dirtyModes.isNotEmpty()
                                    },
                                    onAppPortConfig = { editingAppRules = it },
                                    updatedApp = rulesUpdatedApp,
                                    saveTrigger = saveTrigger,
                                    pendingMassRules = localMassRules,
                                    pendingIfacePrefixes = localIfacePrefixes,
                                    onSaved = {
                                        localMassRules = null
                                        localIfacePrefixes = null
                                        rulesUpdatedApp = null
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            Tab.Diagnostics -> {
                                DiagnosticsScreen(
                                    selfNeedsRestart = restart,
                                    onOpenHookTesting = { showHookTesting = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                // Floating Navigation Bar and FAB
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 20.dp)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val showSave = currentTab == Tab.Protection && currentProtectionMode in dirtyProtectionModes
                    val showExtraBtn = currentTab == Tab.Protection

                    val controlsProgress by animateFloatAsState(
                        targetValue = if (showSave || showExtraBtn) 1f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                        label = "controlsProgress",
                    )

                    val saveProgress by animateFloatAsState(
                        targetValue = if (showSave) 1f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                        label = "saveProgress",
                    )

                    val extraBtnProgress by animateFloatAsState(
                        targetValue = if (showExtraBtn) 1f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                        label = "extraBtnProgress",
                    )

                    val bulkColor by animateColorAsState(
                        targetValue = if (showSave) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                        label = "bulkColor",
                    )
                    val bulkContentColor by animateColorAsState(
                        targetValue = if (showSave) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
                        label = "bulkContentColor",
                    )

                    val tabs =
                        listOf(
                            Tab.Dashboard to Icons.Default.Home,
                            Tab.Protection to Icons.Default.Shield,
                            Tab.Diagnostics to Icons.Default.CheckCircle,
                        )

                    // The bounding box for the entire Pill + Save FAB combo
                    Box(contentAlignment = Alignment.Center) {
                        // Invisible layout driver to smoothly animate total width
                        Row(
                            modifier = Modifier.height(60.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(modifier = Modifier.width(260.dp))
                            Spacer(modifier = Modifier.width(76.dp * controlsProgress))
                        }

                        // Save Button (Anchored to the right, scales up)
                        if (saveProgress > 0.01f) {
                            Surface(
                                onClick = { saveTrigger++ },
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .size(60.dp)
                                        .graphicsLayer {
                                            shadowElevation = 8.dp.toPx()
                                            shape = RoundedCornerShape(20.dp)
                                            clip = true
                                            alpha = saveProgress
                                            scaleX = 0.5f + (0.5f * saveProgress)
                                            scaleY = 0.5f + (0.5f * saveProgress)
                                        },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }

                        // Extra Settings Button (Bulk Rules for Ports, Iface Prefixes for TUN)
                        if (extraBtnProgress > 0.01f) {
                            val extraBtnOffset by animateDpAsState(
                                targetValue = if (showSave) (-72).dp else 0.dp,
                                label = "extraBtnOffset",
                            )
                            Surface(
                                onClick = {
                                    if (currentProtectionMode == ProtectionMode.PortHiding) {
                                        showBulkRules = true
                                    } else {
                                        showIfacePrefixes = true
                                    }
                                },
                                color = bulkColor,
                                contentColor = bulkContentColor,
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .offset(y = extraBtnOffset)
                                        .size(60.dp)
                                        .graphicsLayer {
                                            shadowElevation = 8.dp.toPx()
                                            shape = RoundedCornerShape(20.dp)
                                            clip = true
                                            alpha = extraBtnProgress
                                            scaleX = 0.5f + (0.5f * extraBtnProgress)
                                            scaleY = 0.5f + (0.5f * extraBtnProgress)
                                        },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.SettingsSuggest,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }

                        // Navigation Pill (Anchored to the left)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                            tonalElevation = 12.dp,
                            shadowElevation = 8.dp,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .height(60.dp),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                        .width(260.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                tabs.forEach { (tab, icon) ->
                                    val selected = currentTab == tab
                                    Box(
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                ).clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) { currentTab = tab },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint =
                                                if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = editingAppRules != null,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            editingAppRules?.let { app ->
                BackHandler { editingAppRules = null }
                val targets by TargetsCache.snapshot.collectAsState()
                PortRulesScreen(
                    app = app,
                    massRules = localMassRules ?: targets?.massPortRules ?: emptyList(),
                    onBack = { editingAppRules = null },
                    onSave = { updatedRules ->
                        if (updatedRules != app.portRules) {
                            rulesUpdatedApp = app.copy(portRules = updatedRules)
                        }
                        editingAppRules = null
                    },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showBulkRules,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler { showBulkRules = false }
            val targets by TargetsCache.snapshot.collectAsState()
            BulkRulesScreen(
                initialRules = localMassRules ?: targets?.massPortRules ?: emptyList(),
                onBack = { showBulkRules = false },
                onSave = { updatedRules ->
                    localMassRules = updatedRules
                    showBulkRules = false
                },
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showIfacePrefixes,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler { showIfacePrefixes = false }
            val targets by TargetsCache.snapshot.collectAsState()
            IfacePrefixScreen(
                initialPrefixes = localIfacePrefixes ?: targets?.ifacePrefixes ?: emptyList(),
                onBack = { showIfacePrefixes = false },
                onSave = { updatedPrefixes ->
                    localIfacePrefixes = updatedPrefixes
                    showIfacePrefixes = false
                },
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showHookTesting,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler { showHookTesting = false }
            HookTestingScreen(
                onBack = { showHookTesting = false },
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            )
        }
    }
}

@Composable
private fun TabLoadingBar() {
    val appListLoading by AppListCache.loading.collectAsState()
    val targetsLoading by TargetsCache.loading.collectAsState()
    TopProgressBar(visible = appListLoading || targetsLoading)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootDeniedScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.root_error_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.root_error_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KmodMissingScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.kmod_error_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.kmod_error_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshActionIcon(
    currentTab: Tab,
    refreshRestart: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    // Collect loading states locally so that only this icon recomposes when loading status changes.
    val dashboardLoading by DashboardCache.loading.collectAsState()
    val appListLoading by AppListCache.loading.collectAsState()
    val targetsLoading by TargetsCache.loading.collectAsState()

    val refreshContext =
        when (currentTab) {
            Tab.Dashboard -> {
                RefreshContext(
                    loading = dashboardLoading,
                    onRefresh = {
                        DashboardCache.refresh(scope, context, refreshRestart)
                        UpdateCheckCache.refresh(scope, BuildConfig.VERSION_NAME)
                    },
                )
            }

            Tab.Protection -> {
                RefreshContext(
                    loading = appListLoading || targetsLoading,
                    onRefresh = {
                        AppListCache.refresh(scope, context)
                        TargetsCache.refresh(scope, context)
                    },
                )
            }

            Tab.Diagnostics -> {
                null
            }
        }

    refreshContext?.let { rc ->
        IconButton(
            onClick = rc.onRefresh,
            enabled = !rc.loading,
        ) {
            if (rc.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_refresh_apps),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MigrationScreen() {
    Scaffold { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.migrating_data),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
