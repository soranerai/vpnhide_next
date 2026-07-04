package dev.soranerai.vpnhidenext.domain.models

sealed interface ModuleState {
    data object NotInstalled : ModuleState

    data class Installed(
        val version: String?,
        val active: Boolean,
        val targetCount: Int,
        val gkiVariant: String? = null,
        val brokenReason: KmodBrokenReason? = null,
    ) : ModuleState
}

enum class KmodBrokenReason {
    WrongVariant,
    UnsupportedKernel,
    MissingKprobes,
    UnknownVariantInactive,
    AmbiguousLoadFailed,
}

sealed interface LsposedState {
    data object NotInstalled : LsposedState

    data class InstalledInactive(
        val version: String?,
    ) : LsposedState

    data class Active(
        val version: String?,
        val targetCount: Int,
    ) : LsposedState
}

sealed interface ProtectionCheck {
    data object NeedsRestart : ProtectionCheck

    data class Checked(
        val native: NativeResult,
        val java: JavaResult,
    ) : ProtectionCheck
}

sealed interface NativeResult {
    data object Checking : NativeResult

    data class Ok(
        val passed: Int,
        val total: Int,
    ) : NativeResult

    data class Partial(
        val passed: Int,
        val total: Int,
    ) : NativeResult

    data class Fail(
        val passed: Int,
        val total: Int,
    ) : NativeResult

    data object NoModule : NativeResult

    data object VpnOff : NativeResult
}

sealed interface JavaResult {
    data object Checking : JavaResult

    data class Ok(
        val passed: Int,
        val total: Int,
    ) : JavaResult

    data class Partial(
        val passed: Int,
        val total: Int,
    ) : JavaResult

    data class Fail(
        val passed: Int,
        val total: Int,
    ) : JavaResult

    data object HooksInactive : JavaResult

    data object VpnOff : JavaResult
}

enum class IssueSeverity {
    ERROR,
    WARNING,
}

data class Issue(
    val severity: IssueSeverity,
    val text: String,
)

data class AppInterceptStats(
    val packageName: String,
    val appLabel: String,
    val frameworkTotal: Int,
    val nativeTotal: Int,
    val frameworkBreakdown: Map<String, Int>,
    val nativeBreakdown: Map<String, Int>,
    val userId: Int = 0,
    val uid: Int = 0,
)

data class DashboardState(
    val kmod: ModuleState,
    val lsposed: LsposedState,
    val nativeInstallRecommendation: NativeInstallRecommendation?,
    val kmodLoadStatus: KmodLoadStatus?,
    val protection: ProtectionCheck,
    val issues: List<Issue>,
)

data class NativeInstallRecommendation(
    val androidVersion: String,
    val kernelVersion: String,
    val kernelBranch: String?,
    val recommendedArtifact: String,
    val recommendedGkiVariant: String?,
    val preferKmod: Boolean,
    val variantAmbiguous: Boolean = false,
    val alternativeArtifact: String? = null,
    val alternativeGkiVariant: String? = null,
)

data class KmodLoadStatus(
    val timestamp: Long?,
    val bootId: String?,
    val unameR: String?,
    val gkiVariant: String?,
    val kmodVersion: String?,
    val rootManager: String?,
    val kprobes: String?,
    val kretprobes: String?,
    val insmodExit: Int?,
    val loaded: Boolean,
    val insmodStderr: String?,
    val dmesgTail: String?,
    val freshForCurrentBoot: Boolean,
)

enum class NativeModuleKind {
    Kmod,
}

data class ModuleMismatch(
    val kind: NativeModuleKind,
    val moduleVersion: String,
    val appVersion: String,
)
