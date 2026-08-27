package dev.soranerai.vpnhidenext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    fun `selector accepts vendor suffix without android tag`() {
        val result =
            selectKernelAsset(
                runningRelease = "6.1.145+blue-spark",
                installedVersion = "2.2.2",
                assets =
                    listOf(
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
    fun `selector refuses to guess generation for untagged multi generation kernel`() {
        val result =
            selectKernelAsset(
                runningRelease = "5.10.250+blue-spark",
                installedVersion = "2.2.2",
                assets =
                    listOf(
                        asset("5.10.260-android12-lts-AnyKernel3.zip"),
                        asset("5.10.260-android13-lts-AnyKernel3.zip"),
                    ),
            )
        assertEquals(
            KernelSelectionFailure.NO_COMPATIBLE_ASSET,
            (result as KernelSelectionResult.Failed).reason,
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
            appVersion = "2.3.0",
        )
        val validMetadata = metadata()
        assertEquals("v2.3.0", validMetadata?.kernelVersion)
        assertTrue(metadata(installedVersion = "2.3.0") == null)
        assertTrue(metadata(kernelVersion = "v2.4.0") == null)
    }

    @Test
    fun `accepts bridge update when built-in kernel is already current`() {
        val metadata =
            validateBuiltInUpdateMetadataFields(
                bridgeVersion = "2.4.0",
                bridgeVersionCode = 20400,
                bridgeZipUrl =
                    "https://github.com/soranerai/vpnhide_next/releases/download/v2.4.0/vpnhide-bridge.zip",
                bridgeSha256 = "a".repeat(64),
                kernelVersion = "2.4.0",
                kernelVersionCode = 20400,
                kernelReleasesApi =
                    "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
                installedVersion = "2.4.0",
                installedBridgeVersion = "2.3.0",
                appVersion = "2.4.0",
            )

        assertEquals("2.4.0", metadata?.bridgeVersion)
    }

    @Test
    fun `accepts a newer bridge paired with a compatible unchanged built-in kernel`() {
        val metadata =
            validateBuiltInUpdateMetadataFields(
                bridgeVersion = "2.5.4",
                bridgeVersionCode = 20504,
                bridgeZipUrl =
                    "https://github.com/soranerai/vpnhide_next/releases/download/v2.5.4/vpnhide-bridge.zip",
                bridgeSha256 = "a".repeat(64),
                kernelVersion = "2.5.3",
                kernelVersionCode = 20503,
                kernelReleasesApi =
                    "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
                installedVersion = "2.5.3",
                installedBridgeVersion = "2.5.3",
                appVersion = "2.5.4",
            )

        assertEquals("2.5.4", metadata?.bridgeVersion)
        assertEquals("2.5.3", metadata?.kernelVersion)
    }

    @Test
    fun `built-in metadata from a newer matrix row is rejected for the current app`() {
        val metadata =
            validateBuiltInUpdateMetadataFields(
                bridgeVersion = "2.5.2",
                bridgeVersionCode = 20502,
                bridgeZipUrl =
                    "https://github.com/soranerai/vpnhide_next/releases/download/v2.5.2/vpnhide-bridge.zip",
                bridgeSha256 = "a".repeat(64),
                kernelVersion = "2.5.2",
                kernelVersionCode = 20502,
                kernelReleasesApi =
                    "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
                installedVersion = "2.5.1",
                appVersion = "2.5.1",
            )

        assertTrue(metadata == null)
    }

    @Test
    fun `debug metadata permits current version without checksum`() {
        val metadata =
            validateBuiltInUpdateMetadataFields(
                bridgeVersion = "v2.3.0",
                bridgeVersionCode = 20300,
                bridgeZipUrl =
                    "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.0/vpnhide-bridge.zip",
                bridgeSha256 = "",
                kernelVersion = "v2.3.0",
                kernelVersionCode = 20300,
                kernelReleasesApi =
                    "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
                installedVersion = "2.3.0",
                debugMode = true,
                appVersion = "2.3.0",
            )
        assertEquals("v2.3.0", metadata?.kernelVersion)
    }

    @Test
    fun `debug selector permits asset from current release`() {
        val result =
            selectKernelAsset(
                "6.1.157-android14-Wild",
                "2.3.0",
                listOf(asset("6.1.162-android14-2026-03-AnyKernel3.zip", "v2.3.0")),
                maximumVersion = "v2.3.0",
                allowCurrentVersion = true,
            )
        assertEquals(
            "6.1.162-android14-2026-03-AnyKernel3.zip",
            (result as KernelSelectionResult.Selected).asset.name,
        )
    }

    @Test
    fun `rapid tap unlocker requires ten consecutive fast taps`() {
        val unlocker = RapidTapUnlocker()
        repeat(9) { tap -> assertTrue(!unlocker.recordTap(tap * 100L)) }
        assertTrue(unlocker.recordTap(900L))

        repeat(5) { tap -> assertTrue(!unlocker.recordTap(2_000L + tap * 100L)) }
        assertTrue(!unlocker.recordTap(3_500L))
        repeat(8) { tap -> assertTrue(!unlocker.recordTap(3_600L + tap * 100L)) }
        assertTrue(unlocker.recordTap(4_400L))
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
        assumeTrue("optional AK3 example archive is not present: $archive", archive.isFile)
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
    fun `kernel validator accepts standard explicit directory entries`() {
        val archive = Files.createTempFile("vpnhide-ak3-directories-", ".zip").toFile()
        try {
            ZipOutputStream(archive.outputStream()).use { output ->
                listOf("tools/", "META-INF/", "META-INF/com/", "META-INF/com/google/", "META-INF/com/google/android/")
                    .forEach { directory ->
                        output.putNextEntry(ZipEntry(directory))
                        output.closeEntry()
                    }
                REQUIRED_KERNEL_TEST_ENTRIES.forEach { (name, contents) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(contents.toByteArray())
                    output.closeEntry()
                }
            }
            assertEquals(false, validateKernelZip(archive))
        } finally {
            archive.delete()
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

    private companion object {
        val REQUIRED_KERNEL_TEST_ENTRIES =
            mapOf(
                "Image" to "image",
                "anykernel.sh" to "#!/system/bin/sh",
                "tools/ak3-core.sh" to "show_kernel_menu",
                "tools/busybox" to "busybox",
                "tools/magiskboot" to "magiskboot",
                "META-INF/com/google/android/update-binary" to "#!/system/bin/sh",
            )
    }
}
