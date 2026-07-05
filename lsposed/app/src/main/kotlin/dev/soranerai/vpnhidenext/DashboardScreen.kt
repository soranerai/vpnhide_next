package dev.soranerai.vpnhidenext

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.soranerai.vpnhidenext.data.repository.DashboardRepository
import dev.soranerai.vpnhidenext.domain.models.*
import dev.soranerai.vpnhidenext.ui.dashboard.components.*
import dev.soranerai.vpnhidenext.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProtectionLevel { MIN, AVG, MAX }

private val PROTECTION_MASK_MIN = 0x127FFu
private val PROTECTION_MASK_AVG = 0x1FFFFu
private val PROTECTION_MASK_MAX = 0xFFFFFFFFu

private fun UInt.toProtectionLevel(): ProtectionLevel? =
    when (this) {
        PROTECTION_MASK_MIN -> ProtectionLevel.MIN
        PROTECTION_MASK_AVG -> ProtectionLevel.AVG
        PROTECTION_MASK_MAX -> ProtectionLevel.MAX
        else -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    selfNeedsRestart: Boolean,
    onOpenInterceptStats: () -> Unit = {},
    onOpenDiagnosticsDetail: (scrollToBottom: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by DashboardCache.state.collectAsState()
    val updateInfo by UpdateCheckCache.info.collectAsState()
    var showChangelog by remember { mutableStateOf(false) }
    var changelogData by remember { mutableStateOf<ChangelogData?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // Both caches are reactive to tab switches without re-doing work:
    // ensureLoaded / ensureFresh are no-ops if the data is already
    // populated or an inflight job hasn't finished yet.
    LaunchedEffect(Unit) {
        DashboardCache.ensureLoaded(scope, context, selfNeedsRestart)
        UpdateCheckCache.ensureFresh(scope, BuildConfig.VERSION_NAME)
        if (shouldShowChangelog(context)) {
            val data = withContext(Dispatchers.IO) { loadChangelog(context) }
            if (data != null) {
                changelogData = data
                showChangelog = true
            }
            markChangelogSeen(context)
        }
    }

    if (showChangelog && changelogData != null) {
        ChangelogDialog(
            data = changelogData!!,
            onDismiss = { showChangelog = false },
        )
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                DashboardCache.refresh(scope, context, selfNeedsRestart)
                DiagnosticsCache.retry(scope, context)
                InterceptStatsCache.refresh(scope, context)

                val startTime = System.currentTimeMillis()
                kotlinx.coroutines.delay(50) // Allow loading flags to transition to true

                combine(
                    DashboardCache.loading,
                    DiagnosticsCache.state,
                    InterceptStatsCache.loading,
                ) { dashboard, diag, statsLoading ->
                    dashboard || (diag is DiagnosticsCache.State.Running) || statsLoading
                }.first { !it }

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 500) {
                    kotlinx.coroutines.delay(500 - elapsed)
                }
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
            Spacer(Modifier.height(12.dp))

            val s = state
            if (s == null) {
                SkeletonDashboard()
            } else {
                DashboardContent(
                    s = s,
                    selfNeedsRestart = selfNeedsRestart,
                    updateInfo = updateInfo,
                    scope = scope,
                    context = context,
                    onOpenInterceptStats = onOpenInterceptStats,
                    onOpenDiagnosticsDetail = onOpenDiagnosticsDetail,
                )
            }

            val bottomNavPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(bottomNavPadding + 100.dp))
        }
    }
}

@Composable
private fun SkeletonDashboard() {
    Column {
        Text(
            text = stringResource(R.string.dashboard_modules),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) { SkeletonModuleCard() }
            Box(modifier = Modifier.weight(1f)) { SkeletonModuleCard() }
        }
    }
}

@Composable
private fun SkeletonModuleCard() {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ShimmerPlaceholder(
                    modifier = Modifier.width(70.dp).height(16.dp),
                )
                ShimmerPlaceholder(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                )
            }
            Spacer(Modifier.height(8.dp))
            ShimmerPlaceholder(
                modifier = Modifier.width(100.dp).height(12.dp),
            )
        }
    }
}

@Composable
private fun DashboardContent(
    s: DashboardState,
    selfNeedsRestart: Boolean,
    updateInfo: UpdateInfo?,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onOpenInterceptStats: () -> Unit,
    onOpenDiagnosticsDetail: (scrollToBottom: Boolean) -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val errorBg = if (darkTheme) Color(0xFFB71C1C).copy(alpha = 0.3f) else Color(0xFFFFEBEE)
    val errorHeader = if (darkTheme) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val warningBg = if (darkTheme) Color(0xFFE65100).copy(alpha = 0.2f) else Color(0xFFFFF3E0)
    val warningHeader = if (darkTheme) Color(0xFFFFB74D) else Color(0xFFE65100)
    val onBannerColor = MaterialTheme.colorScheme.onSurface

    var kernelMask by remember { mutableStateOf<UInt?>(null) }
    val activeLevel: ProtectionLevel? = kernelMask?.toProtectionLevel()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db =
                dev.soranerai.vpnhidenext.db.AppDatabase
                    .getInstance(context)
            val config =
                db.globalConfigDao().getConfig()
                    ?: dev.soranerai.vpnhidenext.db
                        .DbGlobalConfig()
            withContext(Dispatchers.Main) { kernelMask = config.kernelHookMask.toUInt() }
        }
    }

    ProtectionLevelCard(
        activeLevel = activeLevel,
        isLoaded = kernelMask != null,
        onLevelSelected = { level ->
            val newMask =
                when (level) {
                    ProtectionLevel.MIN -> PROTECTION_MASK_MIN
                    ProtectionLevel.AVG -> PROTECTION_MASK_AVG
                    ProtectionLevel.MAX -> PROTECTION_MASK_MAX
                }
            kernelMask = newMask
            scope.launch(Dispatchers.IO) {
                val db =
                    dev.soranerai.vpnhidenext.db.AppDatabase
                        .getInstance(context)
                val dao = db.globalConfigDao()
                val cur =
                    dao.getConfig() ?: dev.soranerai.vpnhidenext.db
                        .DbGlobalConfig()
                dao.insertConfig(cur.copy(kernelHookMask = newMask.toLong()))
                dev.soranerai.vpnhidenext.db.DatabaseSync
                    .sync(context)
            }
        },
    )

    Spacer(Modifier.height(12.dp))

    Column {
        val (javaResult, nativeResult) =
            when (val p = s.protection) {
                is ProtectionCheck.Checked -> Pair(p.java, p.native)
                else -> Pair(null, null)
            }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LsposedCard(
                    state = s.lsposed,
                    javaResult = javaResult,
                    selfNeedsRestart = selfNeedsRestart,
                    onOpenDiagnosticsDetail = { onOpenDiagnosticsDetail(true) },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ModuleCard(
                    name = stringResource(R.string.dashboard_kmod),
                    state = s.kmod,
                    nativeResult = nativeResult,
                    selfNeedsRestart = selfNeedsRestart,
                    onOpenDiagnosticsDetail = { onOpenDiagnosticsDetail(false) },
                )
            }
        }

        when (val p = s.protection) {
            is ProtectionCheck.NeedsRestart -> {
                Spacer(Modifier.height(12.dp))
                StatusBanner(
                    text = stringResource(R.string.dashboard_needs_restart),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            else -> {}
        }

        s.nativeInstallRecommendation?.let { recommendation ->
            Spacer(Modifier.height(12.dp))
            NativeInstallRecommendationCard(recommendation)
        }
        updateInfo?.let { info ->
            Spacer(Modifier.height(12.dp))
            UpdateAvailableCard(info)
        }

        Spacer(Modifier.height(12.dp))
        InterceptionStatsCard(onOpenDetails = onOpenInterceptStats)

        // Issues
        val (errors, warnings) = s.issues.partition { it.severity == IssueSeverity.ERROR }

        if (errors.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.dashboard_issues, errors.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (issue in errors) {
                StatusBanner(
                    text = issue.text,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.dashboard_warnings, warnings.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (issue in warnings) {
                StatusBanner(
                    text = issue.text,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── UI Components ────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ── Protection Level Card ─────────────────────────────────────────────────

@Composable
private fun ProtectionLevelCard(
    activeLevel: ProtectionLevel?,
    isLoaded: Boolean,
    onLevelSelected: (ProtectionLevel) -> Unit,
) {
    var showFaq by remember { mutableStateOf(false) }

    if (showFaq) {
        AlertDialog(
            onDismissRequest = { showFaq = false },
            title = { Text(stringResource(R.string.protection_faq_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                tint = TelBlue,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                stringResource(R.string.protection_faq_min_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TelBlue,
                            )
                        }
                        Text(
                            stringResource(R.string.protection_faq_min_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = TelGreen,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                stringResource(R.string.protection_faq_avg_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TelGreen,
                            )
                        }
                        Text(
                            stringResource(R.string.protection_faq_avg_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_protection_max),
                                contentDescription = null,
                                tint = TelOrange,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                stringResource(R.string.protection_faq_max_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TelOrange,
                            )
                        }
                        Text(
                            stringResource(R.string.protection_faq_max_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFaq = false }) {
                    Text(stringResource(R.string.protection_faq_close))
                }
            },
        )
    }

    data class LevelDef(
        val level: ProtectionLevel,
        val color: Color,
        val labelRes: Int,
    )

    val levels =
        listOf(
            LevelDef(ProtectionLevel.MIN, TelBlue, R.string.protection_level_min),
            LevelDef(ProtectionLevel.AVG, TelGreen, R.string.protection_level_avg),
            LevelDef(ProtectionLevel.MAX, TelOrange, R.string.protection_level_max),
        )
    val levelIcons =
        listOf(
            Icons.Filled.VisibilityOff,
            Icons.Outlined.Visibility,
            ImageVector.vectorResource(R.drawable.ic_protection_max),
        )

    val selectedIndex =
        when (activeLevel) {
            ProtectionLevel.MIN -> 0
            ProtectionLevel.AVG -> 1
            ProtectionLevel.MAX -> 2
            null -> -1
        }

    val darkTheme = isSystemInDarkTheme()

    val indicatorColor by animateColorAsState(
        targetValue =
            when (activeLevel) {
                ProtectionLevel.MIN -> TelBlue
                ProtectionLevel.AVG -> TelGreen
                ProtectionLevel.MAX -> TelOrange
                null -> Color.Transparent
            },
        label = "protectionLevelColor",
    )

    val cardContainerColor by animateColorAsState(
        targetValue =
            when (activeLevel) {
                ProtectionLevel.MIN -> if (darkTheme) Color(0xFF0D1D30) else Color(0xFFDCEEFA)
                ProtectionLevel.AVG -> if (darkTheme) Color(0xFF0E2016) else Color(0xFFDCF0E3)
                ProtectionLevel.MAX -> if (darkTheme) Color(0xFF221800) else Color(0xFFFFF3DC)
                null -> MaterialTheme.colorScheme.surface
            },
        label = "protectionCardBg",
    )

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.protection_level_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLoaded && activeLevel == null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = stringResource(R.string.protection_level_custom),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { showFaq = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val tabWidth = maxWidth / 3

                    val indicatorOffset by animateDpAsState(
                        targetValue = if (selectedIndex >= 0) tabWidth * selectedIndex else -tabWidth,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                        label = "protectionLevelIndicator",
                    )

                    Box(
                        modifier =
                            Modifier
                                .offset(x = indicatorOffset)
                                .width(tabWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(10.dp))
                                .background(indicatorColor.copy(alpha = 0.18f)),
                    )

                    Row(modifier = Modifier.fillMaxSize()) {
                        levels.forEachIndexed { idx, def ->
                            val isActive = activeLevel == def.level
                            val contentColor =
                                if (isActive) {
                                    def.color
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                }
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { onLevelSelected(def.level) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = levelIcons[idx],
                                        contentDescription = null,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stringResource(def.labelRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = contentColor,
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

// ── Update & Changelog ──────────────────────────────────────────────────

@Composable
private fun UpdateAvailableCard(info: UpdateInfo) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (darkTheme) {
                        Color(0xFF0D47A1).copy(alpha = 0.28f)
                    } else {
                        Color(0xFFE3F2FD)
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text =
                        stringResource(
                            R.string.update_available_subtitle,
                            info.latestVersion,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)),
                    )
                },
            ) { Text(stringResource(R.string.update_download)) }
        }
    }
}

@Composable
private fun ChangelogDialog(
    data: ChangelogData,
    onDismiss: () -> Unit,
) {
    val entries = remember(data) { data.history }
    if (entries.isEmpty()) {
        onDismiss()
        return
    }
    var index by remember { mutableIntStateOf(0) }
    val entry = entries[index]
    val locale =
        LocalContext.current.resources.configuration.locales[0]
            .language
    val sectionLabels =
        mapOf(
            "added" to stringResource(R.string.changelog_section_added),
            "changed" to stringResource(R.string.changelog_section_changed),
            "fixed" to stringResource(R.string.changelog_section_fixed),
            "notes" to stringResource(R.string.changelog_section_notes),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entries.size > 1) {
                    IconButton(
                        onClick = { index-- },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.changelog_title, entry.version),
                    modifier = Modifier.weight(1f),
                )
                if (entries.size > 1) {
                    IconButton(
                        onClick = { index++ },
                        enabled = index < entries.size - 1,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (section in entry.sections) {
                    if (section.items.isEmpty()) continue
                    Text(
                        text = sectionLabels[section.type] ?: section.type,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    for (item in section.items) {
                        val text = if (locale == "ru") item.ru else item.en
                        Text(
                            text = "\u2022 $text",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
