package dev.soranerai.vpnhidenext

import android.content.Context
import android.util.Base64
import dev.soranerai.vpnhidenext.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Per-screen protection state from root-owned files and package
 * manager lookups, cached once for the lifetime of the app session.
 *
 * Without this cache, every tab switch into Protection triggered 3-4
 * sequential `suExec` roundtrips per screen. Root shell roundtrips
 * are ~50-100ms each on most devices, so a single tab switch added
 * hundreds of milliseconds of "loading" time even after AppListCache
 * made the package list itself instant. Bundling every read into a
 * single batched shell invocation + caching the result means subsequent
 * tab switches render immediately from memory.
 *
 * Invalidated when:
 * - The user taps Save on any Protection screen (target files have
 *   just been overwritten — need a fresh read next time).
 * - The user taps the top-bar Refresh button on Protection.
 */
internal data class TargetsSnapshot(
    val listMode: dev.soranerai.vpnhidenext.db.PolicyListMode,
    val kmodModuleInstalled: Boolean,
    val kmodActive: Boolean,
    val kmodTargets: Set<Pair<String, Int>>,
    val lsposedEntries: Set<Pair<String, Int>>,
    val portsObservers: Set<Pair<String, Int>>,
    val systemPolicyExplicitApps: Set<Pair<String, Int>>,
    val portRules: Map<Pair<String, Int>, List<PortRule>>,
    val kernelHookMasks: Map<Pair<String, Int>, Long>,
    val javaHookMasks: Map<Pair<String, Int>, Long>,
    val massPortRules: List<PortRule>,
    val ifacePrefixes: List<String>,
    val uidToPkg: Map<Int, String>,
    val packageUids: Map<Pair<String, Int>, Int>,
    val systemPackages: Set<Pair<String, Int>>,
)

internal object TargetsCache : AsyncCache<TargetsSnapshot>() {
    val snapshot: StateFlow<TargetsSnapshot?> = state
    private val reloadMutex = Mutex()

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchEnsureLoaded(scope) {
            reload(context.applicationContext)
        }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        launchReload(scope) {
            reload(context.applicationContext)
        }
    }

    /**
     * Refreshes after a policy write and does not return until the fresh
     * snapshot has been published. Save flows use this as a commit barrier so
     * the picker cannot be rehydrated from an older target snapshot.
     */
    suspend fun refreshAndWait(context: Context): TargetsSnapshot =
        reloadNow {
            reload(context.applicationContext)
        }

    // Read only module status and Package Manager state. Policy selections
    // come from the app-owned JSON database; no target files are consulted.
    private const val SENTINEL = "===VPNHIDE-TARGETS-BOUNDARY==="
    private const val END = "===VPNHIDE-TARGETS-END==="

    internal suspend fun reload(appContext: Context): TargetsSnapshot =
        reloadMutex.withLock { reloadLocked(appContext) }

    private suspend fun reloadLocked(appContext: Context): TargetsSnapshot {
        val db = AppDatabase.getInstance(appContext)
        val appDao = db.appDao()
        val portRuleDao = db.portRuleDao()
        val massPortRuleDao = db.massPortRuleDao()
        val ifacePrefixDao = db.ifacePrefixDao()
        val listMode =
            db.globalConfigDao().getConfig()?.listMode
                ?: dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST

        var dbPopulatedOrUpdated = false

        // Keep the manager package in the declarative policy. In ALLOWLIST it
        // is an ordinary unlisted eligible UID. In BLACKLIST the native layer
        // must include it so VPNHide can protect its own process as well.
        val selfPkg = appContext.packageName
        val selfProto = appDao.getAppProtection(selfPkg, 0)
        if (selfProto == null) {
            appDao.insertAppProtection(
                dev.soranerai.vpnhidenext.db.AppProtection(
                    packageName = selfPkg,
                    userId = 0,
                    uid = appContext.applicationInfo.uid,
                    kmod = listMode == dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST,
                ),
            )
            dbPopulatedOrUpdated = true
        } else {
            val expectedKmod = listMode == dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST
            if (selfProto.kmod != expectedKmod) {
                appDao.insertAppProtection(selfProto.copy(kmod = expectedKmod))
                dbPopulatedOrUpdated = true
            }
        }

        // Now read from DB to heal and get the actual data
        var apps = appDao.getAllAppProtectionSync()
        val massRules = massPortRuleDao.getMassRulesSync()
        val ifacePrefixes = ifacePrefixDao.getAllPrefixesSync()

        // Still need module status and PM list
        val statusScript =
            """
            echo "$SENTINEL KMOD_MODULE_DIR"
            [ -d $kmodModuleDir ] && echo 1 || echo 0
            echo "$SENTINEL LSMOD"
            lsmod | grep -q vpnhide_kmod && echo 1 || echo 0
            echo "$SENTINEL PM_LIST"
            pm list packages -U --user all 2>/dev/null || true
            echo "$SENTINEL PM_SYSTEM"
            pm list packages -s -U --user all 2>/dev/null || true
            echo "$END"
            """.trimIndent()

        val (_, statusOut) = withContext(Dispatchers.IO) { suExec(statusScript) }
        val statusSnapshot = parse(statusOut)

        // Database Healing:
        // 1. Reconcile persisted UIDs with Package Manager. Policy ABI v4
        //    treats this application-owned snapshot as authoritative.
        // 2. Prune uninstalled apps (if actualUid == 0 and not selfPkg)
        if (statusSnapshot.packageUids.isNotEmpty()) {
            var modified = false
            for (app in apps) {
                if (app.packageName == selfPkg) continue

                val actualUid =
                    statusSnapshot.packageUids[app.packageName to app.userId] ?: 0

                if (actualUid == 0 || !actualUid.isEligiblePolicyUid()) {
                    // Uninstalled and core-UID packages are not policy rows.
                    appDao.deleteAppProtection(app)
                    modified = true
                } else if (app.uid != actualUid) {
                    // Heal missing and stale UIDs after reinstall/restore.
                    appDao.insertAppProtection(app.copy(uid = actualUid))
                    modified = true
                }
            }
            if (modified) {
                apps = appDao.getAllAppProtectionSync()
                dbPopulatedOrUpdated = true
            }
        }

        // v3 relied on daemon-side PM expansion for implicit system
        // exceptions. Materialize any missing v4 defaults in the app-owned
        // config. Existing entries win by full UID, preserving explicit
        // deselection and shared-UID policy.
        if (listMode == dev.soranerai.vpnhidenext.db.PolicyListMode.ALLOWLIST &&
            statusSnapshot.systemPackages.isNotEmpty()
        ) {
            val defaults =
                missingSystemPolicyDefaults(listMode, apps, statusSnapshot.systemPackages, selfPkg)
            if (defaults.isNotEmpty()) {
                appDao.insertAppProtections(defaults)
                apps = appDao.getAllAppProtectionSync()
                dbPopulatedOrUpdated = true
            }
        }

        if (dbPopulatedOrUpdated) {
            dev.soranerai.vpnhidenext.db.DatabaseSync
                .sync(appContext)
        }

        val portRulesMap = mutableMapOf<Pair<String, Int>, List<PortRule>>()
        for (app in apps) {
            if (app.portHiding) {
                portRulesMap[app.packageName to app.userId] =
                    portRuleDao.getRulesForAppSync(app.packageName, app.userId).map {
                        PortRule(
                            id = it.id.toString(),
                            startPort = it.startPort,
                            endPort = it.endPort,
                            protocol = it.protocol,
                            label = it.label,
                            enabled = it.enabled,
                        )
                    }
            }
        }

        val snapshot =
            TargetsSnapshot(
                listMode = listMode,
                kmodModuleInstalled = statusSnapshot.kmodModuleInstalled,
                kmodActive = statusSnapshot.kmodActive,
                kmodTargets = apps.filter { it.kmod }.map { it.packageName to it.userId }.toSet(),
                lsposedEntries = apps.filter { it.lsposed }.map { it.packageName to it.userId }.toSet(),
                portsObservers = apps.filter { it.portHiding }.map { it.packageName to it.userId }.toSet(),
                systemPolicyExplicitApps =
                    apps.filter { it.systemPolicyExplicit }.map { it.packageName to it.userId }.toSet(),
                portRules = portRulesMap,
                kernelHookMasks =
                    apps
                        .mapNotNull { a -> a.kernelHookMask?.let { (a.packageName to a.userId) to it } }
                        .toMap(),
                javaHookMasks =
                    apps
                        .mapNotNull { a -> a.javaHookMask?.let { (a.packageName to a.userId) to it } }
                        .toMap(),
                massPortRules =
                    massRules.map { m ->
                        PortRule(
                            id = m.id.toString(),
                            startPort = m.startPort,
                            endPort = m.endPort,
                            protocol = m.protocol,
                            label = m.label,
                            enabled = m.enabled,
                        )
                    },
                ifacePrefixes = ifacePrefixes,
                uidToPkg = statusSnapshot.uidToPkg,
                packageUids = statusSnapshot.packageUids,
                systemPackages = statusSnapshot.systemPackages,
            )
        return snapshot
    }

    private fun parse(out: String): TargetsSnapshot {
        val sections = mutableMapOf<String, String>()
        var currentKey: String? = null
        val buf = StringBuilder()
        for (line in out.lines()) {
            when {
                line.startsWith(SENTINEL) -> {
                    currentKey?.let { sections[it] = buf.toString() }
                    buf.clear()
                    currentKey = line.removePrefix("$SENTINEL ").trim()
                }

                line.startsWith(END) -> {
                    currentKey?.let { sections[it] = buf.toString() }
                    currentKey = null
                }

                currentKey != null -> {
                    buf.appendLine(line)
                }
            }
        }

        val uidToPkg = mutableMapOf<Int, String>()
        val packageUids = mutableMapOf<Pair<String, Int>, Int>()
        sections["PM_LIST"]?.lines()?.forEach { line ->
            if (!line.startsWith("package:")) return@forEach
            val parts = line.split(" uid:")
            if (parts.size < 2) return@forEach
            val pkg = parts[0].removePrefix("package:").trim()
            val uidsStr = parts[1].trim()
            uidsStr.split(",").forEach { uidStr ->
                val uid = uidStr.trim().toIntOrNull()
                if (uid != null) {
                    uidToPkg.putIfAbsent(uid, pkg)
                    packageUids[pkg to (uid / 100000)] = uid
                }
            }
        }
        val systemPackages = mutableSetOf<Pair<String, Int>>()
        sections["PM_SYSTEM"]?.lines()?.forEach { line ->
            if (!line.startsWith("package:")) return@forEach
            val parts = line.split(" uid:")
            if (parts.size < 2) return@forEach
            val pkg = parts[0].removePrefix("package:").trim()
            parts[1].trim().split(",").forEach { uidStr ->
                uidStr.trim().toIntOrNull()?.let { uid -> systemPackages.add(pkg to uid) }
            }
        }

        return TargetsSnapshot(
            listMode = dev.soranerai.vpnhidenext.db.PolicyListMode.BLACKLIST,
            kmodModuleInstalled = sections["KMOD_MODULE_DIR"]?.trim() == "1",
            kmodActive = sections["LSMOD"]?.trim() == "1",
            kmodTargets = emptySet(),
            lsposedEntries = emptySet(),
            portsObservers = emptySet(),
            systemPolicyExplicitApps = emptySet(),
            portRules = emptyMap(),
            kernelHookMasks = emptyMap(),
            javaHookMasks = emptyMap(),
            massPortRules = emptyList(),
            ifacePrefixes = emptyList(),
            uidToPkg = uidToPkg,
            packageUids = packageUids,
            systemPackages = systemPackages,
        )
    }
}
