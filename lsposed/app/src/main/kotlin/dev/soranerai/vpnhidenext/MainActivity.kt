package dev.soranerai.vpnhidenext
    
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
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
        // Load the user's debug-logging preference before anything else
        // runs so the first suExec + Dashboard reload honor it.
        VpnHideLog.init(applicationContext)
        // Re-propagate the persisted flag to the on-disk sinks as a
        // safety-net. Most reinstall scenarios are now covered by:
        //   - the canonical /data/adb/vpnhide_zygisk/debug_logging file
        //     surviving module reinstall (lives outside /data/adb/modules/),
        //   - kmod's service.sh re-seeding /proc/vpnhide_debug at boot,
        //   - zygisk's service.sh copying the canonical debug_logging
        //     into the module dir at boot.
        // The remaining gap is "user reinstalled a native module mid-
        // session and didn't reboot before opening the app" — service.sh
        // hasn't re-seeded the module-dir copy yet, so the next fork of
        // a target app would default to OFF without this re-write.
        // Cheap — one `su` roundtrip on a background dispatcher.
        lifecycleScope.launch(Dispatchers.IO) {
            applyDebugLoggingRuntime(VpnHideLog.enabled)
        }
        setContent { VpnHideApp(onReady = { splashReady.set(true) }) }
    }
}

private sealed class RootState {
    data class Granted(val startup: StartupResult) : RootState()

    data object Denied : RootState()
}

@Composable
fun VpnHideApp(onReady: () -> Unit = {}) {
    VpnHideTheme {
        val context = LocalContext.current
        var rootState by remember { mutableStateOf<RootState?>(null) }
        LaunchedEffect(Unit) {
            val appCtx = context.applicationContext
            val res =
                withContext(Dispatchers.IO) {
                    performStartupOptimized(appCtx.packageName)
                }
            rootState = if (res.rootGranted) RootState.Granted(res) else RootState.Denied
        }

        when (rootState) {
            // splash holds until root check completes
            null -> {
                Unit
            }

            RootState.Denied -> {
                // Drop splash — RootDeniedScreen has no async prerequisites.
                LaunchedEffect(Unit) { onReady() }
                RootDeniedScreen()
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
    var sortOrder by remember { mutableStateOf(AppSortOrder.NAME_ASC) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var isProtectionDirty by remember { mutableStateOf(false) }
    var saveTrigger by remember { mutableStateOf(0) }
    var showFaq by remember { mutableStateOf(false) }
    val refreshRestart = selfNeedsRestart ?: false

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cleanupStaleZygiskStatus(context, startup.currentBootId)
        }
        if (startup.addedToTargets) {
            withContext(Dispatchers.IO) {
                applyKmodTargets(context)
            }
        }
    }

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
        if (!r) DiagnosticsCache.run(scope, context)
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

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {}
    }

    Scaffold(
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        topBar = {
            if (searchActive && currentTab == Tab.Protection) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    active = false,
                    onActiveChange = {},
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
                    modifier = Modifier.fillMaxWidth(),
                ) {}
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    actions = {
                        RefreshActionIcon(
                            currentTab = currentTab,
                            refreshRestart = refreshRestart,
                            scope = scope,
                            context = context,
                        )
                        IconButton(onClick = { showFaq = true }) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = stringResource(R.string.faq_title),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (currentTab == Tab.Protection) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                )
                            }
                            Box {
                                val anyFilterActive = showSystem || showRussianOnly || showOnlySelected || sortOrder != AppSortOrder.NAME_ASC
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
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_name_asc)) },
                                        onClick = { sortOrder = AppSortOrder.NAME_ASC; showFilterMenu = false },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = sortOrder == AppSortOrder.NAME_ASC,
                                                onClick = null,
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_name_desc)) },
                                        onClick = { sortOrder = AppSortOrder.NAME_DESC; showFilterMenu = false },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = sortOrder == AppSortOrder.NAME_DESC,
                                                onClick = null,
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_selected_first)) },
                                        onClick = { sortOrder = AppSortOrder.SELECTED_FIRST; showFilterMenu = false },
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenWidth = maxWidth
            val density = androidx.compose.ui.platform.LocalDensity.current

            Column(
                modifier = Modifier
                    .padding(top = innerPadding.calculateTopPadding())
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
                                sortOrder = sortOrder,
                                onDirtyChange = { isProtectionDirty = it },
                                saveTrigger = saveTrigger,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Tab.Diagnostics -> {
                            DiagnosticsScreen(
                                selfNeedsRestart = restart,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // Floating Navigation Bar and FAB
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val showSave = isProtectionDirty && currentTab == Tab.Protection
                val tabs = listOf(
                    Tab.Dashboard to Icons.Default.Home,
                    Tab.Protection to Icons.Default.Shield,
                    Tab.Diagnostics to Icons.Default.CheckCircle
                )

                val saveProgress by animateFloatAsState(
                    targetValue = if (showSave) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "saveProgress"
                )

                // The bounding box for the entire Pill + Save FAB combo
                Box(contentAlignment = Alignment.Center) {
                    // Invisible layout driver to smoothly animate total width
                    Row(
                        modifier = Modifier.height(60.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(260.dp))
                        Spacer(modifier = Modifier.width(76.dp * saveProgress))
                    }

                    // Save Button (Anchored to the right, scales up)
                    if (saveProgress > 0.01f) {
                        Surface(
                            onClick = { saveTrigger++ },
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(60.dp)
                                .graphicsLayer {
                                    shadowElevation = 8.dp.toPx()
                                    shape = RoundedCornerShape(20.dp)
                                    clip = true
                                    alpha = saveProgress
                                    scaleX = 0.5f + (0.5f * saveProgress)
                                    scaleY = 0.5f + (0.5f * saveProgress)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
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
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .height(60.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .width(260.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEach { (tab, icon) ->
                                val selected = currentTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else Color.Transparent
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { currentTab = tab },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showFaq) {
                BackHandler { showFaq = false }
                FaqScreen(
                    onBack = { showFaq = false },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                )
            }
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
