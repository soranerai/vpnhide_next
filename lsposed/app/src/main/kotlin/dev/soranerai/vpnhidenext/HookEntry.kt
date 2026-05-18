package dev.soranerai.vpnhidenext

import android.database.sqlite.SQLiteDatabase
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.soranerai.vpnhidenext.generated.IfaceLists
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnHide — hide VPN presence from apps via system_server Binder hooks.
 *
 * This implementation is a symbiosis of the original VpnHide writeToParcel hooks
 * and ConnectivityService hooks:
 * 1. ThreadLocal Context: Tracks the target UID during system_server push callbacks
 *    (callCallbackForRequest) so writeToParcel hooks can sanitize data dispatched asynchronously.
 * 2. Request Poisoning: Strips NOT_VPN and TRANSPORT_VPN from requests so they match
 *    the VPN network, avoiding timeouts when the physical network is blocked/unreachable.
 * 3. writeToParcel hooks: Synchronously strips VPN properties and adds NOT_VPN, ensuring
 *    the app receives clean network data for both synchronous and asynchronous calls.
 */
class HookEntry : IXposedHookLoadPackage {
    private val hookInstalled = AtomicBoolean(false)

    // ThreadLocal context to track the target UID during system_server push callbacks
    private val currentCallbackUid = ThreadLocal<Int>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
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

    private fun isVpnInterfaceName(name: String): Boolean = IfaceLists.isVpnIface(name, loadIfacePrefixes())

    private fun sanitizeLinkProperties(copy: LinkProperties): Boolean {
        var modified = false

        val ifaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (ifaceName != null && isVpnInterfaceName(ifaceName)) {
            XposedHelpers.setObjectField(copy, "mIfaceName", null)
            modified = true
        }

        val currentIfaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (currentIfaceName == null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val dnsesField = XposedHelpers.getObjectField(copy, "mDnses") as? MutableCollection<java.net.InetAddress>
                if (dnsesField != null && dnsesField.isNotEmpty()) {
                    dnsesField.clear()
                    modified = true
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to sanitize mDnses: ${t.message}")
            }

            try {
                if (XposedHelpers.getObjectField(copy, "mDomains") != null) {
                    XposedHelpers.setObjectField(copy, "mDomains", null)
                    modified = true
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to sanitize mDomains: ${t.message}")
            }
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

    @Volatile private var databaseFileObserver: android.os.FileObserver? = null

    @Volatile private var selfUid: Int = -1
    private val uidLock = Any()

    private fun getIPackageManager(): Any? =
        try {
            val appGlobals = XposedHelpers.findClass("android.app.AppGlobals", null)
            XposedHelpers.callStaticMethod(appGlobals, "getPackageManager")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get IPackageManager: ${t.message}")
            null
        }

    private fun getPackageUid(
        pm: Any,
        pkg: String,
        userId: Int,
    ): Int =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    XposedHelpers.callMethod(pm, "getPackageUid", pkg, 0L, userId) as Int
                } catch (_: Throwable) {
                    XposedHelpers.callMethod(pm, "getPackageUid", pkg, 0, userId) as Int
                }
            } else {
                XposedHelpers.callMethod(pm, "getPackageUid", pkg, userId) as Int
            }
        } catch (t: Throwable) {
            -1
        }

    private fun loadTargetUids(): Set<Int> {
        systemServerTargetUids?.let { return it }
        synchronized(uidLock) {
            systemServerTargetUids?.let { return it }
            val uids = mutableSetOf<Int>()
            try {
                val dbFile = File(DB_PATH)
                if (dbFile.exists()) {
                    SQLiteDatabase
                        .openDatabase(
                            dbFile.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                        ).use { db ->
                            db.rawQuery("SELECT packageName, uid FROM app_protection WHERE lsposed = 1", null).use { cursor ->
                                val uidIdx = cursor.getColumnIndex("uid")
                                val pkgIdx = cursor.getColumnIndex("packageName")
                                while (cursor.moveToNext()) {
                                    val uid = if (uidIdx != -1) cursor.getInt(uidIdx) else 0
                                    if (uid != 0) {
                                        uids.add(uid)
                                    }
                                    if (pkgIdx != -1) {
                                        val pkg = cursor.getString(pkgIdx)
                                        if (!pkg.isNullOrEmpty()) {
                                            val parsed = pkg.toIntOrNull()
                                            if (parsed != null) {
                                                uids.add(parsed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to read database: ${t.message}")
            }

            if (selfUid == -1) {
                val pm = getIPackageManager()
                if (pm != null) {
                    selfUid = getPackageUid(pm, "dev.soranerai.vpnhidenext", 0)
                }
            }
            if (selfUid != -1) uids.add(selfUid)

            val result: Set<Int> = uids.toSet()
            systemServerTargetUids = result
            return result
        }
    }

    private fun isTargetCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 1000) { // system_server is pushing data
            val cbUid = currentCallbackUid.get()
            if (cbUid != null) {
                return loadTargetUids().contains(cbUid)
            }
        }
        return loadTargetUids().contains(callingUid)
    }

    @Volatile private var systemServerIfacePrefixes: List<String>? = null

    private fun loadIfacePrefixes(): List<String> {
        val cached = systemServerIfacePrefixes
        if (cached != null) return cached
        synchronized(uidLock) {
            val cached2 = systemServerIfacePrefixes
            if (cached2 != null) return cached2
            val prefixes = mutableListOf<String>()
            try {
                val dbFile = File(DB_PATH)
                if (dbFile.exists()) {
                    SQLiteDatabase
                        .openDatabase(
                            dbFile.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                        ).use { db ->
                            db.rawQuery("SELECT prefix FROM iface_prefixes", null)?.use { cursor ->
                                val idx = cursor.getColumnIndex("prefix")
                                if (idx != -1) {
                                    while (cursor.moveToNext()) {
                                        val prefix = cursor.getString(idx)
                                        if (prefix != null) {
                                            prefixes.add(prefix)
                                        }
                                    }
                                }
                            }
                        }
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to load iface prefixes: ${t.message}")
            }
            val result = prefixes.toList()
            systemServerIfacePrefixes = result
            return result
        }
    }

    private fun invalidateTargetUids() {
        systemServerTargetUids = null
        systemServerIfacePrefixes = null
    }

    private fun installSystemServerHooks(): List<String> {
        val brokenFields = runReflectionSmokeCheck()

        fun anyBroken(critical: Set<String>): Boolean = brokenFields.any { it.substringBefore(':') in critical }

        if (!anyBroken(LP_CRITICAL_KEYS)) tryHook("LP.writeToParcel") { hookLPWriteToParcel() }
        if (!anyBroken(NC_CRITICAL_KEYS)) tryHook("NC.writeToParcel") { hookNCWriteToParcel() }
        if (!anyBroken(NI_CRITICAL_KEYS)) tryHook("NI.writeToParcel") { hookNIWriteToParcel() }

        tryHook("APEX_Services") { hookApexServices() }
        tryHook("FileObserver") { watchDatabaseFile() }
        return brokenFields
    }

    private data class FieldProbe(
        val key: String,
        val clazz: Class<*>,
        val name: String,
        val minSdk: Int = 0,
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
            if (!probe.typeCheck(field.type)) broken += "${probe.key}:type=${field.type.name}"
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

    private fun writeHookStatusFile(brokenFields: List<String>) {
        try {
            val bootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
            val timestamp = System.currentTimeMillis() / 1000
            val version =
                try {
                    BuildConfig.VERSION_NAME
                } catch (_: Throwable) {
                    "1.0.0"
                }
            val sdk = Build.VERSION.SDK_INT
            val sb = java.lang.StringBuilder()
            sb.append("version=").append(version).append('\n')
            sb.append("boot_id=").append(bootId).append('\n')
            sb.append("timestamp=").append(timestamp).append('\n')
            sb.append("aosp_sdk=").append(sdk).append('\n')
            if (brokenFields.isNotEmpty()) {
                sb.append("broken_fields=").append(brokenFields.joinToString(",")).append('\n')
            }
            val statusFile = File(HOOK_STATUS_FILE)
            statusFile.writeText(sb.toString())
            HookLog.i(
                "VpnHide: wrote hook status file (version=$version, boot_id=$bootId, " +
                    "sdk=$sdk, broken=${brokenFields.size})",
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to write hook status file: ${t.message}")
        }
    }

    private fun watchDatabaseFile() {
        val dbFile = File(DB_PATH)
        val dir = dbFile.parent ?: "/data/system/vpnhide"
        val observer =
            object : android.os.FileObserver(dir, CLOSE_WRITE or MOVED_TO or DELETE) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path != null && (path == dbFile.name || path.startsWith("${dbFile.name}-"))) {
                        invalidateTargetUids()
                    }
                }
            }
        databaseFileObserver = observer
        observer.startWatching()
    }

    // ------------------------------------------------------------------
    //  Synchronous getNetworkCapabilities Hooks (writeToParcel)
    // ------------------------------------------------------------------

    private fun hookNCWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val nc = param.thisObject as NetworkCapabilities
                    val copy = NetworkCapabilities(nc)
                    if (!sanitizeNetworkCapabilities(copy)) return

                    val parcel = param.args[0] as android.os.Parcel
                    val flags = param.args[1] as Int
                    writingCopy.set(true)
                    try {
                        copy.writeToParcel(parcel, flags)
                    } finally {
                        writingCopy.set(false)
                    }
                    param.result = null
                }
            },
        )
    }

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
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val ni = param.thisObject as NetworkInfo
                    if (XposedHelpers.getIntField(ni, "mNetworkType") != TYPE_VPN) return

                    val ctor =
                        NetworkInfo::class.java.getDeclaredConstructor(
                            Integer.TYPE,
                            Integer.TYPE,
                            String::class.java,
                            String::class.java,
                        )
                    ctor.isAccessible = true
                    val copy = ctor.newInstance(TYPE_VPN, 0, "VPN", "") as NetworkInfo
                    XposedHelpers.setObjectField(copy, "mState", NetworkInfo.State.DISCONNECTED)
                    XposedHelpers.setObjectField(copy, "mDetailedState", NetworkInfo.DetailedState.DISCONNECTED)
                    XposedHelpers.setBooleanField(copy, "mIsAvailable", false)

                    val parcel = param.args[0] as android.os.Parcel
                    val flags = param.args[1] as Int
                    writingCopy.set(true)
                    try {
                        copy.writeToParcel(parcel, flags)
                    } finally {
                        writingCopy.set(false)
                    }
                    param.result = null
                }
            },
        )
    }

    private fun hookLPWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            LinkProperties::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val lp = param.thisObject as LinkProperties
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
                }
            },
        )
    }

    private fun sanitizeNetworkCapabilities(copy: NetworkCapabilities): Boolean {
        val transportTypes = XposedHelpers.getLongField(copy, "mTransportTypes")
        val vpnBit = 1L shl TRANSPORT_VPN
        if ((transportTypes and vpnBit) == 0L) return false

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
        return true
    }

    private fun poisonRequestCapabilities(copy: NetworkCapabilities): Boolean {
        var modified = false
        val transportTypes = XposedHelpers.getLongField(copy, "mTransportTypes")
        val vpnBit = 1L shl TRANSPORT_VPN
        // Remove VPN transport from request
        if ((transportTypes and vpnBit) != 0L) {
            XposedHelpers.setLongField(copy, "mTransportTypes", transportTypes and vpnBit.inv())
            modified = true
        }
        val caps = XposedHelpers.getLongField(copy, "mNetworkCapabilities")
        val notVpnBit = 1L shl NET_CAPABILITY_NOT_VPN
        // Remove NOT_VPN capability requirement from request so it successfully matches VPN network
        if ((caps and notVpnBit) != 0L) {
            XposedHelpers.setLongField(copy, "mNetworkCapabilities", caps and notVpnBit.inv())
            modified = true
        }
        return modified
    }

    // ------------------------------------------------------------------
    //  APEX / Asynchronous Hooks (Nekohasekai / Sing-box Architecture)
    // ------------------------------------------------------------------

    private val hookedServices = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun hookApexServices() {
        val smClass = XposedHelpers.findClass("android.os.ServiceManager", null)

        XposedBridge.hookAllMethods(
            smClass,
            "addService",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as? String ?: return
                    val binder = param.args[1] as? android.os.IBinder ?: return
                    val classLoader = binder.javaClass.classLoader ?: return
                    handleServiceHook(name, classLoader, "addService")
                }
            },
        )

        checkAndHookExistingService("connectivity", smClass)
    }

    private fun checkAndHookExistingService(
        name: String,
        smClass: Class<*>,
    ) {
        try {
            val binder = XposedHelpers.callStaticMethod(smClass, "getService", name) as? android.os.IBinder
            val classLoader = binder?.javaClass?.classLoader
            if (classLoader != null) handleServiceHook(name, classLoader, "getService")
        } catch (t: Throwable) {
        }
    }

    private fun handleServiceHook(
        name: String,
        classLoader: ClassLoader,
        source: String,
    ) {
        val hookKey = "$name@${System.identityHashCode(classLoader)}"
        if (!hookedServices.add(hookKey)) return

        when (name) {
            "connectivity" -> {
                HookLog.i("VpnHide: Installing APEX Connectivity hooks...")
                tryHook("ConnectivityService.networkLogic") { hookConnectivityService(classLoader) }
            }
        }
    }

    private fun hookConnectivityService(classLoader: ClassLoader) {
        val csClass =
            try {
                XposedHelpers.findClass("android.net.connectivity.com.android.server.ConnectivityService", classLoader)
            } catch (t: Throwable) {
                try {
                    XposedHelpers.findClass("com.android.server.ConnectivityService", classLoader)
                } catch (t2: Throwable) {
                    HookLog.e("VpnHide: failed to load ConnectivityService from both repackaged and original classes: ${t2.message}")
                    return
                }
            }

        val requestMethods =
            setOf(
                "requestNetwork",
                "listenForNetwork",
                "pendingRequestForNetwork",
                "pendingListenForNetwork",
            )

        for (method in csClass.declaredMethods) {
            // 1. ThreadLocal Context Injection (Fixed UID fields for modern Android)
            if (method.name == "callCallbackForRequest" || method.name == "sendPendingIntentForRequest") {
                try {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val nri = param.args.firstOrNull() ?: return
                                val uid =
                                    try {
                                        XposedHelpers.getIntField(nri, "mAsUid")
                                    } catch (_: Throwable) {
                                        try {
                                            XposedHelpers.getIntField(nri, "mUid")
                                        } catch (_: Throwable) {
                                            try {
                                                XposedHelpers.getIntField(nri, "uid")
                                            } catch (_: Throwable) {
                                                -1
                                            }
                                        }
                                    }

                                if (uid != -1 && loadTargetUids().contains(uid)) {
                                    currentCallbackUid.set(uid) // WriteToParcel hooks will now see this!
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                currentCallbackUid.remove()
                            }
                        },
                    )
                } catch (t: Throwable) {
                    HookLog.e("VpnHide: failed to hook callback injector: ${t.message}")
                }
            } else if (method.name in requestMethods || method.name.contains("DefaultNetworkCapabilities")) {
                // 2. Request Poisoning (removes NOT_VPN so it matches VPN network)
                try {
                    if (method.name.contains("DefaultNetworkCapabilities")) {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val uid =
                                        if (method.name.startsWith("copy")) {
                                            param.args.getOrNull(2) as? Int
                                        } else {
                                            param.args.getOrNull(0) as? Int
                                        }
                                    if (uid != null && loadTargetUids().contains(uid)) {
                                        val nc = param.result as? NetworkCapabilities ?: return
                                        val copy = NetworkCapabilities(nc)
                                        if (poisonRequestCapabilities(copy)) param.result = copy
                                    }
                                }
                            },
                        )
                    } else {
                        val ncIndex = method.parameterTypes.indexOfFirst { it == NetworkCapabilities::class.java }
                        if (ncIndex != -1) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val callingUid = Binder.getCallingUid()
                                        if (loadTargetUids().contains(callingUid)) {
                                            val nc = param.args[ncIndex] as? NetworkCapabilities ?: return
                                            val copy = NetworkCapabilities(nc)
                                            if (poisonRequestCapabilities(copy)) param.args[ncIndex] = copy
                                        }
                                    }
                                },
                            )
                        }
                    }
                } catch (t: Throwable) {
                }
            }
        }
        HookLog.e("VpnHide: Successfully applied Nekohasekai/VpnHide symbiosis architecture hooks.")
    }

    companion object {
        private const val TRANSPORT_VPN = 4
        private const val NET_CAPABILITY_NOT_VPN = 15
        const val TYPE_VPN = 17
        const val TYPE_WIFI = 1
        const val HOOK_STATUS_FILE = "/data/system/vpnhide_hook_active"
        const val DB_PATH = "/data/system/vpnhide/vpnhide_config.db"

        private val FIELD_PROBES =
            listOf(
                FieldProbe("LinkProperties.mIfaceName", LinkProperties::class.java, "mIfaceName") { it == String::class.java },
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
                    "LinkProperties.mDnses",
                    LinkProperties::class.java,
                    "mDnses",
                ) { java.util.Collection::class.java.isAssignableFrom(it) },
                FieldProbe("LinkProperties.mDomains", LinkProperties::class.java, "mDomains") { it == String::class.java },
                FieldProbe("NetworkCapabilities.mTransportTypes", NetworkCapabilities::class.java, "mTransportTypes") {
                    it ==
                        java.lang.Long.TYPE
                },
                FieldProbe("NetworkCapabilities.mNetworkCapabilities", NetworkCapabilities::class.java, "mNetworkCapabilities") {
                    it ==
                        java.lang.Long.TYPE
                },
                FieldProbe(
                    "NetworkCapabilities.mTransportInfo",
                    NetworkCapabilities::class.java,
                    "mTransportInfo",
                    minSdk = Build.VERSION_CODES.Q,
                ) { fieldType ->
                    runCatching { Class.forName("android.net.TransportInfo") }.map { it.isAssignableFrom(fieldType) }.getOrDefault(false)
                },
                FieldProbe("NetworkInfo.mNetworkType", NetworkInfo::class.java, "mNetworkType") { it == Integer.TYPE },
                FieldProbe("NetworkInfo.mState", NetworkInfo::class.java, "mState") { it == NetworkInfo.State::class.java },
                FieldProbe("NetworkInfo.mDetailedState", NetworkInfo::class.java, "mDetailedState") {
                    it ==
                        NetworkInfo.DetailedState::class.java
                },
                FieldProbe("NetworkInfo.mIsAvailable", NetworkInfo::class.java, "mIsAvailable") { it == java.lang.Boolean.TYPE },
            )

        private val CTOR_PROBES =
            listOf(
                CtorProbe("LinkProperties.<init>(LinkProperties)", LinkProperties::class.java, arrayOf(LinkProperties::class.java)),
                CtorProbe(
                    "NetworkInfo.<init>(int,int,String,String)",
                    NetworkInfo::class.java,
                    arrayOf(Integer.TYPE, Integer.TYPE, String::class.java, String::class.java),
                ),
            )

        private val LP_CRITICAL_KEYS = setOf("LinkProperties.mIfaceName", "LinkProperties.<init>(LinkProperties)")
        private val NC_CRITICAL_KEYS = setOf("NetworkCapabilities.mTransportTypes", "NetworkCapabilities.mNetworkCapabilities")
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
