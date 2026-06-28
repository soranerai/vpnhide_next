package dev.soranerai.vpnhidenext

import android.content.Context
import android.util.Base64
import dev.soranerai.vpnhidenext.generated.IfaceLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VpnHide"

internal const val KMOD_TARGETS = "/data/adb/vpnhide_kmod/targets.txt"

internal const val KMOD_CTL = "/data/adb/modules/vpnhide_kmod/vpnhide-ctl"
internal const val PORTS_OBSERVERS_FILE = "/data/adb/vpnhide_ports/observers.txt"
internal const val PORTS_RULES_FILE = "/data/adb/vpnhide_ports/rules.txt"
internal const val IFACE_PREFIXES_FILE = "/data/adb/vpnhide_kmod_interfaces.txt"
internal const val DEV_NODE = "/dev/vpnhide_ctrl"
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
                VpnHideLog.w(TAG, "su exec timed out after ${timeoutSec}s: $cmd")
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
        VpnHideLog.e(TAG, "su exec failed: ${e.message}")
        -1 to ""
    }

internal suspend fun suExecAsync(
    cmd: String,
    timeoutSec: Long = SU_DEFAULT_TIMEOUT_SEC,
): Pair<Int, String> = withContext(Dispatchers.IO) { suExec(cmd, timeoutSec) }

internal data class StartupResult(
    val rootGranted: Boolean,
    val kmodActive: Boolean,
    val addedToTargets: Boolean,
    val currentBootId: String,
)

/**
 * Batched startup check: root access, target sync, UID resolution and boot ID.
 * Replaces checkRootAccess and ensureSelfInTargets.
 */
internal fun performStartupOptimized(): StartupResult {
    val script =
        """
        # Check root
        id | grep -q "uid=0" || { echo "root=0"; exit 0; }
        echo "root=1"
        
        # Check kmod
        [ -c $DEV_NODE ] && echo "kmod=1" || echo "kmod=0"
        
        echo "added=0"
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
        kmodActive = props["kmod"] == "1",
        addedToTargets = props["added"] == "1",
        currentBootId = props["boot_id"] ?: "",
    )
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
