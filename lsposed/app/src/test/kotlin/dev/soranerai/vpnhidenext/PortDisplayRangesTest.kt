package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.domain.models.PortAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class PortDisplayRangesTest {
    @Test
    fun `single ports and pairs stay separate`() {
        val ranges = buildPortDisplayRanges(listOf(access(80), access(81), access(443)))

        assertEquals(listOf("80", "81", "443"), ranges.map { it.label })
    }

    @Test
    fun `three or more consecutive ports are compacted`() {
        val ranges = buildPortDisplayRanges((1000..1004).map(::access) + access(8080))

        assertEquals(listOf("1000–1004", "8080"), ranges.map { it.label })
        assertEquals(5, ranges.first().count)
    }

    @Test
    fun `duplicate port counts are summed`() {
        val ranges =
            buildPortDisplayRanges(
                listOf(access(53, 2), access(53, 3), access(54), access(55, 4)),
            )

        assertEquals(listOf("53–55"), ranges.map { it.label })
        assertEquals(10, ranges.single().count)
    }

    @Test
    fun `valid port boundaries are supported`() {
        val ranges = buildPortDisplayRanges(listOf(access(1), access(65535)))

        assertEquals(listOf("1", "65535"), ranges.map { it.label })
    }

    private fun access(
        port: Int,
        count: Int = 1,
    ) = PortAccess(port = port, protocol = "tcp", count = count)
}
