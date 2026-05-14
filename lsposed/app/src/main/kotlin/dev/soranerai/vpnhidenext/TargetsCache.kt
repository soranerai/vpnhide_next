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
    val uidToPkg: Map<Int, String>,
)

internal object TargetsCache {
    private val _snapshot = MutableStateFlow<TargetsSnapshot?>(null)
    val snapshot: StateFlow<TargetsSnapshot?> = _snapshot.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var inflight: Job? = null

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        if (_snapshot.value != null || inflight?.isActive == true) return
        inflight = scope.launch { reload(context.applicationContext) }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        inflight?.cancel()
        inflight = scope.launch { reload(context.applicationContext) }
    }

    /** Drop the cached snapshot so the next subscriber triggers a
     * fresh load. Save handlers call this because they just mutated
     * one of the files this cache mirrors.
     */
    fun invalidate() {
        _snapshot.value = null
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
        echo "$SENTINEL LSPOSED_TARGETS"
        cat $LSPOSED_TARGETS 2>/dev/null || true
        echo "$SENTINEL PORTS_OBSERVERS"
        cat $PORTS_OBSERVERS_FILE 2>/dev/null || true
        echo "$SENTINEL PORTS_RULES"
        cat $PORTS_RULES_FILE 2>/dev/null || true
        echo "$SENTINEL PM_LIST"
        pm list packages -U --user all 2>/dev/null || true
        echo "$END"
        """.trimIndent()

    private suspend fun reload(appContext: Context) {
        _loading.value = true
        try {
            val db = AppDatabase.getInstance(appContext)
            val appDao = db.appDao()
            val portRuleDao = db.portRuleDao()
            val massPortRuleDao = db.massPortRuleDao()

            // Check if DB is empty
            val allAppsSync = appDao.getAllAppProtectionSync()
            if (allAppsSync.isEmpty()) {
                // Initial migration: read from files
                val (_, out) = withContext(Dispatchers.IO) { suExec(BATCH_SCRIPT) }
                val snapshot = parse(out)

                // Populate DB
                // Populate DB
                for (entry in snapshot.kmodTargets + snapshot.lsposedTargets + snapshot.portsObservers) {
                    val (pkg, userId) = entry
                    appDao.insertAppProtection(
                        dev.soranerai.vpnhidenext.db.AppProtection(
                            packageName = pkg,
                            userId = userId,
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
            }

            // Now read from DB
            val apps = appDao.getAllAppProtectionSync()
            val massRules = massPortRuleDao.getMassRulesSync()

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

            _snapshot.value =
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
                    uidToPkg = statusSnapshot.uidToPkg,
                )
        } finally {
            _loading.value = false
        }
    }

    suspend fun applyPortRulesToKernel(context: Context) {
        val db = AppDatabase.getInstance(context)
        val appDao = db.appDao()
        val portRuleDao = db.portRuleDao()
        val massPortRuleDao = db.massPortRuleDao()

        val apps = appDao.getAllAppProtectionSync().filter { it.portHiding }
        if (apps.isEmpty()) {
            suExec("[ -c $DEV_NODE ] && $KMOD_CTL port_rules; true")
            return
        }

        val massRules =
            massPortRuleDao.getMassRulesSync().filter { it.enabled }.map { m ->
                PortRule(m.id.toString(), m.startPort, m.endPort, m.protocol, m.label, m.enabled)
            }

        // Resolve UIDs once
        val (_, pmRaw) = suExec("pm list packages -U --user all 2>/dev/null")
        val pkgToUids = parsePmList(pmRaw)

        val ruleMap = mutableMapOf<Int, List<PortRule>>()
        apps.forEach { app ->
            val uids = pkgToUids[app.packageName] ?: return@forEach
            val uid = uids.find { it / 100000 == app.userId } ?: return@forEach
            val localRules =
                portRuleDao.getRulesForAppSync(app.packageName, app.userId).map { r ->
                    PortRule(r.id.toString(), r.startPort, r.endPort, r.protocol, r.label, r.enabled)
                }
            ruleMap[uid] = localRules + massRules
        }

        val header = context.getString(R.string.save_header_comment)
        val parts = mutableListOf<String>()

        // Observers file
        val observersBody = StringBuilder(header).append("\n")
        apps.forEach { app ->
            val entry = if (app.userId == 0) app.packageName else "${app.packageName}:${app.userId}"
            observersBody.append(entry).append("\n")
        }
        val b64Observers = android.util.Base64.encodeToString(observersBody.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val observersDir = PORTS_OBSERVERS_FILE.substringBeforeLast('/')
        parts += "mkdir -p $observersDir ; echo '$b64Observers' | base64 -d > $PORTS_OBSERVERS_FILE && chmod 644 $PORTS_OBSERVERS_FILE"

        // Rules persistence file
        val rulesBody = StringBuilder(header).append("\n")
        ruleMap.forEach { (uid, rules) ->
            if (rules.isNotEmpty()) {
                rulesBody.append(uid)
                rules.forEach { rule ->
                    val proto =
                        when (rule.protocol) {
                            PortProtocol.TCP -> 0
                            PortProtocol.UDP -> 1
                            PortProtocol.BOTH -> 2
                        }
                    rulesBody.append(" ${rule.startPort}-${rule.endPort}:$proto")
                }
                rulesBody.append("\n")
            }
        }
        val b64Rules = android.util.Base64.encodeToString(rulesBody.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val rulesDir = PORTS_RULES_FILE.substringBeforeLast('/')
        parts += "mkdir -p $rulesDir ; echo '$b64Rules' | base64 -d > $PORTS_RULES_FILE && chmod 644 $PORTS_RULES_FILE"

        // Apply to kmod
        parts += buildKmodPortRulesApplyCommand(ruleMap)

        withContext(Dispatchers.IO) {
            suExec(parts.joinToString(" ; "))
        }
    }

    private fun parsePmList(raw: String): Map<String, List<Int>> {
        val out = mutableMapOf<String, MutableList<Int>>()
        raw.lineSequence().forEach { line ->
            if (!line.startsWith("package:")) return@forEach
            val parts = line.removePrefix("package:").split(" uid:")
            if (parts.size < 2) return@forEach
            val pkg = parts[0].trim()
            val uids = parts[1].split(',').mapNotNull { it.trim().toIntOrNull() }
            out.getOrPut(pkg) { mutableListOf() }.addAll(uids)
        }
        return out
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
                    if (entry.contains(":")) {
                        val parts = entry.split(":")
                        parts[0] to (parts[1].toIntOrNull() ?: 0)
                    } else {
                        entry to 0
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
            lsposedTargets = parseEntries(sections["LSPOSED_TARGETS"]),
            portsObservers = parseEntries(sections["PORTS_OBSERVERS"]),
            portRules = portRules,
            massPortRules = emptyList(),
            uidToPkg = uidToPkg,
        )
    }
}
