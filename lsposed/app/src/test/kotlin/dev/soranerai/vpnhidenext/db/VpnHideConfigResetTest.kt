package dev.soranerai.vpnhidenext.db

import dev.soranerai.vpnhidenext.PortProtocol
import dev.soranerai.vpnhidenext.missingSystemPolicyDefaults
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

    @Test
    fun allowlistSnapshotKeepsExplicitSystemDeselectionAndBlocksDefaultRematerialization() {
        val bard =
            AppProtection(
                packageName = "com.google.android.apps.bard",
                userId = 0,
                uid = 10265,
                kmod = true,
                lsposed = false,
                portHiding = true,
                systemPolicyExplicit = true,
            )
        val snapshot =
            VpnHideConfig().withProtectionPolicySnapshot(
                listMode = PolicyListMode.ALLOWLIST,
                apps = listOf(bard),
                resetRulesAndOverrides = false,
            )

        assertEquals(bard, snapshot.apps["com.google.android.apps.bard" to 0])
        assertTrue(
            missingSystemPolicyDefaults(
                listMode = PolicyListMode.ALLOWLIST,
                configured = snapshot.apps.values.toList(),
                systemPackages = setOf("com.google.android.apps.bard" to 10265),
                selfPackage = "dev.soranerai.vpnhidenext",
            ).isEmpty(),
        )
    }

    @Test
    fun policySnapshotDropsCoreUidRowsBeforeSerialization() {
        val eligible = AppProtection("com.example.app", uid = 10042, lsposed = true)
        val core = AppProtection("com.android.core", uid = 1000, lsposed = true)

        val snapshot =
            VpnHideConfig().withProtectionPolicySnapshot(
                listMode = PolicyListMode.ALLOWLIST,
                apps = listOf(eligible, core),
                resetRulesAndOverrides = false,
            )

        assertEquals(listOf(eligible), snapshot.apps.values.toList())
    }
}
