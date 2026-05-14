package dev.soranerai.vpnhidenext

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.soranerai.vpnhidenext.generated.IfaceLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VpnHide"

internal const val KMOD_TARGETS = "/data/adb/vpnhide_kmod/targets.txt"

internal const val LSPOSED_TARGETS = "/data/adb/vpnhide_lsposed/targets.txt"
internal const val KMOD_CTL = "/data/adb/modules/vpnhide_kmod/vpnhide-ctl"
internal const val SS_UIDS_FILE = "/data/system/vpnhide_uids.txt"
internal const val PORTS_OBSERVERS_FILE = "/data/adb/vpnhide_ports/observers.txt"
internal const val PORTS_RULES_FILE = "/data/adb/vpnhide_ports/rules.txt"
internal const val DEV_NODE = "/dev/vpnhide_ctrl"
internal const val PORTS_APPLY_SCRIPT = "/data/adb/modules/vpnhide_kmod/vpnhide_ports_apply.sh"
internal const val KMOD_MODULE_DIR = "/data/adb/modules/vpnhide_kmod"
internal const val KMOD_LOAD_STATUS_FILE = "/data/adb/vpnhide_kmod/load_status"
internal const val KMOD_LOAD_DMESG_FILE = "/data/adb/vpnhide_kmod/load_dmesg"

/** Default cap on a single su invocation. Most root commands here finish
 *  in milliseconds; this only fires if the su binary is genuinely stuck
 *  (e.g. waiting on a GUI prompt that the user dismissed). */
internal const val SU_DEFAULT_TIMEOUT_SEC: Long = 10

/**
 * Returns exit code and stdout. Exit code -1 means the su binary
 * couldn't be executed at all (not installed, permission denied, or
 * still running after [timeoutSec] seconds — in which case it gets
 * destroyForcibly()'d so we don't leak the process).
 *
 * Both pipes are drained on dedicated threads — `readText()` directly
 * on `proc.inputStream` would block until EOF, so a hung child means
 * `waitFor(timeout)` is never even reached. The threads exit naturally
 * once the child (or destroyForcibly) closes its pipes.
 */
internal fun suExec(
    cmd: String,
    timeoutSec: Long = SU_DEFAULT_TIMEOUT_SEC,
): Pair<Int, String> =
    try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        try {
            val stdoutHolder = AtomicReference("")
            val stdoutDrain =
                Thread {
                    runCatching { stdoutHolder.set(proc.inputStream.bufferedReader().readText()) }
                }
            val stderrDrain = Thread { runCatching { proc.errorStream.readBytes() } }
            stdoutDrain.start()
            stderrDrain.start()

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "su exec timed out after ${timeoutSec}s: $cmd")
                proc.destroyForcibly()
            }
            // After destroyForcibly the pipes close and the drains exit;
            // a 1s join is plenty and bounds the worst case.
            stdoutDrain.join(1_000)
            stderrDrain.join(1_000)

            val exit = if (finished) proc.exitValue() else -1
            exit to stdoutHolder.get()
        } finally {
            proc.destroy()
        }
    } catch (e: Exception) {
        Log.e(TAG, "su exec failed: ${e.message}")
        -1 to ""
    }

internal suspend fun suExecAsync(
    cmd: String,
    timeoutSec: Long = SU_DEFAULT_TIMEOUT_SEC,
): Pair<Int, String> = withContext(Dispatchers.IO) { suExec(cmd, timeoutSec) }

internal data class StartupResult(
    val rootGranted: Boolean,
    val addedToTargets: Boolean,
    val currentBootId: String,
)

/**
 * Batched startup check: root access, target sync, UID resolution and boot ID.
 * Replaces checkRootAccess and ensureSelfInTargets.
 */
internal fun performStartupOptimized(selfPkg: String): StartupResult {
    val script =
        """
        # Check root
        id | grep -q "uid=0" || { echo "root=0"; exit 0; }
        echo "root=1"
        
        # Resolve UIDs
        ALL_PKGS=${'$'}(pm list packages -U --user all 2>/dev/null)
        SELF_UIDS=${'$'}(echo "${'$'}ALL_PKGS" | awk -v p="package:$selfPkg" '${'$'}1 == p { sub(/uid:/, "", ${'$'}2); print ${'$'}2; exit }' | tr ',' '\n')
        
        # Add self to module targets
        ADDED=0
        for path in ${'$'}KMOD_TARGETS ${'$'}LSPOSED_TARGETS; do
          dir=${'$'}(dirname "${'$'}path")
          if [ -d "${'$'}dir" ]; then
            for U in ${'$'}SELF_UIDS; do
              if ! grep -q "^${'$'}U${'$'}" "${'$'}path" 2>/dev/null; then
                 echo "${'$'}U" >> "${'$'}path"
                 chmod 644 "${'$'}path"
                 ADDED=1
              fi
            done
          fi
        done

        
        
        # UIDs list
        for U in ${'$'}SELF_UIDS; do
          if ! grep -q "^${'$'}U${'$'}" $SS_UIDS_FILE 2>/dev/null; then
            echo "${'$'}U" >> $SS_UIDS_FILE
            chmod 640 $SS_UIDS_FILE
            chown root:system $SS_UIDS_FILE
            chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null || true
          fi
        done
        
        echo "added=${'$'}ADDED"
        echo "boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
        """.trimIndent()

    val (_, out) = suExec(script)
    val props =
        out
            .lines()
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

    return StartupResult(
        rootGranted = props["root"] == "1",
        addedToTargets = props["added"] == "1",
        currentBootId = props["boot_id"] ?: "",
    )
}

/**
 * Single source of truth for "is a VPN currently up?". Both the dashboard
 * (sync, off the main thread already) and the diagnostics screen (suspend,
 * via `withContext(Dispatchers.IO)`) call this so the answer doesn't drift
 * — a previous version of the dashboard hard-coded a prefix list and missed
 * names the codegen-driven `IfaceLists.isVpnIface` catches (e.g. `if<N>`
 * from issue #86, `MyVPN`, `wg-client`).
 */
internal fun isVpnActiveBlocking(): Boolean {
    val (exitCode, output) = suExec("ls /sys/class/net/ 2>/dev/null")
    if (exitCode != 0) return false
    val vpnIfaces =
        output.lines().map { it.trim() }.filter { name -> IfaceLists.isVpnIface(name) }
    if (vpnIfaces.isEmpty()) {
        VpnHideLog.d(TAG, "isVpnActive: no VPN interfaces found")
        return false
    }
    return vpnIfaces.any { iface ->
        val (_, state) = suExec("cat /sys/class/net/$iface/operstate 2>/dev/null")
        val up = state.trim() == "unknown" || state.trim() == "up"
        VpnHideLog.d(TAG, "isVpnActive: $iface operstate=${state.trim()} up=$up")
        up
    }
}

/**
 * Ensure the VPNHide Next app itself is in all 3 target lists + resolve UIDs.
 * Returns true if self had to be added to any list (= hooks may not be
 * applied to the current process, restart needed).
 * Called once at app startup; result is shared with all screens.
 */
internal fun ensureSelfInTargets(selfPkg: String): Boolean {
    var added = false

    fun addIfMissing(
        path: String,
        dirCheck: String?,
    ) {
        if (dirCheck != null) {
            val (_, exists) = suExec("[ -d $dirCheck ] && echo 1 || echo 0")
            if (exists.trim() != "1") {
                VpnHideLog.d(TAG, "ensureSelfInTargets: $dirCheck not found, skipping $path")
                return
            }
        }
        val (_, raw) = suExec("cat $path 2>/dev/null || true")
        val existing = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (selfPkg in existing) {
            VpnHideLog.d(TAG, "ensureSelfInTargets: $selfPkg already in $path")
            return
        }
        val (_, uidsRaw) = suExec("pm list packages -U --user all 2>/dev/null | grep \"package:${'$'}selfPkg \" | awk '{sub(/uid:/, \"\", ${'$'}2); print ${'$'}2}' | tr ',' '\\n'")
        val selfUids = uidsRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        for (u in selfUids) {
            if (u !in existing) {
                val newBody = "# Managed by VPNHide Next app\n" + (existing + u).sorted().joinToString("\n") + "\n"
                val b64 = Base64.encodeToString(newBody.toByteArray(), Base64.NO_WRAP)
                suExec("echo '$b64' | base64 -d > $path && chmod 644 $path")
                VpnHideLog.i(TAG, "ensureSelfInTargets: added $u to $path")
                added = true
            }
        }
    }

    addIfMissing(KMOD_TARGETS, "/data/adb/vpnhide_kmod")
    suExec("mkdir -p /data/adb/vpnhide_lsposed")
    addIfMissing(LSPOSED_TARGETS, null)

    // Resolve UIDs so hooks pick us up immediately (kmod + lsposed support live reload).
    // `--user all` catches the case where vpnhide is installed in a work profile too —
    // each UID gets added to targets so both instances are covered. `tr ',' '\n'`
    // expands comma-separated UIDs, then we iterate one per line and dedup against
    // the existing file content.
    val uidCmd =
        buildString {
            // Literal field match via awk — grep would treat dots in
            // `selfPkg` as regex wildcards.
            append("ALL_PKGS=\"\$(pm list packages -U --user all 2>/dev/null)\"")
            append(
                "; SELF_UIDS=\$(echo \"\$ALL_PKGS\" | awk -v p=\"package:$selfPkg\" " +
                    "'\$1 == p { sub(/uid:/, \"\", \$2); print \$2; exit }' | tr ',' '\\n')",
            )
            append("; if [ -n \"\$SELF_UIDS\" ]; then")
            append("   for U in \$SELF_UIDS; do")
            append("     EXISTING2=\$(cat $SS_UIDS_FILE 2>/dev/null)")
            append(
                "   ; echo \"\$EXISTING2\" | grep -q \"^\$U\$\" || { echo \"\$U\" >> $SS_UIDS_FILE; chmod 640 $SS_UIDS_FILE; chown root:system $SS_UIDS_FILE; chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null; }",
            )
            append("   ; done")

            append("; fi")
        }
    suExec(uidCmd)
    VpnHideLog.d(TAG, "ensureSelfInTargets: done, added=$added")
    return added
}

internal fun buildUidResolver(
    uids: List<Int>,
    outputFile: String,
): String {
    if (uids.isEmpty()) {
        return "echo > $outputFile 2>/dev/null"
    }
    val body = uids.sorted().joinToString("\n") + "\n"
    val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
    return "echo '$b64' | base64 -d > $outputFile && chmod 644 $outputFile"
}

internal fun buildWriteTargetsCommand(
    path: String,
    header: String,
    uids: List<Int>,
): String {
    val body = "$header\n" + uids.sorted().joinToString("\n") + if (uids.isNotEmpty()) "\n" else ""
    val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
    val dir = path.substringBeforeLast('/')
    return "mkdir -p $dir ; echo '$b64' | base64 -d > $path && chmod 644 $path"
}

internal fun buildKmodApplyCommand(
    uids: List<Int>,
    targetType: String = "targets",
): String {
    if (uids.isEmpty()) return "$KMOD_CTL $targetType ; true"
    return "$KMOD_CTL $targetType ${uids.sorted().joinToString(" ")}"
}

internal fun buildKmodPortRulesApplyCommand(rules: Map<Int, List<PortRule>>): String {
    if (rules.isEmpty()) {
        return "[ -c $DEV_NODE ] && $KMOD_CTL port_rules; true"
    }

    return buildString {
        append("if [ -c $DEV_NODE ]; then ")
        append("ARGS=\"\"; ")
        rules.forEach { (uid, portRules) ->
            if (portRules.isEmpty()) {
                append("ARGS=\"\$ARGS $uid 1 0 65535 2\"; ")
            } else {
                append("ARGS=\"\$ARGS $uid ${portRules.size}")
                portRules.forEach { rule ->
                    val proto =
                        when (rule.protocol) {
                            PortProtocol.TCP -> 0
                            PortProtocol.UDP -> 1
                            PortProtocol.BOTH -> 2
                        }
                    append(" ${rule.startPort} ${rule.endPort} $proto")
                }
                append("\"; ")
            }
        }
        append("[ -n \"\$ARGS\" ] && $KMOD_CTL port_rules \$ARGS; fi")
    }
}

internal fun buildLsposedApplyCommand(uids: List<Int>): String {
    if (uids.isEmpty()) {
        return "echo > $SS_UIDS_FILE; chmod 640 $SS_UIDS_FILE; " +
            "chown root:system $SS_UIDS_FILE; " +
            "chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null; true"
    }

    return buildString {
        append(buildUidResolver(uids, SS_UIDS_FILE))
        append(" ; chmod 640 $SS_UIDS_FILE")
        append(" ; chown root:system $SS_UIDS_FILE")
        append(" ; chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null")
    }
}

internal fun applyKmodTargets(context: Context) {
    val uids = readTargetList(KMOD_TARGETS)
    suExec(buildKmodApplyCommand(uids, targetType = "targets"))
}

internal fun readTargetList(path: String): List<Int> {
    val (_, raw) = suExec("cat $path 2>/dev/null || true")
    return raw
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { it.toIntOrNull() }
}
