package dev.soranerai.vpnhidenext.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSchemaTest {
    @Test
    fun `legacy config version is upgraded to version one`() {
        assertEquals(1, migratedConfigSchemaVersion(null))
        assertEquals(1, migratedConfigSchemaVersion(0))
    }

    @Test
    fun `current schema version is preserved`() {
        assertEquals(1, migratedConfigSchemaVersion(1))
    }

    @Test
    fun `future schema is rejected instead of being silently interpreted`() {
        try {
            migratedConfigSchemaVersion(99)
            throw AssertionError("Expected unsupported schema to be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("99"))
        }
    }
}
