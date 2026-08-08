package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.hooks.core.HookContext
import org.junit.Assert.assertEquals
import org.junit.Test

class HookStatsCounterTest {
    @Test
    fun `drain returns only unsent deltas`() {
        val counter = HookContext.RollingCounter()
        repeat(3) { counter.increment() }

        assertEquals(3, counter.drain())
        assertEquals(0, counter.drain())

        counter.increment()
        assertEquals(1, counter.drain())
    }

    @Test
    fun `failed batch can be restored without losing new events`() {
        val counter = HookContext.RollingCounter()
        repeat(2) { counter.increment() }
        val batch = counter.drain()
        counter.increment()

        counter.restore(batch)

        assertEquals(3, counter.drain())
    }
}
