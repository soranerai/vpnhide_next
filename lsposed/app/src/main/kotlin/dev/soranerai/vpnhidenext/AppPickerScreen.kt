package dev.soranerai.vpnhidenext

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
import dev.soranerai.vpnhidenext.ui.theme.*
import io.github.oikvpqya.compose.fastscroller.VerticalScrollbar
import io.github.oikvpqya.compose.fastscroller.indicator.IndicatorConstants
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.rememberScrollbarAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppPickerScreen(
    apps: List<AppEntry>,
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    showOnlySelected: Boolean,
    sortOrder: AppSortOrder,
    onUpdate: (List<AppEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appList by AppListCache.apps.collectAsState()
    val targets by TargetsCache.snapshot.collectAsState()
    val loading = targets == null || appList == null || (apps.isEmpty() && appList?.isNotEmpty() == true)
    val scope = rememberCoroutineScope()

    // Pull to Refresh state
    var refreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Stable ID list for the display to prevent shuffling on toggle
    var sortedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val currentPackageNames = remember(apps) { apps.map { it.packageName }.toSet() }
    
    // Update the sort order only on filters/search/refresh OR initial load
    LaunchedEffect(currentPackageNames.isEmpty(), searchQuery, showSystem, showRussianOnly, showOnlySelected, sortOrder, refreshTrigger) {
        if (apps.isEmpty()) return@LaunchedEffect
        
        val q = searchQuery.trim().lowercase()
        sortedIds = apps
            .filter { app ->
                (showSystem || !app.isSystem || app.kmod || app.zygisk || app.lsposed) &&
                    (!showRussianOnly || isRussianApp(app.packageName, app.label)) &&
                    (!showOnlySelected || app.anyProtection) &&
                    (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
            }.let { list ->
                when (sortOrder) {
                    AppSortOrder.NAME_ASC -> list.sortedBy { it.label.lowercase() }
                    AppSortOrder.NAME_DESC -> list.sortedByDescending { it.label.lowercase() }
                    AppSortOrder.SELECTED_FIRST -> list.sortedWith(
                        compareByDescending<AppEntry> { it.anyProtection }.thenBy { it.label.lowercase() }
                    )
                }
            }.map { it.packageName }
    }

    // Map current data to the stable order
    val displayApps = remember(apps, sortedIds) {
        sortedIds.mapNotNull { pkg -> apps.find { it.packageName == pkg } }
    }

    val installed =
        remember(targets) {
            InstalledModules(
                kmod = targets?.kmodModuleInstalled == true,
                kmodActive = targets?.kmodActive == true,
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
            
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        refreshTrigger++
                        delay(500)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(displayApps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                userNames = emptyMap(),
                                installed = installed,
                                onToggle = { layer ->
                                    val newList =
                                        apps.map {
                                            if (it.packageName != app.packageName) {
                                                it
                                            } else {
                                                when (layer) {
                                                    Layer.KMOD -> it.copy(kmod = !it.kmod)
                                                    Layer.ZYGISK -> it.copy(zygisk = !it.zygisk)
                                                    Layer.LSPOSED -> it.copy(lsposed = !it.lsposed)
                                                }
                                            }
                                        }
                                    onUpdate(newList)
                                },
                                onToggleAll = {
                                    val newState = !(app.kmod || app.zygisk || app.lsposed)
                                    val newList =
                                        apps.map {
                                            if (it.packageName != app.packageName) {
                                                it
                                            } else {
                                                it.copy(
                                                    kmod = if (installed.kmod) newState else false,
                                                    zygisk = if (installed.zygisk) newState else false,
                                                    lsposed = newState,
                                                )
                                            }
                                        }
                                    onUpdate(newList)
                                },
                            )
                        }
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    val isDragged by interactionSource.collectIsDraggedAsState()
                    val alpha by animateFloatAsState(if (isDragged) 1f else 0f, label = "scrollbar_alpha")

                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .fillMaxHeight()
                            .graphicsLayer { this.alpha = alpha },
                        adapter = rememberScrollbarAdapter(listState),
                        style = defaultMaterialScrollbarStyle(),
                        indicator = { index, _ ->
                            if (isDragged) {
                                val itemIndex = index.toInt()
                                val label = displayApps.getOrNull(itemIndex)?.label?.take(1)?.uppercase() ?: ""
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    tonalElevation = 8.dp
                                ) {
                                    Box(
                                        modifier = Modifier.size(48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
