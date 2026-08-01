package dev.soranerai.vpnhidenext

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VpnHide"

internal var kmodCtl = "/data/adb/modules/vpnhide_kmod/vpnhide-ctl"
internal const val DEV_NODE = "/dev/vpnhide_ctrl"
internal var kmodModuleDir = "/data/adb/modules/vpnhide_kmod"
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
private var pathsResolved = false

@Synchronized
internal fun resolvePathsIfNeeded() {
    if (pathsResolved) return
    val script =
        """
        for d in /data/adb/modules/vpnhide_*; do
          if [ -d "${'$'}d" ]; then
            echo "dir=${'$'}d"
            if [ -f "${'$'}d/vpnhide-ctl" ]; then
              echo "ctl=${'$'}d/vpnhide-ctl"
            fi
            break
          fi
        done
        """.trimIndent()
    try {
        val (_, out) = suExec(script, skipPathResolve = true)
        out.lines().forEach { line ->
            if (line.startsWith("dir=")) {
                kmodModuleDir = line.removePrefix("dir=").trim()
            }
            if (line.startsWith("ctl=")) {
                kmodCtl = line.removePrefix("ctl=").trim()
            }
        }
        pathsResolved = true
    } catch (e: Exception) {
        VpnHideLog.e("VpnHide", "failed to resolve module paths: ${e.message}")
    }
}

internal fun suExec(
    cmd: String,
    timeoutSec: Long = SU_DEFAULT_TIMEOUT_SEC,
    skipPathResolve: Boolean = false,
): Pair<Int, String> =
    try {
        if (!skipPathResolve) {
            resolvePathsIfNeeded()
        }
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
    val isKmodType: Boolean,
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
        grep -q "vpnhide" /proc/modules 2>/dev/null && echo "is_kmod=1" || echo "is_kmod=0"
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
        isKmodType = props["is_kmod"] == "1",
    )
}
