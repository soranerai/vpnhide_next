package dev.soranerai.vpnhidenext

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.soranerai.vpnhidenext.data.repository.DashboardRepository
import dev.soranerai.vpnhidenext.domain.models.*
import dev.soranerai.vpnhidenext.ui.dashboard.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 3 content — was "Diagnostics" (moved to Settings), now dedicated to
 * intercept statistics (moved out of Dashboard, which had no other reason
 * to load/refresh [InterceptStatsCache] once this section left it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by InterceptStatsCache.stats.collectAsState()
    val statsUnavailable by InterceptStatsCache.unavailable.collectAsState()
    val statsResponse by InterceptStatsCache.response.collectAsState()
    val appsList by AppListCache.apps.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedPortApp by remember { mutableStateOf<AppInterceptStats?>(null) }

    selectedPortApp?.let { app ->
        Dialog(
            onDismissRequest = { selectedPortApp = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            PortStatisticsDetailScreen(
                app = app,
                onBack = { selectedPortApp = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    LaunchedEffect(Unit) {
        InterceptStatsCache.ensureLoaded(scope, context)
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                InterceptStatsCache.refresh(scope, context)
                InterceptStatsCache.loading.first { !it }
                refreshing = false
            }
        },
        indicator = {
            UnifiedRefreshIndicator(
                visible = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            PillTabSelector(
                tabs =
                    listOf(
                        PillTab(Icons.Default.Code, stringResource(R.string.statistics_tab_hooks)),
                        PillTab(Icons.Default.Dns, stringResource(R.string.statistics_tab_ports)),
                    ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                tonalElevation = 12.dp,
                shadowElevation = 8.dp,
            )
            Spacer(Modifier.height(12.dp))

            if (selectedTab == 0) {
                InterceptStatisticsSection(
                    stats =
                        stats
                            ?.filter { it.frameworkTotal > 0 || it.nativeTotal > 0 }
                            ?.sortedByDescending { it.frameworkTotal + it.nativeTotal },
                    appsList = appsList,
                    unavailable = statsUnavailable,
                    historyDropped = statsResponse?.dropped == true,
                )
            } else {
                PortStatisticsSection(
                    stats = stats?.filter { it.portsCount > 0 }?.sortedByDescending { it.portsCount },
                    appsList = appsList,
                    unavailable = statsUnavailable,
                    historyDropped = statsResponse?.dropped == true,
                    onOpenApp = { selectedPortApp = it },
                )
            }

            val bottomNavPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(bottomNavPadding + 100.dp))
        }
    }
}

@Composable
private fun InterceptStatisticsSection(
    stats: List<AppInterceptStats>?,
    appsList: List<AppSummary>?,
    unavailable: Boolean,
    historyDropped: Boolean,
) {
    var expandedApps by remember { mutableStateOf(setOf<Int>()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DashboardRepository(context.applicationContext) }

    val darkTheme = isSystemInDarkTheme()
    val containerColor = if (darkTheme) Color(0xFF1E3E28) else Color(0xFFE8F5E9)
    val contentColor = if (darkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    // Nested per-app cards stay in the same green family as the panel — a
    // lighter/darker tonal step, not the theme's neutral surface — so they
    // read as "inside this card" instead of an unrelated black/white pane.
    val innerCardColor = if (darkTheme) Color(0xFF17331F) else Color.White

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_intercept_statistics),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text =
                            stringResource(R.string.dashboard_stats_lifetime_hint_fmt),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }

                if (stats != null && stats.isNotEmpty()) {
                    SettingsSquareIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.btn_clear),
                        onClick = {
                            scope.launch {
                                // 1. Instantly clear the UI stats cache
                                InterceptStatsCache.clearStats()
                                // Clear only the daemon's userspace history. Kernel counters
                                // remain cumulative unless an explicit kernel reset is requested.
                                withContext(Dispatchers.IO) {
                                    repository.resetInterceptStats()
                                }
                                // 3. Silently refresh to ensure absolute sync
                                InterceptStatsCache.refresh(scope, context)
                            }
                        },
                        tint = contentColor,
                        size = 36.dp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (historyDropped) {
                Text(
                    text = stringResource(R.string.dashboard_stats_history_dropped),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (unavailable) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.dashboard_stats_unavailable_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dashboard_stats_unavailable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else if (stats == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonStatsCard(containerColor = innerCardColor)
                    SkeletonStatsCard(containerColor = innerCardColor)
                }
            } else if (stats.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = contentColor.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_stats_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dashboard_stats_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (appStat in stats) {
                        val isExpanded = expandedApps.contains(appStat.uid)
                        val appSummary = appsList?.find { it.uid == appStat.uid }
                        val icon = appSummary?.icon
                        val canExpand = appStat.frameworkTotal > 0 || appStat.nativeTotal > 0

                        ElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                CardDefaults.elevatedCardColors(
                                    containerColor = innerCardColor,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .then(
                                                if (canExpand) {
                                                    Modifier.clickable {
                                                        expandedApps =
                                                            if (isExpanded) {
                                                                expandedApps - appStat.uid
                                                            } else {
                                                                expandedApps + appStat.uid
                                                            }
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ).padding(14.dp),
                                ) {
                                    // App Icon
                                    Box(modifier = Modifier.size(40.dp)) {
                                        if (icon != null) {
                                            Image(
                                                bitmap = remember(icon) { icon.toBitmap(48, 48).asImageBitmap() },
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Surface(
                                                modifier = Modifier.fillMaxSize(),
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize(),
                                                ) {
                                                    Text(
                                                        text = appStat.appLabel.take(1).uppercase(),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onPrimaryContainer,
                                                    )
                                                }
                                            }
                                        }

                                        if (appStat.userId != 0) {
                                            Surface(
                                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                                                shape = CircleShape,
                                                color = Color(0xFF2196F3),
                                                tonalElevation = 4.dp,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Work,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(3.dp).size(12.dp),
                                                    tint = Color.White,
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    // App Label & Package Name
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = appStat.appLabel,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (appStat.userId != 0) Color(0xFF2196F3) else Color.Unspecified,
                                        )
                                        Text(
                                            text = appStat.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Badges for totals
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (appStat.frameworkTotal > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor =
                                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                            ) {
                                                Text(
                                                    text = "F: ${appStat.frameworkTotal}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier =
                                                        Modifier.padding(
                                                            horizontal = 6.dp,
                                                            vertical = 3.dp,
                                                        ),
                                                )
                                            }
                                        }
                                        if (appStat.nativeTotal > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                contentColor =
                                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                            ) {
                                                Text(
                                                    text = "N: ${appStat.nativeTotal}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier =
                                                        Modifier.padding(
                                                            horizontal = 6.dp,
                                                            vertical = 3.dp,
                                                        ),
                                                )
                                            }
                                        }
                                        if (canExpand) {
                                            Icon(
                                                imageVector =
                                                    if (isExpanded) {
                                                        Icons.Default.KeyboardArrowUp
                                                    } else {
                                                        Icons.Default.KeyboardArrowDown
                                                    },
                                                contentDescription = null,
                                                tint =
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.7f,
                                                    ),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }

                                // Expandable breakdowns
                                if (canExpand && isExpanded) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                    ) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        )
                                        Spacer(Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            // Framework Breakdown Column
                                            if (appStat.frameworkTotal > 0) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(R.string.dashboard_stats_framework_title),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    for ((hook, count) in appStat.frameworkBreakdown) {
                                                        Row(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 2.dp),
                                                            horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Text(
                                                                text = hook,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color =
                                                                    MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Spacer(Modifier.width(8.dp))
                                                            Text(
                                                                text = count.toString(),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Native Breakdown Column
                                            if (appStat.nativeTotal > 0) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(R.string.dashboard_stats_native_title),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    for ((vector, count) in appStat.nativeBreakdown) {
                                                        Row(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 2.dp),
                                                            horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            val vectorLabel =
                                                                when (vector) {
                                                                    "ioctl" -> stringResource(R.string.vector_label_ioctl)
                                                                    "netlink" -> stringResource(R.string.vector_label_netlink)
                                                                    "proc" -> stringResource(R.string.vector_label_proc)
                                                                    "sockopt" -> stringResource(R.string.vector_label_sockopt)
                                                                    "connect" -> stringResource(R.string.vector_label_connect)
                                                                    "getname" -> stringResource(R.string.vector_label_getname)
                                                                    else -> vector
                                                                }
                                                            Text(
                                                                text = vectorLabel,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color =
                                                                    MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Spacer(Modifier.width(8.dp))
                                                            Text(
                                                                text = count.toString(),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortStatisticsSection(
    stats: List<AppInterceptStats>?,
    appsList: List<AppSummary>?,
    unavailable: Boolean,
    historyDropped: Boolean,
    onOpenApp: (AppInterceptStats) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DashboardRepository(context.applicationContext) }
    val darkTheme = isSystemInDarkTheme()
    val containerColor = if (darkTheme) Color(0xFF1E3E28) else Color(0xFFE8F5E9)
    val contentColor = if (darkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val innerCardColor = if (darkTheme) Color(0xFF17331F) else Color.White

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.statistics_ports_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_stats_lifetime_hint_fmt),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
                if (!stats.isNullOrEmpty()) {
                    SettingsSquareIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.btn_clear),
                        onClick = {
                            scope.launch {
                                InterceptStatsCache.clearStats()
                                withContext(Dispatchers.IO) { repository.resetInterceptStats() }
                                InterceptStatsCache.refresh(scope, context)
                            }
                        },
                        tint = contentColor,
                        size = 36.dp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            if (historyDropped) {
                Text(
                    text = stringResource(R.string.dashboard_stats_history_dropped),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            when {
                unavailable -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_stats_unavailable_desc),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }

                stats == null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonStatsCard(containerColor = innerCardColor)
                        SkeletonStatsCard(containerColor = innerCardColor)
                    }
                }

                stats.isEmpty() -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = contentColor.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.statistics_ports_empty),
                            textAlign = TextAlign.Center,
                            color = contentColor,
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        )
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats.forEach { appStat ->
                            val appSummary = appsList?.find { it.uid == appStat.uid }
                            ElevatedCard(
                                onClick = { onOpenApp(appStat) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = innerCardColor),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                ) {
                                    AppStatsIcon(appStat, appSummary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = appStat.appLabel,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = appStat.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = appStat.portsCount.toString(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        Text(
                                            text =
                                                stringResource(
                                                    R.string.statistics_unique_ports_fmt,
                                                    appStat.portAccesses
                                                        .map { it.port }
                                                        .distinct()
                                                        .size,
                                                ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppStatsIcon(
    appStat: AppInterceptStats,
    appSummary: AppSummary?,
) {
    Box(modifier = Modifier.size(40.dp)) {
        val icon = appSummary?.icon
        if (icon != null) {
            Image(
                bitmap = remember(icon) { icon.toBitmap(48, 48).asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = appStat.appLabel.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (appStat.userId != 0) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                shape = CircleShape,
                color = Color(0xFF2196F3),
            ) {
                Icon(
                    imageVector = Icons.Default.Work,
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp).size(12.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

internal data class PortDisplayRange(
    val start: Int,
    val end: Int,
    val count: Int,
) {
    val label: String get() = if (start == end) start.toString() else "$start–$end"
}

internal fun buildPortDisplayRanges(accesses: List<PortAccess>): List<PortDisplayRange> {
    val counts = accesses.groupBy { it.port }.mapValues { (_, values) -> values.sumOf { it.count } }.toSortedMap()
    val ports = counts.keys.toList()
    val result = mutableListOf<PortDisplayRange>()
    var index = 0
    while (index < ports.size) {
        var endIndex = index
        while (endIndex + 1 < ports.size && ports[endIndex + 1] == ports[endIndex] + 1) endIndex++
        if (endIndex - index + 1 >= 3) {
            result +=
                PortDisplayRange(
                    start = ports[index],
                    end = ports[endIndex],
                    count = (index..endIndex).sumOf { counts.getValue(ports[it]) },
                )
        } else {
            for (item in index..endIndex) {
                val port = ports[item]
                result += PortDisplayRange(port, port, counts.getValue(port))
            }
        }
        index = endIndex + 1
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortStatisticsDetailScreen(
    app: AppInterceptStats,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(app.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf("tcp", "udp").forEach { protocol ->
                val accesses = app.portAccesses.filter { it.protocol == protocol }
                if (accesses.isNotEmpty()) {
                    Text(
                        text = protocol.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            buildPortDisplayRanges(accesses).forEachIndexed { index, range ->
                                if (index > 0) HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(range.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = range.count.toString(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonStatsCard(containerColor: Color = MaterialTheme.colorScheme.surface) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerPlaceholder(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerPlaceholder(modifier = Modifier.width(120.dp).height(16.dp))
                Spacer(Modifier.height(6.dp))
                ShimmerPlaceholder(modifier = Modifier.width(80.dp).height(12.dp))
            }
        }
    }
}
