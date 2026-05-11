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
    val kmodTargets: Set<String>,
    val kmodDirectTargets: Set<String>,
    val lsposedTargets: Set<String>,
    val portsObservers: Set<String>,
    val portRules: Map<String, List<PortRule>>,
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
        echo "$SENTINEL KMOD_DIRECT_TARGETS"
        cat $KMOD_DIRECT_TARGETS 2>/dev/null || true
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
                val allPkgs =
                    (
                        snapshot.kmodTargets + snapshot.kmodDirectTargets +
                            snapshot.lsposedTargets +
                            snapshot.portsObservers
                    ).distinct()

                for (pkg in allPkgs) {
                    appDao.insertAppProtection(
                        dev.soranerai.vpnhidenext.db.AppProtection(
                            packageName = pkg,
                            kmod = pkg in snapshot.kmodTargets,
                            lsposed = pkg in snapshot.lsposedTargets,
                            tunBypass = pkg in snapshot.kmodDirectTargets,
                            portHiding = pkg in snapshot.portsObservers,
                        ),
                    )

                    snapshot.portRules[pkg]?.forEach { rule ->
                        portRuleDao.insertRule(
                            dev.soranerai.vpnhidenext.db.DbPortRule(
                                packageName = pkg,
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

            val portRulesMap = mutableMapOf<String, List<PortRule>>()
            for (app in apps) {
                if (app.portHiding) {
                    portRulesMap[app.packageName] =
                        portRuleDao.getRulesForAppSync(app.packageName).map {
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
                    kmodTargets = apps.filter { it.kmod }.map { it.packageName }.toSet(),
                    kmodDirectTargets = apps.filter { it.tunBypass }.map { it.packageName }.toSet(),
                    lsposedTargets = apps.filter { it.lsposed }.map { it.packageName }.toSet(),
                    portsObservers = apps.filter { it.portHiding }.map { it.packageName }.toSet(),
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
        val massRules =
            massPortRuleDao.getMassRulesSync().filter { it.enabled }.map { m ->
                PortRule(m.id.toString(), m.startPort, m.endPort, m.protocol, m.label, m.enabled)
            }

        val ruleMap =
            apps.associate { app ->
                val localRules =
                    portRuleDao.getRulesForAppSync(app.packageName).map { r ->
                        PortRule(r.id.toString(), r.startPort, r.endPort, r.protocol, r.label, r.enabled)
                    }
                app.packageName to (localRules + massRules)
            }

        val header = context.getString(R.string.save_header_comment)
        val parts = mutableListOf<String>()

        // Observers file
        val pkgs = apps.map { it.packageName }.sorted()
        parts += "echo '$header' > $PORTS_OBSERVERS_FILE"
        pkgs.forEach { parts += "echo '$it' >> $PORTS_OBSERVERS_FILE" }
        parts += "chmod 644 $PORTS_OBSERVERS_FILE"

        // Rules persistence file
        val rulesBody = StringBuilder(header).append("\n")
        ruleMap.forEach { (pkg, rules) ->
            if (rules.isNotEmpty()) {
                rulesBody.append(pkg)
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

        fun nonEmptyLines(raw: String?): Set<String> =
            raw
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?.toSet() ?: emptySet()

        // With `--user all`, multi-profile packages report comma-separated
        // UIDs: `package:com.android.chrome uid:10187,1010187`. Each UID
        // becomes its own entry in the reverse map so observer lookups
        // from any profile resolve back to the same package name.
        val pmLine = Regex("""^package:(.+) uid:(\d+(?:,\d+)*)$""")
        val uidToPkg = mutableMapOf<Int, String>()
        sections["PM_LIST"]?.lines()?.forEach { line ->
            val match = pmLine.find(line) ?: return@forEach
            val pkg = match.groupValues[1]
            val uids = match.groupValues[2].split(",")
            uids.forEach { uidStr ->
                uidStr.toIntOrNull()?.let { uidToPkg[it] = pkg }
            }
        }

        val portRules = mutableMapOf<String, List<PortRule>>()
        sections["PORTS_RULES"]?.lines()?.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split(" ")
            if (parts.size < 2) return@forEach
            val pkg = parts[0]
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
            portRules[pkg] = rulesList
        }

        return TargetsSnapshot(
            kmodModuleInstalled = sections["KMOD_MODULE_DIR"]?.trim() == "1",
            kmodActive = sections["LSMOD"]?.trim() == "1",
            kmodTargets = nonEmptyLines(sections["KMOD_TARGETS"]),
            kmodDirectTargets = nonEmptyLines(sections["KMOD_DIRECT_TARGETS"]),
            lsposedTargets = nonEmptyLines(sections["LSPOSED_TARGETS"]),
            portsObservers = nonEmptyLines(sections["PORTS_OBSERVERS"]),
            portRules = portRules,
            massPortRules = emptyList(),
            uidToPkg = uidToPkg,
        )
    }
}
