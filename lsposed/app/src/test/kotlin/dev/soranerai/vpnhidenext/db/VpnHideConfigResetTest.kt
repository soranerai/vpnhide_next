package dev.soranerai.vpnhidenext.db

import dev.soranerai.vpnhidenext.PortProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnHideConfigResetTest {
    @Test
    fun listModeResetClearsTargetsHookOverridesAndPortRules() {
        val config =
            VpnHideConfig(
                globalConfig =
                    DbGlobalConfig(
                        listMode = PolicyListMode.BLACKLIST,
                        kernelHookMask = 1L,
                        javaHookMask = 2L,
                        debugLogging = 1,
                        updateCheckEnabled = false,
                    ),
                apps =
                    mapOf(
                        ("com.example.app" to 10) to
                            AppProtection(
                                packageName = "com.example.app",
                                userId = 10,
                                kmod = true,
                                kernelHookMask = 3L,
                                javaHookMask = 4L,
                            ),
                    ),
                portRules =
                    listOf(
                        DbPortRule(
                            packageName = "com.example.app",
                            userId = 10,
                            startPort = 8080,
                            endPort = 8080,
                            protocol = PortProtocol.TCP,
                        ),
                    ),
                massPortRules =
                    listOf(
                        DbMassPortRule(
                            startPort = 53,
                            endPort = 53,
                            protocol = PortProtocol.UDP,
                        ),
                    ),
                ifacePrefixes = listOf("tun", "wg"),
            )

        val reset = config.resetProtectionForListMode(PolicyListMode.ALLOWLIST)

        assertEquals(PolicyListMode.ALLOWLIST, reset.globalConfig.listMode)
        assertEquals(DbGlobalConfig().kernelHookMask, reset.globalConfig.kernelHookMask)
        assertEquals(DbGlobalConfig().javaHookMask, reset.globalConfig.javaHookMask)
        assertTrue(reset.apps.isEmpty())
        assertTrue(reset.portRules.isEmpty())
        assertTrue(reset.massPortRules.isEmpty())
        assertEquals(listOf("tun", "wg"), reset.ifacePrefixes)
        assertEquals(1, reset.globalConfig.debugLogging)
        assertEquals(false, reset.globalConfig.updateCheckEnabled)
    }
}
