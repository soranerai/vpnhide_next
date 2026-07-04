package dev.soranerai.vpnhidenext

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cache for `runAllChecks` results.
 *
 * Diagnostics answer one question: *do the hooks work for this app
 * process right now?* The hooks themselves are fixed at process
 * creation time — kmod loads at boot, LSPosed injects into
 * system_server at its boot —
 * so a run's result is valid for the entire lifetime of this app
 * process. Re-running every tab switch is pure waste.
 *
 * State machine:
 * - [State.NotRun] — fresh, nothing attempted yet.
 * - [State.Running] — a run is in flight.
 * - [State.Ready] — results captured; exposed to both the Dashboard
 *   protection panel and the Diagnostics screen.
 *
 * Once [State.Ready] is reached, [run] becomes a no-op — results don't
 * change mid-process. The only path back to "please retry" requires
 * killing the process (new launch = fresh cache) or calling [reset]
 * (currently unused; reserved for explicit force-refresh flows, e.g.
 * "new kernel module just installed, please verify from a fresh
 * process" style prompts if we ever want them).
 */
internal object DiagnosticsCache {
    sealed interface State {
        data object NotRun : State

        data class Running(
            val results: CheckResults,
        ) : State

        data class Ready(
            val results: CheckResults,
        ) : State

        data object VpnOff : State
    }

    private val _state = MutableStateFlow<State>(State.NotRun)
    val state: StateFlow<State> = _state.asStateFlow()

    private var inflight: Job? = null
    private val lock = Any()

    /** Start a run if one isn't already in flight and we don't have a
     * completed result yet. Idempotent — safe to call from both
     * Dashboard and Diagnostics screens on every composition.
     */
    fun run(
        scope: CoroutineScope,
        context: Context,
    ) {
        val s = _state.value
        if (s is State.Ready || s is State.Running || s is State.VpnOff) return

        if (inflight?.isActive == true) return
        inflight = scope.launch { doRun(context.applicationContext) }
    }

    /**
     * Await the results of the diagnostics run. If not already running or
     * finished, starts a new run.
     */
    suspend fun awaitResults(context: Context): CheckResults? {
        val s = _state.value
        if (s is State.Ready) return s.results
        if (s is State.VpnOff) return null

        if (s !is State.Running) {
            withContext(Dispatchers.Main) {
                doRun(context.applicationContext)
            }
        }

        state.first { it is State.Ready || it is State.VpnOff }
        return (_state.value as? State.Ready)?.results
    }

    /** Used by the retry button in the "VPN off" banner. Same as [run]
     * but bypasses the `VpnOff` short-circuit (which [run] doesn't
     * actually have — the guard above covers NotRun/VpnOff both — so
     * this is really just a named alias for readability at the
     * callsite).
     */
    fun retry(
        scope: CoroutineScope,
        context: Context,
    ) {
        reset()
        run(scope, context)
    }

    /** Drop any cached result. Next [run] will execute fresh.
     * Not wired to any UI at the moment — reserved for scenarios like
     * "user installed a new module and wants to force a re-run from the
     * same process". Normal refreshes never need this because results
     * are fixed for the process lifetime.
     */
    fun reset() {
        inflight?.cancel()
        _state.value = State.NotRun
    }

    private fun hasActiveVpnInterface(): Boolean {
        val (exit, out) = suExec("ip link show up 2>/dev/null")
        if (exit != 0 || out.isBlank()) return true
        val vpnPrefixes = listOf("tun", "wg", "ppp", "tap", "ipsec")
        return out.lines().any { line ->
            val name =
                Regex("""^\d+:\s+(\S+?)[@:]""")
                    .find(line.trim())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.lowercase()
                    ?: return@any false
            vpnPrefixes.any { name.startsWith(it) }
        }
    }

    private suspend fun doRun(appContext: Context) {
        val hookMask =
            withContext(Dispatchers.IO) {
                val db =
                    dev.soranerai.vpnhidenext.db.AppDatabase
                        .getInstance(appContext)
                db
                    .globalConfigDao()
                    .getConfig()
                    ?.kernelHookMask
                    ?.toUInt() ?: 0xFFFFFFFFu
            }

        val initialResults = getPlaceholderResults(appContext, hookMask)
        _state.value = State.Running(initialResults)

        val vpnActive = withContext(Dispatchers.IO) { hasActiveVpnInterface() }
        if (!vpnActive) {
            _state.value = State.VpnOff
            return
        }

        var currentResults = initialResults

        coroutineScope {
            val tickerJob =
                launch(Dispatchers.Default) {
                    while (isActive) {
                        delay(100)
                        synchronized(lock) {
                            val current = _state.value
                            if (current is State.Running && current.results != currentResults) {
                                _state.value = State.Running(currentResults)
                            }
                        }
                    }
                }

            try {
                val cm = appContext.getSystemService(ConnectivityManager::class.java)
                val results =
                    runAllChecks(cm, appContext, hookMask) { updatedResult, isJava ->
                        synchronized(lock) {
                            val newNative =
                                if (isJava) {
                                    currentResults.native
                                } else {
                                    currentResults.native.map {
                                        if (it.name == updatedResult.name) updatedResult else it
                                    }
                                }
                            val newJava =
                                if (!isJava) {
                                    currentResults.java
                                } else {
                                    currentResults.java.map {
                                        if (it.name == updatedResult.name) updatedResult else it
                                    }
                                }
                            currentResults = CheckResults(newNative, newJava)
                        }
                    }
                tickerJob.cancel()
                _state.value = State.Ready(results)
            } catch (e: Exception) {
                tickerJob.cancel()
                val failedNative =
                    currentResults.native.map {
                        if (it.isRunning) it.copy(passed = false, isRunning = false) else it
                    }
                val failedJava =
                    currentResults.java.map {
                        if (it.isRunning) it.copy(passed = false, isRunning = false) else it
                    }
                _state.value = State.Ready(CheckResults(failedNative, failedJava))
                VpnHideLog.w("VpnHide-Diag", "runAllChecks failed: ${e.message}")
            }
        }
    }
}
