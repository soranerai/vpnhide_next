package dev.soranerai.vpnhidenext

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.domain.models.BackendDiagnostics
import dev.soranerai.vpnhidenext.domain.models.BackendKind
import dev.soranerai.vpnhidenext.domain.models.ComponentDiagnostic
import dev.soranerai.vpnhidenext.domain.models.DashboardState
import dev.soranerai.vpnhidenext.domain.models.DiagnosticStatus
import dev.soranerai.vpnhidenext.domain.models.ModuleState
import dev.soranerai.vpnhidenext.domain.usecase.buildNativeInstallRecommendation
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun BackendGateScreen(
    state: DashboardState,
    appUpdate: UpdateInfo?,
    updateCheckComplete: Boolean,
    scope: CoroutineScope,
    context: android.content.Context,
    onRefresh: () -> Unit,
) {
    val kmodState by KmodUpdateCache.state.collectAsState()
    val builtInState by BuiltInUpdateCache.state.collectAsState()
    var confirmBuiltIn by remember { mutableStateOf<BuiltInUpdateInfo?>(null) }
    val recommendation =
        remember(state) {
            state.nativeInstallRecommendation
                ?: state.kernelVersion?.let { buildNativeInstallRecommendation(it, "") }
        }
    val installedNative = state.kmod as? ModuleState.Installed
    val compatibility =
        remember(state) {
            installedNative?.let { installed ->
                val builtInMode = !installed.isKmodType
                CompatibilityResolver.resolve(
                    InstalledComponentVersions(
                        lsposed = BuildConfig.VERSION_NAME,
                        bridge = installed.bridgeVersion.takeIf { builtInMode },
                        builtIn = state.kmodLoadStatus?.runtimeVersion.takeIf { builtInMode },
                        kmod = installed.version.takeIf { !builtInMode },
                    ),
                )
            }
        }
    val requiredComponent = (compatibility as? CompatibilityResult.Requires)?.component
    val allowKmodRepair =
        state.diagnostics.backend.status != DiagnosticStatus.AVAILABLE || requiredComponent == "kmod"
    val nativeUpdatesAllowed = updateCheckComplete && appUpdate == null
    val kmodTarget =
        remember(state, recommendation, allowKmodRepair, nativeUpdatesAllowed) {
            if (!allowKmodRepair || !nativeUpdatesAllowed) {
                null
            } else if (installedNative?.isKmodType == true) {
                resolveKmodUpdateTarget(
                    installedVersion = installedNative.version,
                    installedKmi = installedNative.gkiVariant ?: state.kmodLoadStatus?.gkiVariant,
                    unameR = state.kmodLoadStatus?.unameR,
                )
            } else {
                resolveKmodInstallTarget(recommendation?.recommendedGkiVariant)
            }
        }
    val bridgeOnlyRepair =
        state.diagnostics.backendKind == BackendKind.BUILT_IN &&
            (state.diagnostics.bridge.status != DiagnosticStatus.AVAILABLE || requiredComponent == "bridge")
    val builtInTarget =
        remember(state, recommendation, nativeUpdatesAllowed) {
            if (nativeUpdatesAllowed &&
                installedNative?.isKmodType != true &&
                (
                    state.diagnostics.backend.status != DiagnosticStatus.AVAILABLE ||
                        bridgeOnlyRepair || requiredComponent == "built-in"
                )
            ) {
                resolveBuiltInInstallTarget(
                    state.kernelVersion ?: recommendation?.kernelVersion,
                    installedVersion = installedNative?.version ?: "0.0.0",
                    installedBridgeVersion = installedNative?.bridgeVersion,
                    forceBridgeRepair = bridgeOnlyRepair,
                )
            } else {
                null
            }
        }

    LaunchedEffect(kmodTarget) { kmodTarget?.let { KmodUpdateCache.ensureFresh(scope, it) } }
    LaunchedEffect(builtInTarget) { builtInTarget?.let { BuiltInUpdateCache.ensureFresh(it) } }

    val ready = state.diagnostics.isReady()
    val busy = kmodState.isBusy() || builtInState.isBusy() || !updateCheckComplete
    val rotation by rememberInfiniteTransition(label = "gate_rotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "gate_rotation",
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier.size(76.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!ready && busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 3.dp,
                        )
                    }
                    Icon(
                        imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).then(if (busy) Modifier.rotate(rotation) else Modifier),
                        tint = if (ready) Color(0xFF35B56A) else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(if (ready) R.string.gate_ready_title else R.string.gate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(if (ready) R.string.gate_ready_desc else R.string.gate_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))

                Column(Modifier.fillMaxWidth()) {
                    GateRow(0, R.string.diagnostics_root, state.diagnostics.root)
                    GateRow(1, R.string.diagnostics_backend, state.diagnostics.backend, state.diagnostics.backendKind)
                    GateRow(2, R.string.diagnostics_bridge, state.diagnostics.bridge)
                    GateRow(3, R.string.diagnostics_lsposed, state.diagnostics.lsposed)
                }

                if (!ready && !busy) {
                    Column(
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                    ) {
                        Spacer(Modifier.height(18.dp))
                        appUpdate?.let { info ->
                            GateActionCard(
                                title = stringResource(R.string.update_available_title),
                                subtitle = stringResource(R.string.update_available_subtitle, info.latestVersion),
                                icon = Icons.Default.SystemUpdate,
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                                },
                            )
                        }
                        val hasRepairChoice = (allowKmodRepair && kmodTarget != null) || builtInTarget != null
                        if (hasRepairChoice) {
                            Text(
                                text = stringResource(R.string.gate_fix_hint),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (allowKmodRepair && nativeUpdatesAllowed) {
                            when (val s = kmodState) {
                                is KmodUpdateState.Available -> {
                                    GateActionCard(
                                        title = stringResource(R.string.gate_kmod_option),
                                        subtitle = s.info.kmi,
                                        icon = Icons.Default.SystemUpdate,
                                        onClick = { KmodUpdateCache.install(scope, context, s.info) },
                                    )
                                }

                                is KmodUpdateState.AwaitingReboot -> {
                                    RebootCard { KmodUpdateCache.reboot(scope) }
                                }

                                is KmodUpdateState.Failed -> {
                                    RetryCard { kmodTarget?.let { KmodUpdateCache.refresh(scope, it) } }
                                }

                                else -> {
                                    Unit
                                }
                            }
                        }
                        if (builtInTarget != null) {
                            when (val s = builtInState) {
                                is BuiltInUpdateState.Available -> {
                                    GateActionCard(
                                        title =
                                            stringResource(
                                                if (bridgeOnlyRepair) R.string.gate_bridge_option else R.string.gate_builtin_option,
                                            ),
                                        subtitle =
                                            stringResource(
                                                if (bridgeOnlyRepair) R.string.gate_bridge_subtitle else R.string.gate_builtin_subtitle,
                                            ),
                                        icon = Icons.Default.SystemUpdate,
                                        onClick = { BuiltInUpdateCache.download(context, s.info) },
                                    )
                                }

                                is BuiltInUpdateState.ReadyToConfirm -> {
                                    GateActionCard(
                                        title = stringResource(R.string.gate_builtin_confirm),
                                        subtitle = s.info.metadata.kernelVersion,
                                        icon = Icons.Default.Build,
                                        onClick = { confirmBuiltIn = s.info },
                                    )
                                }

                                is BuiltInUpdateState.AwaitingReboot -> {
                                    RebootCard { BuiltInUpdateCache.reboot() }
                                }

                                is BuiltInUpdateState.Failed -> {
                                    RetryCard { builtInTarget?.let { BuiltInUpdateCache.refresh(it) } }
                                }

                                else -> {
                                    Unit
                                }
                            }
                        }
                        if ((!allowKmodRepair || kmodTarget == null || kmodState == KmodUpdateState.None) &&
                            (builtInTarget == null || builtInState == BuiltInUpdateState.None)
                        ) {
                            OutlinedButton(onClick = onRefresh) {
                                Text(stringResource(R.string.gate_retry))
                            }
                        }
                    }
                }
            }
        }
    }

    confirmBuiltIn?.let { info ->
        AlertDialog(
            onDismissRequest = { confirmBuiltIn = null },
            title = { Text(stringResource(R.string.gate_builtin_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.builtin_update_confirm_message, info.metadata.bridgeVersion, info.metadata.kernelVersion),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBuiltIn = null
                    BuiltInUpdateCache.install(info, KernelImageMode.NORMAL)
                }) {
                    Text(stringResource(R.string.builtin_update_confirm_action))
                }
            },
            dismissButton = { TextButton(onClick = { confirmBuiltIn = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GateRow(
    index: Int,
    labelRes: Int,
    diagnostic: ComponentDiagnostic,
    kind: BackendKind? = null,
) {
    val available = diagnostic.status == DiagnosticStatus.AVAILABLE
    val color by animateColorAsState(
        if (available) Color(0xFF35B56A) else MaterialTheme.colorScheme.error,
        label = "gate_status_color",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(color.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                if (available) {
                    Icon(Icons.Default.CheckCircle, null, tint = color, modifier = Modifier.size(19.dp))
                } else {
                    Icon(Icons.Default.ErrorOutline, null, tint = color, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(labelRes), fontWeight = FontWeight.Medium)
                Text(
                    text =
                        when (kind) {
                            BackendKind.KMOD -> stringResource(R.string.diagnostics_backend_kmod)
                            BackendKind.BUILT_IN -> stringResource(R.string.diagnostics_backend_builtin)
                            else -> stringResource(gateStatusString(diagnostic.status))
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
            Text("$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GateActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RebootCard(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(Icons.Default.RestartAlt, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.gate_reboot))
    }
}

@Composable
private fun RetryCard(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(R.string.gate_retry_download))
    }
}

internal fun BackendDiagnostics.isReady(): Boolean =
    root.status == DiagnosticStatus.AVAILABLE &&
        backend.status == DiagnosticStatus.AVAILABLE &&
        bridge.status == DiagnosticStatus.AVAILABLE &&
        lsposed.status == DiagnosticStatus.AVAILABLE

private fun KmodUpdateState.isBusy(): Boolean = this is KmodUpdateState.Downloading || this is KmodUpdateState.Installing

private fun BuiltInUpdateState.isBusy(): Boolean =
    this is BuiltInUpdateState.Downloading || this is BuiltInUpdateState.Validating ||
        this is BuiltInUpdateState.PreparingInstall || this is BuiltInUpdateState.BackingUp ||
        this is BuiltInUpdateState.InstallingBridge || this is BuiltInUpdateState.FlashingKernel

private fun gateStatusString(status: DiagnosticStatus): Int =
    when (status) {
        DiagnosticStatus.AVAILABLE -> R.string.diagnostics_status_available
        DiagnosticStatus.MISSING -> R.string.diagnostics_status_missing
        DiagnosticStatus.INACTIVE -> R.string.diagnostics_status_inactive
        DiagnosticStatus.BROKEN -> R.string.diagnostics_status_broken
        DiagnosticStatus.BLOCKED -> R.string.diagnostics_status_blocked
        DiagnosticStatus.UNKNOWN -> R.string.diagnostics_status_unknown
    }
