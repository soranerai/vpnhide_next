package dev.soranerai.vpnhidenext

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val BUILT_IN_UPDATE_TAG = "VpnHide-BuiltInUpdate"
private const val BUILT_IN_METADATA_URL =
    "https://raw.githubusercontent.com/soranerai/vpnhide_next/main/update-json/update-bridge.json"
private const val TRUSTED_KERNEL_RELEASES_API =
    "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100"
private const val MAX_BUILT_IN_METADATA_BYTES = 128 * 1024
private const val MAX_RELEASES_METADATA_BYTES = 4 * 1024 * 1024
private const val MAX_BRIDGE_ZIP_BYTES = 16L * 1024 * 1024
private const val MAX_KERNEL_ZIP_BYTES = 64L * 1024 * 1024
private const val MAX_BRIDGE_EXPANDED_BYTES = 32L * 1024 * 1024
private const val MAX_KERNEL_EXPANDED_BYTES = 160L * 1024 * 1024
private const val MAX_ZIP_ENTRIES = 256
private const val MAX_AK3_CORE_BYTES = 256 * 1024

internal data class BuiltInUpdateTarget(
    val installedVersion: String,
    val unameR: String,
    val debugMode: Boolean = false,
)

internal data class BuiltInUpdateMetadata(
    val bridgeVersion: String,
    val bridgeVersionCode: Int,
    val bridgeZipUrl: String,
    val bridgeSha256: String,
    val kernelVersion: String,
    val kernelVersionCode: Int,
    val kernelReleasesApi: String,
)

internal data class KernelReleaseAsset(
    val releaseTag: String,
    val name: String,
    val url: String,
    val sha256: String,
)

internal data class BuiltInUpdateInfo(
    val metadata: BuiltInUpdateMetadata,
    val kernelAsset: KernelReleaseAsset,
    val unameR: String,
    val debugMode: Boolean = false,
)

internal enum class KernelSelectionFailure {
    UNSUPPORTED_RUNNING_KERNEL,
    NO_COMPATIBLE_ASSET,
    ALL_COMPATIBLE_ASSETS_OLDER,
}

internal sealed interface KernelSelectionResult {
    data class Selected(
        val asset: KernelReleaseAsset,
    ) : KernelSelectionResult

    data class Failed(
        val reason: KernelSelectionFailure,
    ) : KernelSelectionResult
}

internal enum class KernelImageMode {
    NORMAL,
    BYPASS,
}

internal enum class BuiltInUpdateError {
    METADATA,
    UNSUPPORTED_KERNEL,
    NO_COMPATIBLE_KERNEL,
    KERNEL_DOWNGRADE,
    DOWNLOAD,
    CHECKSUM,
    INVALID_BRIDGE,
    INVALID_KERNEL,
    STORAGE,
    ROOT_DENIED,
    BACKUP_FAILED,
    UNSUPPORTED_ROOT_MANAGER,
    BRIDGE_INSTALL_FAILED,
    KERNEL_INSTALL_FAILED,
    REBOOT_FAILED,
}

internal sealed interface BuiltInUpdateState {
    data object None : BuiltInUpdateState

    data class Available(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class Downloading(
        val info: BuiltInUpdateInfo,
        val component: String,
        val progress: Int?,
    ) : BuiltInUpdateState

    data class Validating(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class ReadyToConfirm(
        val info: BuiltInUpdateInfo,
        val hasBypass: Boolean,
    ) : BuiltInUpdateState

    data class PreparingInstall(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class BackingUp(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class InstallingBridge(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class FlashingKernel(
        val info: BuiltInUpdateInfo,
    ) : BuiltInUpdateState

    data class AwaitingReboot(
        val version: String,
        val backupPath: String,
    ) : BuiltInUpdateState

    data class Failed(
        val info: BuiltInUpdateInfo?,
        val error: BuiltInUpdateError,
        val backupPath: String? = null,
    ) : BuiltInUpdateState
}

internal fun parseBuiltInUpdateMetadata(
    raw: String,
    installedVersion: String,
    debugMode: Boolean = false,
): BuiltInUpdateMetadata? {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val bridgeVersion = json.optString("version")
    val bridgeVersionCode = json.optInt("versionCode", -1)
    val bridgeZipUrl = json.optString("zipUrl")
    val bridgeSha256 = json.optString("sha256").lowercase()
    val kernelVersion = json.optString("kernelVersion")
    val kernelVersionCode = json.optInt("kernelVersionCode", -1)
    val kernelReleasesApi = json.optString("kernelReleasesApi")
    return validateBuiltInUpdateMetadataFields(
        bridgeVersion,
        bridgeVersionCode,
        bridgeZipUrl,
        bridgeSha256,
        kernelVersion,
        kernelVersionCode,
        kernelReleasesApi,
        installedVersion,
        debugMode,
    )
}

internal fun validateBuiltInUpdateMetadataFields(
    bridgeVersion: String,
    bridgeVersionCode: Int,
    bridgeZipUrl: String,
    bridgeSha256: String,
    kernelVersion: String,
    kernelVersionCode: Int,
    kernelReleasesApi: String,
    installedVersion: String,
    debugMode: Boolean = false,
): BuiltInUpdateMetadata? {
    val versionAllowed =
        isNewerVersion(kernelVersion, installedVersion) ||
            (debugMode && baseVersion(kernelVersion) == baseVersion(installedVersion))
    if (!versionAllowed) return null
    if (bridgeVersionCode < 0 || kernelVersionCode < 0) return null
    if (baseVersion(bridgeVersion) != baseVersion(kernelVersion)) return null
    if (!debugMode && !bridgeSha256.matches(Regex("[0-9a-f]{64}"))) return null
    if (!isTrustedBridgeUrl(bridgeZipUrl)) return null
    if (kernelReleasesApi != TRUSTED_KERNEL_RELEASES_API) return null
    return BuiltInUpdateMetadata(
        bridgeVersion = bridgeVersion,
        bridgeVersionCode = bridgeVersionCode,
        bridgeZipUrl = bridgeZipUrl,
        bridgeSha256 = bridgeSha256.lowercase(),
        kernelVersion = kernelVersion,
        kernelVersionCode = kernelVersionCode,
        kernelReleasesApi = kernelReleasesApi,
    )
}

internal fun parseKernelReleaseAssets(
    raw: String,
    debugMode: Boolean = false,
): List<KernelReleaseAsset> {
    val releases = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val result = mutableListOf<KernelReleaseAsset>()
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val tag = release.optString("tag_name")
        if (versionTagToCode(tag) == null) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
            if (!debugMode && !digest.matches(Regex("[0-9a-f]{64}"))) continue
            if (!isTrustedKernelAssetUrl(url, tag, name)) continue
            result += KernelReleaseAsset(tag, name, url, digest)
        }
    }
    return result
}

internal fun selectKernelAsset(
    runningRelease: String,
    installedVersion: String,
    assets: List<KernelReleaseAsset>,
    maximumVersion: String? = null,
    allowCurrentVersion: Boolean = false,
): KernelSelectionResult {
    val running =
        detectRunningKernel(runningRelease)
            ?: return KernelSelectionResult.Failed(KernelSelectionFailure.UNSUPPORTED_RUNNING_KERNEL)
    val installedCode = versionTagToCode(installedVersion) ?: -1
    val maximumCode = maximumVersion?.let(::versionTagToCode) ?: Int.MAX_VALUE
    val majorMinor = "${running.first}.${running.second}"
    val currentPatch = running.third
    var compatible = false
    var selected: KernelReleaseAsset? = null
    var selectedPatch = Int.MAX_VALUE

    for (asset in assets) {
        val releaseCode = versionTagToCode(asset.releaseTag) ?: continue
        if (releaseCode < installedCode || (!allowCurrentVersion && releaseCode == installedCode) ||
            releaseCode > maximumCode
        ) {
            continue
        }
        val match = KERNEL_ASSET_NAME.matchEntire(asset.name) ?: continue
        val candidateMajorMinor = "${match.groupValues[1]}.${match.groupValues[2]}"
        val patch = match.groupValues[3].toIntOrNull() ?: continue
        val generation = match.groupValues[4]
        if (candidateMajorMinor != majorMinor || generation != running.fourth) continue
        compatible = true
        if (patch >= currentPatch && patch < selectedPatch) {
            selected = asset
            selectedPatch = patch
        }
    }
    return when {
        selected != null -> KernelSelectionResult.Selected(selected)
        compatible -> KernelSelectionResult.Failed(KernelSelectionFailure.ALL_COMPATIBLE_ASSETS_OLDER)
        else -> KernelSelectionResult.Failed(KernelSelectionFailure.NO_COMPATIBLE_ASSET)
    }
}

private data class RunningKernel(
    val first: Int,
    val second: Int,
    val third: Int,
    val fourth: String,
)

private val RUNNING_KERNEL = Regex("""^(\d+)\.(\d+)\.(\d+).*-(android\d+)(?:-.*)?$""")
private val KERNEL_ASSET_NAME = Regex("""^(\d+)\.(\d+)\.(\d+)-(android\d+)-.+-AnyKernel3\.zip$""")

private fun detectRunningKernel(value: String): RunningKernel? {
    val match = RUNNING_KERNEL.matchEntire(value.trim()) ?: return null
    return RunningKernel(
        match.groupValues[1].toIntOrNull() ?: return null,
        match.groupValues[2].toIntOrNull() ?: return null,
        match.groupValues[3].toIntOrNull() ?: return null,
        match.groupValues[4],
    )
}

internal fun versionTagToCode(value: String): Int? {
    val parts = normalizeVersion(value).split('.')
    if (parts.size != 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull()?.takeIf { it <= 99 } ?: return null
    val patch = parts[2].toIntOrNull()?.takeIf { it <= 99 } ?: return null
    return major * 10_000 + minor * 100 + patch
}

private fun isTrustedBridgeUrl(raw: String): Boolean = trustedGithubReleaseUrl(raw, "soranerai", "vpnhide_next", "vpnhide-bridge.zip")

private fun isTrustedKernelAssetUrl(
    raw: String,
    tag: String,
    name: String,
): Boolean = trustedGithubReleaseUrl(raw, "soranerai", "GKI_KernelSU_SUSFS", name, tag)

private fun trustedGithubReleaseUrl(
    raw: String,
    owner: String,
    repo: String,
    expectedName: String,
    expectedTag: String? = null,
): Boolean =
    runCatching {
        val uri = URI(raw)
        val prefix = "/$owner/$repo/releases/download/"
        uri.scheme == "https" && uri.host == "github.com" && uri.rawQuery == null &&
            uri.rawFragment == null && uri.path.startsWith(prefix) &&
            uri.path.substringAfterLast('/') == expectedName &&
            (expectedTag == null || uri.path.removePrefix(prefix).substringBefore('/') == expectedTag)
    }.getOrDefault(false)

internal object BuiltInUpdateCache {
    // Installation must outlive the dashboard composable. Cancelling a UI scope while AK3 is
    // flashing the boot image could otherwise leave the device without a bootable kernel.
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<BuiltInUpdateState>(BuiltInUpdateState.None)
    val state: StateFlow<BuiltInUpdateState> = _state.asStateFlow()
    private var target: BuiltInUpdateTarget? = null
    private var checkJob: Job? = null
    private var bridgeFile: File? = null
    private var kernelFile: File? = null

    fun ensureFresh(updateTarget: BuiltInUpdateTarget) {
        if (target == updateTarget && (_state.value != BuiltInUpdateState.None || checkJob?.isActive == true)) return
        refresh(updateTarget)
    }

    fun refresh(updateTarget: BuiltInUpdateTarget) {
        if (isBusy(_state.value)) return
        checkJob?.cancel()
        clearDownloads()
        target = updateTarget
        _state.value = BuiltInUpdateState.None
        checkJob =
            operationScope.launch {
                val result = withContext(Dispatchers.IO) { checkForBuiltInUpdate(updateTarget) }
                if (target != updateTarget) return@launch
                _state.value =
                    when (result) {
                        is BuiltInCheckOutcome.Available -> BuiltInUpdateState.Available(result.info)
                        is BuiltInCheckOutcome.Failed -> BuiltInUpdateState.Failed(null, result.error)
                        BuiltInCheckOutcome.None -> BuiltInUpdateState.None
                    }
            }
    }

    fun download(
        context: Context,
        info: BuiltInUpdateInfo,
    ) {
        if (isBusy(_state.value)) return
        operationScope.launch {
            val appContext = context.applicationContext
            val result =
                withContext(Dispatchers.IO) {
                    downloadAndValidate(
                        appContext,
                        info,
                        onProgress = { component, progress ->
                            _state.value = BuiltInUpdateState.Downloading(info, component, progress)
                        },
                        onValidating = { _state.value = BuiltInUpdateState.Validating(info) },
                    )
                }
            when (result) {
                is DownloadResult.Ready -> {
                    bridgeFile = result.bridge
                    kernelFile = result.kernel
                    _state.value = BuiltInUpdateState.ReadyToConfirm(info, result.hasBypass)
                }

                is DownloadResult.Failed -> {
                    _state.value = BuiltInUpdateState.Failed(info, result.error)
                }
            }
        }
    }

    fun install(
        info: BuiltInUpdateInfo,
        mode: KernelImageMode,
    ) {
        val bridge =
            bridgeFile ?: run {
                _state.value = BuiltInUpdateState.Failed(info, BuiltInUpdateError.STORAGE)
                return
            }
        val kernel =
            kernelFile ?: run {
                _state.value = BuiltInUpdateState.Failed(info, BuiltInUpdateError.STORAGE)
                return
            }
        if (isBusy(_state.value)) return
        // Block repeated confirmation immediately. Repacking and validating the controlled
        // bridge/AK3 archives happens before the root backup and can take a visible moment.
        _state.value = BuiltInUpdateState.PreparingInstall(info)
        operationScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val suffix = System.currentTimeMillis()
                    val preparedBridge = File(kernel.parentFile, "vpnhide-bridge-app-$suffix.zip")
                    val preparedKernel = File(kernel.parentFile, "vpnhide-selected-$suffix.zip")
                    try {
                        if (!prepareBridgeForAppInstall(bridge, preparedBridge)) {
                            return@withContext InstallResult.Failed(BuiltInUpdateError.INVALID_BRIDGE)
                        }
                        if (!validateBridgeZip(preparedBridge, info.metadata)) {
                            return@withContext InstallResult.Failed(BuiltInUpdateError.INVALID_BRIDGE)
                        }
                        if (!prepareNonInteractiveAk3(kernel, preparedKernel)) {
                            return@withContext InstallResult.Failed(BuiltInUpdateError.INVALID_KERNEL)
                        }
                        if (validateKernelZip(preparedKernel) == null) {
                            return@withContext InstallResult.Failed(BuiltInUpdateError.INVALID_KERNEL)
                        }
                        installBuiltInWithRoot(
                            preparedBridge,
                            preparedKernel,
                            info,
                            mode,
                            onStage = { stage -> _state.value = stage },
                        )
                    } finally {
                        preparedBridge.delete()
                        preparedKernel.delete()
                    }
                }
            clearDownloads()
            _state.value =
                when (result) {
                    is InstallResult.Success -> {
                        BuiltInUpdateState.AwaitingReboot(
                            normalizeVersion(info.metadata.kernelVersion),
                            result.backupPath,
                        )
                    }

                    is InstallResult.Failed -> {
                        BuiltInUpdateState.Failed(info, result.error, result.backupPath)
                    }
                }
        }
    }

    fun reboot() {
        val current = _state.value as? BuiltInUpdateState.AwaitingReboot ?: return
        operationScope.launch(Dispatchers.IO) {
            val (exit, _) = suExec("svc power reboot", timeoutSec = 5)
            if (exit != 0 && exit != -1) {
                _state.value = BuiltInUpdateState.Failed(null, BuiltInUpdateError.REBOOT_FAILED, current.backupPath)
            }
        }
    }

    private fun clearDownloads() {
        bridgeFile?.delete()
        kernelFile?.delete()
        bridgeFile = null
        kernelFile = null
    }
}

private fun isBusy(state: BuiltInUpdateState): Boolean =
    state is BuiltInUpdateState.Downloading || state is BuiltInUpdateState.Validating ||
        state is BuiltInUpdateState.PreparingInstall || state is BuiltInUpdateState.BackingUp ||
        state is BuiltInUpdateState.InstallingBridge || state is BuiltInUpdateState.FlashingKernel

private sealed interface BuiltInCheckOutcome {
    data object None : BuiltInCheckOutcome

    data class Available(
        val info: BuiltInUpdateInfo,
    ) : BuiltInCheckOutcome

    data class Failed(
        val error: BuiltInUpdateError,
    ) : BuiltInCheckOutcome
}

private fun checkForBuiltInUpdate(target: BuiltInUpdateTarget): BuiltInCheckOutcome {
    return try {
        val metadataRaw =
            downloadSmallText(BUILT_IN_METADATA_URL, MAX_BUILT_IN_METADATA_BYTES)
                ?: return BuiltInCheckOutcome.Failed(BuiltInUpdateError.METADATA)
        val metadata =
            parseBuiltInUpdateMetadata(metadataRaw, target.installedVersion, target.debugMode)
                ?: return BuiltInCheckOutcome.None
        val releasesRaw =
            downloadSmallText(metadata.kernelReleasesApi, MAX_RELEASES_METADATA_BYTES)
                ?: return BuiltInCheckOutcome.Failed(BuiltInUpdateError.METADATA)
        val assets = parseKernelReleaseAssets(releasesRaw, target.debugMode)
        when (
            val selection =
                selectKernelAsset(
                    target.unameR,
                    target.installedVersion,
                    assets,
                    maximumVersion = metadata.kernelVersion,
                    allowCurrentVersion = target.debugMode,
                )
        ) {
            is KernelSelectionResult.Selected -> {
                BuiltInCheckOutcome.Available(
                    BuiltInUpdateInfo(metadata, selection.asset, target.unameR, target.debugMode),
                )
            }

            is KernelSelectionResult.Failed -> {
                BuiltInCheckOutcome.Failed(
                    when (selection.reason) {
                        KernelSelectionFailure.UNSUPPORTED_RUNNING_KERNEL -> BuiltInUpdateError.UNSUPPORTED_KERNEL
                        KernelSelectionFailure.NO_COMPATIBLE_ASSET -> BuiltInUpdateError.NO_COMPATIBLE_KERNEL
                        KernelSelectionFailure.ALL_COMPATIBLE_ASSETS_OLDER -> BuiltInUpdateError.KERNEL_DOWNGRADE
                    },
                )
            }
        }
    } catch (e: Exception) {
        VpnHideLog.w(BUILT_IN_UPDATE_TAG, "Update check failed: ${e.message}")
        BuiltInCheckOutcome.Failed(BuiltInUpdateError.METADATA)
    }
}

private sealed interface DownloadResult {
    data class Ready(
        val bridge: File,
        val kernel: File,
        val hasBypass: Boolean,
    ) : DownloadResult

    data class Failed(
        val error: BuiltInUpdateError,
    ) : DownloadResult
}

private fun downloadAndValidate(
    context: Context,
    info: BuiltInUpdateInfo,
    onProgress: (String, Int?) -> Unit,
    onValidating: () -> Unit,
): DownloadResult {
    val requiredCacheBytes = MAX_KERNEL_ZIP_BYTES * 2 + MAX_BRIDGE_ZIP_BYTES * 2
    if (context.cacheDir.usableSpace < requiredCacheBytes) {
        return DownloadResult.Failed(BuiltInUpdateError.STORAGE)
    }
    val bridge = File(context.cacheDir, "vpnhide-bridge-${baseVersion(info.metadata.bridgeVersion)}.zip")
    val kernel = File(context.cacheDir, info.kernelAsset.name)
    bridge.delete()
    kernel.delete()
    val bridgeError =
        downloadFile(
            info.metadata.bridgeZipUrl,
            info.metadata.bridgeSha256,
            bridge,
            MAX_BRIDGE_ZIP_BYTES,
            verifyChecksum = !info.debugMode,
        ) { onProgress("bridge", it) }
    if (bridgeError != null) {
        bridge.delete()
        return DownloadResult.Failed(bridgeError)
    }
    val kernelError =
        downloadFile(
            info.kernelAsset.url,
            info.kernelAsset.sha256,
            kernel,
            MAX_KERNEL_ZIP_BYTES,
            verifyChecksum = !info.debugMode,
        ) { onProgress("kernel", it) }
    if (kernelError != null) {
        bridge.delete()
        kernel.delete()
        return DownloadResult.Failed(kernelError)
    }
    onValidating()
    if (!validateBridgeZip(bridge, info.metadata)) {
        bridge.delete()
        kernel.delete()
        return DownloadResult.Failed(BuiltInUpdateError.INVALID_BRIDGE)
    }
    val kernelValidation = validateKernelZip(kernel)
    if (kernelValidation == null) {
        bridge.delete()
        kernel.delete()
        return DownloadResult.Failed(BuiltInUpdateError.INVALID_KERNEL)
    }
    return DownloadResult.Ready(bridge, kernel, kernelValidation)
}

private fun downloadFile(
    rawUrl: String,
    expectedSha256: String,
    destination: File,
    maxBytes: Long,
    verifyChecksum: Boolean = true,
    onProgress: (Int?) -> Unit,
): BuiltInUpdateError? {
    val partial = File(destination.parentFile, "${destination.name}.part")
    partial.delete()
    return try {
        val connection = openBuiltInHttp(rawUrl, 10_000, 60_000)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return BuiltInUpdateError.DOWNLOAD
            val length = connection.contentLengthLong
            if (length > maxBytes) return BuiltInUpdateError.DOWNLOAD
            val knownLength = length.takeIf { it in 1..maxBytes }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return BuiltInUpdateError.DOWNLOAD
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(knownLength?.let { (total * 100 / it).toInt().coerceIn(0, 100) })
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (verifyChecksum && actual != expectedSha256) return BuiltInUpdateError.CHECKSUM
            if (!partial.renameTo(destination)) return BuiltInUpdateError.STORAGE
            null
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        VpnHideLog.w(BUILT_IN_UPDATE_TAG, "Download failed: ${e.message}")
        BuiltInUpdateError.DOWNLOAD
    } finally {
        partial.delete()
    }
}

private fun validateBridgeZip(
    file: File,
    metadata: BuiltInUpdateMetadata,
): Boolean =
    runCatching {
        ZipFile(file).use { zip ->
            if (!validateZipEntries(zip, MAX_BRIDGE_EXPANDED_BYTES)) return false
            val props = readModuleProps(zip, "module.prop") ?: return false
            props["id"] == "vpnhide_kpatch" &&
                baseVersion(props["version"].orEmpty()) == baseVersion(metadata.bridgeVersion) &&
                props["versionCode"]?.toIntOrNull() == metadata.bridgeVersionCode &&
                REQUIRED_BRIDGE_ENTRIES.all { zip.getEntry(it)?.isDirectory == false }
        }
    }.getOrDefault(false)

internal fun validateKernelZip(file: File): Boolean? =
    runCatching {
        ZipFile(file).use { zip ->
            if (!validateZipEntries(zip, MAX_KERNEL_EXPANDED_BYTES)) return null
            if (REQUIRED_KERNEL_ENTRIES.any { zip.getEntry(it)?.isDirectory != false }) return null
            val image = zip.getEntry("Image") ?: return null
            if (image.size <= 0) return null
            val bypass = zip.getEntry("Bypass-Image")
            if (bypass != null && (bypass.isDirectory || bypass.size <= 0)) return null
            bypass != null
        }
    }.getOrNull()

private fun validateZipEntries(
    zip: ZipFile,
    maxExpandedBytes: Long,
): Boolean {
    var count = 0
    var expanded = 0L
    val names = mutableSetOf<String>()
    val foldedNames = mutableSetOf<String>()
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        count++
        if (count > MAX_ZIP_ENTRIES) return false
        val name = entry.name
        val normalizedName = if (entry.isDirectory) name.removeSuffix("/") else name
        if (normalizedName.isBlank() || normalizedName.startsWith('/') || normalizedName.contains('\\') ||
            normalizedName.split('/').any { it == "." || it == ".." || it.isBlank() }
        ) {
            return false
        }
        if (!names.add(normalizedName) || !foldedNames.add(normalizedName.lowercase())) return false
        if (entry.size < 0 || entry.size > maxExpandedBytes) return false
        expanded += entry.size
        if (expanded > maxExpandedBytes) return false
        if (!entry.isDirectory) {
            zip.getInputStream(entry).use { input ->
                var readTotal = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    readTotal += read
                    if (readTotal > entry.size || readTotal > maxExpandedBytes) return false
                }
                if (readTotal != entry.size) return false
            }
        }
    }
    return true
}

private fun readModuleProps(
    zip: ZipFile,
    path: String,
): Map<String, String>? {
    val entry = zip.getEntry(path) ?: return null
    if (entry.size !in 1..64_000) return null
    return zip.getInputStream(entry).bufferedReader().useLines { lines ->
        lines
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
    }
}

private val REQUIRED_BRIDGE_ENTRIES =
    setOf(
        "module.prop",
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "vpnhide-ctl",
        "vpnhide-daemon",
        "META-INF/com/google/android/update-binary",
    )

private val REQUIRED_KERNEL_ENTRIES =
    setOf(
        "Image",
        "anykernel.sh",
        "tools/ak3-core.sh",
        "tools/busybox",
        "tools/magiskboot",
        "META-INF/com/google/android/update-binary",
    )

private fun prepareBridgeForAppInstall(
    source: File,
    destination: File,
): Boolean =
    runCatching {
        ZipFile(source).use { zip ->
            ZipOutputStream(FileOutputStream(destination).buffered()).use { output ->
                val entries = zip.entries()
                var customizeSeen = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "kernel-update.sh") continue
                    val copy = ZipEntry(entry.name).apply { time = entry.time }
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) {
                        if (entry.name == "customize.sh") {
                            val customize = zip.getInputStream(entry).bufferedReader().readText()
                            val start = customize.indexOf("if [ -r \"${'$'}MODPATH/kernel-update.sh\" ]; then")
                            val marker = "# Legacy targets files to migrate"
                            val markerIndex = customize.indexOf(marker)
                            val prepared =
                                if (start >= 0) {
                                    if (markerIndex <= start) return false
                                    customize.substring(0, start) +
                                        "ui_print \"- Kernel updates are managed by the VPNHide Next app\"\n\n" +
                                        customize.substring(markerIndex)
                                } else {
                                    customize
                                }
                            output.write(prepared.toByteArray())
                            customizeSeen = true
                        } else {
                            zip.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    output.closeEntry()
                }
                if (!customizeSeen) return false
            }
        }
        destination.isFile && destination.length() > 0
    }.getOrDefault(false)

internal fun prepareNonInteractiveAk3(
    source: File,
    destination: File,
): Boolean =
    runCatching {
        ZipFile(source).use { zip ->
            ZipOutputStream(FileOutputStream(destination).buffered()).use { output ->
                val entries = zip.entries()
                var patched = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val copy = ZipEntry(entry.name).apply { time = entry.time }
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) {
                        if (entry.name == "tools/ak3-core.sh") {
                            if (entry.size !in 1..MAX_AK3_CORE_BYTES.toLong()) return false
                            val core = zip.getInputStream(entry).bufferedReader().readText()
                            val call = Regex("(?m)^show_kernel_menu[ \\t]*$")
                            if (call.findAll(core).count() != 1) return false
                            output.write(call.replace(core) { NON_INTERACTIVE_AK3_SELECTION }.toByteArray())
                            patched = true
                        } else {
                            zip.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    output.closeEntry()
                }
                if (!patched) return false
            }
        }
        destination.isFile && destination.length() > 0
    }.getOrDefault(false)

private val NON_INTERACTIVE_AK3_SELECTION =
    """
case "${'$'}{VPNHIDE_IMAGE_MODE:-}" in
    normal)
        ui_print "Selected by VPNHide Next: Normal Mode"
        ;;
    bypass)
        [ -s "${'$'}AKHOME/Bypass-Image" ] || abort "Bypass-Image is missing. Aborting..."
        ui_print "Selected by VPNHide Next: Bypass Mode"
        mv -f "${'$'}AKHOME/Bypass-Image" "${'$'}AKHOME/Image" || abort "Could not select Bypass-Image"
        ;;
    *)
        abort "VPNHide image mode is missing. Aborting..."
        ;;
esac
    """.trimIndent()

private sealed interface InstallResult {
    data class Success(
        val backupPath: String,
    ) : InstallResult

    data class Failed(
        val error: BuiltInUpdateError,
        val backupPath: String? = null,
    ) : InstallResult
}

private fun installBuiltInWithRoot(
    bridge: File,
    kernel: File,
    info: BuiltInUpdateInfo,
    mode: KernelImageMode,
    onStage: (BuiltInUpdateState) -> Unit,
): InstallResult {
    onStage(BuiltInUpdateState.BackingUp(info))
    val modeValue = if (mode == KernelImageMode.BYPASS) "bypass" else "normal"
    val (backupExit, backupOutput) = suExec(buildBootBackupScript(info.unameR), timeoutSec = 180)
    val backup = extractBackupPath(backupOutput)
    if (backupExit != 0 || backup == null) {
        val error =
            if (backupExit == -1 || backupOutput.contains("vpnhide_error=root")) {
                BuiltInUpdateError.ROOT_DENIED
            } else {
                BuiltInUpdateError.BACKUP_FAILED
            }
        return InstallResult.Failed(error, backup)
    }

    onStage(BuiltInUpdateState.InstallingBridge(info))
    val (bridgeExit, bridgeOutput) =
        suExec(
            buildBridgeInstallScript(bridge.absolutePath, baseVersion(info.metadata.bridgeVersion)),
            timeoutSec = 180,
        )
    if (bridgeExit != 0) {
        val error =
            when {
                bridgeExit == -1 || bridgeOutput.contains("vpnhide_error=root") -> BuiltInUpdateError.ROOT_DENIED
                bridgeOutput.contains("vpnhide_error=manager") -> BuiltInUpdateError.UNSUPPORTED_ROOT_MANAGER
                else -> BuiltInUpdateError.BRIDGE_INSTALL_FAILED
            }
        return InstallResult.Failed(error, backup)
    }

    onStage(BuiltInUpdateState.FlashingKernel(info))
    val (kernelExit, kernelOutput) =
        suExec(buildKernelFlashScript(kernel.absolutePath, modeValue), timeoutSec = 600)
    if (kernelExit != 0) {
        VpnHideLog.w(
            BUILT_IN_UPDATE_TAG,
            "AK3 failed: exit=$kernelExit output=${kernelOutput.takeLast(1500)}",
        )
        val error =
            if (kernelExit == -1 || kernelOutput.contains("vpnhide_error=root")) {
                BuiltInUpdateError.ROOT_DENIED
            } else {
                BuiltInUpdateError.KERNEL_INSTALL_FAILED
            }
        return InstallResult.Failed(error, backup)
    }
    return InstallResult.Success(backup)
}

private fun extractBackupPath(output: String): String? =
    output
        .lineSequence()
        .firstOrNull { it.startsWith("vpnhide_backup=") }
        ?.removePrefix("vpnhide_backup=")
        ?.trim()
        ?.ifBlank { null }

internal fun buildBootBackupScript(unameR: String): String {
    val release = builtInShellQuote(unameR)
    return """
        id | grep -q 'uid=0' || { echo 'vpnhide_error=root'; exit 70; }
        SLOT=${'$'}(getprop ro.boot.slot_suffix 2>/dev/null)
        if [ -z "${'$'}SLOT" ]; then
          SLOT=${'$'}(getprop ro.boot.slot 2>/dev/null)
          [ -n "${'$'}SLOT" ] && SLOT="_${'$'}SLOT"
        fi
        [ "${'$'}SLOT" = normal ] && SLOT=
        case "${'$'}SLOT" in _a|_b|'') ;; a|b) SLOT="_${'$'}SLOT" ;; *) echo 'vpnhide_error=backup'; exit 73 ;; esac
        BOOT=
        for CANDIDATE in "/dev/block/by-name/boot${'$'}SLOT" "/dev/block/bootdevice/by-name/boot${'$'}SLOT"; do
          [ -e "${'$'}CANDIDATE" ] && { BOOT="${'$'}CANDIDATE"; break; }
        done
        [ -n "${'$'}BOOT" ] || BOOT=${'$'}(find /dev/block/platform -path "*/by-name/boot${'$'}SLOT" 2>/dev/null | head -n 1)
        [ -n "${'$'}BOOT" ] || { echo 'vpnhide_error=backup'; exit 73; }
        STORAGE=
        for DIR in /sdcard /storage/emulated/0 /data/media/0; do
          [ -d "${'$'}DIR" ] && [ -w "${'$'}DIR" ] && { STORAGE="${'$'}DIR"; break; }
        done
        [ -n "${'$'}STORAGE" ] || { echo 'vpnhide_error=backup'; exit 73; }
        SAFE_RELEASE=${'$'}(printf '%s' $release | tr -c 'A-Za-z0-9._-' '_')
        BACKUP="${'$'}STORAGE/vpnhide-boot-backup-${'$'}SAFE_RELEASE-${'$'}(date +%Y%m%d-%H%M%S).img"
        dd if="${'$'}BOOT" of="${'$'}BACKUP" bs=1048576 2>/dev/null || { rm -f "${'$'}BACKUP"; echo 'vpnhide_error=backup'; exit 73; }
        sync
        [ -s "${'$'}BACKUP" ] || { rm -f "${'$'}BACKUP"; echo 'vpnhide_error=backup'; exit 73; }
        chmod 0644 "${'$'}BACKUP" 2>/dev/null || true
        [ "${'$'}STORAGE" = /data/media/0 ] && chown 1023:1023 "${'$'}BACKUP" 2>/dev/null || true
        restorecon "${'$'}BACKUP" 2>/dev/null || true
        echo "vpnhide_backup=${'$'}BACKUP"
        """.trimIndent()
}

internal fun buildBridgeInstallScript(
    bridgePath: String,
    expectedBridgeVersion: String,
): String {
    val bridge = builtInShellQuote(bridgePath)
    val expected = builtInShellQuote(expectedBridgeVersion)
    return """
        id | grep -q 'uid=0' || { echo 'vpnhide_error=root'; exit 70; }
        WORK=/data/local/tmp/vpnhide-bridge-update-${'$'}${'$'}
        mkdir -p "${'$'}WORK" || exit 71
        trap 'rm -rf "${'$'}WORK"' EXIT
        cp $bridge "${'$'}WORK/bridge.zip" || exit 71
        chmod 0600 "${'$'}WORK/bridge.zip"

        if [ -d /data/adb/ksu ] && command -v ksud >/dev/null 2>&1; then
          ksud module install "${'$'}WORK/bridge.zip"
        elif [ -d /data/adb/ap ] && command -v apd >/dev/null 2>&1; then
          apd module install "${'$'}WORK/bridge.zip"
        elif command -v magisk >/dev/null 2>&1; then
          magisk --install-module "${'$'}WORK/bridge.zip"
        else
          echo 'vpnhide_error=manager'
          exit 72
        fi
        [ ${'$'}? -eq 0 ] || { echo 'vpnhide_error=bridge'; exit 74; }
        PROP=/data/adb/modules_update/vpnhide_kpatch/module.prop
        [ -f "${'$'}PROP" ] || PROP=/data/adb/modules/vpnhide_kpatch/module.prop
        grep -q '^id=vpnhide_kpatch${'$'}' "${'$'}PROP" || { echo 'vpnhide_error=bridge'; exit 74; }
        ACTUAL=${'$'}(sed -n 's/^version=v\{0,1\}//p' "${'$'}PROP" | head -n 1)
        [ "${'$'}ACTUAL" = $expected ] || { echo 'vpnhide_error=bridge'; exit 74; }
        """.trimIndent()
}

internal fun buildKernelFlashScript(
    kernelPath: String,
    mode: String,
): String {
    val kernel = builtInShellQuote(kernelPath)
    val selectedMode = builtInShellQuote(mode)
    return """
        id | grep -q 'uid=0' || { echo 'vpnhide_error=root'; exit 70; }
        WORK=/data/local/tmp/vpnhide-kernel-update-${'$'}${'$'}
        mkdir -p "${'$'}WORK" || exit 71
        trap 'rm -rf "${'$'}WORK"' EXIT
        cp $kernel "${'$'}WORK/kernel.zip" || exit 71
        chmod 0600 "${'$'}WORK/kernel.zip"
        INSTALLER="${'$'}WORK/META-INF/com/google/android/update-binary"
        unzip -oq "${'$'}WORK/kernel.zip" 'META-INF/com/google/android/update-binary' -d "${'$'}WORK" || exit 75
        [ -s "${'$'}INSTALLER" ] || exit 75
        chmod 0755 "${'$'}INSTALLER"
        AKHOME="${'$'}WORK/ak3" VPNHIDE_IMAGE_MODE=$selectedMode sh "${'$'}INSTALLER" 3 1 "${'$'}WORK/kernel.zip"
        [ ${'$'}? -eq 0 ] || exit 76
        sync
        """.trimIndent()
}

private fun downloadSmallText(
    rawUrl: String,
    limit: Int,
): String? {
    val connection = openBuiltInHttp(rawUrl, 5_000, 15_000)
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = connection.inputStream.use { it.readBuiltInLimited(limit) } ?: return null
        return bytes.toString(Charsets.UTF_8)
    } finally {
        connection.disconnect()
    }
}

private fun openBuiltInHttp(
    rawUrl: String,
    connectMs: Int,
    readMs: Int,
): HttpURLConnection =
    (URL(rawUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "vpnhide-android")
        setRequestProperty("Accept", "application/vnd.github+json")
        connectTimeout = connectMs
        readTimeout = readMs
    }

private fun InputStream.readBuiltInLimited(limit: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) return output.toByteArray()
        total += read
        if (total > limit) return null
        output.write(buffer, 0, read)
    }
}

private fun builtInShellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
