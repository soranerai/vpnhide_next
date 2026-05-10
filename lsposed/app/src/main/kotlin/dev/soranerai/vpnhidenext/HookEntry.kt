package dev.soranerai.vpnhidenext

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.soranerai.vpnhidenext.generated.IfaceLists
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnHide — hide VPN presence from apps via system_server Binder hooks.
 *
 * Hooks writeToParcel() on NetworkCapabilities, NetworkInfo, and
 * LinkProperties inside system_server. When the Binder caller is a
 * target UID, VPN-related data is stripped before serialization —
 * the app receives clean data without any in-process hooks.
 *
 * This covers all Java API detection paths:
 *   - NetworkCapabilities: hasTransport(VPN), hasCapability(NOT_VPN),
 *     getTransportTypes(), getTransportInfo(), toString()
 *   - NetworkInfo: getType(), getTypeName()
 *   - ConnectivityManager: all methods that return NetworkCapabilities,
 *     NetworkInfo, or LinkProperties over Binder
 *   - LinkProperties: getInterfaceName(), getRoutes(), getDnsServers()
 *
 * Native detection paths (getifaddrs, ioctl, /proc/net) are covered
 * by vpnhide-kmod (kernel module) or vpnhide-zygisk (in-process hooks).
 *
 * Only "System Framework" needs to be in LSPosed scope.
 */
class HookEntry : IXposedHookLoadPackage {
    private val hookInstalled = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook system_server. handleLoadPackage fires multiple times
        // in system_server (once per hosted package / APEX), so we use
        // compareAndSet to install hooks exactly once.
        val inSystemServer =
            hookInstalled.get() ||
                lpparam.processName == "android" ||
                android.os.Process.myUid() == 1000

        if (!inSystemServer) return

        if (hookInstalled.compareAndSet(false, true)) {
            HookLog.install()
            HookLog.i("VpnHide: system_server detected, installing Binder hooks")
            val brokenFields = installSystemServerHooks()
            writeHookStatusFile(brokenFields)
        }
    }

    private inline fun tryHook(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            HookLog.e("VpnHide: $name hook failed: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun isVpnInterfaceName(name: String): Boolean = IfaceLists.isVpnIface(name)

    private fun sanitizeLinkProperties(copy: LinkProperties): Boolean {
        var modified = false

        val ifaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (ifaceName != null && isVpnInterfaceName(ifaceName)) {
            XposedHelpers.setObjectField(copy, "mIfaceName", null)
            modified = true
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val routesField = XposedHelpers.getObjectField(copy, "mRoutes") as? MutableList<RouteInfo>
            if (routesField != null) {
                val filtered =
                    routesField.filterNot { route ->
                        val routeIface = route.`interface`
                        routeIface != null && isVpnInterfaceName(routeIface)
                    }
                if (filtered.size != routesField.size) {
                    routesField.clear()
                    routesField.addAll(filtered)
                    modified = true
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mRoutes: ${t.message}")
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val stacked = XposedHelpers.getObjectField(copy, "mStackedLinks") as? MutableMap<String, LinkProperties>
            if (stacked != null && stacked.isNotEmpty()) {
                val filtered = LinkedHashMap<String, LinkProperties>()
                for ((key, value) in stacked) {
                    val stackedCopy =
                        try {
                            val ctor = LinkProperties::class.java.getDeclaredConstructor(LinkProperties::class.java)
                            ctor.isAccessible = true
                            ctor.newInstance(value) as LinkProperties
                        } catch (_: Throwable) {
                            value
                        }
                    val stackedModified = sanitizeLinkProperties(stackedCopy)
                    val stackedIface = XposedHelpers.getObjectField(stackedCopy, "mIfaceName") as? String
                    if (stackedIface == null && stackedCopy.routes.isEmpty()) {
                        if (stackedModified || isVpnInterfaceName(key)) {
                            modified = true
                        } else {
                            filtered[key] = stackedCopy
                        }
                    } else {
                        // Only mark `modified` if sanitization actually
                        // changed something. The previous condition also
                        // tripped on `stackedCopy !== value`, which is
                        // true after every successful clone — so any
                        // non-empty stacked map forced a clear+putAll
                        // even when no VPN data was present.
                        if (stackedModified) modified = true
                        filtered[key] = stackedCopy
                    }
                }
                if (filtered.size != stacked.size || modified) {
                    stacked.clear()
                    stacked.putAll(filtered)
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mStackedLinks: ${t.message}")
        }

        return modified
    }

    // ==================================================================
    //  system_server hooks — per-UID Binder filtering
    // ==================================================================

    @Volatile private var systemServerTargetUids: Set<Int>? = null

    @Volatile private var targetUidsFileObserver: android.os.FileObserver? = null
    private val uidLock = Any()

    private fun loadTargetUids(): Set<Int> {
        // Fast path: already cached (volatile read)
        systemServerTargetUids?.let { return it }

        // Slow path: only one thread reads the file
        synchronized(uidLock) {
            systemServerTargetUids?.let { return it }

            val uids = mutableSetOf<Int>()

            // Read pre-resolved numeric UIDs written by vpnhide-kmod's
            // service.sh into /data/system/vpnhide_uids.txt.
            // system_server can read /data/system/ (SELinux: system_data_file).
            try {
                val file = File("/data/system/vpnhide_uids.txt")
                if (file.exists()) {
                    file.readLines().forEach { line ->
                        line.trim().toIntOrNull()?.let { uids.add(it) }
                    }
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to read UIDs: ${t.message}")
            }

            val result: Set<Int> = uids.toSet()
            if (result.isNotEmpty()) {
                HookLog.i("VpnHide: system_server loaded ${result.size} target UIDs: $result")
            }
            // Always cache (even if empty) to avoid re-reading until invalidated
            systemServerTargetUids = result
            return result
        }
    }

    private fun isTargetCaller(): Boolean {
        val uid = Binder.getCallingUid()
        return loadTargetUids().contains(uid)
    }

    private fun invalidateTargetUids() {
        systemServerTargetUids = null
    }

    // Smoke-check at install time: every private AOSP field/ctor we touch
    // by reflection in the writeToParcel hooks. Returns the keys that
    // failed (missing or wrong-typed). Empty list = all good.
    //
    // Per-hook gates below skip installing a hook entirely when its
    // critical reflection broke — silent fail-open is preferable to
    // throwing NoSuchFieldError on every writeToParcel call (system_server
    // gets that on every NetworkCapabilities IPC, target or not). The
    // dashboard surfaces the broken_fields list as a red error so the
    // user can see and report the AOSP drift.
    private fun installSystemServerHooks(): List<String> {
        val brokenFields = runReflectionSmokeCheck()
        if (brokenFields.isNotEmpty()) {
            HookLog.e("VpnHide: reflection smoke-check found broken keys: $brokenFields")
        }

        // Match a probe key against either an exact entry in `broken` or
        // an entry with a `:type=...` suffix (wrong-typed field).
        fun anyBroken(critical: Set<String>): Boolean = brokenFields.any { it.substringBefore(':') in critical }

        // LP: mIfaceName + copy ctor are critical. mRoutes / mStackedLinks
        // are non-critical — the existing inner try/catch in
        // sanitizeLinkProperties already lets the rest of the sanitizer
        // proceed when those are absent.
        if (anyBroken(LP_CRITICAL_KEYS)) {
            HookLog.e("VpnHide: LP.writeToParcel hook SKIPPED — critical reflection broken")
        } else {
            tryHook("LP.writeToParcel") { hookLPWriteToParcel() }
        }

        // NC: the two long bitmasks are critical. mTransportInfo is
        // non-critical because it doesn't exist on API 28 (Android 9)
        // and the existing inner try/catch in hookNCWriteToParcel already
        // tolerates its absence on API 29+ if AOSP renames it later.
        if (anyBroken(NC_CRITICAL_KEYS)) {
            HookLog.e("VpnHide: NC.writeToParcel hook SKIPPED — critical reflection broken")
        } else {
            tryHook("NC.writeToParcel") { hookNCWriteToParcel() }
        }

        // NI: every field + ctor is critical — the hook body has no
        // inner try/catch around the per-field setIntField/setBooleanField
        // calls, so any rename would fail-open per call with logcat spam.
        if (anyBroken(NI_CRITICAL_KEYS)) {
            HookLog.e("VpnHide: NI.writeToParcel hook SKIPPED — critical reflection broken")
        } else {
            tryHook("NI.writeToParcel") { hookNIWriteToParcel() }
        }

        tryHook("FileObserver") { watchTargetUidsFile() }
        return brokenFields
    }

    private data class FieldProbe(
        val key: String,
        val clazz: Class<*>,
        val name: String,
        // If the device's SDK is below this, the probe is skipped entirely
        // (not "found", not "broken" — not applicable). Used for fields
        // introduced after our minSdk floor (e.g. mTransportInfo at API 29).
        // Listed before `typeCheck` so the latter stays the last parameter
        // — that lets call sites use trailing-lambda syntax for the probe
        // without having to name `typeCheck =` every time.
        val minSdk: Int = 0,
        // Field-type compatibility predicate. For collections we use
        // isAssignableFrom() so AOSP swapping ArrayList → LinkedList stays OK.
        val typeCheck: (Class<*>) -> Boolean,
    )

    private data class CtorProbe(
        val key: String,
        val clazz: Class<*>,
        val params: Array<Class<*>>,
    )

    private fun runReflectionSmokeCheck(): List<String> {
        val broken = mutableListOf<String>()
        for (probe in FIELD_PROBES) {
            if (Build.VERSION.SDK_INT < probe.minSdk) continue
            val field =
                try {
                    XposedHelpers.findField(probe.clazz, probe.name)
                } catch (_: NoSuchFieldError) {
                    broken += probe.key
                    continue
                }
            if (!probe.typeCheck(field.type)) {
                // Suffix carries the actual type to help debug AOSP-drift
                // bug reports without rebuilding/instrumenting the device.
                broken += "${probe.key}:type=${field.type.name}"
            }
        }
        for (probe in CTOR_PROBES) {
            try {
                probe.clazz.getDeclaredConstructor(*probe.params)
            } catch (_: NoSuchMethodException) {
                broken += probe.key
            }
        }
        return broken
    }

    /**
     * Write a status file so the VPNHide Next app can verify hooks are active.
     * Includes boot_id to distinguish stale files from previous boots,
     * aosp_sdk for diagnostic context in bug reports, and (only when
     * non-empty) broken_fields listing the reflection probes that the
     * smoke-check rejected this boot.
     */
    private fun writeHookStatusFile(brokenFields: List<String>) {
        try {
            val bootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
            val timestamp = System.currentTimeMillis() / 1000
            val version = BuildConfig.VERSION_NAME
            val sdk = Build.VERSION.SDK_INT
            val sb = StringBuilder()
            sb.append("version=").append(version).append('\n')
            sb.append("boot_id=").append(bootId).append('\n')
            sb.append("timestamp=").append(timestamp).append('\n')
            sb.append("aosp_sdk=").append(sdk).append('\n')
            if (brokenFields.isNotEmpty()) {
                sb.append("broken_fields=").append(brokenFields.joinToString(",")).append('\n')
            }
            val statusFile = File(HOOK_STATUS_FILE)
            statusFile.writeText(sb.toString())
            // Don't expose this file to untrusted apps — anti-tamper SDKs
            // scan /data/system/ for known marker filenames. The VPNHide Next
            // app reads it via root (`suExec("cat ...")`), see
            // DashboardData.kt — same pattern as vpnhide_uids.txt.
            HookLog.i(
                "VpnHide: wrote hook status file (version=$version, boot_id=$bootId, " +
                    "sdk=$sdk, broken=${brokenFields.size})",
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to write hook status: ${t.message}")
        }
    }

    /**
     * Watch /data/system/vpnhide_uids.txt for changes via inotify.
     * When modified (e.g. by the VPNHide Next app), invalidate the
     * cached UID set so the next writeToParcel call re-reads it.
     */
    private fun watchTargetUidsFile() {
        val dir = "/data/system"
        val filename = "vpnhide_uids.txt"
        val observer =
            object : android.os.FileObserver(
                File(dir),
                // CLOSE_WRITE + MOVED_TO is enough: the writers we control
                // either do a single short `> file` redirect (one write +
                // close) or atomic-rename via `mv`. MODIFY would fire
                // mid-write on multi-write writers and let the hook read
                // a partially-populated file before the writer closes.
                CREATE or CLOSE_WRITE or MOVED_TO,
            ) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path == filename) {
                        HookLog.i("VpnHide: $filename changed (event=$event), invalidating UID cache")
                        systemServerTargetUids = null
                    }
                }
            }
        targetUidsFileObserver = observer
        observer.startWatching()
        HookLog.i("VpnHide: watching $dir for $filename changes (inotify)")
    }

    /**
     * Hook NetworkCapabilities.writeToParcel in system_server.
     * For target UIDs, creates a copy with VPN stripped and writes
     * the copy to the Parcel instead of the original. The original
     * object is never mutated, avoiding race conditions with
     * ConnectivityService threads.
     */
    private fun hookNCWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = Binder.getCallingUid()
                    val targets = loadTargetUids()
                    val isTarget = targets.contains(callerUid)
                    val nc = param.thisObject as NetworkCapabilities
                    val transportTypes = XposedHelpers.getLongField(nc, "mTransportTypes")
                    val hasVpn = (transportTypes and (1L shl TRANSPORT_VPN)) != 0L
                    // Per-request diagnostic line. Gated by the debug-logging
                    // toggle: these fire on every NC.writeToParcel inside
                    // system_server and directly name the target UIDs we hook,
                    // which is exactly what users hiding their setup want
                    // kept out of logcat.
                    HookLog.i(
                        "VpnHide-NC: uid=$callerUid target=$isTarget hasVpn=$hasVpn " +
                            "transports=0x${transportTypes.toString(16)}",
                    )
                    if (!isTarget) return

                    try {
                        val vpnBit = 1L shl TRANSPORT_VPN
                        if (transportTypes and vpnBit == 0L) return

                        val copy = NetworkCapabilities(nc)
                        XposedHelpers.setLongField(copy, "mTransportTypes", transportTypes and vpnBit.inv())
                        val caps = XposedHelpers.getLongField(copy, "mNetworkCapabilities")
                        XposedHelpers.setLongField(copy, "mNetworkCapabilities", caps or (1L shl NET_CAPABILITY_NOT_VPN))
                        try {
                            val ti = XposedHelpers.getObjectField(copy, "mTransportInfo")
                            if (ti != null && ti.javaClass.name == "android.net.VpnTransportInfo") {
                                XposedHelpers.setObjectField(copy, "mTransportInfo", null)
                            }
                        } catch (_: Throwable) {
                        }

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-NC: uid=$callerUid STRIPPED VPN")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: NC.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked NetworkCapabilities.writeToParcel")
    }

    /**
     * Hook NetworkInfo.writeToParcel — disguise VPN NetworkInfo for target callers.
     * Creates a copy with type changed from VPN to WIFI, writes the copy.
     */
    @Suppress("DEPRECATION")
    private fun hookNIWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkInfo::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = Binder.getCallingUid()
                    val isTarget = loadTargetUids().contains(callerUid)
                    val ni = param.thisObject as NetworkInfo
                    val type = XposedHelpers.getIntField(ni, "mNetworkType")
                    val isVpn = type == TYPE_VPN
                    HookLog.i(
                        "VpnHide-NI: uid=$callerUid target=$isTarget isVpn=$isVpn type=$type",
                    )
                    if (!isTarget) return
                    try {
                        if (!isVpn) return

                        val ctor =
                            NetworkInfo::class.java.getDeclaredConstructor(
                                Integer.TYPE,
                                Integer.TYPE,
                                String::class.java,
                                String::class.java,
                            )
                        ctor.isAccessible = true
                        val copy = ctor.newInstance(TYPE_WIFI, 0, "WIFI", "") as NetworkInfo
                        XposedHelpers.setIntField(copy, "mState", XposedHelpers.getIntField(ni, "mState"))
                        XposedHelpers.setIntField(copy, "mDetailedState", XposedHelpers.getIntField(ni, "mDetailedState"))
                        XposedHelpers.setBooleanField(copy, "mIsAvailable", XposedHelpers.getBooleanField(ni, "mIsAvailable"))

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-NI: uid=$callerUid STRIPPED VPN (disguised as WIFI)")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: NI.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked NetworkInfo.writeToParcel")
    }

    /**
     * Hook LinkProperties.writeToParcel — clear VPN interface name and
     * routes for target callers. Creates a copy to avoid mutating the
     * original object shared by ConnectivityService threads.
     */
    private fun hookLPWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            LinkProperties::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = Binder.getCallingUid()
                    val isTarget = loadTargetUids().contains(callerUid)
                    val lp = param.thisObject as LinkProperties
                    val ifname = XposedHelpers.getObjectField(lp, "mIfaceName") as? String
                    HookLog.i("VpnHide-LP: uid=$callerUid target=$isTarget ifname=$ifname")
                    if (!isTarget) return
                    try {
                        val ctor = LinkProperties::class.java.getDeclaredConstructor(LinkProperties::class.java)
                        ctor.isAccessible = true
                        val copy = ctor.newInstance(lp) as LinkProperties
                        if (!sanitizeLinkProperties(copy)) return

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-LP: uid=$callerUid STRIPPED VPN (ifname was $ifname)")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: LP.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked LinkProperties.writeToParcel")
    }

    companion object {
        private const val TRANSPORT_VPN = 4
        private const val NET_CAPABILITY_NOT_VPN = 15
        private const val TYPE_VPN = 17
        private const val TYPE_WIFI = 1
        const val HOOK_STATUS_FILE = "/data/system/vpnhide_hook_active"

        private val FIELD_PROBES =
            listOf(
                FieldProbe(
                    "LinkProperties.mIfaceName",
                    LinkProperties::class.java,
                    "mIfaceName",
                ) { it == String::class.java },
                FieldProbe(
                    "LinkProperties.mRoutes",
                    LinkProperties::class.java,
                    "mRoutes",
                ) { MutableList::class.java.isAssignableFrom(it) },
                FieldProbe(
                    "LinkProperties.mStackedLinks",
                    LinkProperties::class.java,
                    "mStackedLinks",
                ) { MutableMap::class.java.isAssignableFrom(it) },
                FieldProbe(
                    "NetworkCapabilities.mTransportTypes",
                    NetworkCapabilities::class.java,
                    "mTransportTypes",
                ) { it == java.lang.Long.TYPE },
                FieldProbe(
                    "NetworkCapabilities.mNetworkCapabilities",
                    NetworkCapabilities::class.java,
                    "mNetworkCapabilities",
                ) { it == java.lang.Long.TYPE },
                FieldProbe(
                    "NetworkCapabilities.mTransportInfo",
                    NetworkCapabilities::class.java,
                    "mTransportInfo",
                    minSdk = Build.VERSION_CODES.Q,
                ) { fieldType ->
                    // android.net.TransportInfo arrived in API 29; on
                    // API 28 the probe is skipped via minSdk above.
                    runCatching { Class.forName("android.net.TransportInfo") }
                        .map { it.isAssignableFrom(fieldType) }
                        .getOrDefault(false)
                },
                FieldProbe(
                    "NetworkInfo.mNetworkType",
                    NetworkInfo::class.java,
                    "mNetworkType",
                ) { it == Integer.TYPE },
                FieldProbe(
                    "NetworkInfo.mState",
                    NetworkInfo::class.java,
                    "mState",
                ) { it == NetworkInfo.State::class.java },
                FieldProbe(
                    "NetworkInfo.mDetailedState",
                    NetworkInfo::class.java,
                    "mDetailedState",
                ) { it == NetworkInfo.DetailedState::class.java },
                FieldProbe(
                    "NetworkInfo.mIsAvailable",
                    NetworkInfo::class.java,
                    "mIsAvailable",
                ) { it == java.lang.Boolean.TYPE },
            )

        private val CTOR_PROBES =
            listOf(
                CtorProbe(
                    "LinkProperties.<init>(LinkProperties)",
                    LinkProperties::class.java,
                    arrayOf(LinkProperties::class.java),
                ),
                CtorProbe(
                    "NetworkInfo.<init>(int,int,String,String)",
                    NetworkInfo::class.java,
                    arrayOf(Integer.TYPE, Integer.TYPE, String::class.java, String::class.java),
                ),
            )

        // Per-hook critical-probe sets. A hook is skipped if any key in
        // its set is in the broken list. mRoutes / mStackedLinks /
        // mTransportInfo are intentionally NOT critical — graceful
        // degradation lives in the existing inner try/catch blocks.
        private val LP_CRITICAL_KEYS =
            setOf(
                "LinkProperties.mIfaceName",
                "LinkProperties.<init>(LinkProperties)",
            )
        private val NC_CRITICAL_KEYS =
            setOf(
                "NetworkCapabilities.mTransportTypes",
                "NetworkCapabilities.mNetworkCapabilities",
            )
        private val NI_CRITICAL_KEYS =
            setOf(
                "NetworkInfo.mNetworkType",
                "NetworkInfo.mState",
                "NetworkInfo.mDetailedState",
                "NetworkInfo.mIsAvailable",
                "NetworkInfo.<init>(int,int,String,String)",
            )
    }
}
