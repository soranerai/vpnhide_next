package dev.soranerai.vpnhidenext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AsyncCacheTest {
    @Test
    fun `reload publishes the newest value and clears loading`() {
        val cache = IntCache()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        cache.reload(scope, 42)

        assertEquals(42, cache.state.value)
        assertFalse(cache.loading.value)
    }

    @Test
    fun `invalidate clears value and cancels loading state`() {
        val cache = IntCache()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        cache.reload(scope, 42)

        cache.clear()

        assertNull(cache.state.value)
        assertFalse(cache.loading.value)
    }

    private class IntCache : AsyncCache<Int>() {
        fun reload(
            scope: CoroutineScope,
            value: Int,
        ) = launchReload(scope) { value }

        fun clear() = invalidate()
    }
}
