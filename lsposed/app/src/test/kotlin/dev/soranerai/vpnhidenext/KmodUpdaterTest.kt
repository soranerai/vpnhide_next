package dev.soranerai.vpnhidenext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KmodUpdaterTest {
    private val checksum = "a".repeat(64)

    private fun fields(
        version: String = "2.3.0",
        kmi: String = "android14-6.1",
        sha256: String = checksum,
        url: String = "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.0/vpnhide-kmod-$kmi.zip",
        installedVersion: String = "2.2.2",
    ): KmodUpdateInfo? = validateKmodUpdateFields("v$version", 20300, url, sha256, kmi, installedVersion, version)

    @Test
    fun `valid newer metadata is accepted`() {
        val info = fields()!!
        assertEquals("v2.3.0", info.version)
        assertEquals("android14-6.1", info.kmi)
        assertEquals(checksum, info.sha256)
    }

    @Test
    fun `same and older releases are ignored`() {
        assertNull(fields(version = "2.3.0", installedVersion = "2.3.0"))
        assertNull(fields(version = "2.2.2", installedVersion = "2.3.0"))
    }

    @Test
    fun `metadata without a valid checksum is rejected`() {
        assertNull(fields(sha256 = ""))
        assertNull(fields(sha256 = "abc"))
    }

    @Test
    fun `download must be the exact artifact in the official release path`() {
        assertTrue(
            isTrustedKmodDownloadUrl(
                "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.0/vpnhide-kmod-android14-6.1.zip",
                "vpnhide-kmod-android14-6.1.zip",
                "2.3.0",
            ),
        )
        assertFalse(
            isTrustedKmodDownloadUrl(
                "https://example.com/vpnhide-kmod-android14-6.1.zip",
                "vpnhide-kmod-android14-6.1.zip",
                "2.3.0",
            ),
        )
        assertFalse(
            isTrustedKmodDownloadUrl(
                "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.0/vpnhide-kmod-android15-6.6.zip",
                "vpnhide-kmod-android14-6.1.zip",
                "2.3.0",
            ),
        )
        assertFalse(
            isTrustedKmodDownloadUrl(
                "https://github.com/soranerai/vpnhide_next/releases/download/v2.3.1/vpnhide-kmod-android14-6.1.zip",
                "vpnhide-kmod-android14-6.1.zip",
                "2.3.0",
            ),
        )
    }

    @Test
    fun `installed variant is reused on an ambiguous custom kernel`() {
        val target = resolveKmodUpdateTarget("2.2.2", "android13-5.10", "5.10.250-custom")
        assertEquals(KmodUpdateTarget("2.2.2", "android13-5.10"), target)
    }

    @Test
    fun `exact kernel mismatch blocks automatic update`() {
        assertNull(resolveKmodUpdateTarget("2.2.2", "android13-5.10", "5.10.250-android12-g123"))
    }

    @Test
    fun `exact uname supplies variant for legacy module prop`() {
        val target = resolveKmodUpdateTarget("v2.2.2", null, "6.1.80-android14-g123")
        assertEquals(KmodUpdateTarget("v2.2.2", "android14-6.1"), target)
    }

    @Test
    fun `vendor suffix supplies deterministic variant for legacy module prop`() {
        val target = resolveKmodUpdateTarget("v2.2.2", null, "6.1.145+blue-spark")
        assertEquals(KmodUpdateTarget("v2.2.2", "android14-6.1"), target)
    }

    @Test
    fun `installed older kmod remains eligible for update`() {
        val target = resolveKmodUpdateTarget("2.5.1", "android14-6.1", "6.1.80-android14-g123")
        assertEquals(KmodUpdateTarget("2.5.1", "android14-6.1"), target)
    }

    @Test
    fun `kmod from a newer matrix row is rejected for the current app`() {
        assertNull(
            validateKmodUpdateFields(
                version = "v2.5.2",
                versionCode = 20502,
                zipUrl = "https://github.com/soranerai/vpnhide_next/releases/download/v2.5.2/vpnhide-kmod-android14-6.1.zip",
                sha256 = checksum,
                kmi = "android14-6.1",
                installedVersion = "2.5.1",
                appVersion = "2.5.1",
            ),
        )
    }
}
