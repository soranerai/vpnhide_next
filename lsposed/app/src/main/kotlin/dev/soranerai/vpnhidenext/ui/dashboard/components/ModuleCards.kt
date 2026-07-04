package dev.soranerai.vpnhidenext.ui.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.BuildConfig
import dev.soranerai.vpnhidenext.R
import dev.soranerai.vpnhidenext.domain.models.*
import dev.soranerai.vpnhidenext.ui.theme.*

@Composable
fun SkeletonModuleCard() {
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
                Box(
                    modifier =
                        Modifier
                            .width(70.dp)
                            .height(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp),
                            ),
                )
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                shape = CircleShape,
                            ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .width(100.dp)
                        .height(12.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp),
                        ),
            )
        }
    }
}

@Composable
fun ModuleCard(
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

            val targetsText =
                when {
                    brokenSubtitleRes != null -> stringResource(brokenSubtitleRes)
                    active -> stringResource(R.string.dashboard_active_targets, state.targetCount)
                    selfNeedsRestart -> stringResource(R.string.dashboard_installed_restart_app)
                    else -> stringResource(R.string.dashboard_installed_inactive)
                }

            val protectionText =
                if (active) {
                    when (nativeResult) {
                        is NativeResult.Checking -> {
                            val statusText = stringResource(R.string.dashboard_protection_checking)
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    statusText,
                                )
                        }

                        is NativeResult.Ok -> {
                            val statusText = stringResource(R.string.dashboard_protection_ok)
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    "$statusText (${nativeResult.passed}/${nativeResult.total})",
                                )
                        }

                        is NativeResult.Partial -> {
                            val statusText = stringResource(R.string.dashboard_protection_partial)
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    "$statusText (${nativeResult.passed}/${nativeResult.total})",
                                )
                        }

                        is NativeResult.Fail -> {
                            val statusText = stringResource(R.string.dashboard_protection_fail)
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    "$statusText (${nativeResult.passed}/${nativeResult.total})",
                                )
                        }

                        is NativeResult.NoModule -> {
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    stringResource(R.string.dashboard_protection_no_module),
                                )
                        }

                        is NativeResult.VpnOff -> {
                            "\n" +
                                stringResource(
                                    R.string.dashboard_protection_prefix,
                                    stringResource(R.string.dashboard_protection_vpn_off),
                                )
                        }

                        null -> {
                            ""
                        }
                    }
                } else {
                    ""
                }

            val subtitle = targetsText + protectionText

            val isFail = broken != null || (active && nativeResult is NativeResult.Fail)
            val isPartial = active && nativeResult is NativeResult.Partial
            val isOk = active && nativeResult is NativeResult.Ok
            val isVpnOff = active && nativeResult is NativeResult.VpnOff

            val (containerColor, contentColor, dotColor) =
                getCardColors(
                    isFail = isFail,
                    isPartial = isPartial,
                    isOk = isOk,
                    isVpnOff = isVpnOff,
                    active = active,
                    darkTheme = darkTheme,
                )

            ModuleCardShell(
                name = name,
                version = state.version,
                subtitle = subtitle,
                dotColor = dotColor,
                containerColor = containerColor,
                contentColor = contentColor,
                showLoading = active && nativeResult is NativeResult.Checking,
            )
        }
    }
}

@Composable
fun LsposedCard(
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

        is LsposedState.Active -> {
            val targetsText = stringResource(R.string.dashboard_active_targets, state.targetCount)
            val protectionText =
                when (javaResult) {
                    is JavaResult.Checking -> {
                        val statusText = stringResource(R.string.dashboard_protection_checking)
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                statusText,
                            )
                    }

                    is JavaResult.Ok -> {
                        val statusText = stringResource(R.string.dashboard_protection_ok)
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                "$statusText (${javaResult.passed}/${javaResult.total})",
                            )
                    }

                    is JavaResult.Partial -> {
                        val statusText = stringResource(R.string.dashboard_protection_partial)
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                "$statusText (${javaResult.passed}/${javaResult.total})",
                            )
                    }

                    is JavaResult.Fail -> {
                        val statusText = stringResource(R.string.dashboard_protection_fail)
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                "$statusText (${javaResult.passed}/${javaResult.total})",
                            )
                    }

                    is JavaResult.HooksInactive -> {
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                stringResource(R.string.dashboard_protection_hooks_inactive),
                            )
                    }

                    is JavaResult.VpnOff -> {
                        "\n" +
                            stringResource(
                                R.string.dashboard_protection_prefix,
                                stringResource(R.string.dashboard_protection_vpn_off),
                            )
                    }

                    null -> {
                        ""
                    }
                }

            val subtitle = targetsText + protectionText

            val isFail = javaResult is JavaResult.Fail
            val isPartial = javaResult is JavaResult.Partial
            val isOk = javaResult is JavaResult.Ok
            val isVpnOff = javaResult is JavaResult.VpnOff

            val (containerColor, contentColor, dotColor) =
                getCardColors(
                    isFail = isFail,
                    isPartial = isPartial,
                    isOk = isOk,
                    isVpnOff = isVpnOff,
                    active = true,
                    darkTheme = darkTheme,
                )

            ModuleCardShell(
                name = moduleName,
                version = installedVersion,
                subtitle = subtitle,
                dotColor = dotColor,
                containerColor = containerColor,
                contentColor = contentColor,
                showLoading = javaResult is JavaResult.Checking,
            )
        }
    }
}

@Composable
fun ModuleCardShell(
    name: String,
    version: String?,
    subtitle: String,
    dotColor: Color,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    showLoading: Boolean = false,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
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
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = contentColor.copy(alpha = 0.6f),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(color = dotColor, shape = CircleShape),
                    )
                }
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
private fun getCardColors(
    isFail: Boolean,
    isPartial: Boolean,
    isOk: Boolean,
    active: Boolean,
    darkTheme: Boolean,
    isVpnOff: Boolean = false,
): Triple<Color, Color, Color> =
    when {
        isFail -> {
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurface,
                TelRed,
            )
        }

        isPartial -> {
            if (darkTheme) {
                Triple(Color(0xFF0D243A), Color(0xFF90CAF9), TelBlue)
            } else {
                Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), TelBlue)
            }
        }

        isOk -> {
            if (darkTheme) {
                Triple(Color(0xFF1E3E28), Color(0xFFA5D6A7), TelGreen)
            } else {
                Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), TelGreen)
            }
        }

        isVpnOff -> {
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurface,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }

        else -> {
            val dot = if (active) TelGreen else TelOrange
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurface,
                dot,
            )
        }
    }
