package dev.soranerai.vpnhidenext

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.ShimmerPlaceholder
import dev.soranerai.vpnhidenext.shimmer
import dev.soranerai.vpnhidenext.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(
    selfNeedsRestart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by DashboardCache.state.collectAsState()
    val updateInfo by UpdateCheckCache.info.collectAsState()
    var showChangelog by remember { mutableStateOf(false) }
    var changelogData by remember { mutableStateOf<ChangelogData?>(null) }

    // Both caches are reactive to tab switches without re-doing work:
    // ensureLoaded / ensureFresh are no-ops if the data is already
    // populated or an inflight job hasn't finished yet.
    LaunchedEffect(Unit) {
        DashboardCache.ensureLoaded(scope, context, selfNeedsRestart)
        UpdateCheckCache.ensureFresh(scope, BuildConfig.VERSION_NAME)
    }
    LaunchedEffect(Unit) {
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

    Column(
        modifier =
            modifier
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
            )
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
            Box(modifier = Modifier.weight(1f)) {
                SkeletonModuleCard()
            }
            Box(modifier = Modifier.weight(1f)) {
                SkeletonModuleCard()
            }
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
) {
    val darkTheme = isSystemInDarkTheme()
    val errorBg = if (darkTheme) Color(0xFFB71C1C).copy(alpha = 0.3f) else Color(0xFFFFEBEE)
    val errorHeader = if (darkTheme) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val warningBg = if (darkTheme) Color(0xFFE65100).copy(alpha = 0.2f) else Color(0xFFFFF3E0)
    val warningHeader = if (darkTheme) Color(0xFFFFB74D) else Color(0xFFE65100)
    val onBannerColor = MaterialTheme.colorScheme.onSurface

    Column {
        // Module status cards
        Text(
            text = stringResource(R.string.dashboard_modules),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        val (javaResult, nativeResult) = when (val p = s.protection) {
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
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ModuleCard(
                    name = stringResource(R.string.dashboard_kmod),
                    state = s.kmod,
                    nativeResult = nativeResult,
                    selfNeedsRestart = selfNeedsRestart,
                )
            }
        }

        when (val p = s.protection) {
            is ProtectionCheck.NoVpn -> {
                Spacer(Modifier.height(12.dp))
                VpnOffPrompt(
                    onRetry = {
                        DashboardCache.refresh(scope, context, selfNeedsRestart)
                        DiagnosticsCache.retry(scope, context)
                    },
                )
            }

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

        // Issues
        val errors = s.issues.filter { it.severity == IssueSeverity.ERROR }
        val warnings = s.issues.filter { it.severity == IssueSeverity.WARNING }

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
private fun ModuleCard(
    name: String,
    state: ModuleState,
    nativeResult: NativeResult?,
    selfNeedsRestart: Boolean = false,
) {
    val darkTheme = isSystemInDarkTheme()
    when (state) {
        is ModuleState.NotInstalled -> {
            val containerColor = MaterialTheme.colorScheme.surfaceVariant
            val contentColor = MaterialTheme.colorScheme.onSurface
            ModuleCardShell(
                name = name,
                version = null,
                subtitle = stringResource(R.string.dashboard_not_installed),
                dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }

        is ModuleState.Installed -> {
            val active = state.active
            val broken = state.brokenReason
            val brokenSubtitleRes =
                when (broken) {
                    KmodBrokenReason.WrongVariant -> R.string.dashboard_kmod_broken_wrong_variant
                    KmodBrokenReason.UnsupportedKernel -> R.string.dashboard_kmod_broken_unsupported_kernel
                    KmodBrokenReason.MissingKprobes -> R.string.dashboard_kmod_broken_no_kprobes
                    KmodBrokenReason.UnknownVariantInactive -> R.string.dashboard_kmod_broken_unknown_variant
                    KmodBrokenReason.AmbiguousLoadFailed -> R.string.dashboard_kmod_broken_ambiguous
                    null -> null
                }

            val targetsText = when {
                brokenSubtitleRes != null -> stringResource(brokenSubtitleRes)
                active -> stringResource(R.string.dashboard_active_targets, state.targetCount)
                selfNeedsRestart -> stringResource(R.string.dashboard_installed_restart_app)
                else -> stringResource(R.string.dashboard_installed_inactive)
            }

            val protectionText = if (active) {
                when (nativeResult) {
                    is NativeResult.Ok -> "\n" + stringResource(R.string.dashboard_protection_prefix, stringResource(R.string.dashboard_protection_ok))
                    is NativeResult.Fail -> {
                        val failText = if (nativeResult.passed > 0) {
                            stringResource(R.string.dashboard_protection_partial)
                        } else {
                            stringResource(R.string.dashboard_protection_fail)
                        }
                        "\n" + stringResource(R.string.dashboard_protection_prefix, failText)
                    }
                    is NativeResult.NoModule -> "\n" + stringResource(R.string.dashboard_protection_prefix, stringResource(R.string.dashboard_protection_no_module))
                    null -> ""
                }
            } else ""

            val subtitle = targetsText + protectionText

            val isFail = broken != null || (active && nativeResult is NativeResult.Fail)
            val isOk = active && nativeResult is NativeResult.Ok

            val (containerColor, contentColor, dotColor) = when {
                isFail -> {
                    if (darkTheme) {
                        Triple(Color(0xFF421C1C), Color(0xFFEF9A9A), TelRed)
                    } else {
                        Triple(Color(0xFFFFEBEE), Color(0xFFC62828), TelRed)
                    }
                }
                isOk -> {
                    if (darkTheme) {
                        Triple(Color(0xFF1E3E28), Color(0xFFA5D6A7), TelGreen)
                    } else {
                        Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), TelGreen)
                    }
                }
                else -> {
                    val dot = when {
                        active -> TelGreen
                        else -> TelOrange
                    }
                    Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, dot)
                }
            }

            ModuleCardShell(
                name = name,
                version = state.version,
                subtitle = subtitle,
                dotColor = dotColor,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }
    }
}

@Composable
private fun LsposedCard(
    state: LsposedState,
    javaResult: JavaResult?,
    selfNeedsRestart: Boolean,
) {
    val darkTheme = isSystemInDarkTheme()
    val moduleName = stringResource(R.string.dashboard_lsposed_module)
    val installedVersion = BuildConfig.VERSION_NAME
    when (state) {
        is LsposedState.NotInstalled -> {
            val containerColor = MaterialTheme.colorScheme.surfaceVariant
            val contentColor = MaterialTheme.colorScheme.onSurface
            ModuleCardShell(
                name = moduleName,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_not_installed),
                dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }

        is LsposedState.InstalledInactive -> {
            val containerColor = MaterialTheme.colorScheme.surfaceVariant
            val contentColor = MaterialTheme.colorScheme.onSurface
            ModuleCardShell(
                name = moduleName,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_installed_inactive),
                dotColor = TelOrange,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }

        is LsposedState.NeedsReboot -> {
            val containerColor = MaterialTheme.colorScheme.surfaceVariant
            val contentColor = MaterialTheme.colorScheme.onSurface
            ModuleCardShell(
                name = moduleName,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_reboot_needed),
                dotColor = TelOrange,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }

        is LsposedState.Active -> {
            val targetsText = stringResource(R.string.dashboard_active_targets, state.targetCount)
            val protectionText = when (javaResult) {
                is JavaResult.Ok -> "\n" + stringResource(R.string.dashboard_protection_prefix, stringResource(R.string.dashboard_protection_ok))
                is JavaResult.Fail -> "\n" + stringResource(R.string.dashboard_protection_prefix, stringResource(R.string.dashboard_protection_fail))
                is JavaResult.HooksInactive -> "\n" + stringResource(R.string.dashboard_protection_prefix, stringResource(R.string.dashboard_protection_hooks_inactive))
                null -> ""
            }

            val subtitle = targetsText + protectionText

            val isFail = javaResult is JavaResult.Fail
            val isOk = javaResult is JavaResult.Ok

            val (containerColor, contentColor, dotColor) = when {
                isFail -> {
                    if (darkTheme) {
                        Triple(Color(0xFF421C1C), Color(0xFFEF9A9A), TelRed)
                    } else {
                        Triple(Color(0xFFFFEBEE), Color(0xFFC62828), TelRed)
                    }
                }
                isOk -> {
                    if (darkTheme) {
                        Triple(Color(0xFF1E3E28), Color(0xFFA5D6A7), TelGreen)
                    } else {
                        Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), TelGreen)
                    }
                }
                else -> {
                    Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, TelGreen)
                }
            }

            ModuleCardShell(
                name = moduleName,
                version = installedVersion,
                subtitle = subtitle,
                dotColor = dotColor,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }
    }
}

@Composable
private fun ModuleCardShell(
    name: String,
    version: String?,
    subtitle: String,
    dotColor: Color,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(color = dotColor, shape = CircleShape),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 4,
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.2f,
            )

            if (version != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "v$version",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    modifier =
                        Modifier
                            .background(
                                color = contentColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp),
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NativeInstallRecommendationCard(recommendation: NativeInstallRecommendation) {
    val darkTheme = isSystemInDarkTheme()
    val containerColor =
        if (recommendation.preferKmod) {
            if (darkTheme) Color(0xFF0D47A1).copy(alpha = 0.28f) else Color(0xFFE3F2FD)
        } else {
            if (darkTheme) Color(0xFF4E342E).copy(alpha = 0.32f) else Color(0xFFFFF3E0)
        }

    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (recommendation.preferKmod) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.dashboard_install_recommendation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    stringResource(
                        R.string.dashboard_install_recommendation_device,
                        recommendation.androidVersion,
                        recommendation.kernelVersion,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // ... (rest of the text logic remains same)
            // Disambiguate the GKI KMI tag baked into uname -r (e.g.
            // "android12-5.10") from the device's Android OS release on
            // devices where they differ — common on old Pixels still on
            // an android12 KMI kernel under an Android 14/15 ROM. Hide
            // the note when both match (would just be noise) or when
            // uname -r carries no KMI tag at all.
            val kmiBranch = recommendation.kernelBranch
            if (kmiBranch != null && kmiBranch != recommendation.androidVersion) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            R.string.dashboard_install_recommendation_kmi_note,
                            kmiBranch.replace(" ", "").lowercase(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            val alternative = recommendation.alternativeArtifact
            Text(
                text =
                    when {
                        !recommendation.preferKmod -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod_unsupported,
                                recommendation.recommendedArtifact,
                            )
                        }

                        recommendation.variantAmbiguous && alternative != null -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod_ambiguous,
                                recommendation.recommendedArtifact,
                                alternative,
                            )
                        }

                        else -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod,
                                recommendation.recommendedArtifact,
                            )
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
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

// ── Update & Changelog ──────────────────────────────────────────────────

@Composable
private fun UpdateAvailableCard(info: UpdateInfo) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (darkTheme) Color(0xFF0D47A1).copy(alpha = 0.28f) else Color(0xFFE3F2FD),
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
                    text = stringResource(R.string.update_available_subtitle, info.latestVersion),
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
            ) {
                Text(stringResource(R.string.update_download))
            }
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}
