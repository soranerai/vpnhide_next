package dev.soranerai.vpnhidenext

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val appsList by AppListCache.apps.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var retentionPeriod by remember { mutableStateOf(StatsRetentionPeriod.THIRTY_MIN) }

    LaunchedEffect(Unit) {
        InterceptStatsCache.ensureLoaded(scope, context)
        retentionPeriod = withContext(Dispatchers.IO) { getStatsRetentionPeriod(context) }
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
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            InterceptStatisticsSection(stats = stats, appsList = appsList, retentionPeriod = retentionPeriod)

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
    retentionPeriod: StatsRetentionPeriod,
) {
    var expandedApps by remember { mutableStateOf(setOf<Int>()) }

    Spacer(Modifier.height(12.dp))

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
                            stringResource(
                                R.string.dashboard_stats_lifetime_hint_fmt,
                                retentionPeriod.displayLabel(),
                            ),
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
                                // 2. Perform the actual backend reset
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

            if (stats == null) {
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

                        ElevatedCard(
                            onClick = {
                                expandedApps =
                                    if (isExpanded) {
                                        expandedApps - appStat.uid
                                    } else {
                                        expandedApps + appStat.uid
                                    }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                CardDefaults.elevatedCardColors(
                                    containerColor = innerCardColor,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
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
                                        if (appStat.portsCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor =
                                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                            ) {
                                                Text(
                                                    text = "P: ${appStat.portsCount}",
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

                                // Expandable breakdowns
                                if (isExpanded) {
                                    Spacer(Modifier.height(12.dp))
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
                                                    ) {
                                                        Text(
                                                            text = hook,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color =
                                                                MaterialTheme.colorScheme
                                                                    .onSurfaceVariant,
                                                        )
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
                                                        )
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
