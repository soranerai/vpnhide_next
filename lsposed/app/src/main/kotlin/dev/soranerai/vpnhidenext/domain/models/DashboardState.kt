package dev.soranerai.vpnhidenext.domain.models

sealed interface ModuleState {
    data object NotInstalled : ModuleState

    data class Installed(
        val version: String?,
        val active: Boolean,
        val targetCount: Int,
        val gkiVariant: String? = null,
        val brokenReason: KmodBrokenReason? = null,
        val isKmodType: Boolean = true,
        val bridgeVersion: String? = null,
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
    val portsCount: Int = 0,
    val portAccesses: List<PortAccess> = emptyList(),
    val userId: Int = 0,
    val uid: Int = 0,
)

data class PortAccess(
    val port: Int,
    val protocol: String,
    val count: Int,
)

data class HookCount(
    val name: String,
    val count: Int,
)

data class InterceptStatsSummary(
    val totalIntercepts: Int,
    val nativeTotal: Int,
    val lsposedTotal: Int,
    val portsTotal: Int,
    val topFrameworkHooks: List<HookCount>,
    val topNativeHooks: List<HookCount>,
)

fun List<AppInterceptStats>.summarize(topHooksLimit: Int = 5): InterceptStatsSummary {
    val portsTotal = sumOf { it.portsCount }
    val nativeTotal = sumOf { it.nativeTotal }
    val lsposedTotal = sumOf { it.frameworkTotal }

    val frameworkCounts = mutableMapOf<String, Int>()
    val nativeCounts = mutableMapOf<String, Int>()
    forEach { app ->
        app.frameworkBreakdown.forEach { (hook, count) -> frameworkCounts[hook] = (frameworkCounts[hook] ?: 0) + count }
        app.nativeBreakdown.forEach { (hook, count) -> nativeCounts[hook] = (nativeCounts[hook] ?: 0) + count }
    }
    val topFrameworkHooks =
        frameworkCounts.entries
            .sortedByDescending { it.value }
            .take(topHooksLimit)
            .map { HookCount(it.key, it.value) }

    val topNativeHooks =
        nativeCounts.entries
            .sortedByDescending { it.value }
            .take(topHooksLimit)
            .map { HookCount(it.key, it.value) }

    return InterceptStatsSummary(
        totalIntercepts = nativeTotal + lsposedTotal + portsTotal,
        nativeTotal = nativeTotal,
        lsposedTotal = lsposedTotal,
        portsTotal = portsTotal,
        topFrameworkHooks = topFrameworkHooks,
        topNativeHooks = topNativeHooks,
    )
}

data class DashboardState(
    val kmod: ModuleState,
    val lsposed: LsposedState,
    val diagnostics: BackendDiagnostics,
    val kernelVersion: String?,
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
    val runtimeVersion: String?,
    val provider: String?,
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
