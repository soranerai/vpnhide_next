package dev.okhsunrog.vpnhide

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.ui.theme.*
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.oikvpqya.compose.fastscroller.VerticalScrollbar
import io.github.oikvpqya.compose.fastscroller.indicator.IndicatorConstants
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.rememberScrollbarAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.okhsunrog.vpnhide.ShimmerPlaceholder

@Composable
internal fun AppPickerScreen(
    apps: List<AppEntry>,
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    onUpdate: (List<AppEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets by TargetsCache.snapshot.collectAsState()
    val loading = targets == null

    val filteredApps =
        remember(apps, searchQuery, showSystem, showRussianOnly) {
            val q = searchQuery.trim().lowercase()
            apps.filter { app ->
                (showSystem || !app.isSystem || app.kmod || app.zygisk || app.lsposed) &&
                    (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                    (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
            }
        }

    val installed =
        remember(targets) {
            InstalledModules(
                kmod = targets?.kmodModuleInstalled == true,
                zygisk = targets?.zygiskModuleInstalled == true,
            )
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (loading) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(10) { SkeletonAppRow() }
            }
        } else {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    item {
                        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                            HelpAccordion(
                                prefKey = "apps_vpn",
                                title = stringResource(R.string.apps_help_title),
                            ) {
                                Text(
                                    text = stringResource(R.string.apps_hint_toggles),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.apps_hint_restart_target),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.apps_hint_zygisk),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            userNames = emptyMap(),
                            installed = installed,
                            onToggle = { layer ->
                                val newList = apps.map {
                                    if (it.packageName != app.packageName) it
                                    else when (layer) {
                                        Layer.KMOD -> it.copy(kmod = !it.kmod)
                                        Layer.ZYGISK -> it.copy(zygisk = !it.zygisk)
                                        Layer.LSPOSED -> it.copy(lsposed = !it.lsposed)
                                    }
                                }
                                onUpdate(newList)
                            },
                            onToggleAll = {
                                val newState = !(app.kmod || app.zygisk || app.lsposed)
                                val newList = apps.map {
                                    if (it.packageName != app.packageName) it
                                    else it.copy(
                                        kmod = if (installed.kmod) newState else false,
                                        zygisk = if (installed.zygisk) newState else false,
                                        lsposed = newState,
                                    )
                                }
                                onUpdate(newList)
                            },
                        )
                    }
                }
                
                val interactionSource = remember { MutableInteractionSource() }
                val isDragging by interactionSource.collectIsDraggedAsState()
                val indicatorAlpha by animateFloatAsState(if (isDragging) 1f else 0f, label = "alpha")
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState = listState),
                    interactionSource = interactionSource,
                    style = defaultMaterialScrollbarStyle(),
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                    indicator = { position, isVisible ->
                        val firstChar = filteredApps.getOrNull(listState.firstVisibleItemIndex)?.label?.firstOrNull()?.uppercase() ?: ""
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).graphicsLayer {
                                translationY = position; alpha = indicatorAlpha
                            }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isVisible) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(firstChar, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    userNames: Map<Int, String>,
    installed: InstalledModules,
    onToggle: (Layer) -> Unit,
    onToggleAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleAll)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = labelWithUsers(app.label, app.userIds, userNames),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayerChip("LSPOSED", app.lsposed, true) { onToggle(Layer.LSPOSED) }
                if (installed.kmod) {
                    LayerChip("KMOD", app.kmod, true) { onToggle(Layer.KMOD) }
                }
                if (installed.zygisk) {
                    LayerChip("ZYGISK", app.zygisk, true) { onToggle(Layer.ZYGISK) }
                }
            }
        }
    }
}

@Composable
private fun LayerChip(
    label: String,
    enabled: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.clickable(enabled = available, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SkeletonAppRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerPlaceholder(modifier = Modifier.size(44.dp), shape = CircleShape)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerPlaceholder(modifier = Modifier.width(150.dp).height(20.dp))
            Spacer(Modifier.height(8.dp))
            ShimmerPlaceholder(modifier = Modifier.width(220.dp).height(14.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    ShimmerPlaceholder(modifier = Modifier.width(60.dp).height(24.dp), shape = RoundedCornerShape(12.dp))
                }
            }
        }
    }
}
