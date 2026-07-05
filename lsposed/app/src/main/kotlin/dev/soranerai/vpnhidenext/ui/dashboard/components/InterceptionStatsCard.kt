package dev.soranerai.vpnhidenext.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.InterceptStatsCache
import dev.soranerai.vpnhidenext.R
import dev.soranerai.vpnhidenext.domain.models.InterceptStatsSummary
import dev.soranerai.vpnhidenext.domain.models.summarize
import dev.soranerai.vpnhidenext.ui.theme.TelGreen
import kotlin.math.roundToInt

private data class GreenSlice(
    val label: String,
    val count: Int,
    val color: Color,
)

@Composable
fun InterceptionStatsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by InterceptStatsCache.stats.collectAsState()

    LaunchedEffect(Unit) { InterceptStatsCache.ensureLoaded(scope, context) }

    val darkTheme = isSystemInDarkTheme()
    val containerColor = if (darkTheme) Color(0xFF1E3E28) else Color(0xFFE8F5E9)
    val contentColor = if (darkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)

    AnimatedVisibility(
        visible = stats != null,
        enter = fadeIn() + scaleIn(initialScale = 0.94f),
        exit = fadeOut() + scaleOut(targetScale = 0.94f),
    ) {
        val summary = remember(stats) { stats?.summarize() }
        if (summary != null) {
            InterceptionStatsCardContent(
                summary = summary,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }
    }
}

@Composable
private fun InterceptionStatsCardContent(
    summary: InterceptStatsSummary,
    containerColor: Color,
    contentColor: Color,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                text = stringResource(R.string.dashboard_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${summary.totalIntercepts}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Text(
                text = stringResource(R.string.dashboard_summary_total_label),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.8f),
            )

            if (summary.totalIntercepts == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_summary_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
                return@Column
            }

            Spacer(Modifier.height(16.dp))

            val slices =
                listOf(
                    GreenSlice(stringResource(R.string.dashboard_summary_native), summary.nativeTotal, TelGreen),
                    GreenSlice(
                        stringResource(R.string.dashboard_summary_lsposed),
                        summary.lsposedTotal,
                        TelGreen.copy(alpha = 0.65f),
                    ),
                    GreenSlice(
                        stringResource(R.string.dashboard_summary_ports),
                        summary.portsTotal,
                        TelGreen.copy(alpha = 0.35f),
                    ),
                ).filter { it.count > 0 }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    slices = slices,
                    total = summary.totalIntercepts,
                    trackColor = contentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(96.dp),
                )

                Spacer(Modifier.width(20.dp))

                Column(modifier = Modifier.weight(1f)) {
                    for (slice in slices) {
                        val pct = (slice.count * 100f / summary.totalIntercepts).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .background(slice.color, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = slice.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${slice.count} ($pct%)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                            )
                        }
                    }
                }
            }

            if (summary.topFrameworkHooks.isNotEmpty() || summary.topNativeHooks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = contentColor.copy(alpha = 0.15f))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.dashboard_summary_top_hooks),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Framework Breakdown Column (Left)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dashboard_summary_lsposed),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(4.dp))
                        if (summary.topFrameworkHooks.isEmpty()) {
                            Text(
                                text = "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.5f),
                            )
                        } else {
                            for (hook in summary.topFrameworkHooks) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = hook.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.85f),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${hook.count}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }

                    // Native Breakdown Column (Right)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dashboard_summary_native),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(4.dp))
                        if (summary.topNativeHooks.isEmpty()) {
                            Text(
                                text = "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.5f),
                            )
                        } else {
                            for (hook in summary.topNativeHooks) {
                                val vectorLabel =
                                    when (hook.name) {
                                        "ioctl" -> stringResource(R.string.vector_label_ioctl)
                                        "netlink" -> stringResource(R.string.vector_label_netlink)
                                        "proc" -> stringResource(R.string.vector_label_proc)
                                        "sockopt" -> stringResource(R.string.vector_label_sockopt)
                                        "connect" -> stringResource(R.string.vector_label_connect)
                                        "getname" -> stringResource(R.string.vector_label_getname)
                                        else -> hook.name
                                    }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = vectorLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.85f),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${hook.count}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
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

@Composable
private fun DonutChart(
    slices: List<GreenSlice>,
    total: Int,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val animatedSweeps = remember(slices, total) { slices.map { Animatable(0f) } }

    LaunchedEffect(slices, total) {
        slices.forEachIndexed { i, slice ->
            val targetSweep = 360f * slice.count / total
            animatedSweeps[i].animateTo(
                targetSweep,
                animationSpec = tween(durationMillis = 700, delayMillis = i * 120),
            )
        }
    }

    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            topLeft =
                androidx.compose.ui.geometry
                    .Offset(strokeWidth / 2, strokeWidth / 2),
        )

        var startAngle = -90f
        slices.forEachIndexed { i, slice ->
            val sweep = animatedSweeps[i].value
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                topLeft =
                    androidx.compose.ui.geometry
                        .Offset(strokeWidth / 2, strokeWidth / 2),
            )
            startAngle += 360f * slice.count / total
        }
    }
}
