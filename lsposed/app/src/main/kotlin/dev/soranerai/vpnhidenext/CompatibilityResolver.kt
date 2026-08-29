package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.generated.CompatibleRelease
import dev.soranerai.vpnhidenext.generated.compatibilityMatrix

internal data class InstalledComponentVersions(
    val lsposed: String?,
    val bridge: String?,
    val builtIn: String?,
    val kmod: String?,
)

internal sealed interface CompatibilityResult {
    data object Compatible : CompatibilityResult

    data object Unknown : CompatibilityResult

    data class Requires(
        val component: String,
        val version: String,
    ) : CompatibilityResult
}

/** Resolves compatibility using explicit known-good component combinations. */
internal object CompatibilityResolver {
    fun resolve(installed: InstalledComponentVersions): CompatibilityResult {
        val lsposed = installed.lsposed ?: return CompatibilityResult.Unknown
        val matchingApp = compatibleReleasesForApp(lsposed)
        if (matchingApp.isEmpty()) return CompatibilityResult.Unknown

        val bridge = installed.bridge
        val native = installed.kmod ?: installed.builtIn ?: return CompatibilityResult.Unknown
        val compatible =
            matchingApp.filter {
                (bridge == null || baseVersion(it.bridge) == baseVersion(bridge)) &&
                    nativeMatches(it, installed, native)
            }
        if (compatible.isNotEmpty()) return CompatibilityResult.Compatible

        // Keep every valid matrix row in play. If one installed component
        // identifies a row, request its paired counterpart rather than the
        // first row for this APK version.
        val nativeMatches =
            matchingApp.filter {
                nativeMatches(it, installed, native)
            }
        if (bridge != null && nativeMatches.isNotEmpty()) {
            return CompatibilityResult.Requires("bridge", nativeMatches.first().bridge)
        }

        val bridgeMatches =
            bridge
                ?.let { value -> matchingApp.filter { baseVersion(it.bridge) == baseVersion(value) } }
                .orEmpty()
        val expected = (bridgeMatches.ifEmpty { matchingApp }).first()

        return if (installed.kmod != null) {
            CompatibilityResult.Requires("kmod", expected.kmod)
        } else {
            CompatibilityResult.Requires("built-in", expected.builtIn)
        }
    }

    fun compatibleReleasesForApp(appVersion: String): List<CompatibleRelease> =
        compatibilityMatrix.filter { baseVersion(it.lsposed) == baseVersion(appVersion) }

    fun isKmodCompatibleWithApp(
        appVersion: String,
        kmodVersion: String,
    ): Boolean = compatibleReleasesForApp(appVersion).any { baseVersion(it.kmod) == baseVersion(kmodVersion) }

    fun isBuiltInCompatibleWithApp(
        appVersion: String,
        bridgeVersion: String,
        builtInVersion: String,
    ): Boolean =
        compatibleReleasesForApp(appVersion).any {
            baseVersion(it.bridge) == baseVersion(bridgeVersion) &&
                baseVersion(it.builtIn) == baseVersion(builtInVersion)
        }

    private fun nativeMatches(
        release: CompatibleRelease,
        installed: InstalledComponentVersions,
        native: String,
    ): Boolean =
        if (installed.kmod != null) {
            baseVersion(release.kmod) == baseVersion(native)
        } else {
            baseVersion(release.builtIn) == baseVersion(native)
        }
}
