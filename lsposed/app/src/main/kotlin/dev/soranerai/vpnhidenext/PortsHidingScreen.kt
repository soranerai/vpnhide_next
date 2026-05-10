package dev.soranerai.vpnhidenext

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
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.soranerai.vpnhidenext.ShimmerPlaceholder
import io.github.oikvpqya.compose.fastscroller.VerticalScrollbar
import io.github.oikvpqya.compose.fastscroller.indicator.IndicatorConstants
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.rememberScrollbarAdapter

@Composable
internal fun PortsHidingScreen(
    apps: List<AppEntry>,
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    sortOrder: AppSortOrder,
    onUpdate: (List<AppEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets by TargetsCache.snapshot.collectAsState()
    val loading = targets == null

    val filteredApps =
        remember(apps, searchQuery, showSystem, showRussianOnly, showOnlySelected, sortOrder) {
            val q = searchQuery.trim().lowercase()
            apps
                .filter { app ->
                    (showSystem || !app.isSystem || app.portHiding) &&
                        (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                        (!showOnlySelected || app.portHiding) &&
                        (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
                }.let { list ->
                    when (sortOrder) {
                        AppSortOrder.NAME_ASC -> {
                            list.sortedBy { it.label.lowercase() }
                        }

                        AppSortOrder.NAME_DESC -> {
                            list.sortedByDescending { it.label.lowercase() }
                        }

                        AppSortOrder.SELECTED_FIRST -> {
                            list.sortedWith(
                                compareByDescending<AppEntry> { it.portHiding }.thenBy { it.label.lowercase() },
                            )
                        }
                    }
                }
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (loading) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(10) { SkeletonAppRow() }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(sortOrder) {
                listState.scrollToItem(0)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        PortAppRow(
                            app = app,
                            onToggle = {
                                val newList =
                                    apps.map {
                                        if (it.packageName != app.packageName) it else it.copy(portHiding = !it.portHiding)
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
                        val firstChar =
                            filteredApps
                                .getOrNull(listState.firstVisibleItemIndex)
                                ?.label
                                ?.firstOrNull()
                                ?.uppercase() ?: ""
                        Box(
                            modifier =
                                Modifier.align(Alignment.TopEnd).padding(end = 8.dp).graphicsLayer {
                                    translationY = position
                                    alpha = indicatorAlpha
                                },
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isVisible) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        firstChar,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PortAppRow(
    app: AppEntry,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
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
                text = app.label,
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
        }
        Checkbox(
            checked = app.portHiding,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
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
        }
    }
}
