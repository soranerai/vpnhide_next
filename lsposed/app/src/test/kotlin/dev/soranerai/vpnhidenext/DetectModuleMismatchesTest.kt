package dev.soranerai.vpnhidenext

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectModuleMismatchesTest {
    private fun installed(version: String?) = ModuleState.Installed(version = version, active = true, targetCount = 0)

    @Test
    fun `no modules produces no mismatches`() {
        assertEquals(emptyList<ModuleMismatch>(), detectModuleMismatches(emptyList(), "0.6.2"))
    }

    @Test
    fun `not-installed modules are skipped`() {
        val modules =
            listOf(
                ModuleState.NotInstalled to NativeModuleKind.Kmod,
                ModuleState.NotInstalled to NativeModuleKind.Zygisk,
            )
        assertEquals(emptyList<ModuleMismatch>(), detectModuleMismatches(modules, "0.6.2"))
    }

    @Test
    fun `installed modules without a version are skipped`() {
        val modules = listOf(installed(null) to NativeModuleKind.Kmod)
        assertEquals(emptyList<ModuleMismatch>(), detectModuleMismatches(modules, "0.6.2"))
    }

    @Test
    fun `matching base version does not mismatch`() {
        val modules =
            listOf(
                installed("0.6.2") to NativeModuleKind.Kmod,
                installed("v0.6.2") to NativeModuleKind.Zygisk,
            )
        assertEquals(emptyList<ModuleMismatch>(), detectModuleMismatches(modules, "0.6.2-14-g1f2205e"))
    }

    @Test
    fun `different base version produces a mismatch per module`() {
        val modules =
            listOf(
                installed("0.6.1") to NativeModuleKind.Kmod,
                installed("0.6.2") to NativeModuleKind.Zygisk,
                installed("0.5.0") to NativeModuleKind.Ports,
            )
        val result = detectModuleMismatches(modules, "0.6.2")
        assertEquals(
            listOf(
                ModuleMismatch(NativeModuleKind.Kmod, "0.6.1", "0.6.2"),
                ModuleMismatch(NativeModuleKind.Ports, "0.5.0", "0.6.2"),
            ),
            result,
        )
    }

    @Test
    fun `module kind and versions preserved in result`() {
        val modules = listOf(installed("0.5.0") to NativeModuleKind.Ports)
        val result = detectModuleMismatches(modules, "0.6.2")
        assertEquals(1, result.size)
        val only = result.single()
        assertEquals(NativeModuleKind.Ports, only.kind)
        assertEquals("0.5.0", only.moduleVersion)
        assertEquals("0.6.2", only.appVersion)
    }
}
