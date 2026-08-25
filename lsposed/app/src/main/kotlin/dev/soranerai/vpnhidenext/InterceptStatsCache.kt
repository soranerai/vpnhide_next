package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.data.repository.DashboardRepository
import dev.soranerai.vpnhidenext.domain.models.AppInterceptStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()
    private val _response = MutableStateFlow<KmodStatsResponse?>(null)
    val response: StateFlow<KmodStatsResponse?> = _response.asStateFlow()

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchEnsureLoaded(scope) {
            val repository = DashboardRepository(context.applicationContext)
            load(repository)
        }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchReload(scope) {
            val repository = DashboardRepository(context.applicationContext)
            load(repository)
        }
    }

    private fun load(repository: DashboardRepository): List<AppInterceptStats> =
        try {
            repository.loadInterceptStats().also {
                _response.value = repository.lastKmodStatsResponse
                _unavailable.value = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _response.value = null
            _unavailable.value = true
            emptyList()
        }

    fun clearStats() {
        synchronized(lock) {
            _unavailable.value = false
            _response.value = null
            updateState(emptyList())
        }
    }
}
