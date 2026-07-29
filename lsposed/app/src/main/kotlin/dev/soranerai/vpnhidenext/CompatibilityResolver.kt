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
        val matchingApp = compatibilityMatrix.filter { baseVersion(it.lsposed) == baseVersion(lsposed) }
        if (matchingApp.isEmpty()) return CompatibilityResult.Unknown

        val bridge = installed.bridge
        if (bridge != null && matchingApp.none { baseVersion(it.bridge) == baseVersion(bridge) }) {
            return CompatibilityResult.Requires("bridge", matchingApp.first().bridge)
        }

        val native = installed.kmod ?: installed.builtIn ?: return CompatibilityResult.Unknown
        val nativeMatches =
            matchingApp.filter {
                baseVersion(it.kmod) == baseVersion(native) ||
                    baseVersion(it.builtIn) == baseVersion(native)
            }
        if (nativeMatches.isNotEmpty()) return CompatibilityResult.Compatible

        val expected = matchingApp.first()
        return if (installed.kmod != null) {
            CompatibilityResult.Requires("kmod", expected.kmod)
        } else {
            CompatibilityResult.Requires("built-in", expected.builtIn)
        }
    }

    fun expectedForApp(appVersion: String): CompatibleRelease? =
        compatibilityMatrix.firstOrNull { baseVersion(it.lsposed) == baseVersion(appVersion) }
}
