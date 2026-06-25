package dev.soranerai.vpnhidenext.data.repository

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.soranerai.vpnhidenext.BuildConfig
import dev.soranerai.vpnhidenext.DEV_NODE
import dev.soranerai.vpnhidenext.KMOD_CTL
import dev.soranerai.vpnhidenext.KMOD_LOAD_DMESG_FILE
import dev.soranerai.vpnhidenext.KMOD_LOAD_STATUS_FILE
import dev.soranerai.vpnhidenext.KMOD_MODULE_DIR
import dev.soranerai.vpnhidenext.R
import dev.soranerai.vpnhidenext.AppListCache
import dev.soranerai.vpnhidenext.DiagnosticsCache
import dev.soranerai.vpnhidenext.VpnHideLog
import dev.soranerai.vpnhidenext.suExec
import dev.soranerai.vpnhidenext.versionsMismatch
import dev.soranerai.vpnhidenext.compareSemver
import dev.soranerai.vpnhidenext.normalizeVersion
import dev.soranerai.vpnhidenext.isEnabledInPrefs
import dev.soranerai.vpnhidenext.domain.models.*
import dev.soranerai.vpnhidenext.domain.usecase.buildNativeInstallRecommendation

private const val TAG = "VpnHide-Repository"

private sealed interface LsposedRuntime {
    data object Inactive : LsposedRuntime
    data class Active(val version: String?) : LsposedRuntime
}

private sealed interface LsposedFramework {
    data object NotInstalled : LsposedFramework
    data class Installed(val disabled: Boolean) : LsposedFramework
}

private sealed interface LsposedConfig {
    data object ModuleNotConfigured : LsposedConfig
    data object Disabled : LsposedConfig
}

private fun parseKeyValue(text: String): Map<String, String> =
    text.lines()
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

private data class RawDashboardSnapshot(
    val props: Map<String, String>,
) {
    fun get(key: String): String = props[key] ?: ""

    fun decodeBase64(key: String): String = decodeBase64String(get(key))
}

class DashboardRepository(private val context: Context) {

    private val res = context.resources
    private val selfPkg = context.packageName

    private fun collectDashboardSnapshot(): RawDashboardSnapshot {
        val script =
            """
            echo "kmod_prop=${'$'}(cat $KMOD_MODULE_DIR/module.prop 2>/dev/null | base64 | tr -d '\n')"
            echo "uname=${'$'}(uname -r)"
            echo "boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id)"
            echo "load_status=${'$'}(cat $KMOD_LOAD_STATUS_FILE 2>/dev/null | base64 | tr -d '\n')"
            echo "load_dmesg=${'$'}(cat $KMOD_LOAD_DMESG_FILE 2>/dev/null | tail -n 50 | base64 | tr -d '\n')"
            [ -c $DEV_NODE ] && echo "lsmod=1" || echo "lsmod=0"
    
            # LSPosed framework
            LSP_INSTALLED=0
            LSP_DISABLED=0
            for id in lsposed; do
              for dir in /data/adb/modules/${'$'}id /data/adb/modules_update/${'$'}id; do
                if [ -f ${'$'}dir/module.prop ]; then
                  LSP_INSTALLED=1
                  [ -f ${'$'}dir/disable ] && LSP_DISABLED=1
                  break 2
                fi
              done
            done
            echo "lsp_installed=${'$'}LSP_INSTALLED"
            echo "lsp_disabled=${'$'}LSP_DISABLED"
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
    ): String {
        val normalizedModuleVersion = normalizeVersion(moduleVersion)
        val normalizedAppVersion = normalizeVersion(appVersion)
        return when (compareSemver(normalizedModuleVersion, normalizedAppVersion)) {
            null, 0 -> {
                res.getString(
                    R.string.dashboard_issue_kmod_version_mismatch,
                    moduleVersion,
                    appVersion,
                )
            }

            in Int.MIN_VALUE..-1 -> {
                res.getString(
                    R.string.dashboard_issue_update_kmod,
                    moduleVersion,
                    appVersion,
                )
            }

            else -> {
                res.getString(
                    R.string.dashboard_issue_update_app_for_kmod,
                    moduleVersion,
                    appVersion,
                )
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

    private fun readKmodLoadStatus(snapshot: RawDashboardSnapshot, currentBootId: String): KmodLoadStatus? {
        val raw = snapshot.decodeBase64("load_status")
        if (raw.isBlank()) return null
        val props = parseKeyValue(raw)
        val dmesgRaw = snapshot.decodeBase64("load_dmesg")
        val bootId = props["boot_id"]?.trim()
        return KmodLoadStatus(
            timestamp = props["timestamp"]?.trim()?.toLongOrNull(),
            bootId = bootId,
            unameR = props["uname_r"]?.trim(),
            gkiVariant = props["gki_variant"]?.trim()?.ifBlank { null },
            kmodVersion = props["kmod_version"]?.trim()?.ifBlank { null },
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

    private fun detectLsposedFramework(snapshot: RawDashboardSnapshot): LsposedFramework {
        val installed = snapshot.get("lsp_installed") == "1"
        val disabled = snapshot.get("lsp_disabled") == "1"
        val framework =
            if (installed) {
                LsposedFramework.Installed(disabled = disabled)
            } else {
                LsposedFramework.NotInstalled
            }
        VpnHideLog.i(TAG, "lsposed framework: $framework")
        return framework
    }

    suspend fun loadDashboardState(selfNeedsRestart: Boolean): DashboardState {
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
        val db = dev.soranerai.vpnhidenext.db.AppDatabase.getInstance(context)
        val appsSync = db.appDao().getAllAppProtectionSync()

        // kmod
        val kmodProp = parseModuleProp(snapshot.decodeBase64("kmod_prop"))
        val kmodActive = kmodProp.installed && snapshot.get("lsmod") == "1"
        val kmodTargetCount =
            if (kmodProp.installed) {
                appsSync.count { it.kmod && it.packageName != selfPkg }
            } else {
                0
            }
        val kmodRaw: ModuleState =
            if (kmodProp.installed) {
                ModuleState.Installed(
                    version = kmodProp.version,
                    active = kmodActive,
                    targetCount = kmodTargetCount,
                    gkiVariant = kmodProp.gkiVariant,
                )
            } else {
                ModuleState.NotInstalled
            }
        VpnHideLog.i(TAG, "kmodRaw: $kmodRaw")

        val kernelRaw = snapshot.get("uname")
        val kernelRecommendation =
            buildNativeInstallRecommendation(kernelRaw, androidMajorVersionLabel())
        val kmodLoadStatus = readKmodLoadStatus(snapshot, currentBootId.trim())
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
        val nativeInstallRecommendation =
            kernelRecommendation?.takeIf { kmod is ModuleState.NotInstalled }
        VpnHideLog.i(
            TAG,
            "nativeInstallRecommendation=$nativeInstallRecommendation " +
                "(raw=$kernelRecommendation variantMismatch=$kmodVariantMismatch " +
                "unknownVariantBroken=$kmodUnknownVariantBroken)",
        )

        // lsposed hook status
        val (_, hookStatusRaw) = suExec("$KMOD_CTL hook_status 2>/dev/null || true")
        val hookProps = parseKeyValue(hookStatusRaw)
        val hookVersion = hookProps["version"]
        val hookBootId = hookProps["boot_id"]
        val hooksActiveThisBoot = hookBootId != null && hookBootId == currentBootId.trim()
        val lsposedTargetCount = appsSync.count { it.lsposed }
        val lsposedFramework = detectLsposedFramework(snapshot)
        val lsposedConfig =
            when (lsposedFramework) {
                LsposedFramework.NotInstalled -> {
                    LsposedConfig.ModuleNotConfigured
                }

                is LsposedFramework.Installed -> {
                    if (lsposedFramework.disabled) {
                        LsposedConfig.Disabled
                    } else {
                        null
                    }
                }
            }
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
                    when (lsposedConfig) {
                        null -> {
                            LsposedState.InstalledInactive(null)
                        }

                        LsposedConfig.ModuleNotConfigured -> {
                            when (lsposedFramework) {
                                LsposedFramework.NotInstalled -> {
                                    LsposedState.NotInstalled
                                }

                                is LsposedFramework.Installed -> {
                                    LsposedState.InstalledInactive(null)
                                }
                            }
                        }

                        LsposedConfig.Disabled -> {
                            LsposedState.InstalledInactive(null)
                        }
                    }
                }
            }
        VpnHideLog.i(
            TAG,
            "lsposed: $lsposed (hookBootId=$hookBootId currentBootId=${currentBootId.trim()} framework=$lsposedFramework runtime=$lsposedRuntime config=$lsposedConfig)",
        )

        // ── Issues ──
        val hasNative = kmod is ModuleState.Installed
        if (!hasNative) {
            err(res.getString(R.string.dashboard_issue_no_native))
        }
        if (lsposedFramework is LsposedFramework.NotInstalled && lsposed !is LsposedState.Active) {
            err(res.getString(R.string.dashboard_issue_lsposed_not_installed))
        }
        val brokenFields = hookProps["broken_fields"]?.takeIf { it.isNotBlank() }
        if (brokenFields != null) {
            val sdkLabel = hookProps["aosp_sdk"]?.takeIf { it.isNotBlank() } ?: "?"
            err(res.getString(R.string.dashboard_issue_lsposed_field_rename, brokenFields, sdkLabel))
        }

        val appVersion = BuildConfig.VERSION_NAME
        if (kmod is ModuleState.Installed) {
            val moduleVersion = kmod.version
            if (moduleVersion != null && versionsMismatch(moduleVersion, appVersion)) {
                warn(buildModuleVersionIssue(moduleVersion, appVersion))
            }
        }
        val totalTargets = lsposedTargetCount + kmodTargetCount
        if (totalTargets == 0) {
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
                    val diagResults = DiagnosticsCache.awaitResults(context)

                    val native =
                        if (hasNative) {
                            if (diagResults == null) {
                                NativeResult.Fail(0, 1)
                            } else {
                                val passed = diagResults.native.count { it.passed == true }
                                val failed = diagResults.native.count { it.passed == false }
                                val total = passed + failed
                                when {
                                    failed == 0 -> NativeResult.Ok(passed, total)
                                    passed > 0 -> NativeResult.Partial(passed, total)
                                    else -> NativeResult.Fail(0, total)
                                }
                            }
                        } else {
                            NativeResult.NoModule
                        }

                    val java =
                        if (lsposed is LsposedState.Active) {
                            if (diagResults == null) {
                                JavaResult.Fail(0, 1)
                            } else {
                                val passed = diagResults.java.count { it.passed == true }
                                val failed = diagResults.java.count { it.passed == false }
                                val total = passed + failed
                                when {
                                    failed == 0 -> JavaResult.Ok(passed, total)
                                    passed > 0 -> JavaResult.Partial(passed, total)
                                    else -> JavaResult.Fail(0, total)
                                }
                            }
                        } else {
                            JavaResult.HooksInactive
                        }

                    ProtectionCheck.Checked(native, java)
                }
            }

        VpnHideLog.i(TAG, "protection=$protection issues=$issues")
        VpnHideLog.i(TAG, "=== Dashboard state loaded in ${System.currentTimeMillis() - startTime}ms ===")

        return DashboardState(
            kmod = kmod,
            lsposed = lsposed,
            nativeInstallRecommendation = nativeInstallRecommendation,
            kmodLoadStatus = kmodLoadStatus,
            protection = protection,
            issues = issues,
        )
    }

    fun loadInterceptStats(): List<AppInterceptStats> {
        val pm = context.packageManager
        val uidToAppMap = mutableMapOf<Int, Pair<String, String>>()

        fun getAppInfoForUid(uid: Int): Pair<String, String> {
            uidToAppMap[uid]?.let {
                return it
            }
            val pkgs =
                try {
                    pm.getPackagesForUid(uid)
                } catch (_: SecurityException) {
                    val (_, stdout) = suExec("pm list packages --uid $uid 2>/dev/null")
                    val pkgLine = stdout.lines().firstOrNull { it.startsWith("package:") }
                    if (pkgLine != null) {
                        val pkgName = pkgLine.substringAfter("package:").substringBefore(" ").trim()
                        if (pkgName.isNotEmpty()) arrayOf(pkgName) else null
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }

            if (!pkgs.isNullOrEmpty()) {
                val pkg = pkgs[0]
                val userId = uid / 100000
                val label = resolveAppLabelWithFallback(context, pkg, userId)
                val res = Pair(pkg, label)
                uidToAppMap[uid] = res
                return res
            }
            val unknown = Pair("uid.$uid", "UID $uid")
            uidToAppMap[uid] = unknown
            return unknown
        }

        val script =
            """
            if [ -x $KMOD_CTL ] && [ -c $DEV_NODE ]; then
              echo "framework_stats=${'$'}($KMOD_CTL java_stats 2>/dev/null | base64 | tr -d '\n')"
              echo "native_stats=${'$'}($KMOD_CTL stats 2>/dev/null | base64 | tr -d '\n')"
            fi
            """.trimIndent()

        val (_, out) = suExec(script)
        val props = parseKeyValue(out)

        val frameworkStatsRaw = decodeBase64String(props["framework_stats"] ?: "")
        val uidFrameworkMap = mutableMapOf<Int, MutableMap<String, Int>>()
        if (frameworkStatsRaw.isNotBlank()) {
            frameworkStatsRaw.lines().forEach { line ->
                val parts = line.split(';')
                if (parts.size == 3) {
                    val uid = parts[0].toIntOrNull()
                    val hook = parts[1]
                    val count = parts[2].toIntOrNull()
                    if (uid != null && count != null && count > 0) {
                        val hookMap = uidFrameworkMap.computeIfAbsent(uid) { mutableMapOf() }
                        hookMap[hook] = (hookMap[hook] ?: 0) + count
                    }
                }
            }
        }

        val nativeStatsRaw = decodeBase64String(props["native_stats"] ?: "")
        val uidNativeMap = mutableMapOf<Int, MutableMap<String, Int>>()
        if (nativeStatsRaw.isNotBlank()) {
            nativeStatsRaw.lines().forEach { line ->
                val parts = line.split(';')
                if (parts.size == 5) {
                    val uid = parts[0].toIntOrNull()
                    val ioctl = parts[1].toIntOrNull() ?: 0
                    val netlink = parts[2].toIntOrNull() ?: 0
                    val connect = parts[3].toIntOrNull() ?: 0
                    val getname = parts[4].toIntOrNull() ?: 0
                    if (uid != null && (ioctl > 0 || netlink > 0 || connect > 0 || getname > 0)) {
                        val hookMap = uidNativeMap.computeIfAbsent(uid) { mutableMapOf() }
                        if (ioctl > 0) hookMap["ioctl"] = ioctl
                        if (netlink > 0) hookMap["netlink"] = netlink
                        if (connect > 0) hookMap["connect"] = connect
                        if (getname > 0) hookMap["getname"] = getname
                    }
                }
            }
        }

        val selfUid = context.applicationInfo.uid
        val allUids = (uidFrameworkMap.keys + uidNativeMap.keys).filter { it != selfUid }
        return allUids
            .map { uid ->
                val (pkg, label) = getAppInfoForUid(uid)
                val fBreakdown = uidFrameworkMap[uid] ?: emptyMap()
                val nBreakdown = uidNativeMap[uid] ?: emptyMap()
                AppInterceptStats(
                    packageName = pkg,
                    appLabel = label,
                    frameworkTotal = fBreakdown.values.sum(),
                    nativeTotal = nBreakdown.values.sum(),
                    frameworkBreakdown = fBreakdown,
                    nativeBreakdown = nBreakdown,
                    userId = uid / 100000,
                    uid = uid,
                )
            }.filter { it.frameworkTotal > 0 || it.nativeTotal > 0 }
            .sortedByDescending { it.frameworkTotal + it.nativeTotal }
    }

    fun resetInterceptStats() {
        suExec("$KMOD_CTL java_stats clear 2>/dev/null")
        suExec("$KMOD_CTL stats clear 2>/dev/null")
    }

    private fun resolveAppLabelWithFallback(
        context: Context,
        packageName: String,
        userId: Int
    ): String {
        val cachedApp = AppListCache.apps.value?.find { it.packageName == packageName && it.userId == userId }
        if (cachedApp != null) {
            val label = cachedApp.label
            if (label.isNotEmpty()) return label
        }

        val pm = context.packageManager
        val label = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().trim()
        } catch (_: Throwable) {
            null
        }

        if (!label.isNullOrEmpty()) return label

        val (_, pathOut) = suExec("pm path $packageName 2>/dev/null")
        val pathLine = pathOut.lines().firstOrNull { it.startsWith("package:") }
        val apkPath = pathLine?.substringAfter("package:")?.trim()
        val archiveInfo = if (!apkPath.isNullOrBlank()) {
            runCatching { pm.getPackageArchiveInfo(apkPath, 0) }.getOrNull()?.applicationInfo?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
            }
        } else null

        val archiveLabel = if (archiveInfo != null) {
            pm.getApplicationLabel(archiveInfo).toString().trim()
        } else null

        return if (!archiveLabel.isNullOrEmpty()) archiveLabel else packageName
    }
}
