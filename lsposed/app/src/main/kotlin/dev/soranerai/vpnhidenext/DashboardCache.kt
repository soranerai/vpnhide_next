package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.data.repository.DashboardRepository
import dev.soranerai.vpnhidenext.domain.models.DashboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-scoped cache for the Dashboard's computed state. Previously
 * `DashboardScreen` ran `loadDashboardState()` in its own
 * `LaunchedEffect(Unit)` on every composition — which means every
 * tab switch re-ran all the module-prop / target / kprobes / SELinux
 * checks via `suExec`. Cache them once at startup; refresh them
 * explicitly on user action or after a Save.
 *
 * The Dashboard screen reads [state] and shows the previous value
 * while a refresh is in flight so tab switches feel instant even when
 * data changes underneath.
 */
internal object DashboardCache : AsyncCache<DashboardState>() {
    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        launchEnsureLoaded(scope) {
            val repository = DashboardRepository(context.applicationContext)
            repository.loadDashboardState(selfNeedsRestart)
        }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        launchReload(scope) {
            val repository = DashboardRepository(context.applicationContext)
            repository.loadDashboardState(selfNeedsRestart)
        }
    }
}
