package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.data.repository.DashboardRepository
import dev.soranerai.vpnhidenext.domain.models.AppInterceptStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-scoped cache for the Intercept Statistics.
 * This is decoupled from DashboardCache because the framework stats polling
 * involves a 1.1s sleep (to wait for system_server dump), which would
 * otherwise delay the entire dashboard render.
 */
internal object InterceptStatsCache : AsyncCache<List<AppInterceptStats>>() {
    val stats: StateFlow<List<AppInterceptStats>?> = state

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchEnsureLoaded(scope) {
            AppListCache.apps.first { it != null }
            val repository = DashboardRepository(context.applicationContext)
            repository.loadInterceptStats()
        }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchReload(scope) {
            AppListCache.apps.first { it != null }
            val repository = DashboardRepository(context.applicationContext)
            repository.loadInterceptStats()
        }
    }

    fun clearStats() {
        synchronized(lock) {
            updateState(emptyList())
        }
    }
}
