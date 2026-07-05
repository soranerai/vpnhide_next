package dev.soranerai.vpnhidenext

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.soranerai.vpnhidenext.ui.theme.TelGreen
import dev.soranerai.vpnhidenext.ui.theme.TelRed

/**
 * Full breakdown of native/Java diagnostics checks, extracted out of the
 * main Diagnostics tab (which now only shows a compact pass/fail summary)
 * so it's reachable via Settings → Testing → "Diagnostics details" instead
 * of always taking up space on the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDetailScreen(
    selfNeedsRestart: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val diagState by DiagnosticsCache.state.collectAsState()
    val context = LocalContext.current

    val stateVal = diagState
    val results =
        when (stateVal) {
            is DiagnosticsCache.State.Ready -> stateVal.results
            is DiagnosticsCache.State.Running -> stateVal.results
            else -> null
        }
    val networkBlocked = results?.native?.any { it.passed == null && !it.isRunning } == true
    val hasFailed = results?.all?.any { !it.isSkipped && it.passed == false } == true
    val isChecking = remember(results) { results?.all?.any { it.isRunning } == true }
    val nativeByTier = groupNativeChecksByTier(context, results?.native.orEmpty())

    val listState = rememberLazyListState()
    // Jump straight to the first failed check whenever the results settle
    // with at least one failure — this screen is most often opened *because*
    // something is wrong (either from here directly or via a Dashboard card
    // tap), so scrolling past a wall of passing checks to find it is wasted
    // motion. Ready is a terminal state (DiagnosticsCache never re-runs), so
    // this fires exactly once per screen visit.
    LaunchedEffect(diagState) {
        if (diagState is DiagnosticsCache.State.Ready && hasFailed) {
            val index = firstFailedItemIndex(networkBlocked, nativeByTier, results?.java.orEmpty())
            if (index != null) listState.animateScrollToItem(index)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_diagnostics_detail_title)) },
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
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }

            if (selfNeedsRestart) {
                item(key = "banner_restart") {
                    StatusBanner(
                        text = stringResource(R.string.banner_added_self),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            } else if (diagState is DiagnosticsCache.State.NotRun) {
                item(key = "progress_indicator") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (diagState is DiagnosticsCache.State.VpnOff) {
                item(key = "vpn_off_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = stringResource(R.string.diag_no_vpn_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.diag_no_vpn_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (diagState is DiagnosticsCache.State.Running || diagState is DiagnosticsCache.State.Ready) {
                if (networkBlocked) {
                    item(key = "banner_network_blocked") {
                        StatusBanner(
                            text = stringResource(R.string.banner_network_blocked),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                results?.let { r ->
                    if (isChecking) {
                        item(key = "diag_running_card") {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(R.string.diag_running_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_running_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!hasFailed) {
                        item(key = "diag_all_good_card") {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = TelGreen.copy(alpha = 0.15f)),
                                border = BorderStroke(1.dp, TelGreen.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = TelGreen,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(R.string.diag_all_good_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TelGreen,
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_all_good_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "diag_some_failed_card") {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = TelRed,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(R.string.diag_some_failed_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TelRed,
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_some_failed_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "spacer_before_lists") { Spacer(Modifier.height(4.dp)) }

                    // Always show the full check-by-check breakdown — this
                    // screen's whole purpose is "show me everything", so no
                    // expand/collapse gate here (unlike the compact summary
                    // card on the main Diagnostics tab). Native checks are
                    // further split by depth tier so the sheer count of
                    // low-level kmod checks doesn't read as one undifferentiated
                    // wall of rows.
                    item(key = "header_native") {
                        SectionHeader(
                            title = stringResource(R.string.section_native),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    for (tier in NativeCheckTier.entries) {
                        val tierChecks = nativeByTier[tier].orEmpty()
                        if (tierChecks.isEmpty()) continue
                        item(key = "tier_header_${tier.name}") {
                            TierHeader(title = stringResource(nativeTierTitleRes(tier)))
                        }
                        checksListCard(tierChecks)
                    }

                    item(key = "spacer_between_sections") { Spacer(Modifier.height(20.dp)) }

                    item(key = "header_framework") {
                        SectionHeader(
                            title = stringResource(R.string.section_framework),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    checksListCard(r.java)
                }
            }

            item(key = "spacer_bottom") {
                val bottomNavPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 24.dp))
            }
        }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Flat LazyColumn index of the first failed check, or null if there isn't
 * one. Mirrors the item order emitted in the `results?.let { r -> ... }`
 * branch above — keep the two in sync if that layout changes.
 */
private fun firstFailedItemIndex(
    networkBlocked: Boolean,
    nativeByTier: Map<NativeCheckTier, List<CheckResult>>,
    java: List<CheckResult>,
): Int? {
    var index = 1 // spacer_top
    if (networkBlocked) index++ // banner_network_blocked
    index++ // status card (diag_running/all_good/some_failed)
    index++ // spacer_before_lists
    index++ // header_native
    for (tier in NativeCheckTier.entries) {
        val tierChecks = nativeByTier[tier].orEmpty()
        if (tierChecks.isEmpty()) continue
        index++ // tier_header
        for (check in tierChecks) {
            if (!check.isSkipped && check.passed == false) return index
            index++
        }
    }
    index++ // spacer_between_sections
    index++ // header_framework
    for (check in java) {
        if (!check.isSkipped && check.passed == false) return index
        index++
    }
    return null
}

private fun nativeTierTitleRes(tier: NativeCheckTier): Int =
    when (tier) {
        NativeCheckTier.CLASSIC -> R.string.native_tier_classic
        NativeCheckTier.ADVANCED -> R.string.native_tier_advanced
        NativeCheckTier.EXTREME -> R.string.native_tier_extreme
    }

@Composable
private fun TierHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp),
    )
}

internal fun LazyListScope.checksListCard(checks: List<CheckResult>) {
    itemsIndexed(checks, key = { _, c -> c.name }) { index, check ->
        val isFirst = index == 0
        val isLast = index == checks.lastIndex

        val shape =
            when {
                isFirst && isLast -> RoundedCornerShape(16.dp)
                isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                else -> RectangleShape
            }

        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                CheckRow(check)
                if (!isLast) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckRow(r: CheckResult) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: (r.passed == false)

    val statusIcon =
        when {
            r.isRunning -> null
            r.isSkipped -> Icons.Default.RemoveCircle
            r.passed == true -> Icons.Default.CheckCircle
            r.passed == false -> Icons.Default.Cancel
            else -> Icons.Default.Info
        }
    val statusColor =
        when {
            r.isRunning -> MaterialTheme.colorScheme.primary
            r.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            r.passed == true -> TelGreen
            r.passed == false -> TelRed
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    val badgeText =
        when {
            r.isRunning -> {
                stringResource(R.string.badge_running)
            }

            r.isSkipped -> {
                stringResource(R.string.badge_skipped)
            }

            else -> {
                stringResource(
                    when (r.passed) {
                        true -> R.string.badge_pass
                        false -> R.string.badge_fail
                        else -> R.string.badge_info
                    },
                )
            }
        }

    val rowBgColor =
        if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val toastMsg = stringResource(R.string.toast_copied_to_clipboard)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(rowBgColor)
                .combinedClickable(
                    enabled = !r.isRunning,
                    onClick = {
                        if (r.detail.isNotBlank()) {
                            userExpanded = !expanded
                        }
                    },
                    onLongClick = {
                        val textToCopy =
                            if (r.detail.isNotBlank()) {
                                "${r.name}: $badgeText\n${r.detail}"
                            } else {
                                "${r.name}: $badgeText"
                            }
                        clipboardManager.setText(AnnotatedString(textToCopy))
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    },
                ).padding(vertical = 10.dp, horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (r.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                statusIcon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = r.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = badgeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = statusColor,
                )
                if (!r.isRunning && r.detail.isNotBlank()) {
                    Icon(
                        imageVector =
                            if (expanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (expanded && r.detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            val detailBgColor =
                when {
                    r.isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    r.passed == true -> TelGreen.copy(alpha = 0.08f)
                    r.passed == false -> TelRed.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            val detailTextColor =
                when {
                    r.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    r.passed == true -> TelGreen
                    r.passed == false -> TelRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
            Surface(
                color = detailBgColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = r.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = detailTextColor.copy(alpha = 0.9f),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
