package dev.soranerai.vpnhidenext.domain.usecase

import dev.soranerai.vpnhidenext.domain.models.ModuleMismatch
import dev.soranerai.vpnhidenext.domain.models.ModuleState
import dev.soranerai.vpnhidenext.domain.models.NativeInstallRecommendation
import dev.soranerai.vpnhidenext.domain.models.NativeModuleKind
import dev.soranerai.vpnhidenext.versionsMismatch

internal fun parseKernelSeries(raw: String): String? = Regex("""\b(\d+\.\d+)""").find(raw)?.groupValues?.get(1)

internal fun parseKernelAndroidBranch(raw: String): String? =
    Regex("""android(\d+)""")
        .find(raw)
        ?.groupValues
        ?.get(1)
        ?.let { "Android $it" }

/**
 * Pick the right native-module artifact for the device based on its kernel version (from `uname
 * -r`) and Android OS label (from `Build.VERSION.RELEASE`).
 */
internal fun buildNativeInstallRecommendation(
    kernelRaw: String,
    deviceAndroidLabel: String,
): NativeInstallRecommendation? {
    val kernelVersion =
        kernelRaw.trim().ifBlank {
            return null
        }
    val kernelSeries = parseKernelSeries(kernelVersion)
    val kernelBranch = parseKernelAndroidBranch(kernelVersion) // GKI KMI

    data class KmiMatch(
        val kmi: String,
        val zip: String,
    )

    val exact: KmiMatch? =
        when (kernelBranch to kernelSeries) {
            "Android 12" to "5.10" -> {
                KmiMatch("android12-5.10", "vpnhide-kmod-android12-5.10.zip")
            }

            "Android 13" to "5.10" -> {
                KmiMatch("android13-5.10", "vpnhide-kmod-android13-5.10.zip")
            }

            "Android 13" to "5.15" -> {
                KmiMatch("android13-5.15", "vpnhide-kmod-android13-5.15.zip")
            }

            "Android 14" to "5.15" -> {
                KmiMatch("android14-5.15", "vpnhide-kmod-android14-5.15.zip")
            }

            "Android 14" to "6.1" -> {
                KmiMatch("android14-6.1", "vpnhide-kmod-android14-6.1.zip")
            }

            "Android 15" to "6.6" -> {
                KmiMatch("android15-6.6", "vpnhide-kmod-android15-6.6.zip")
            }

            "Android 16" to "6.12" -> {
                KmiMatch("android16-6.12", "vpnhide-kmod-android16-6.12.zip")
            }

            else -> {
                null
            }
        }
    if (exact != null) {
        return NativeInstallRecommendation(
            androidVersion = deviceAndroidLabel,
            kernelVersion = kernelVersion,
            kernelBranch = kernelBranch,
            recommendedArtifact = exact.zip,
            recommendedGkiVariant = exact.kmi,
            preferKmod = true,
        )
    }

    val fallback: Pair<KmiMatch, KmiMatch?>? =
        when (kernelSeries) {
            "5.10" -> {
                KmiMatch("android12-5.10", "vpnhide-kmod-android12-5.10.zip") to
                    KmiMatch("android13-5.10", "vpnhide-kmod-android13-5.10.zip")
            }

            "5.15" -> {
                KmiMatch("android13-5.15", "vpnhide-kmod-android13-5.15.zip") to
                    KmiMatch("android14-5.15", "vpnhide-kmod-android14-5.15.zip")
            }

            "6.1" -> {
                KmiMatch("android14-6.1", "vpnhide-kmod-android14-6.1.zip") to null
            }

            "6.6" -> {
                KmiMatch("android15-6.6", "vpnhide-kmod-android15-6.6.zip") to null
            }

            "6.12" -> {
                KmiMatch("android16-6.12", "vpnhide-kmod-android16-6.12.zip") to null
            }

            else -> {
                null
            }
        }
    if (fallback != null) {
        val (primary, alternative) = fallback
        return NativeInstallRecommendation(
            androidVersion = deviceAndroidLabel,
            kernelVersion = kernelVersion,
            kernelBranch = kernelBranch,
            recommendedArtifact = primary.zip,
            recommendedGkiVariant = primary.kmi,
            preferKmod = true,
            variantAmbiguous = alternative != null,
            alternativeArtifact = alternative?.zip,
            alternativeGkiVariant = alternative?.kmi,
        )
    }

    return null
}

fun detectModuleMismatches(
    modules: List<Pair<ModuleState, NativeModuleKind>>,
    appVersion: String,
): List<ModuleMismatch> =
    modules.mapNotNull { (state, kind) ->
        val installed = state as? ModuleState.Installed ?: return@mapNotNull null
        val moduleVersion = installed.version ?: return@mapNotNull null
        if (versionsMismatch(moduleVersion, appVersion)) {
            ModuleMismatch(kind, moduleVersion, appVersion)
        } else {
            null
        }
    }
