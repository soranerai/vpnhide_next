package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.domain.models.BackendKind
import dev.soranerai.vpnhidenext.domain.models.DiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GateRepairPlanTest {
    private fun compatibility(
        bridge: String? = null,
        builtIn: String? = null,
        kmod: String? = null,
    ): CompatibilityResult =
        CompatibilityResolver.resolve(
            InstalledComponentVersions(
                lsposed = "2.5.4",
                bridge = bridge,
                builtIn = builtIn,
                kmod = kmod,
            ),
        )

    private fun plan(
        result: CompatibilityResult,
        backendStatus: DiagnosticStatus = DiagnosticStatus.BROKEN,
        bridgeStatus: DiagnosticStatus = DiagnosticStatus.AVAILABLE,
        installedNativeIsKmod: Boolean,
    ): GateRepairPlan =
        resolveGateRepairPlan(
            requiredComponent = (result as? CompatibilityResult.Requires)?.component,
            backendStatus = backendStatus,
            backendKind = BackendKind.BUILT_IN,
            bridgeStatus = bridgeStatus,
            installedNativeIsKmod = installedNativeIsKmod,
        )

    @Test
    fun `kmod mismatch offers only kmod repair`() {
        val result = compatibility(kmod = "2.5.0")

        assertEquals(CompatibilityResult.Requires("kmod", "2.5.3"), result)
        assertEquals(
            GateRepairPlan(offerKmod = true, offerBuiltIn = false, bridgeOnly = false),
            plan(result, installedNativeIsKmod = true),
        )
    }

    @Test
    fun `bridge mismatch offers only bridge repair`() {
        val result = compatibility(bridge = "2.5.3", builtIn = "2.5.3")

        assertEquals(CompatibilityResult.Requires("bridge", "2.5.4"), result)
        assertEquals(
            GateRepairPlan(offerKmod = false, offerBuiltIn = true, bridgeOnly = true),
            plan(result, bridgeStatus = DiagnosticStatus.BROKEN, installedNativeIsKmod = false),
        )
    }

    @Test
    fun `kpatch mismatch offers only full built-in repair`() {
        val result = compatibility(bridge = "2.5.4", builtIn = "2.5.2")

        assertEquals(CompatibilityResult.Requires("built-in", "2.5.3"), result)
        assertEquals(
            GateRepairPlan(offerKmod = false, offerBuiltIn = true, bridgeOnly = false),
            plan(result, installedNativeIsKmod = false),
        )
    }

    @Test
    fun `bridge and kpatch mismatch offers only full built-in repair`() {
        val result = compatibility(bridge = "2.5.3", builtIn = "2.5.2")

        assertEquals(CompatibilityResult.Requires("built-in", "2.5.3"), result)
        assertEquals(
            GateRepairPlan(offerKmod = false, offerBuiltIn = true, bridgeOnly = false),
            plan(result, bridgeStatus = DiagnosticStatus.BROKEN, installedNativeIsKmod = false),
        )
    }
}
