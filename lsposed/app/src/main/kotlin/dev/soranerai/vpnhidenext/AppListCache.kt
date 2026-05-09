package dev.soranerai.vpnhidenext

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Common per-installed-app fields used by every Protection screen
 * (Tun targets, App hiding, Ports). The three screens merge this with
 * their own per-screen toggle state at render time.
 */
internal data class AppSummary(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val userIds: List<Int> = emptyList(),
)

/**
 * Append a profile list to an app label so users can tell that
 * Telegram-in-Second-Space and Telegram-in-main are the same target.
 * Suppresses the suffix when the app is only in the current profile —
 * otherwise every row in the list reads "Cromite (0)", "Chrome (0)",
 * ... for users who don't even have a secondary profile.
 *
 * When [userNames] contains an entry for a user ID, its friendly name
 * ("Work", "Second Space") is used; otherwise we fall back to the raw
 * numeric ID. This keeps the helper usable even before the user-name
 * map is loaded (no root / parse failure).
 */
internal fun labelWithUsers(
    label: String,
    userIds: List<Int>,
    userNames: Map<Int, String> = emptyMap(),
): String {
    if (userIds.isEmpty()) return label
    val currentUser = Process.myUid() / 100000
    val onlyCurrent = userIds.size == 1 && userIds[0] == currentUser
    if (onlyCurrent) return label
    val formatted = userIds.joinToString(", ") { userNames[it] ?: it.toString() }
    return "$label ($formatted)"
}

/**
 * App-scoped cache for the installed-app list. Loaded asynchronously
 * at startup; Protection screens subscribe to `apps` and render
 * instantly on tab switch.
 *
 * [refreshCounter] increments on every refresh — screens that maintain
 * their own per-screen state (targets.txt / observer files etc.) key
 * their reload `LaunchedEffect` on it, so the TopBar refresh button
 * rehydrates *everything*, not just the package+icon cache.
 */
internal object AppListCache {
    private val _apps = MutableStateFlow<List<AppSummary>?>(null)
    val apps: StateFlow<List<AppSummary>?> = _apps.asStateFlow()

    /** user_id → friendly profile name (e.g. 10 → "Work"). Populated
     * from `pm list users` alongside the package scan. Empty map if
     * root isn't available or parsing failed — `labelWithUsers` falls
     * back to numeric IDs in that case.
     */
    private val _userNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val userNames: StateFlow<Map<Int, String>> = _userNames.asStateFlow()

    private val _refreshCounter = MutableStateFlow(0)
    val refreshCounter: StateFlow<Int> = _refreshCounter.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var inflight: Job? = null

    /** Kick off an initial load if not already loaded or loading. */
    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        if (_apps.value != null || inflight?.isActive == true) return
        inflight = scope.launch { reload(context.applicationContext) }
    }

    /** Force a reload and bump the refresh counter so screens re-read
     * their per-screen state (targets.txt / observer files etc.) too.
     */
    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        inflight?.cancel()
        inflight = scope.launch { reload(context.applicationContext) }
    }

    private suspend fun reload(appContext: Context) {
        _loading.value = true
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val pm = appContext.packageManager
                    val (packages, users) = loadPackagesAndUsersViaRoot()
                    _userNames.value = users
                    if (packages.isNotEmpty()) {
                        packages.entries
                            .map { (pkg, meta) ->
                                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                                val archiveInfo =
                                    if (info == null) loadArchiveApplicationInfo(pm, meta.apkPath) else null
                                val effectiveInfo = info ?: archiveInfo

                                // Archive-parsed ApplicationInfo doesn't carry
                                // FLAG_SYSTEM (that bit is attached by PM at
                                // install time, not stored in the manifest), so
                                // for secondary-only packages we'd misclassify
                                // every system app as user-installed. Fall back
                                // to the APK path: /data/app/... is user-
                                // installed, everything else is baked into the
                                // system image.
                                val isSystem =
                                    if (info != null) {
                                        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                    } else {
                                        !meta.apkPath.startsWith("/data/app/")
                                    }

                                AppSummary(
                                    packageName = pkg,
                                    label = effectiveInfo?.loadLabel(pm)?.toString() ?: pkg,
                                    icon = effectiveInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                                    isSystem = isSystem,
                                    userIds = meta.userIds,
                                )
                            }.sortedBy { it.label.lowercase() }
                    } else {
                        // Fallback: current-profile only (legacy behavior)
                        pm
                            .getInstalledApplications(0)
                            .map { info ->
                                AppSummary(
                                    packageName = info.packageName,
                                    label = info.loadLabel(pm).toString(),
                                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                    userIds = listOf(Process.myUid() / 100000),
                                )
                            }.sortedBy { it.label.lowercase() }
                    }
                }

            _apps.value = loaded
            _refreshCounter.value = _refreshCounter.value + 1
        } finally {
            _loading.value = false
        }
    }

    private data class PkgMeta(
        val apkPath: String,
        val userIds: List<Int>,
    )

    private const val USERS_SENTINEL = "===VPNHIDE-USERS-BOUNDARY==="

    /**
     * Enumerate every installed package and every user profile in a
     * single `su` invocation. `pm list packages -U -f --user all` gives
     * APK path + UID per (pkg, user) tuple; `pm list users` gives the
     * friendly profile names ("Work", "Second Space") that the UI
     * renders instead of raw user IDs. Two commands, one `su` spawn —
     * separated by a sentinel the parser splits on.
     *
     * Packages output is one of:
     *   package:<apk_path>=<pkg> uid:<uid>            (single-user)
     *   package:<apk_path>=<pkg> uid:<uid>,<uid>,...  (AOSP --user all)
     *   package:<apk_path>=<pkg> uid:<uid>            (repeated per user
     *                                                  on some ROMs)
     * Users output is:
     *   UserInfo{<id>:<name>:<flags>} [running ...]
     */
    private fun loadPackagesAndUsersViaRoot(): Pair<Map<String, PkgMeta>, Map<Int, String>> {
        val (exitCode, raw) =
            suExec(
                "pm list packages -U -f --user all 2>/dev/null; " +
                    "echo '$USERS_SENTINEL'; " +
                    "pm list users 2>/dev/null",
            )
        if (exitCode != 0) return emptyMap<String, PkgMeta>() to emptyMap()
        val parts = raw.split(USERS_SENTINEL, limit = 2)
        val packages = parsePackages(parts[0])
        val users = if (parts.size > 1) parseUsers(parts[1]) else emptyMap()
        return packages to users
    }

    private fun parsePackages(raw: String): Map<String, PkgMeta> {
        val out = LinkedHashMap<String, PkgMeta>()
        raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .forEach { line ->
                val body = line.removePrefix("package:")
                val uidMarker = body.lastIndexOf(" uid:")
                if (uidMarker <= 0) return@forEach
                val pathAndPkg = body.substring(0, uidMarker)
                val uidPart = body.substring(uidMarker + " uid:".length).trim()
                val eq = pathAndPkg.lastIndexOf('=')
                if (eq <= 0 || eq >= pathAndPkg.lastIndex) return@forEach
                val apkPath = pathAndPkg.substring(0, eq).trim()
                val pkg = pathAndPkg.substring(eq + 1).trim()
                if (apkPath.isEmpty() || pkg.isEmpty()) return@forEach

                val userIds =
                    uidPart
                        .split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .map { it / 100000 }

                val existing = out[pkg]
                out[pkg] =
                    if (existing == null) {
                        PkgMeta(apkPath, userIds.distinct().sorted())
                    } else {
                        existing.copy(
                            userIds = (existing.userIds + userIds).distinct().sorted(),
                        )
                    }
            }
        return out
    }

    // `UserInfo{10:Work:1030}` — flags are trailing hex, name is
    // everything between the first `:` and the last `:`. The lazy
    // name-group (.*?) combined with a greedy flags-group anchored to
    // `}` handles names that contain `:` (rare, but Android allows it).
    private val userLine = Regex("""UserInfo\{(\d+):(.*?):[0-9a-fA-F]+\}""")

    private fun parseUsers(raw: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        raw.lineSequence().forEach { line ->
            val m = userLine.find(line) ?: return@forEach
            val id = m.groupValues[1].toIntOrNull() ?: return@forEach
            val name = m.groupValues[2].trim()
            if (name.isNotEmpty()) out[id] = name
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun loadArchiveApplicationInfo(
        pm: PackageManager,
        apkPath: String?,
    ): ApplicationInfo? {
        if (apkPath.isNullOrBlank()) return null
        val pkgInfo = runCatching { pm.getPackageArchiveInfo(apkPath, 0) }.getOrNull() ?: return null
        val appinfo = pkgInfo.applicationInfo ?: return null
        appinfo.sourceDir = apkPath
        appinfo.publicSourceDir = apkPath
        return appinfo
    }
}
