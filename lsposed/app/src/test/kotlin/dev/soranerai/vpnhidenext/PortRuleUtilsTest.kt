package dev.soranerai.vpnhidenext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortRuleUtilsTest {
    @Test
    fun `port boundaries are inclusive`() {
        assertEquals(RuleViolationType.NONE, validatePortRange(1, 65535))
        assertTrue(isValidPortRange(1, 65535))
    }

    @Test
    fun `ports outside boundaries are rejected`() {
        assertEquals(RuleViolationType.INVALID_START, validatePortRange(0, 80))
        assertEquals(RuleViolationType.INVALID_END, validatePortRange(80, 65536))
        assertFalse(isValidPortRange(0, 65535))
    }

    @Test
    fun `missing ports are rejected instead of becoming a full range`() {
        assertEquals(RuleViolationType.INVALID_START, validatePortRange(null, 80))
        assertEquals(RuleViolationType.INVALID_END, validatePortRange(80, null))
    }

    @Test
    fun `start cannot be after end`() {
        assertEquals(RuleViolationType.START_AFTER_END, validatePortRange(443, 80))
        assertFalse(isValidPortRange(443, 80))
    }

    @Test
    fun `valid rule still detects duplicate`() {
        val rule = PortRule(startPort = 443, protocol = PortProtocol.TCP)
        val existing = rule.copy(id = "existing")

        assertEquals(RuleViolationType.DUPLICATE, validateRule(rule, listOf(existing)).type)
    }
}
