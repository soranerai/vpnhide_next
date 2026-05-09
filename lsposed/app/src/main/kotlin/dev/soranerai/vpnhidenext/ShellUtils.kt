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
internal const val KMOD_DIRECT_TARGETS = "/data/adb/vpnhide_kmod/direct_targets.txt"
internal const val ZYGISK_TARGETS = "/data/adb/vpnhide_zygisk/targets.txt"
internal const val ZYGISK_MODULE_TARGETS = "/data/adb/modules/vpnhide_zygisk/targets.txt"
internal const val LSPOSED_TARGETS = "/data/adb/vpnhide_lsposed/targets.txt"
internal const val KMOD_CTL = "/data/adb/modules/vpnhide_kmod/vpnhide-ctl"
internal const val SS_UIDS_FILE = "/data/system/vpnhide_uids.txt"
internal const val SS_HIDDEN_PKGS_FILE = "/data/system/vpnhide_hidden_pkgs.txt"
internal const val SS_OBSERVER_UIDS_FILE = "/data/system/vpnhide_observer_uids.txt"
internal const val PORTS_OBSERVERS_FILE = "/data/adb/vpnhide_ports/observers.txt"
internal const val DEV_NODE = "/dev/vpnhide_ctrl"
internal const val PORTS_APPLY_SCRIPT = "/data/adb/modules/vpnhide_ports/vpnhide_ports_apply.sh"
internal const val PORTS_MODULE_DIR = "/data/adb/modules/vpnhide_ports"
internal const val KMOD_MODULE_DIR = "/data/adb/modules/vpnhide_kmod"
internal const val KMOD_LOAD_STATUS_FILE = "/data/adb/vpnhide_kmod/load_status"
internal const val KMOD_LOAD_DMESG_FILE = "/data/adb/vpnhide_kmod/load_dmesg"
internal const val ZYGISK_MODULE_DIR = "/data/adb/modules/vpnhide_zygisk"
internal const val ZYGISK_STATUS_FILE_NAME = "vpnhide_zygisk_active"

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
 * Replaces checkRootAccess, cleanupStaleZygiskStatus and ensureSelfInTargets.
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
        for path in $KMOD_TARGETS $ZYGISK_TARGETS $LSPOSED_TARGETS; do
          dir=${'$'}(dirname "${'$'}path")
          if [ -d "${'$'}dir" ]; then
            if ! grep -q "^$selfPkg${'$'}" "${'$'}path" 2>/dev/null; then
               echo "$selfPkg" >> "${'$'}path"
               chmod 644 "${'$'}path"
               ADDED=1
            fi
          fi
        done
        
        # Sync zygisk if needed
        if [ -d $ZYGISK_MODULE_DIR ]; then
          cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>/dev/null
        fi
        
        # Hidden packages list
        if ! grep -q "^$selfPkg${'$'}" $SS_HIDDEN_PKGS_FILE 2>/dev/null; then
           echo "$selfPkg" >> $SS_HIDDEN_PKGS_FILE
           chmod 640 $SS_HIDDEN_PKGS_FILE
           chown root:system $SS_HIDDEN_PKGS_FILE
           chcon u:object_r:system_data_file:s0 $SS_HIDDEN_PKGS_FILE 2>/dev/null || true
        fi
        
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

internal fun cleanupStaleZygiskStatus(
    context: android.content.Context,
    currentBootId: String,
) {
    val statusFile = File(context.filesDir, ZYGISK_STATUS_FILE_NAME)
    if (!statusFile.isFile) return

    val props =
        try {
            statusFile
                .readLines()
                .mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
        } catch (e: Exception) {
            VpnHideLog.w(TAG, "cleanupStaleZygiskStatus: failed to read heartbeat: ${e.message}")
            emptyMap()
        }

    val heartbeatBootId = props["boot_id"]
    val stale =
        heartbeatBootId.isNullOrBlank() ||
            heartbeatBootId != currentBootId

    if (stale) {
        if (statusFile.delete()) {
            VpnHideLog.i(
                TAG,
                "cleanupStaleZygiskStatus: deleted stale heartbeat " +
                    "(bootId=$heartbeatBootId currentBootId=$currentBootId)",
            )
        } else {
            VpnHideLog.w(TAG, "cleanupStaleZygiskStatus: failed to delete stale heartbeat")
        }
    }
}

/**
 * Ensure the VPNHide Next app itself is in all 3 target lists + resolve UIDs.
 * Returns true if self had to be added to any list (= hooks may not be
 * applied to the current process, restart needed for zygisk).
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
        val newBody =
            "# Managed by VPNHide Next app\n" +
                (existing + selfPkg).sorted().joinToString("\n") + "\n"
        val b64 = Base64.encodeToString(newBody.toByteArray(), Base64.NO_WRAP)
        suExec("echo '$b64' | base64 -d > $path && chmod 644 $path")
        VpnHideLog.i(TAG, "ensureSelfInTargets: added $selfPkg to $path")
        added = true
    }

    addIfMissing(KMOD_TARGETS, "/data/adb/vpnhide_kmod")
    addIfMissing(ZYGISK_TARGETS, "/data/adb/vpnhide_zygisk")
    // Zygisk reads targets from module dir (via get_module_dir() fd), not
    // from persistent dir. Must sync after adding self, otherwise zygisk
    // won't hook us on next launch. Surface real `cp` failures (read-only
    // mount, SELinux denial) — silent failure here used to manifest as
    // "I edited targets in the app but zygisk didn't pick it up".
    val (cpExit, cpOut) =
        suExec("if [ -d $ZYGISK_MODULE_DIR ]; then cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>&1; fi")
    if (cpExit != 0 && cpOut.isNotBlank()) {
        VpnHideLog.w(TAG, "ensureSelfInTargets: zygisk module dir copy failed (exit=$cpExit): ${cpOut.trim()}")
    }
    suExec("mkdir -p /data/adb/vpnhide_lsposed")
    addIfMissing(LSPOSED_TARGETS, null)

    // Always hide self via package visibility hooks — prevents observer apps from seeing us.
    // File lives in /data/system/ (system_data_file), readable by system_server.
    val (_, hiddenRaw) = suExec("cat $SS_HIDDEN_PKGS_FILE 2>/dev/null || true")
    val hiddenExisting = hiddenRaw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    if (selfPkg !in hiddenExisting) {
        val body =
            "# Managed by VPNHide Next app\n" +
                (hiddenExisting + selfPkg).sorted().joinToString("\n") + "\n"
        val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
        suExec(
            // Mode 0640 + group=system: system_server reads via the group
            // bit; untrusted apps fall to "other" and get EACCES.
            // /data/system/ itself is mode 0775 traversable by untrusted —
            // a plain 0644 here used to be enumerable + readable.
            "echo '$b64' | base64 -d > $SS_HIDDEN_PKGS_FILE" +
                " && chmod 640 $SS_HIDDEN_PKGS_FILE" +
                " && chown root:system $SS_HIDDEN_PKGS_FILE" +
                " && chcon u:object_r:system_data_file:s0 $SS_HIDDEN_PKGS_FILE 2>/dev/null; true",
        )
        VpnHideLog.i(TAG, "ensureSelfInTargets: added $selfPkg to $SS_HIDDEN_PKGS_FILE")
        // Don't flip `added`: PM hooks live in system_server and pick up the file change
        // immediately via inotify — no app restart is needed, unlike native (zygisk) hooks.
    }

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
    packages: List<String>,
    outputFile: String,
): String =
    buildString {
        // `--user all` produces comma-separated UIDs for packages that
        // exist in multiple profiles (e.g. work profile), like:
        //   package:com.android.chrome uid:10187,1010187
        // `tr ',' '\n'` expands each to its own line so every profile's
        // copy of the target is individually filtered by the hooks.
        // Literal field match via awk — grep would treat dots in `pkg`
        // as regex wildcards, occasionally cross-matching distinct
        // packages.
        append("ALL_PKGS=\"\$(pm list packages -U --user all 2>/dev/null)\"")
        append("; UIDS=\"\"")
        for (pkg in packages) {
            append(
                "; U=\$(echo \"\$ALL_PKGS\" | awk -v p=\"package:$pkg\" " +
                    "'\$1 == p { sub(/uid:/, \"\", \$2); print \$2; exit }' | tr ',' '\\n')",
            )
            append("; if [ -n \"\$U\" ]; then if [ -z \"\$UIDS\" ]; then UIDS=\"\$U\"; else UIDS=\"\$UIDS")
            append("\n")
            append("\$U\"; fi; fi")
        }
        append("; if [ -n \"\$UIDS\" ]; then echo \"\$UIDS\" > $outputFile 2>/dev/null")
        append("; else echo > $outputFile 2>/dev/null; fi")
    }

internal fun buildWriteTargetsCommand(
    path: String,
    header: String,
    pkgs: List<String>,
): String {
    val body = "$header\n" + pkgs.joinToString("\n") + if (pkgs.isNotEmpty()) "\n" else ""
    val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
    val dir = path.substringBeforeLast('/')
    return "mkdir -p $dir ; echo '$b64' | base64 -d > $path && chmod 644 $path"
}

internal fun buildKmodApplyCommand(
    pkgs: List<String>,
    isDirect: Boolean = false,
): String {
    val targetType = if (isDirect) "direct" else "targets"
    if (pkgs.isEmpty()) {
        return "[ -c $DEV_NODE ] && $KMOD_CTL $targetType; true"
    }

    val pkgList = pkgs.joinToString("|") { it.replace(".", "\\.") }
    val awkCmd = "awk -v p=\"^package:($pkgList) \" '\$0 ~ p { sub(/.*uid:/, \"\"); gsub(/,/, \" \"); print }'"
    return "if [ -c $DEV_NODE ]; then " +
        "UIDS=\$(pm list packages -U | $awkCmd | xargs); " +
        "[ -n \"\$UIDS\" ] && $KMOD_CTL $targetType \$UIDS; fi"
}

internal fun buildLsposedApplyCommand(pkgs: List<String>): String {
    if (pkgs.isEmpty()) {
        return "echo > $SS_UIDS_FILE; chmod 640 $SS_UIDS_FILE; " +
            "chown root:system $SS_UIDS_FILE; " +
            "chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null; true"
    }

    return buildString {
        append(buildUidResolver(pkgs, SS_UIDS_FILE))
        append(" ; chmod 640 $SS_UIDS_FILE")
        append(" ; chown root:system $SS_UIDS_FILE")
        append(" ; chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null")
    }
}

internal fun applyKmodTargets(context: Context) {
    val kmodFile = readPackageList(KMOD_TARGETS)
    suExec(buildKmodApplyCommand(kmodFile, isDirect = false))
}

internal fun readPackageList(path: String): List<String> {
    val (_, raw) = suExec("cat $path 2>/dev/null || true")
    return raw
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
}
