package dev.soranerai.vpnhidenext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class BuiltInUpdaterTest {
    private fun asset(
        name: String,
        tag: String = "v2.3.0",
    ) = KernelReleaseAsset(
        releaseTag = tag,
        name = name,
        url = "https://github.com/soranerai/GKI_KernelSU_SUSFS/releases/download/$tag/$name",
        sha256 = "a".repeat(64),
    )

    @Test
    fun `selector picks nearest patch not older than running kernel`() {
        val result =
            selectKernelAsset(
                runningRelease = "6.1.157-android14-11-gabcdef",
                installedVersion = "2.2.2",
                assets =
                    listOf(
                        asset("6.1.145-android14-2025-08-AnyKernel3.zip"),
                        asset("6.1.175-android14-lts-AnyKernel3.zip"),
                        asset("6.1.172-android14-2026-06-AnyKernel3.zip"),
                        asset("6.1.162-android14-2026-03-AnyKernel3.zip"),
                        asset("6.6.142-android15-lts-AnyKernel3.zip"),
                    ),
            )
        assertEquals(
            "6.1.162-android14-2026-03-AnyKernel3.zip",
            (result as KernelSelectionResult.Selected).asset.name,
        )
    }

    @Test
    fun `selector reports compatible assets that would downgrade`() {
        val result =
            selectKernelAsset(
                "6.1.176-android14-Wild",
                "2.2.2",
                listOf(asset("6.1.175-android14-lts-AnyKernel3.zip")),
            )
        assertEquals(
            KernelSelectionFailure.ALL_COMPATIBLE_ASSETS_OLDER,
            (result as KernelSelectionResult.Failed).reason,
        )
    }

    @Test
    fun `selector rejects wrong Android generation`() {
        val result =
            selectKernelAsset(
                "6.1.157-android13-custom",
                "2.2.2",
                listOf(asset("6.1.162-android14-2026-03-AnyKernel3.zip")),
            )
        assertEquals(
            KernelSelectionFailure.NO_COMPATIBLE_ASSET,
            (result as KernelSelectionResult.Failed).reason,
        )
    }

    @Test
    fun `assets from installed and older releases are ignored`() {
        val result =
            selectKernelAsset(
                "6.1.157-android14-Wild",
                "2.3.0",
                listOf(asset("6.1.162-android14-2026-03-AnyKernel3.zip", "v2.3.0")),
            )
        assertEquals(
            KernelSelectionFailure.NO_COMPATIBLE_ASSET,
            (result as KernelSelectionResult.Failed).reason,
        )
    }

    @Test
    fun `version code conversion matches bridge shell logic`() {
        assertEquals(20202, versionTagToCode("v2.2.2"))
        assertEquals(20300, versionTagToCode("2.3.0"))
        assertTrue(versionTagToCode("v2.100.0") == null)
    }

    @Test
    fun `built-in metadata requires paired versions and bridge checksum`() {
        fun metadata(
            kernelVersion: String = "v2.3.0",
            installedVersion: String = "2.2.2",
        ) = validateBuiltInUpdateMetadataFields(
            bridgeVersion = "v2.3.0",
            bridgeVersionCode = 20300,
            bridgeZipUrl =
                "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.0/vpnhide-bridge.zip",
            bridgeSha256 = "a".repeat(64),
            kernelVersion = kernelVersion,
            kernelVersionCode = 20300,
            kernelReleasesApi =
                "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
            installedVersion = installedVersion,
        )
        val validMetadata = metadata()
        assertEquals("v2.3.0", validMetadata?.kernelVersion)
        assertTrue(metadata(installedVersion = "2.3.0") == null)
        assertTrue(metadata(kernelVersion = "v2.4.0") == null)
    }

    @Test
    fun `selector never crosses the version advertised by update metadata`() {
        val result =
            selectKernelAsset(
                "6.1.157-android14-Wild",
                "2.2.2",
                listOf(asset("6.1.158-android14-next-AnyKernel3.zip", "v2.4.0")),
                maximumVersion = "v2.3.0",
            )
        assertEquals(
            KernelSelectionFailure.NO_COMPATIBLE_ASSET,
            (result as KernelSelectionResult.Failed).reason,
        )
    }

    @Test
    fun `example AK3 archive validates and can be made non-interactive`() {
        val archive = File(System.getProperty("user.dir"), "../../ak3_kernel_example.zip").canonicalFile
        assertTrue("missing example archive: $archive", archive.isFile)
        assertEquals(true, validateKernelZip(archive))

        val prepared = Files.createTempFile("vpnhide-ak3-test-", ".zip").toFile()
        try {
            assertTrue(prepareNonInteractiveAk3(archive, prepared))
            assertEquals(true, validateKernelZip(prepared))
            ZipFile(prepared).use { zip ->
                val core =
                    zip
                        .getInputStream(zip.getEntry("tools/ak3-core.sh"))
                        .bufferedReader()
                        .readText()
                assertTrue(core.contains("VPNHIDE_IMAGE_MODE"))
                assertTrue(!Regex("(?m)^show_kernel_menu[ \\t]*$").containsMatchIn(core))
                assertShellSyntax(core)
            }
        } finally {
            prepared.delete()
        }
    }

    @Test
    fun `generated root scripts have valid shell syntax`() {
        assertShellSyntax(buildBootBackupScript("6.1.157-android14-11-gabcdef"))
        assertShellSyntax(buildBridgeInstallScript("/data/user/0/app/cache/bridge.zip", "2.3.0"))
        assertShellSyntax(buildKernelFlashScript("/data/user/0/app/cache/kernel.zip", "normal"))
        assertShellSyntax(buildKernelFlashScript("/data/user/0/app/cache/kernel.zip", "bypass"))
    }

    private fun assertShellSyntax(script: String) {
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
    }
}
