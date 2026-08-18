package dev.soranerai.vpnhidenext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AsyncCacheTest {
    @Test
    fun `new generation invalidates an older request`() {
        val generations = RequestGeneration()

        val oldRequest = generations.next()
        val newRequest = generations.next()

        assertFalse(generations.isCurrent(oldRequest))
        assertEquals(true, generations.isCurrent(newRequest))
    }

    @Test
    fun `reload publishes the newest value and clears loading`() = runTest {
        val cache = IntCache(Dispatchers.Unconfined)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        cache.reload(scope, 42)

        assertEquals(42, cache.state.value)
        assertFalse(cache.loading.value)
    }

    @Test
    fun `invalidate clears value and cancels loading state`() = runTest {
        val cache = IntCache(Dispatchers.Unconfined)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        cache.reload(scope, 42)

        cache.clear()

        assertNull(cache.state.value)
        assertFalse(cache.loading.value)
    }

    private class IntCache(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : AsyncCache<Int>(dispatcher) {
        fun reload(
            scope: CoroutineScope,
            value: Int,
        ) = launchReload(scope) { value }

        fun clear() = invalidate()
    }
}
