package dev.okhsunrog.vpnhide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnHideApp(onReady: () -> Unit = {}) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) darkColorScheme() else lightColorScheme()
        }

    MaterialTheme(colorScheme = colorScheme) {
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
    var showFilterMenu by remember { mutableStateOf(false) }
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

    Scaffold(
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
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Box {
                                val anyFilterActive = showSystem || showRussianOnly
                                // Active-filter indicator: the old `tint = primary`
                                // did not contrast reliably against the topbar's
                                // `primaryContainer` on Material You palettes where
                                // primary and primaryContainer end up close in tone.
                                // FilledIconButton paints itself with `primary` /
                                // `onPrimary`, a pair M3 guarantees to contrast,
                                // so the indicator reads on any dynamic theme.
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
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == Tab.Dashboard,
                    onClick = { currentTab = Tab.Dashboard },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_dashboard)) },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Protection,
                    onClick = { currentTab = Tab.Protection },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_protection)) },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Diagnostics,
                    onClick = { currentTab = Tab.Diagnostics },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_diagnostics)) },
                )
            }
        },
    ) { innerPadding ->
        val restart = selfNeedsRestart
        Column(modifier = Modifier.padding(innerPadding)) {
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
