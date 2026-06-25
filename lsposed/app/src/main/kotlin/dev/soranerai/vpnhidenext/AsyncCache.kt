package dev.soranerai.vpnhidenext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal abstract class AsyncCache<T> {
    private val _state = MutableStateFlow<T?>(null)
    val state: StateFlow<T?> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    protected var inflight: Job? = null
    protected val lock = Any()

    fun invalidate() {
        synchronized(lock) {
            _state.value = null
        }
    }

    protected fun updateState(value: T?) {
        _state.value = value
    }

    protected fun launchReload(
        scope: CoroutineScope,
        block: suspend () -> T,
    ) {
        synchronized(lock) {
            inflight?.cancel()
            _loading.value = true
            inflight =
                scope.launch {
                    try {
                        val next = withContext(Dispatchers.IO) { block() }
                        _state.value = next
                    } finally {
                        _loading.value = false
                    }
                }
        }
    }

    protected fun launchEnsureLoaded(
        scope: CoroutineScope,
        block: suspend () -> T,
    ) {
        synchronized(lock) {
            if (_state.value != null || inflight?.isActive == true) return
            _loading.value = true
            inflight =
                scope.launch {
                    try {
                        val next = withContext(Dispatchers.IO) { block() }
                        _state.value = next
                    } finally {
                        _loading.value = false
                    }
                }
        }
    }
}
