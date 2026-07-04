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
    private var diagnosticsJob: Job? = null

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        launchEnsureLoaded(scope) {
            val repository = DashboardRepository(context.applicationContext)
            repository.loadDashboardState(selfNeedsRestart)
        }
        observeDiagnostics(scope, context, selfNeedsRestart)
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
        observeDiagnostics(scope, context, selfNeedsRestart)
    }

    private fun observeDiagnostics(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        synchronized(lock) {
            if (diagnosticsJob?.isActive == true) return
            diagnosticsJob =
                scope.launch {
                    DiagnosticsCache.state.collect { diagState ->
                        val current = state.value
                        if (current != null && (diagState is DiagnosticsCache.State.Ready || diagState is DiagnosticsCache.State.VpnOff)) {
                            val repository = DashboardRepository(context.applicationContext)
                            val next = repository.loadDashboardState(selfNeedsRestart)
                            updateState(next)
                        }
                    }
                }
        }
    }
}
