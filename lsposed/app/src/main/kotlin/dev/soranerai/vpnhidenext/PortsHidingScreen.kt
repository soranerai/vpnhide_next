package dev.soranerai.vpnhidenext

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortsHidingScreen(
    apps: List<AppEntry>,
    @Suppress("UNUSED_PARAMETER") searchQuery: String,
    @Suppress("UNUSED_PARAMETER") showSystem: Boolean,
    @Suppress("UNUSED_PARAMETER") showRussianOnly: Boolean,
    @Suppress("UNUSED_PARAMETER") showOnlySelected: Boolean,
    @Suppress("UNUSED_PARAMETER") showOnlyWorkProfile: Boolean,
    @Suppress("UNUSED_PARAMETER") sortOrder: AppSortOrder,
    onUpdate: (List<AppEntry>) -> Unit,
    onConfigClick: (AppEntry) -> Unit,
    sortedIds: List<String>,
    onRefresh: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val targets by TargetsCache.snapshot.collectAsState()
    val loading = targets == null
    val scope = rememberCoroutineScope()

    // Pull to Refresh state
    var refreshing by remember { mutableStateOf(false) }

    // Map current data to the stable order.
    // Key on BOTH apps and sortedIds:
    //   - sortedIds changes  → order changes (filter/search/save)
    //   - apps changes       → content changes (toggle), order is preserved
    // NOTE: derivedStateOf cannot be used here because 'apps' is a plain parameter,
    // not a State object, so derivedStateOf's lambda wouldn't react to it.
    val displayApps =
        remember(apps, sortedIds) {
            sortedIds.mapNotNull { id ->
                val parts = id.split(":")
                val pkg = parts[0]
                val uid = parts[1].toIntOrNull() ?: 0
                apps.find { it.packageName == pkg && it.userId == uid }
            }
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (loading) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(10) { SkeletonAppRow() }
            }
        } else {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        onRefresh()
                        delay(500)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomNavPadding + 100.dp),
                    ) {
                        items(displayApps, key = { "${it.packageName}:${it.userId}" }) { app ->
                            PortAppRow(
                                app = app,
                                onToggle = {
                                    val newList =
                                        apps.map {
                                            if (it.packageName != app.packageName ||
                                                it.userId != app.userId
                                            ) {
                                                it
                                            } else {
                                                it.copy(portHiding = !it.portHiding)
                                            }
                                        }
                                    onUpdate(newList)
                                },
                                onConfigClick = { onConfigClick(app) },
                            )
                        }
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    val isDragged by interactionSource.collectIsDraggedAsState()
                    val alpha by animateFloatAsState(if (isDragged) 1f else 0f, label = "scrollbar_alpha")

                    VerticalScrollbar(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp)
                                .fillMaxHeight()
                                .graphicsLayer { this.alpha = alpha },
                        adapter = rememberScrollbarAdapter(listState),
                        style = defaultMaterialScrollbarStyle(),
                        indicator = { index, _ ->
                            if (isDragged) {
                                val itemIndex = index.toInt()
                                val label =
                                    displayApps
                                        .getOrNull(itemIndex)
                                        ?.label
                                        ?.take(1)
                                        ?.uppercase() ?: ""
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    tonalElevation = 8.dp,
                                ) {
                                    Box(
                                        modifier = Modifier.size(48.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(label, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
