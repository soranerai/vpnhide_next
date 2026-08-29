package dev.soranerai.vpnhidenext

import android.content.Context
import dev.soranerai.vpnhidenext.domain.usecase.buildNativeInstallRecommendation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

private const val KMOD_UPDATE_TAG = "VpnHide-KmodUpdate"
private const val KMOD_METADATA_ROOT =
    "https://raw.githubusercontent.com/soranerai/vpnhide_next/main/update-json"
private const val MAX_METADATA_BYTES = 64 * 1024
private const val MAX_ZIP_BYTES = 32L * 1024 * 1024
private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

internal val SUPPORTED_KMOD_KMIS =
    setOf(
        "android12-5.10",
        "android13-5.10",
        "android13-5.15",
        "android14-5.15",
        "android14-6.1",
        "android15-6.6",
        "android16-6.12",
    )

internal data class KmodUpdateTarget(
    val installedVersion: String,
    val kmi: String,
)

internal data class KmodUpdateInfo(
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val sha256: String,
    val kmi: String,
)

internal enum class KmodUpdateError {
    DOWNLOAD,
    CHECKSUM,
    INVALID_PACKAGE,
    ROOT_DENIED,
    UNSUPPORTED_ROOT_MANAGER,
    INSTALL_FAILED,
}

internal sealed interface KmodUpdateState {
    data object None : KmodUpdateState

    data class Available(
        val info: KmodUpdateInfo,
    ) : KmodUpdateState

    data class Downloading(
        val info: KmodUpdateInfo,
        val progress: Int?,
    ) : KmodUpdateState

    data class Installing(
        val info: KmodUpdateInfo,
    ) : KmodUpdateState

    data class AwaitingReboot(
        val version: String,
    ) : KmodUpdateState

    data class Failed(
        val info: KmodUpdateInfo,
        val error: KmodUpdateError,
    ) : KmodUpdateState
}

internal fun resolveKmodUpdateTarget(
    installedVersion: String?,
    installedKmi: String?,
    unameR: String?,
): KmodUpdateTarget? {
    val version = installedVersion?.takeIf { it.isNotBlank() } ?: return null
    val recommendation =
        unameR
            ?.takeIf { it.isNotBlank() }
            ?.let { buildNativeInstallRecommendation(it, "") }
    val kmi = installedKmi ?: recommendation?.recommendedGkiVariant ?: return null
    if (kmi !in SUPPORTED_KMOD_KMIS) return null

    // An installed and active module is authoritative for an ambiguous custom
    // kernel. For an exact uname match, refuse to update a different variant.
    if (installedKmi != null && recommendation != null && !recommendation.variantAmbiguous &&
        recommendation.recommendedGkiVariant != installedKmi
    ) {
        return null
    }
    return KmodUpdateTarget(version, kmi)
}

internal fun resolveKmodInstallTarget(kmi: String?): KmodUpdateTarget? =
    kmi?.takeIf { it in SUPPORTED_KMOD_KMIS }?.let { KmodUpdateTarget("0.0.0", it) }

internal fun parseKmodUpdateMetadata(
    raw: String,
    kmi: String,
    installedVersion: String,
    appVersion: String,
): KmodUpdateInfo? {
    if (kmi !in SUPPORTED_KMOD_KMIS) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return validateKmodUpdateFields(
        version = json.optString("version"),
        versionCode = json.optInt("versionCode", -1),
        zipUrl = json.optString("zipUrl"),
        sha256 = json.optString("sha256"),
        kmi = kmi,
        installedVersion = installedVersion,
        appVersion = appVersion,
    )
}

internal fun validateKmodUpdateFields(
    version: String,
    versionCode: Int,
    zipUrl: String,
    sha256: String,
    kmi: String,
    installedVersion: String,
    appVersion: String,
): KmodUpdateInfo? {
    if (kmi !in SUPPORTED_KMOD_KMIS || version.isBlank()) return null
    if (!isNewerVersion(version, installedVersion)) return null
    if (!CompatibilityResolver.isKmodCompatibleWithApp(appVersion, version)) return null
    if (versionCode < 0) return null
    val expectedName = "vpnhide-kmod-$kmi.zip"
    if (!isTrustedKmodDownloadUrl(zipUrl, expectedName, version)) return null
    val normalizedSha256 = sha256.lowercase()
    if (!normalizedSha256.matches(Regex("[0-9a-f]{64}"))) return null
    return KmodUpdateInfo(version, versionCode, zipUrl, normalizedSha256, kmi)
}

internal fun isTrustedKmodDownloadUrl(
    raw: String,
    expectedName: String,
    expectedVersion: String,
): Boolean =
    isTrustedGithubReleaseAssetUrl(
        raw,
        owner = "soranerai",
        repository = "vpnhide_next",
        tag = "v${normalizeVersion(expectedVersion)}",
        expectedName = expectedName,
    )

internal object KmodUpdateCache {
    private val _state = MutableStateFlow<KmodUpdateState>(KmodUpdateState.None)
    val state: StateFlow<KmodUpdateState> = _state.asStateFlow()
    private var target: KmodUpdateTarget? = null
    private var checkJob: Job? = null
    private val generation = RequestGeneration()

    fun ensureFresh(
        scope: CoroutineScope,
        updateTarget: KmodUpdateTarget,
    ) {
        if (target == updateTarget && (_state.value != KmodUpdateState.None || checkJob?.isActive == true)) return
        refresh(scope, updateTarget)
    }

    fun refresh(
        scope: CoroutineScope,
        updateTarget: KmodUpdateTarget,
    ) {
        if (_state.value is KmodUpdateState.Downloading || _state.value is KmodUpdateState.Installing) return
        checkJob?.cancel()
        val requestGeneration = generation.next()
        target = updateTarget
        _state.value = KmodUpdateState.None
        checkJob =
            scope.launch {
                val info =
                    withContext(Dispatchers.IO) {
                        checkForKmodUpdate(updateTarget)
                    }
                if (target == updateTarget && generation.isCurrent(requestGeneration)) {
                    _state.value = info?.let(KmodUpdateState::Available) ?: KmodUpdateState.None
                }
            }
    }

    fun install(
        scope: CoroutineScope,
        context: Context,
        info: KmodUpdateInfo,
    ) {
        if (_state.value is KmodUpdateState.Downloading || _state.value is KmodUpdateState.Installing) return
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    downloadAndInstallKmod(
                        context.applicationContext,
                        info,
                        onProgress = { progress ->
                            _state.value = KmodUpdateState.Downloading(info, progress)
                        },
                        onInstalling = { _state.value = KmodUpdateState.Installing(info) },
                    )
                }
            _state.value =
                if (result == null) {
                    KmodUpdateState.AwaitingReboot(normalizeVersion(info.version))
                } else {
                    KmodUpdateState.Failed(info, result)
                }
        }
    }

    fun reboot(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            suExec("svc power reboot", timeoutSec = 5)
        }
    }
}

private fun checkForKmodUpdate(target: KmodUpdateTarget): KmodUpdateInfo? {
    return try {
        val url = "$KMOD_METADATA_ROOT/update-kmod-${target.kmi}.json"
        val connection = openHttp(url, 5_000, 5_000)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readLimited(MAX_METADATA_BYTES) } ?: return null
            parseKmodUpdateMetadata(
                bytes.toString(Charsets.UTF_8),
                target.kmi,
                target.installedVersion,
                BuildConfig.VERSION_NAME,
            )
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        VpnHideLog.d(KMOD_UPDATE_TAG, "Update check failed: ${e.message}")
        null
    }
}

private fun downloadAndInstallKmod(
    context: Context,
    info: KmodUpdateInfo,
    onProgress: (Int?) -> Unit,
    onInstalling: () -> Unit,
): KmodUpdateError? {
    val destination = File(context.cacheDir, "vpnhide-kmod-${info.kmi}-${info.version}.zip")
    return try {
        val downloadError = downloadKmod(info, destination, onProgress)
        if (downloadError != null) return downloadError
        if (!validateKmodZip(destination, info)) return KmodUpdateError.INVALID_PACKAGE
        onInstalling()
        installKmodWithRoot(destination, info)
    } finally {
        destination.delete()
    }
}

private fun downloadKmod(
    info: KmodUpdateInfo,
    destination: File,
    onProgress: (Int?) -> Unit,
): KmodUpdateError? {
    return try {
        val connection = openHttp(info.zipUrl, 10_000, 30_000)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return KmodUpdateError.DOWNLOAD
            val contentLength = connection.contentLengthLong.takeIf { it in 1..MAX_ZIP_BYTES }
            if (connection.contentLengthLong > MAX_ZIP_BYTES) return KmodUpdateError.DOWNLOAD
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ZIP_BYTES) return KmodUpdateError.DOWNLOAD
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(contentLength?.let { (total * 100 / it).toInt().coerceIn(0, 100) })
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != info.sha256) KmodUpdateError.CHECKSUM else null
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        VpnHideLog.w(KMOD_UPDATE_TAG, "Download failed: ${e.message}")
        KmodUpdateError.DOWNLOAD
    }
}

private fun validateKmodZip(
    file: File,
    info: KmodUpdateInfo,
): Boolean =
    runCatching {
        ZipFile(file).use { zip ->
            var expanded = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith('/') || name.split('/').any { it == ".." }) return false
                if (entry.size > 0) expanded += entry.size
                if (expanded > MAX_UNCOMPRESSED_BYTES) return false
            }
            val propEntry = zip.getEntry("module.prop") ?: return false
            val props =
                zip.getInputStream(propEntry).bufferedReader().useLines { lines ->
                    lines
                        .mapNotNull { line ->
                            val parts = line.split('=', limit = 2)
                            if (parts.size == 2) parts[0] to parts[1] else null
                        }.toMap()
                }
            props["id"] == "vpnhide_kmod" &&
                props["gkiVariant"] == info.kmi &&
                baseVersion(props["version"].orEmpty()) == baseVersion(info.version) &&
                zip.getEntry("vpnhide_kmod.ko") != null &&
                zip.getEntry("META-INF/com/google/android/update-binary") != null
        }
    }.getOrDefault(false)

private fun installKmodWithRoot(
    zip: File,
    info: KmodUpdateInfo,
): KmodUpdateError? {
    val source = shellQuote(zip.absolutePath)
    val expectedVersion = shellQuote(baseVersion(info.version))
    val expectedKmi = shellQuote(info.kmi)
    val script =
        """
        id | grep -q 'uid=0' || { echo 'vpnhide_error=root'; exit 70; }
        SRC=$source
        TMP=/data/local/tmp/vpnhide-kmod-update.zip
        rm -f "${'$'}TMP"
        cp "${'$'}SRC" "${'$'}TMP" || exit 71
        chmod 0600 "${'$'}TMP"
        trap 'rm -f "${'$'}TMP"' EXIT
        if [ -d /data/adb/ksu ] && command -v ksud >/dev/null 2>&1; then
          ksud module install "${'$'}TMP"
        elif [ -d /data/adb/ap ] && command -v apd >/dev/null 2>&1; then
          apd module install "${'$'}TMP"
        elif command -v magisk >/dev/null 2>&1; then
          magisk --install-module "${'$'}TMP"
        else
          echo 'vpnhide_error=manager'
          exit 72
        fi
        INSTALL_EXIT=${'$'}?
        [ "${'$'}INSTALL_EXIT" -eq 0 ] || exit "${'$'}INSTALL_EXIT"
        PROP=/data/adb/modules_update/vpnhide_kmod/module.prop
        [ -f "${'$'}PROP" ] || PROP=/data/adb/modules/vpnhide_kmod/module.prop
        grep -q '^id=vpnhide_kmod${'$'}' "${'$'}PROP" || exit 73
        grep -q '^gkiVariant='$expectedKmi'${'$'}' "${'$'}PROP" || exit 74
        ACTUAL_VERSION=${'$'}(sed -n 's/^version=v\{0,1\}//p' "${'$'}PROP" | head -n 1)
        [ "${'$'}ACTUAL_VERSION" = $expectedVersion ] || exit 75
        """.trimIndent()
    val (exit, output) = suExec(script, timeoutSec = 120)
    if (exit == 0) return null
    VpnHideLog.w(KMOD_UPDATE_TAG, "Root installer failed: exit=$exit output=${output.takeLast(500)}")
    return when {
        exit == -1 || output.contains("vpnhide_error=root") -> KmodUpdateError.ROOT_DENIED
        output.contains("vpnhide_error=manager") -> KmodUpdateError.UNSUPPORTED_ROOT_MANAGER
        else -> KmodUpdateError.INSTALL_FAILED
    }
}

private fun openHttp(
    rawUrl: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): HttpURLConnection =
    (URL(rawUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "vpnhide-android")
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
    }

private fun InputStream.readLimited(limit: Int): ByteArray? {
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

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
