package dev.soranerai.vpnhidenext.data.repository

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.soranerai.vpnhidenext.AppListCache
import dev.soranerai.vpnhidenext.BuildConfig
import dev.soranerai.vpnhidenext.CompatibilityResolver
import dev.soranerai.vpnhidenext.CompatibilityResult
import dev.soranerai.vpnhidenext.DEV_NODE
import dev.soranerai.vpnhidenext.DiagnosticsCache
import dev.soranerai.vpnhidenext.InstalledComponentVersions
import dev.soranerai.vpnhidenext.KMOD_LOAD_DMESG_FILE
import dev.soranerai.vpnhidenext.KMOD_LOAD_STATUS_FILE
import dev.soranerai.vpnhidenext.KmodStatsClient
import dev.soranerai.vpnhidenext.KmodStatsResponse
import dev.soranerai.vpnhidenext.R
import dev.soranerai.vpnhidenext.VpnHideLog
import dev.soranerai.vpnhidenext.compareSemver
import dev.soranerai.vpnhidenext.effectiveLayerTargetUidCount
import dev.soranerai.vpnhidenext.domain.models.*
import dev.soranerai.vpnhidenext.domain.usecase.buildNativeInstallRecommendation
import dev.soranerai.vpnhidenext.isEnabledInPrefs
import dev.soranerai.vpnhidenext.kmodCtl
import dev.soranerai.vpnhidenext.kmodModuleDir
import dev.soranerai.vpnhidenext.normalizeVersion
import dev.soranerai.vpnhidenext.suExec
import dev.soranerai.vpnhidenext.versionsMismatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "VpnHide-Repository"

private sealed interface LsposedRuntime {
    data object Inactive : LsposedRuntime

    data class Active(
        val version: String?,
    ) : LsposedRuntime
}

private fun parseKeyValue(text: String): Map<String, String> =
    text
        .lines()
        .mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

private fun decodeBase64String(encoded: String): String =
    try {
        if (encoded.isBlank()) {
            ""
        } else {
            String(Base64.decode(encoded, Base64.DEFAULT))
        }
    } catch (_: Exception) {
        ""
    }

private fun Long.saturatingInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private data class RawDashboardSnapshot(
    val props: Map<String, String>,
) {
    fun get(key: String): String = props[key] ?: ""

    fun decodeBase64(key: String): String = decodeBase64String(get(key))
}

class DashboardRepository(
    private val context: Context,
) {
    internal var lastKmodStatsResponse: KmodStatsResponse? = null

    private val res = context.resources
    private val selfPkg = context.packageName

    private fun collectDashboardSnapshot(): RawDashboardSnapshot {
        val script =
            """
            id | grep -q 'uid=0' && echo "root=1" || echo "root=0"
            echo "kmod_prop=${'$'}(cat $kmodModuleDir/module.prop 2>/dev/null | base64 | tr -d '\n')"
            echo "uname=${'$'}(uname -r)"
            echo "boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id)"
            echo "load_status=${'$'}(cat $KMOD_LOAD_STATUS_FILE 2>/dev/null | base64 | tr -d '\n')"
            echo "load_dmesg=${'$'}(cat $KMOD_LOAD_DMESG_FILE 2>/dev/null | tail -n 50 | base64 | tr -d '\n')"
            # module.prop proves only that a bridge package is present. It
            # must never be used as proof that the kernel backend is active.
            BRIDGE_KMOD=0
            BRIDGE_KPATCH=0
            BRIDGE_VALID=0
            BRIDGE_DISABLED=0
            for d in /data/adb/modules/vpnhide_kmod /data/adb/modules_update/vpnhide_kmod; do
              if [ -f "${'$'}d/module.prop" ]; then
                BRIDGE_KMOD=1
                [ -f "${'$'}d/disable" ] && BRIDGE_DISABLED=1
                [ -x "${'$'}d/vpnhide-ctl" ] && BRIDGE_VALID=1
              fi
            done
            for d in /data/adb/modules/vpnhide_kpatch /data/adb/modules_update/vpnhide_kpatch; do
              if [ -f "${'$'}d/module.prop" ]; then
                BRIDGE_KPATCH=1
                [ -f "${'$'}d/disable" ] && BRIDGE_DISABLED=1
                [ -x "${'$'}d/vpnhide-ctl" ] && BRIDGE_VALID=1
              fi
            done
            echo "bridge_kmod=${'$'}BRIDGE_KMOD"
            echo "bridge_kpatch=${'$'}BRIDGE_KPATCH"
            echo "bridge_valid=${'$'}BRIDGE_VALID"
            echo "bridge_disabled=${'$'}BRIDGE_DISABLED"
            [ -c $DEV_NODE ] && echo "ctrl_device=1" || echo "ctrl_device=0"
            [ -x $kmodCtl ] && echo "ctrl_tool=1" || echo "ctrl_tool=0"
            if [ -c $DEV_NODE ] && [ -x $kmodCtl ] && $kmodCtl version kmod >/dev/null 2>&1; then
              echo "ctrl_responding=1"
              echo "device_version=${'$'}($kmodCtl version kmod 2>/dev/null || true)"
            else
              echo "ctrl_responding=0"
            fi
            grep -q "vpnhide" /proc/modules 2>/dev/null && echo "is_kmod=1" || echo "is_kmod=0"
            
            """.trimIndent()

        val (_, out) = suExec(script)
        return RawDashboardSnapshot(parseKeyValue(out))
    }

    private data class ModulePropInfo(
        val installed: Boolean,
        val version: String?,
        val gkiVariant: String?,
    )

    private val updateJsonKmiRegex = Regex("""update-kmod-([^/]+)\.json""")

    private fun parseModuleProp(raw: String?): ModulePropInfo {
        if (raw == null || raw.isBlank()) return ModulePropInfo(false, null, null)
        var version: String? = null
        var gkiVariant: String? = null
        var updateJsonKmi: String? = null
        for (line in raw.lines()) {
            when {
                line.startsWith("version=") -> {
                    version = normalizeVersion(line.removePrefix("version="))
                }

                line.startsWith("gkiVariant=") -> {
                    gkiVariant = line.removePrefix("gkiVariant=").trim().ifBlank { null }
                }

                line.startsWith("updateJson=") -> {
                    updateJsonKmi =
                        updateJsonKmiRegex
                            .find(line.removePrefix("updateJson="))
                            ?.groupValues
                            ?.get(1)
                }
            }
        }
        return ModulePropInfo(true, version, gkiVariant ?: updateJsonKmi)
    }

    private fun buildModuleVersionIssue(
        moduleVersion: String,
        appVersion: String,
        component: String = "kmod",
    ): String {
        val normalizedModuleVersion = normalizeVersion(moduleVersion)
        val normalizedAppVersion = normalizeVersion(appVersion)
        val componentName = if (component == "bridge") "bridge" else "kmod"
        return when (compareSemver(normalizedModuleVersion, normalizedAppVersion)) {
            null, 0 -> {
                if (componentName == "bridge") {
                    res.getString(R.string.dashboard_issue_bridge_version_mismatch, moduleVersion, appVersion)
                } else {
                    res.getString(R.string.dashboard_issue_kmod_version_mismatch, moduleVersion, appVersion)
                }
            }

            in Int.MIN_VALUE..-1 -> {
                if (componentName == "bridge") {
                    res.getString(R.string.dashboard_issue_update_bridge, moduleVersion, appVersion)
                } else {
                    res.getString(R.string.dashboard_issue_update_kmod, moduleVersion, appVersion)
                }
            }

            else -> {
                if (componentName == "bridge") {
                    res.getString(R.string.dashboard_issue_update_app_for_bridge, moduleVersion, appVersion)
                } else {
                    res.getString(R.string.dashboard_issue_update_app_for_kmod, moduleVersion, appVersion)
                }
            }
        }
    }

    private fun androidMajorVersionLabel(): String {
        @Suppress("DEPRECATION")
        val release =
            if (Build.VERSION.SDK_INT >= 30) {
                Build.VERSION.RELEASE_OR_CODENAME
            } else {
                Build.VERSION.RELEASE
            }.substringBefore('.')
        return "Android $release"
    }

    private fun readKmodLoadStatus(
        snapshot: RawDashboardSnapshot,
        currentBootId: String,
    ): KmodLoadStatus? {
        val raw = snapshot.decodeBase64("load_status")
        if (raw.isBlank()) return null
        val props = parseKeyValue(raw)
        val dmesgRaw = snapshot.decodeBase64("load_dmesg")
        val bootId = props["boot_id"]?.trim()
        val deviceVersion =
            snapshot
                .get("device_version")
                .trim()
                .toIntOrNull()
                ?.let(::versionCodeToVersion)
        return KmodLoadStatus(
            timestamp = props["timestamp"]?.trim()?.toLongOrNull(),
            bootId = bootId,
            unameR = props["uname_r"]?.trim(),
            gkiVariant = props["gki_variant"]?.trim()?.ifBlank { null },
            kmodVersion = props["kmod_version"]?.trim()?.ifBlank { null },
            runtimeVersion = deviceVersion ?: props["runtime_version"]?.trim()?.ifBlank { null },
            provider = props["provider"]?.trim()?.ifBlank { null },
            rootManager = props["root_manager"]?.trim()?.ifBlank { null },
            kprobes = props["kprobes"]?.trim()?.ifBlank { null },
            kretprobes = props["kretprobes"]?.trim()?.ifBlank { null },
            insmodExit = props["insmod_exit"]?.trim()?.toIntOrNull(),
            loaded = props["loaded"]?.trim() == "1",
            insmodStderr = props["insmod_stderr"]?.trim()?.ifBlank { null },
            dmesgTail = dmesgRaw.trim().ifBlank { null },
            freshForCurrentBoot = bootId != null && bootId == currentBootId,
        )
    }

    private fun versionCodeToVersion(code: Int): String? {
        if (code < 10000) return null
        val major = code / 10000
        val minor = (code / 100) % 100
        val patch = code % 100
        return "$major.$minor.$patch"
    }

    suspend fun loadDashboardState(selfNeedsRestart: Boolean): DashboardState =
        withContext(Dispatchers.IO) {
            val issues = mutableListOf<Issue>()

            fun err(text: String) {
                issues += Issue(IssueSeverity.ERROR, text)
            }

            fun warn(text: String) {
                issues += Issue(IssueSeverity.WARNING, text)
            }

            val startTime = System.currentTimeMillis()
            VpnHideLog.i(TAG, "=== Loading dashboard state ===")
            val snapshot = collectDashboardSnapshot()
            val currentBootId = snapshot.get("boot_id")
            val db =
                dev.soranerai.vpnhidenext.db.AppDatabase
                    .getInstance(context)
            val appsSync = db.appDao().getAllAppProtectionSync()
            val policyMode =
                db.globalConfigDao().getConfig()?.listMode
                    ?: dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST
            val installedApps =
                if (selfNeedsRestart) {
                    AppListCache.apps.value.orEmpty()
                } else {
                    AppListCache.apps.first { it != null }.orEmpty()
                }

            // kmod
            val kmodProp = parseModuleProp(snapshot.decodeBase64("kmod_prop"))
            val hasKmodDevice = snapshot.get("ctrl_device") == "1"
            val isKmodType = snapshot.get("is_kmod") == "1"
            val isKmodInstalled =
                kmodProp.installed ||
                    hasKmodDevice ||
                    snapshot.get("bridge_kmod") == "1" ||
                    snapshot.get("bridge_kpatch") == "1"
            val kmodActive = hasKmodDevice
            val kmodLoadStatus = readKmodLoadStatus(snapshot, currentBootId.trim())
            val kmodTargetCount =
                if (isKmodInstalled) {
                    effectiveLayerTargetUidCount(
                        installedApps = installedApps,
                        configured = appsSync,
                        listMode = policyMode,
                        selfPackage = selfPkg,
                        isListed = { it.kmod },
                    )
                } else {
                    0
                }
            val kmodRaw: ModuleState =
                if (isKmodInstalled) {
                    ModuleState.Installed(
                        // The module.prop version belongs to the installed
                        // bridge package. Prefer the version reported by the
                        // running kernel module through /dev.
                        version = kmodLoadStatus?.runtimeVersion ?: kmodProp.version,
                        active = kmodActive,
                        targetCount = kmodTargetCount,
                        gkiVariant = kmodProp.gkiVariant,
                        isKmodType = isKmodType,
                        bridgeVersion = kmodProp.version.takeIf { !isKmodType },
                    )
                } else {
                    ModuleState.NotInstalled
                }
            VpnHideLog.i(TAG, "kmodRaw: $kmodRaw (isKmodType=$isKmodType)")

            val kernelRaw = snapshot.get("uname")
            val kernelRecommendation =
                buildNativeInstallRecommendation(kernelRaw, androidMajorVersionLabel())
            VpnHideLog.i(TAG, "kmodLoadStatus=$kmodLoadStatus")

            val recommendedKmi = kernelRecommendation?.recommendedGkiVariant
            val kmodVariantMismatch =
                kmodRaw is ModuleState.Installed &&
                    !kmodRaw.active &&
                    kernelRecommendation?.preferKmod == true &&
                    recommendedKmi != null &&
                    kmodRaw.gkiVariant != null &&
                    kmodRaw.gkiVariant != recommendedKmi &&
                    kmodRaw.gkiVariant != kernelRecommendation.alternativeGkiVariant
            val kmodUnknownVariantBroken =
                kmodRaw is ModuleState.Installed &&
                    !kmodRaw.active &&
                    kmodRaw.gkiVariant == null &&
                    kernelRecommendation?.preferKmod == true
            val kmodOnUnsupportedKernel =
                kmodRaw is ModuleState.Installed &&
                    !kmodRaw.active &&
                    kernelRecommendation != null &&
                    !kernelRecommendation.preferKmod
            val kmodAmbiguousLoadFailed =
                kmodRaw is ModuleState.Installed &&
                    !kmodRaw.active &&
                    kmodLoadStatus?.freshForCurrentBoot == true &&
                    kernelRecommendation?.variantAmbiguous == true &&
                    kmodRaw.gkiVariant != null &&
                    (
                        kmodRaw.gkiVariant == kernelRecommendation.recommendedGkiVariant ||
                            kmodRaw.gkiVariant == kernelRecommendation.alternativeGkiVariant
                    )
            val kprobesMissing =
                kmodLoadStatus?.freshForCurrentBoot == true && kmodLoadStatus.kretprobes == "n"
            val kmodBrokenReason: KmodBrokenReason? =
                when {
                    kmodRaw !is ModuleState.Installed -> null
                    kprobesMissing -> KmodBrokenReason.MissingKprobes
                    kmodOnUnsupportedKernel -> KmodBrokenReason.UnsupportedKernel
                    kmodVariantMismatch -> KmodBrokenReason.WrongVariant
                    kmodUnknownVariantBroken -> KmodBrokenReason.UnknownVariantInactive
                    kmodAmbiguousLoadFailed -> KmodBrokenReason.AmbiguousLoadFailed
                    else -> null
                }
            val kmod: ModuleState =
                if (kmodRaw is ModuleState.Installed && kmodBrokenReason != null) {
                    kmodRaw.copy(brokenReason = kmodBrokenReason)
                } else {
                    kmodRaw
                }
            VpnHideLog.i(TAG, "kmod (with brokenReason): $kmod")

            val (_, hookStatusRaw) =
                if (snapshot.get("ctrl_responding") == "1") {
                    suExec("$kmodCtl hook_status 2>/dev/null || true")
                } else {
                    1 to ""
                }
            val hookProps = parseKeyValue(hookStatusRaw)
            val hookBootId = hookProps["boot_id"]
            val hooksActiveThisBoot = hookBootId != null && hookBootId == currentBootId.trim()
            var diagnostics =
                BackendDiagnosticsEvaluator.evaluate(
                    BackendProbeFacts(
                        root = snapshot.get("root") == "1",
                        controlDevice = snapshot.get("ctrl_device") == "1",
                        controlTool = snapshot.get("ctrl_tool") == "1",
                        controlToolResponding = snapshot.get("ctrl_responding") == "1",
                        bridgeKmod = snapshot.get("bridge_kmod") == "1",
                        bridgeKpatch = snapshot.get("bridge_kpatch") == "1",
                        bridgeValid = snapshot.get("bridge_valid") == "1",
                        bridgeDisabled = snapshot.get("bridge_disabled") == "1",
                        loadedKmod = snapshot.get("is_kmod") == "1",
                        lsposedHooksActive = hooksActiveThisBoot,
                    ),
                )
            val nativeInstallRecommendation =
                kernelRecommendation?.takeIf { kmod is ModuleState.NotInstalled }
            VpnHideLog.i(
                TAG,
                "nativeInstallRecommendation=$nativeInstallRecommendation " +
                    "(raw=$kernelRecommendation variantMismatch=$kmodVariantMismatch " +
                    "unknownVariantBroken=$kmodUnknownVariantBroken)",
            )

            // lsposed hook status
            val hookVersion = hookProps["version"]
            val lsposedTargetCount =
                effectiveLayerTargetUidCount(
                    installedApps = installedApps,
                    configured = appsSync,
                    listMode = policyMode,
                    selfPackage = selfPkg,
                    isListed = { it.lsposed },
                )
            val lsposedRuntime: LsposedRuntime =
                if (hooksActiveThisBoot) {
                    LsposedRuntime.Active(hookVersion)
                } else {
                    LsposedRuntime.Inactive
                }

            val lsposed: LsposedState =
                when (lsposedRuntime) {
                    is LsposedRuntime.Active -> {
                        LsposedState.Active(lsposedRuntime.version, lsposedTargetCount)
                    }

                    LsposedRuntime.Inactive -> {
                        LsposedState.InstalledInactive(hookVersion)
                    }
                }
            VpnHideLog.i(
                TAG,
                "lsposed: $lsposed (hookBootId=$hookBootId currentBootId=${currentBootId.trim()} runtime=$lsposedRuntime)",
            )

            // ── Issues ──
            val hasNative = diagnostics.backend.status == DiagnosticStatus.AVAILABLE
            when (diagnostics.backend.status) {
                DiagnosticStatus.MISSING -> err(res.getString(R.string.dashboard_issue_backend_missing))
                DiagnosticStatus.INACTIVE -> err(res.getString(R.string.dashboard_issue_backend_inactive))
                else -> Unit
            }
            when (diagnostics.bridge.status) {
                DiagnosticStatus.MISSING -> err(res.getString(R.string.dashboard_issue_bridge_missing))
                DiagnosticStatus.BROKEN -> err(res.getString(R.string.dashboard_issue_bridge_broken))
                else -> Unit
            }
            val brokenFields = hookProps["broken_fields"]?.takeIf { it.isNotBlank() }
            if (brokenFields != null) {
                val sdkLabel = hookProps["aosp_sdk"]?.takeIf { it.isNotBlank() } ?: "?"
                err(res.getString(R.string.dashboard_issue_lsposed_field_rename, brokenFields, sdkLabel))
            }

            val appVersion = BuildConfig.VERSION_NAME
            if (kmod is ModuleState.Installed) {
                val builtInMode = !kmod.isKmodType
                val installedNativeVersion =
                    kmodLoadStatus?.runtimeVersion ?: kmod.version
                val compatibility =
                    CompatibilityResolver.resolve(
                        InstalledComponentVersions(
                            lsposed = appVersion,
                            // In built-in mode module.prop belongs to the
                            // bridge package, while load_status reports the
                            // version of the embedded kernel component.
                            bridge = kmodProp.version.takeIf { builtInMode },
                            builtIn = kmodLoadStatus?.runtimeVersion.takeIf { builtInMode },
                            kmod = installedNativeVersion.takeIf { !builtInMode },
                        ),
                    )
                VpnHideLog.i(
                    TAG,
                    "component compatibility: app=$appVersion native=$installedNativeVersion result=$compatibility",
                )
                if (compatibility is CompatibilityResult.Requires) {
                    diagnostics =
                        when (compatibility.component) {
                            "bridge" -> {
                                diagnostics.copy(
                                    bridge = ComponentDiagnostic(DiagnosticStatus.BROKEN, "version"),
                                )
                            }

                            else -> {
                                diagnostics.copy(
                                    backend = ComponentDiagnostic(DiagnosticStatus.BROKEN, "version"),
                                )
                            }
                        }
                    val installedVersion =
                        when (compatibility.component) {
                            "bridge" -> kmodProp.version
                            else -> installedNativeVersion
                        }
                    if (installedVersion != null) {
                        warn(buildModuleVersionIssue(installedVersion, compatibility.version, compatibility.component))
                    }
                }
            }
            val totalTargets = lsposedTargetCount + kmodTargetCount
            if (totalTargets == 0 && policyMode == dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST) {
                err(res.getString(R.string.dashboard_issue_no_targets))
            }
            if (lsposed is LsposedState.Active) {
                val runningVersion = lsposed.version
                if (versionsMismatch(runningVersion, appVersion)) {
                    VpnHideLog.w(TAG, "version mismatch: running=$runningVersion app=$appVersion")
                    warn(
                        res.getString(
                            R.string.dashboard_issue_version_mismatch,
                            runningVersion,
                            appVersion,
                        ),
                    )
                }
            }

            // Debug logging warning
            if (isEnabledInPrefs(context)) {
                warn(res.getString(R.string.dashboard_issue_debug_logging_on))
            }

            // SELinux warning
            val (_, getenforce) = suExec("getenforce 2>/dev/null")
            if (getenforce.trim().equals("Permissive", ignoreCase = true)) {
                warn(res.getString(R.string.dashboard_issue_selinux_permissive))
            }

            // ── Errors: kmod variant / load problems ──
            val recommendedArtifact = kernelRecommendation?.recommendedArtifact
            if (kmod is ModuleState.Installed) {
                when {
                    kmodLoadStatus?.freshForCurrentBoot == true && kmodLoadStatus.kretprobes == "n" -> {
                        err(res.getString(R.string.dashboard_issue_kprobes_missing))
                    }

                    kmodOnUnsupportedKernel && recommendedArtifact != null -> {
                        err(
                            res.getString(
                                R.string.dashboard_issue_kmod_not_supported_kernel,
                                kmodLoadStatus?.unameR ?: "?",
                                recommendedArtifact,
                            ),
                        )
                    }

                    kmodVariantMismatch -> {
                        err(
                            res.getString(
                                R.string.dashboard_issue_kmod_wrong_variant,
                                kmod.gkiVariant ?: "?",
                                recommendedKmi ?: "?",
                                recommendedArtifact ?: "?",
                            ),
                        )
                    }

                    kmodUnknownVariantBroken && recommendedArtifact != null -> {
                        err(
                            res.getString(
                                R.string.dashboard_issue_kmod_unknown_variant,
                                recommendedArtifact,
                            ),
                        )
                    }

                    kmodAmbiguousLoadFailed -> {
                        val installed = kmod.gkiVariant
                        val tryArtifact =
                            if (installed == kernelRecommendation?.recommendedGkiVariant) {
                                kernelRecommendation?.alternativeArtifact
                            } else {
                                kernelRecommendation?.recommendedArtifact
                            }
                        err(
                            res.getString(
                                R.string.dashboard_issue_kmod_ambiguous_try_alternative,
                                installed ?: "?",
                                tryArtifact ?: "?",
                            ),
                        )
                    }

                    !kmod.active &&
                        kmodLoadStatus?.freshForCurrentBoot == true &&
                        kmodLoadStatus.insmodStderr != null -> {
                        err(
                            res.getString(
                                R.string.dashboard_issue_kmod_load_failed,
                                kmodLoadStatus.insmodStderr,
                            ),
                        )
                    }
                }
            }

            val protection: ProtectionCheck =
                when {
                    selfNeedsRestart -> {
                        ProtectionCheck.NeedsRestart
                    }

                    else -> {
                        val diagState = DiagnosticsCache.state.value
                        when {
                            diagState is DiagnosticsCache.State.VpnOff -> {
                                val native = if (hasNative) NativeResult.VpnOff else NativeResult.NoModule
                                val java = if (lsposed is LsposedState.Active) JavaResult.VpnOff else JavaResult.HooksInactive
                                ProtectionCheck.Checked(native, java)
                            }

                            diagState is DiagnosticsCache.State.Ready -> {
                                val diagResults = diagState.results
                                val native =
                                    if (hasNative) {
                                        val nonSkipped = diagResults.native.filter { !it.isSkipped }
                                        val passed = nonSkipped.count { it.passed == true }
                                        val failed = nonSkipped.count { it.passed == false }
                                        val total = passed + failed
                                        when {
                                            failed == 0 -> NativeResult.Ok(passed, total)
                                            passed > 0 -> NativeResult.Partial(passed, total)
                                            else -> NativeResult.Fail(0, total)
                                        }
                                    } else {
                                        NativeResult.NoModule
                                    }

                                val java =
                                    if (lsposed is LsposedState.Active) {
                                        val nonSkipped = diagResults.java.filter { !it.isSkipped }
                                        val passed = nonSkipped.count { it.passed == true }
                                        val failed = nonSkipped.count { it.passed == false }
                                        val total = passed + failed
                                        when {
                                            failed == 0 -> JavaResult.Ok(passed, total)
                                            passed > 0 -> JavaResult.Partial(passed, total)
                                            else -> JavaResult.Fail(0, total)
                                        }
                                    } else {
                                        JavaResult.HooksInactive
                                    }

                                ProtectionCheck.Checked(native, java)
                            }

                            else -> {
                                // DiagnosticsCache.State.Running or DiagnosticsCache.State.NotRun
                                val native = if (hasNative) NativeResult.Checking else NativeResult.NoModule
                                val java = if (lsposed is LsposedState.Active) JavaResult.Checking else JavaResult.HooksInactive
                                ProtectionCheck.Checked(native, java)
                            }
                        }
                    }
                }

            VpnHideLog.i(TAG, "protection=$protection issues=$issues")
            VpnHideLog.i(TAG, "=== Dashboard state loaded in ${System.currentTimeMillis() - startTime}ms ===")

            DashboardState(
                kmod = kmod,
                lsposed = lsposed,
                diagnostics = diagnostics,
                kernelVersion = kernelRaw.trim().ifBlank { null },
                nativeInstallRecommendation = nativeInstallRecommendation,
                kmodLoadStatus = kmodLoadStatus,
                protection = protection,
                issues = issues,
            )
        }

    fun loadInterceptStats(): List<AppInterceptStats> {
        val pm = context.packageManager
        val uidToAppMap = mutableMapOf<Int, Pair<String, String>>()
        val appList = AppListCache.apps.value
        val labelLookup = appList?.associate { (it.packageName to it.userId) to it.label } ?: emptyMap()

        val uidFrameworkMap = mutableMapOf<Int, MutableMap<String, Int>>()
        val uidNativeMap = mutableMapOf<Int, MutableMap<String, Int>>()
        val uidPortsMap = mutableMapOf<Int, Int>()
        val uidPortDetailsMap = mutableMapOf<Int, MutableMap<Pair<String, Int>, Long>>()

        val daemonStats = KmodStatsClient.getStats()
        lastKmodStatsResponse = daemonStats
        daemonStats.points.filterNot { it.gap }.flatMap { it.uids }.forEach { stats ->
            val fMap = uidFrameworkMap.computeIfAbsent(stats.uid) { mutableMapOf() }
            val hookMap = uidNativeMap.computeIfAbsent(stats.uid) { mutableMapOf() }

            stats.values().forEach { (hook, count) ->
                if (count > 0) {
                    if (hook.startsWith("java_")) {
                        val friendlyName =
                            when (hook) {
                                "java_pm" -> "PackageManager"
                                "java_um" -> "UserManager"
                                "java_nc" -> "NetworkCapabilities"
                                "java_ni" -> "NetworkInfo"
                                "java_net" -> "Network"
                                "java_lp" -> "LinkProperties"
                                "java_cs" -> "ConnectivityService"
                                else -> hook
                            }
                        fMap[friendlyName] = (fMap[friendlyName]?.toLong() ?: 0L).plus(count).saturatingInt()
                    } else {
                        hookMap[hook] = (hookMap[hook]?.toLong() ?: 0L).plus(count).saturatingInt()
                    }
                }
            }

            if (stats.port > 0) {
                uidPortsMap[stats.uid] =
                    (uidPortsMap[stats.uid]?.toLong() ?: 0L)
                        .plus(stats.port)
                        .saturatingInt()
            }
            if (stats.ports.isNotEmpty()) {
                val details = uidPortDetailsMap.computeIfAbsent(stats.uid) { mutableMapOf() }
                stats.ports.forEach { port ->
                    val protocol = if (port.protocol.equals("udp", ignoreCase = true)) "udp" else "tcp"
                    val key = protocol to port.port
                    details[key] = (details[key] ?: 0L) + port.count
                }
            }
        }

        val selfUid = context.applicationInfo.uid
        val allUids =
            (uidFrameworkMap.keys + uidNativeMap.keys + uidPortsMap.keys + uidPortDetailsMap.keys).filter { it != selfUid }

        val uidsToResolveRoot = mutableListOf<Int>()
        for (uid in allUids) {
            try {
                val pkgs = pm.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    val pkg = pkgs[0]
                    val userId = uid / 100000
                    val label = resolveAppLabelWithFallback(context, pkg, userId, labelLookup)
                    uidToAppMap[uid] = Pair(pkg, label)
                } else {
                    uidToAppMap[uid] = Pair("uid.$uid", "UID $uid")
                }
            } catch (_: SecurityException) {
                uidsToResolveRoot.add(uid)
            } catch (_: Throwable) {
                uidToAppMap[uid] = Pair("uid.$uid", "UID $uid")
            }
        }

        if (uidsToResolveRoot.isNotEmpty()) {
            val batchScript =
                buildString {
                    for (uid in uidsToResolveRoot) {
                        appendLine("echo \"UID:$uid\"")
                        appendLine("pm list packages --uid $uid 2>/dev/null")
                    }
                }
            val (_, stdout) = suExec(batchScript)
            var currentUid = -1
            stdout.lineSequence().forEach { line ->
                if (line.startsWith("UID:")) {
                    currentUid = line.substringAfter("UID:").trim().toIntOrNull() ?: -1
                } else if (currentUid != -1 && line.startsWith("package:")) {
                    val pkgName = line.substringAfter("package:").substringBefore(" ").trim()
                    if (pkgName.isNotEmpty()) {
                        val userId = currentUid / 100000
                        val label = resolveAppLabelWithFallback(context, pkgName, userId, labelLookup)
                        uidToAppMap[currentUid] = Pair(pkgName, label)
                        currentUid = -1
                    }
                }
            }
        }

        for (uid in allUids) {
            if (!uidToAppMap.containsKey(uid)) {
                uidToAppMap[uid] = Pair("uid.$uid", "UID $uid")
            }
        }

        return allUids
            .map { uid ->
                val (pkg, label) = uidToAppMap[uid] ?: Pair("uid.$uid", "UID $uid")
                val fBreakdown = uidFrameworkMap[uid] ?: emptyMap()
                val nBreakdown = uidNativeMap[uid] ?: emptyMap()
                val portsCount = uidPortsMap[uid] ?: 0
                val portAccesses =
                    uidPortDetailsMap[uid]
                        .orEmpty()
                        .map { (key, count) ->
                            PortAccess(
                                port = key.second,
                                protocol = key.first,
                                count = count.saturatingInt(),
                            )
                        }.sortedWith(compareBy<PortAccess> { it.protocol }.thenBy { it.port })
                AppInterceptStats(
                    packageName = pkg,
                    appLabel = label,
                    frameworkTotal = fBreakdown.values.sum(),
                    nativeTotal = nBreakdown.values.sum(),
                    frameworkBreakdown = fBreakdown,
                    nativeBreakdown = nBreakdown,
                    portsCount = portsCount,
                    portAccesses = portAccesses,
                    userId = uid / 100000,
                    uid = uid,
                )
            }.filter { it.frameworkTotal > 0 || it.nativeTotal > 0 || it.portsCount > 0 }
            .sortedByDescending { it.frameworkTotal + it.nativeTotal + it.portsCount }
    }

    fun resetInterceptStats() {
        suExec("echo clear_stats > $DEV_NODE 2>/dev/null || true")
        KmodStatsClient.clearHistory()
    }

    private fun resolveAppLabelWithFallback(
        context: Context,
        packageName: String,
        userId: Int,
        labelLookup: Map<Pair<String, Int>, String> = emptyMap(),
    ): String {
        val cachedLabel = labelLookup[packageName to userId]
        if (!cachedLabel.isNullOrEmpty()) return cachedLabel

        val cachedApp = AppListCache.apps.value?.find { it.packageName == packageName && it.userId == userId }
        if (cachedApp != null) {
            val label = cachedApp.label
            if (label.isNotEmpty()) return label
        }

        val pm = context.packageManager
        val label =
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString().trim()
            } catch (_: Throwable) {
                null
            }

        if (!label.isNullOrEmpty()) return label

        val (_, pathOut) = suExec("pm path $packageName 2>/dev/null")
        val pathLine = pathOut.lines().firstOrNull { it.startsWith("package:") }
        val apkPath = pathLine?.substringAfter("package:")?.trim()
        val archiveInfo =
            if (!apkPath.isNullOrBlank()) {
                runCatching { pm.getPackageArchiveInfo(apkPath, 0) }.getOrNull()?.applicationInfo?.apply {
                    sourceDir = apkPath
                    publicSourceDir = apkPath
                }
            } else {
                null
            }

        val archiveLabel =
            if (archiveInfo != null) {
                pm.getApplicationLabel(archiveInfo).toString().trim()
            } else {
                null
            }

        return if (!archiveLabel.isNullOrEmpty()) archiveLabel else packageName
    }
}
