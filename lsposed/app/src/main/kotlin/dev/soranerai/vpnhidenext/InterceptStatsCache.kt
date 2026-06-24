package dev.soranerai.vpnhidenext

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * App-scoped cache for the Intercept Statistics.
 * This is decoupled from DashboardCache because the framework stats polling
 * involves a 1.1s sleep (to wait for system_server dump), which would
 * otherwise delay the entire dashboard render.
 */
internal object InterceptStatsCache {
    private val _stats = MutableStateFlow<List<AppInterceptStats>?>(null)
    val stats: StateFlow<List<AppInterceptStats>?> = _stats.asStateFlow()

    private var inflight: Job? = null

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        if (_stats.value != null || inflight?.isActive == true) return
        inflight = scope.launch { reload(context) }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        inflight?.cancel()
        inflight = scope.launch { reload(context) }
    }

    fun invalidate() {
        _stats.value = null
    }

    fun clearStats() {
        _stats.value = emptyList()
    }

    private suspend fun reload(context: Context) {
        AppListCache.apps.first { it != null }
        val next =
            withContext(Dispatchers.IO) {
                loadInterceptStats(context)
            }
        _stats.value = next
    }
}
