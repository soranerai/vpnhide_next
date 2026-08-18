package dev.soranerai.vpnhidenext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope

/**
 * Owns the dashboard-triggered backend update probes. Keeping these effects
 * outside the dashboard layout makes their lifecycle and re-trigger keys
 * explicit without changing the update caches themselves.
 */
@Composable
internal fun DashboardUpdateEffects(
    scope: CoroutineScope,
    kmodUpdateTarget: KmodUpdateTarget?,
    builtInUpdateTarget: BuiltInUpdateTarget?,
) {
    LaunchedEffect(kmodUpdateTarget) {
        kmodUpdateTarget?.let { KmodUpdateCache.ensureFresh(scope, it) }
    }

    LaunchedEffect(builtInUpdateTarget) {
        builtInUpdateTarget?.let { BuiltInUpdateCache.ensureFresh(it) }
    }
}
