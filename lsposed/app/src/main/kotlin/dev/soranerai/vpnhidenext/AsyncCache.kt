package dev.soranerai.vpnhidenext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RequestGeneration {
    private var value = 0L

    @Synchronized
    fun next(): Long = ++value

    @Synchronized
    fun isCurrent(request: Long): Boolean = value == request
}

internal abstract class AsyncCache<T>(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<T?>(null)
    val state: StateFlow<T?> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    protected var inflight: Job? = null
    protected val lock = Any()
    private val generation = RequestGeneration()

    fun invalidate() {
        synchronized(lock) {
            generation.next()
            inflight?.cancel()
            inflight = null
            _state.value = null
            _loading.value = false
        }
    }

    protected fun updateState(value: T?) {
        _state.value = value
    }

    /**
     * Reloads synchronously and returns only after the new value is published.
     *
     * Callers that mutate the source of a cache and then immediately update
     * their UI must use this instead of [launchReload]. Otherwise an older
     * asynchronous request can publish a stale snapshot after the mutation.
     */
    protected suspend fun reloadNow(block: suspend () -> T): T {
        val requestGeneration: Long
        synchronized(lock) {
            inflight?.cancel()
            inflight = null
            requestGeneration = generation.next()
            _loading.value = true
        }

        return try {
            val next = withContext(ioDispatcher) { block() }
            synchronized(lock) {
                if (generation.isCurrent(requestGeneration)) _state.value = next
            }
            next
        } finally {
            synchronized(lock) {
                if (generation.isCurrent(requestGeneration)) _loading.value = false
            }
        }
    }

    protected fun launchReload(
        scope: CoroutineScope,
        block: suspend () -> T,
    ) {
        synchronized(lock) {
            inflight?.cancel()
            val requestGeneration = generation.next()
            _loading.value = true
            inflight =
                scope.launch {
                    try {
                        val next = withContext(ioDispatcher) { block() }
                        synchronized(lock) {
                            if (generation.isCurrent(requestGeneration)) _state.value = next
                        }
                    } finally {
                        synchronized(lock) {
                            if (generation.isCurrent(requestGeneration)) _loading.value = false
                        }
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
            val requestGeneration = generation.next()
            _loading.value = true
            inflight =
                scope.launch {
                    try {
                        val next = withContext(ioDispatcher) { block() }
                        synchronized(lock) {
                            if (generation.isCurrent(requestGeneration)) _state.value = next
                        }
                    } finally {
                        synchronized(lock) {
                            if (generation.isCurrent(requestGeneration)) _loading.value = false
                        }
                    }
                }
        }
    }
}
