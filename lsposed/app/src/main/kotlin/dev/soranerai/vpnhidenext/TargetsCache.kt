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
    val kmodModuleInstalled: Boolean,
    val kmodActive: Boolean,
    val kmodTargets: Set<Pair<String, Int>>,
    val lsposedTargets: Set<Pair<String, Int>>,
    val portsObservers: Set<Pair<String, Int>>,
    val portRules: Map<Pair<String, Int>, List<PortRule>>,
    val massPortRules: List<PortRule>,
    val ifacePrefixes: List<String>,
    val uidToPkg: Map<Int, String>,
)

internal object TargetsCache : AsyncCache<TargetsSnapshot>() {
    val snapshot: StateFlow<TargetsSnapshot?> = state

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

    // Bundle every read into a single `suExec` call separated by
    // distinctive banner lines. One root roundtrip beats 8 serial
    // ones — PM + 7 cats typically take <100ms this way.
    private const val SENTINEL = "===VPNHIDE-TARGETS-BOUNDARY==="
    private const val END = "===VPNHIDE-TARGETS-END==="

    private val BATCH_SCRIPT =
        """
        echo "$SENTINEL KMOD_MODULE_DIR"
        [ -d $KMOD_MODULE_DIR ] && echo 1 || echo 0
        echo "$SENTINEL LSMOD"
        lsmod | grep -q vpnhide_kmod && echo 1 || echo 0
        echo "$SENTINEL KMOD_TARGETS"
        cat $KMOD_TARGETS 2>/dev/null || true
        echo "$SENTINEL PORTS_OBSERVERS"
        cat $PORTS_OBSERVERS_FILE 2>/dev/null || true
        echo "$SENTINEL PORTS_RULES"
        cat $PORTS_RULES_FILE 2>/dev/null || true
        echo "$SENTINEL IFACE_PREFIXES"
        cat $IFACE_PREFIXES_FILE 2>/dev/null || true
        echo "$SENTINEL PM_LIST"
        pm list packages -U --user all 2>/dev/null || true
        echo "$END"
        """.trimIndent()

    internal suspend fun reload(appContext: Context): TargetsSnapshot {
        val db = AppDatabase.getInstance(appContext)
        val appDao = db.appDao()
        val portRuleDao = db.portRuleDao()
        val massPortRuleDao = db.massPortRuleDao()
        val ifacePrefixDao = db.ifacePrefixDao()

        // Check if DB is empty
        val allAppsSync = appDao.getAllAppProtectionSync()
        var dbPopulatedOrUpdated = false
        if (allAppsSync.isEmpty()) {
            // Initial migration: read from files
            val (_, out) = withContext(Dispatchers.IO) { suExec(BATCH_SCRIPT) }
            val snapshot = parse(out)

            // Populate DB
            val pkgUserToUid = snapshot.uidToPkg.entries.associate { (uid, pkg) -> (pkg to (uid / 100000)) to uid }
            for (entry in snapshot.kmodTargets + snapshot.lsposedTargets + snapshot.portsObservers) {
                val (pkg, userId) = entry
                val uid = pkgUserToUid[pkg to userId] ?: 0
                appDao.insertAppProtection(
                    dev.soranerai.vpnhidenext.db.AppProtection(
                        packageName = pkg,
                        userId = userId,
                        uid = uid,
                        kmod = entry in snapshot.kmodTargets,
                        lsposed = entry in snapshot.lsposedTargets,
                        portHiding = entry in snapshot.portsObservers,
                    ),
                )
                snapshot.portRules[entry]?.forEach { rule ->
                    portRuleDao.insertRule(
                        dev.soranerai.vpnhidenext.db.DbPortRule(
                            packageName = pkg,
                            userId = userId,
                            startPort = rule.startPort,
                            endPort = rule.endPort,
                            protocol = rule.protocol,
                            label = rule.label,
                            enabled = rule.enabled,
                        ),
                    )
                }
            }

            // Migrate iface prefixes from file if DB is empty
            val allPrefixesSync = ifacePrefixDao.getAllPrefixesSync()
            if (allPrefixesSync.isEmpty()) {
                ifacePrefixDao.insertPrefixes(
                    snapshot.ifacePrefixes.map {
                        dev.soranerai.vpnhidenext.db
                            .DbIfacePrefix(it)
                    },
                )
            }
            dbPopulatedOrUpdated = true
        }

        // Ensure the app itself (dev.soranerai.vpnhidenext) is in the database with kmod = true
        val selfPkg = appContext.packageName
        val selfProto = appDao.getAppProtection(selfPkg, 0)
        if (selfProto == null || !selfProto.kmod) {
            val selfUid = appContext.applicationInfo.uid
            appDao.insertAppProtection(
                dev.soranerai.vpnhidenext.db.AppProtection(
                    packageName = selfPkg,
                    userId = 0,
                    uid = selfUid,
                    kmod = true,
                    lsposed = selfProto?.lsposed ?: false,
                    portHiding = selfProto?.portHiding ?: false,
                ),
            )
            dbPopulatedOrUpdated = true
        }

        // Now read from DB to heal and get the actual data
        var apps = appDao.getAllAppProtectionSync()
        val massRules = massPortRuleDao.getMassRulesSync()
        val ifacePrefixes = ifacePrefixDao.getAllPrefixesSync()

        // Still need module status and PM list
        val statusScript =
            """
            echo "$SENTINEL KMOD_MODULE_DIR"
            [ -d $KMOD_MODULE_DIR ] && echo 1 || echo 0
            echo "$SENTINEL LSMOD"
            lsmod | grep -q vpnhide_kmod && echo 1 || echo 0
            echo "$SENTINEL PM_LIST"
            pm list packages -U --user all 2>/dev/null || true
            echo "$END"
            """.trimIndent()

        val (_, statusOut) = withContext(Dispatchers.IO) { suExec(statusScript) }
        val statusSnapshot = parse(statusOut)

        // Database Healing:
        // 1. Populate missing UIDs (if uid = 0)
        // 2. Prune uninstalled apps (if actualUid == 0 and not selfPkg)
        if (statusSnapshot.uidToPkg.isNotEmpty()) {
            var modified = false
            for (app in apps) {
                if (app.packageName == selfPkg) continue

                val actualUid =
                    statusSnapshot.uidToPkg.entries
                        .find {
                            it.value == app.packageName && (it.key / 100000) == app.userId
                        }?.key ?: 0

                if (actualUid == 0) {
                    // App was uninstalled!
                    appDao.deleteAppProtection(app)
                    modified = true
                } else if (app.uid == 0) {
                    // Populate missing UID
                    appDao.insertAppProtection(app.copy(uid = actualUid))
                    modified = true
                }
            }
            if (modified) {
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
                kmodModuleInstalled = statusSnapshot.kmodModuleInstalled,
                kmodActive = statusSnapshot.kmodActive,
                kmodTargets = apps.filter { it.kmod }.map { it.packageName to it.userId }.toSet(),
                lsposedTargets = apps.filter { it.lsposed }.map { it.packageName to it.userId }.toSet(),
                portsObservers = apps.filter { it.portHiding }.map { it.packageName to it.userId }.toSet(),
                portRules = portRulesMap,
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
            )
        updateState(snapshot)
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
        sections["PM_LIST"]?.lines()?.forEach { line ->
            if (!line.startsWith("package:")) return@forEach
            val parts = line.split(" uid:")
            if (parts.size < 2) return@forEach
            val pkg = parts[0].removePrefix("package:").trim()
            val uidsStr = parts[1].trim()
            uidsStr.split(",").forEach { uidStr ->
                val uid = uidStr.trim().toIntOrNull()
                if (uid != null) uidToPkg[uid] = pkg
            }
        }

        fun parseEntries(raw: String?): Set<Pair<String, Int>> =
            raw
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?.map { entry ->
                    val cleanEntry =
                        if (entry.contains(":")) {
                            val parts = entry.split(":")
                            parts[0] to (parts[1].toIntOrNull() ?: 0)
                        } else {
                            entry to 0
                        }
                    val uid = cleanEntry.first.toIntOrNull()
                    if (uid != null) {
                        val resolvedPkg = uidToPkg[uid]
                        if (resolvedPkg != null) {
                            resolvedPkg to (uid / 100000)
                        } else {
                            cleanEntry
                        }
                    } else {
                        cleanEntry
                    }
                }?.toSet() ?: emptySet()

        val portRules = mutableMapOf<Pair<String, Int>, List<PortRule>>()
        sections["PORTS_RULES"]?.lines()?.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split(" ")
            if (parts.size < 2) return@forEach
            val entry = parts[0]
            val key =
                if (entry.contains(":")) {
                    val p = entry.split(":")
                    p[0] to (p[1].toIntOrNull() ?: 0)
                } else {
                    entry to 0
                }
            val rulesList = mutableListOf<PortRule>()
            for (i in 1 until parts.size) {
                val ruleParts = parts[i].split("-", ":")
                if (ruleParts.size == 3) {
                    val start = ruleParts[0].toIntOrNull() ?: 0
                    val end = ruleParts[1].toIntOrNull() ?: 0
                    val protoIdx = ruleParts[2].toIntOrNull() ?: 2
                    val proto =
                        when (protoIdx) {
                            0 -> PortProtocol.TCP
                            1 -> PortProtocol.UDP
                            else -> PortProtocol.BOTH
                        }
                    rulesList.add(PortRule(startPort = start, endPort = end, protocol = proto))
                }
            }
            portRules[key] = rulesList
        }

        return TargetsSnapshot(
            kmodModuleInstalled = sections["KMOD_MODULE_DIR"]?.trim() == "1",
            kmodActive = sections["LSMOD"]?.trim() == "1",
            kmodTargets = parseEntries(sections["KMOD_TARGETS"]),
            lsposedTargets = emptySet(),
            portsObservers = parseEntries(sections["PORTS_OBSERVERS"]),
            portRules = portRules,
            massPortRules = emptyList(),
            ifacePrefixes =
                sections["IFACE_PREFIXES"]
                    ?.lines()
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList(),
            uidToPkg = uidToPkg,
        )
    }
}
