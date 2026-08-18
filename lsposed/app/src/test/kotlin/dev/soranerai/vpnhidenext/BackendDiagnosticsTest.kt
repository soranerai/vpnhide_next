package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.domain.models.BackendDiagnosticsEvaluator
import dev.soranerai.vpnhidenext.domain.models.BackendProbeFacts
import dev.soranerai.vpnhidenext.domain.models.BackendKind
import dev.soranerai.vpnhidenext.domain.models.DiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendDiagnosticsTest {
    @Test
    fun `module metadata does not prove backend activity`() {
        val result = BackendDiagnosticsEvaluator.evaluate(
            BackendProbeFacts(
                root = true,
                controlDevice = false,
                controlTool = true,
                controlToolResponding = false,
                bridgeKmod = true,
                bridgeKpatch = false,
                bridgeValid = true,
                bridgeDisabled = false,
                loadedKmod = false,
                lsposedInstalled = true,
                lsposedDisabled = false,
                lsposedHooksActive = false,
            ),
        )

        assertEquals(DiagnosticStatus.AVAILABLE, result.bridge.status)
        assertEquals(DiagnosticStatus.INACTIVE, result.backend.status)
        assertEquals(DiagnosticStatus.BLOCKED, result.lsposed.status)
        assertEquals(BackendKind.UNKNOWN, result.backendKind)
    }

    @Test
    fun `responding device without proc module is built in`() {
        val result = BackendDiagnosticsEvaluator.evaluate(
            BackendProbeFacts(
                root = true,
                controlDevice = true,
                controlTool = true,
                controlToolResponding = true,
                bridgeKmod = false,
                bridgeKpatch = true,
                bridgeValid = true,
                bridgeDisabled = false,
                loadedKmod = false,
                lsposedInstalled = true,
                lsposedDisabled = false,
                lsposedHooksActive = true,
            ),
        )

        assertEquals(DiagnosticStatus.AVAILABLE, result.backend.status)
        assertEquals(BackendKind.BUILT_IN, result.backendKind)
        assertEquals(DiagnosticStatus.AVAILABLE, result.lsposed.status)
    }

    @Test
    fun `root denial blocks dependent checks`() {
        val result = BackendDiagnosticsEvaluator.evaluate(
            BackendProbeFacts(
                root = false,
                controlDevice = false,
                controlTool = false,
                controlToolResponding = false,
                bridgeKmod = false,
                bridgeKpatch = false,
                bridgeValid = false,
                bridgeDisabled = false,
                loadedKmod = false,
                lsposedInstalled = false,
                lsposedDisabled = false,
                lsposedHooksActive = false,
            ),
        )

        assertEquals(DiagnosticStatus.MISSING, result.root.status)
        assertEquals(DiagnosticStatus.BLOCKED, result.backend.status)
        assertEquals(DiagnosticStatus.BLOCKED, result.bridge.status)
        assertEquals(DiagnosticStatus.BLOCKED, result.lsposed.status)
    }
}
