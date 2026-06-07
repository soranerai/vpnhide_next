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
 * This implementation is a symbiosis of the original VpnHide writeToParcel hooks and
 * ConnectivityService hooks:
 * 1. ThreadLocal Context: Tracks the target UID during system_server push callbacks
 * ```
 *    (callCallbackForRequest) so writeToParcel hooks can sanitize data dispatched asynchronously.
 * ```
 * 2. Request Poisoning: Strips NOT_VPN and TRANSPORT_VPN from requests so they match
 * ```
 *    the VPN network, avoiding timeouts when the physical network is blocked/unreachable.
 * ```
 * 3. writeToParcel hooks: Synchronously strips VPN properties and adds NOT_VPN, ensuring
 * ```
 *    the app receives clean network data for both synchronous and asynchronous calls.
 * ```
 */
class HookEntry : IXposedHookLoadPackage {
    private val hookInstalled = AtomicBoolean(false)
    private val monitoringStarted = AtomicBoolean(false)

    // ThreadLocal context to track the target UID during system_server push callbacks
    private val currentCallbackUid = ThreadLocal<Int>()

    @Volatile private var cachedJavaHooksMask: UInt? = null

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
            watchStatsRequest()
        }
    }

    private fun recordIntercept(hookName: String) {
        val callingUid = Binder.getCallingUid()
        val targetUid =
            if (callingUid == 1000) {
                currentCallbackUid.get() ?: return
            } else {
                callingUid
            }
        if (!loadTargetUids().contains(targetUid)) return
        if (targetUid == selfUid) return

        val appStats =
            hookStats.computeIfAbsent(targetUid) { java.util.concurrent.ConcurrentHashMap() }
        appStats.computeIfAbsent(hookName) { RollingCounter() }.increment()
    }

    /**
     * Dumps the in-memory hookStats map to STATS_FILE exactly once. Called only when the manager
     * app signals via STATS_REQ_FILE. No continuous disk I/O — all stats live in the
     * ConcurrentHashMap.
     */
    private fun dumpHookStats() {
        try {
            val sb = java.lang.StringBuilder()
            for ((uid, appStats) in hookStats) {
                for ((hook, rollingCounter) in appStats) {
                    val count = rollingCounter.getSum()
                    if (count > 0) {
                        sb
                            .append(uid)
                            .append(';')
                            .append(hook)
                            .append(';')
                            .append(count)
                            .append('\n')
                    }
                }
            }
            File(STATS_FILE).writeText(sb.toString())
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to dump hook stats: ${t.message}")
        }
    }

    /**
     * Polls STATS_REQ_FILE every 500 ms from a daemon thread. When the manager app creates that
     * file, system_server deletes it and dumps hookStats to STATS_FILE exactly once.
     *
     * A dedicated thread is used instead of FileObserver to avoid inotify edge cases (e.g. 'touch'
     * on an existing file only emits ATTRIB, not CREATE/CLOSE_WRITE) and SELinux restrictions on
     * cross-UID inotify events that can silently prevent the callback from firing.
     */
    private fun watchStatsRequest() {
        val thread =
            Thread(
                {
                    while (true) {
                        try {
                            val reqFile = File(STATS_REQ_FILE)
                            if (reqFile.exists()) {
                                val content =
                                    runCatching { reqFile.readText().trim() }
                                        .getOrDefault("")
                                try {
                                    reqFile.delete()
                                } catch (_: Throwable) {
                                }
                                if (content == "clear" || content == "reset") {
                                    hookStats.clear()
                                    HookLog.i("VpnHide: cleared in-memory hook stats")
                                } else {
                                    dumpHookStats()
                                }
                            }
                        } catch (t: Throwable) {
                            HookLog.e("VpnHide: stats watcher error: ${t.message}")
                        }
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                "VpnHideStatsWatcher",
            )
        thread.isDaemon = true
        thread.start()
        HookLog.i("VpnHide: stats request watcher started (polling every 500 ms)")
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

    private fun isVpnInterfaceName(name: String): Boolean {
        if (IfaceLists.isVpnIface(name)) return true
        val prefixes = loadIfacePrefixes()
        for (prefix in prefixes) {
            if (name.startsWith(prefix, ignoreCase = true)) return true
        }
        return false
    }

    private fun sanitizeLinkProperties(copy: LinkProperties): Boolean {
        var modified = false
        val targetIface = getActivePhysicalInterfaceName()

        val ifaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (ifaceName != null && isVpnInterfaceName(ifaceName)) {
            XposedHelpers.setObjectField(copy, "mIfaceName", targetIface)
            modified = true
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val routesField =
                XposedHelpers.getObjectField(copy, "mRoutes") as? MutableList<RouteInfo>
            if (routesField != null) {
                val newRoutes = ArrayList<RouteInfo>()
                for (route in routesField) {
                    val routeIface = route.`interface`
                    if (routeIface != null && isVpnInterfaceName(routeIface)) {
                        // Clone the route via parcel to avoid mutating the shared original!
                        val parcel = android.os.Parcel.obtain()
                        val clonedRoute =
                            try {
                                route.writeToParcel(parcel, 0)
                                parcel.setDataPosition(0)
                                RouteInfo.CREATOR.createFromParcel(parcel)
                            } finally {
                                parcel.recycle()
                            }
                        XposedHelpers.setObjectField(clonedRoute, "mInterface", targetIface)
                        newRoutes.add(clonedRoute)
                        modified = true
                    } else {
                        newRoutes.add(route)
                    }
                }
                if (modified) {
                    routesField.clear()
                    routesField.addAll(newRoutes)
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mRoutes: ${t.message}")
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val linkAddresses =
                XposedHelpers.getObjectField(copy, "mLinkAddresses") as? MutableList<Any>
            if (linkAddresses != null) {
                val cs = getConnectivityService()
                val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
                if (physicalLp != null) {
                    @Suppress("UNCHECKED_CAST")
                    val physicalAddresses =
                        XposedHelpers.getObjectField(physicalLp, "mLinkAddresses") as? List<Any>
                    if (physicalAddresses != null) {
                        linkAddresses.clear()
                        linkAddresses.addAll(physicalAddresses)
                        modified = true
                    }
                } else {
                    linkAddresses.clear()
                    modified = true
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mLinkAddresses: ${t.message}")
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val stacked =
                XposedHelpers.getObjectField(copy, "mStackedLinks") as?
                    MutableMap<String, LinkProperties>
            if (stacked != null && stacked.isNotEmpty()) {
                val filtered = LinkedHashMap<String, LinkProperties>()
                for ((key, value) in stacked) {
                    val stackedCopy =
                        try {
                            val ctor =
                                LinkProperties::class.java.getDeclaredConstructor(
                                    LinkProperties::class.java,
                                )
                            ctor.isAccessible = true
                            ctor.newInstance(value) as LinkProperties
                        } catch (_: Throwable) {
                            value
                        }
                    val stackedModified = sanitizeLinkProperties(stackedCopy)
                    val stackedIface =
                        XposedHelpers.getObjectField(stackedCopy, "mIfaceName") as? String
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

        // Sanitize DNS servers
        try {
            @Suppress("UNCHECKED_CAST")
            val dnsField = XposedHelpers.getObjectField(copy, "mDnses") as? MutableCollection<Any>
            if (dnsField != null) {
                val cs = getConnectivityService()
                val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
                if (physicalLp != null) {
                    @Suppress("UNCHECKED_CAST")
                    val physicalDnses =
                        XposedHelpers.getObjectField(physicalLp, "mDnses") as? Collection<Any>
                    if (physicalDnses != null) {
                        dnsField.clear()
                        dnsField.addAll(physicalDnses)
                        modified = true
                    }
                } else {
                    dnsField.clear()
                    // Fallback to standard Google public DNS
                    dnsField.add(java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
                    dnsField.add(java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4)))
                    modified = true
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mDnses: ${t.message}")
        }

        // Sanitize Search domains
        try {
            val domains = XposedHelpers.getObjectField(copy, "mDomains") as? String
            if (!domains.isNullOrEmpty()) {
                val cs = getConnectivityService()
                val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
                val physicalDomains =
                    if (physicalLp != null) {
                        XposedHelpers.getObjectField(physicalLp, "mDomains") as? String
                    } else {
                        null
                    }
                XposedHelpers.setObjectField(copy, "mDomains", physicalDomains ?: "")
                modified = true
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mDomains: ${t.message}")
        }

        // Sanitize MTU
        try {
            val cs = getConnectivityService()
            val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
            val targetMtu =
                if (physicalLp != null) XposedHelpers.getIntField(physicalLp, "mMtu") else 1500
            val currentMtu = XposedHelpers.getIntField(copy, "mMtu")
            if (currentMtu < targetMtu) {
                XposedHelpers.setIntField(copy, "mMtu", targetMtu)
                modified = true
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mMtu: ${t.message}")
        }

        return modified
    }

    private fun getNetworkCapabilitiesSafe(
        cs: Any,
        net: android.net.Network,
    ): NetworkCapabilities? {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return try {
                XposedHelpers.callMethod(cs, "getNetworkCapabilities", net, "android", null) as?
                    NetworkCapabilities
            } catch (_: Throwable) {
                XposedHelpers.callMethod(cs, "getNetworkCapabilities", net) as? NetworkCapabilities
            }
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun getNetworkScore(
        nc: NetworkCapabilities,
        lp: LinkProperties?,
    ): Int {
        var score = 0

        // Base priorities for physical connections
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            score += 10000
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            score += 8000
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            score += 5000
        }

        // Add internet capability score (highest priority to ensure active routing works)
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            score += 10000
        }

        // Add validated capability score (active working internet connection check)
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 2000
        }

        // Add DNS capability score (if network has DNS servers configured)
        if (lp != null && lp.dnsServers.isNotEmpty()) {
            score += 3000
        }

        return score
    }

    private fun getBestPhysicalNetwork(cs: Any): android.net.Network? {
        val networks = XposedHelpers.callMethod(cs, "getAllNetworks") as? Array<*> ?: return null
        var bestNet: android.net.Network? = null
        var bestScore = Int.MIN_VALUE

        for (netObj in networks) {
            val net = netObj as? android.net.Network ?: continue
            val nc = getNetworkCapabilitiesSafe(cs, net) ?: continue

            // Skip VPN networks
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }

            // Must have some physical transport
            val hasPhysicalTransport =
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!hasPhysicalTransport) {
                continue
            }

            val lp = XposedHelpers.callMethod(cs, "getLinkProperties", net) as? LinkProperties
            val score = getNetworkScore(nc, lp)
            if (score > bestScore) {
                bestScore = score
                bestNet = net
            }
        }
        return bestNet
    }

    private fun getPhysicalLinkProperties(cs: Any): LinkProperties? {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val bestNet = getBestPhysicalNetwork(cs)
            if (bestNet != null) {
                val lp =
                    XposedHelpers.callMethod(cs, "getLinkProperties", bestNet) as?
                        LinkProperties
                if (lp != null) return lp
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get physical link properties: ${t.message}")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return null
    }

    private fun getPhysicalNetwork(cs: Any): android.net.Network? {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return getBestPhysicalNetwork(cs)
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get physical network: ${t.message}")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return null
    }

    private fun getActivePhysicalInterfaceName(): String {
        val cs = getConnectivityService()
        if (cs != null) {
            val lp = getPhysicalLinkProperties(cs)
            val iface = lp?.interfaceName
            if (iface != null) {
                return iface
            }
        }
        return "wlan0" // final fallback
    }

    private fun getConnectivityService(): Any? {
        val cached = csInstance
        if (cached != null) return cached
        try {
            val smClass = XposedHelpers.findClass("android.os.ServiceManager", null)
            val binder =
                XposedHelpers.callStaticMethod(smClass, "getService", "connectivity")
                    ?: return null
            val className = binder.javaClass.name
            if (className.contains("ConnectivityService")) {
                if (className.endsWith("ConnectivityService")) {
                    csInstance = binder
                    return binder
                }
                try {
                    val this0Field = XposedHelpers.findField(binder.javaClass, "this$0")
                    this0Field.isAccessible = true
                    val outer = this0Field.get(binder)
                    if (outer != null && outer.javaClass.name.contains("ConnectivityService")) {
                        csInstance = outer
                        return outer
                    }
                } catch (_: Throwable) {
                }
                csInstance = binder
                return binder
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get ConnectivityService: ${t.message}")
        }
        return null
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
        val pm = getIPackageManager()
        if (selfUid == -1 && pm != null) {
            synchronized(uidLock) {
                if (selfUid == -1) {
                    selfUid = getPackageUid(pm, "dev.soranerai.vpnhidenext", 0)
                    if (selfUid != -1) {
                        systemServerTargetUids = null
                    }
                }
            }
        }

        systemServerTargetUids?.let {
            return it
        }
        synchronized(uidLock) {
            systemServerTargetUids?.let {
                return it
            }
            val uids = mutableSetOf<Int>()
            try {
                val dbFile = File(DB_PATH)
                if (dbFile.exists()) {
                    SQLiteDatabase
                        .openDatabase(
                            dbFile.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or
                                SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                        ).use { db ->
                            db
                                .rawQuery(
                                    "SELECT packageName, userId, uid FROM app_protection WHERE lsposed = 1",
                                    null,
                                ).use { cursor ->
                                    val uidIdx = cursor.getColumnIndex("uid")
                                    val pkgIdx = cursor.getColumnIndex("packageName")
                                    val userIdIdx = cursor.getColumnIndex("userId")
                                    while (cursor.moveToNext()) {
                                        val pkg =
                                            if (pkgIdx != -1) {
                                                cursor.getString(pkgIdx)
                                            } else {
                                                ""
                                            }
                                        val userId =
                                            if (userIdIdx != -1) {
                                                cursor.getInt(userIdIdx)
                                            } else {
                                                0
                                            }
                                        val dbUid =
                                            if (uidIdx != -1) {
                                                cursor.getInt(uidIdx)
                                            } else {
                                                0
                                            }

                                        if (pkg.isNotEmpty()) {
                                            val resolvedUid =
                                                if (pm != null) {
                                                    val realUid =
                                                        getPackageUid(
                                                            pm,
                                                            pkg,
                                                            userId,
                                                        )
                                                    if (realUid > 0) realUid else dbUid
                                                } else {
                                                    dbUid
                                                }
                                            if (resolvedUid > 0) {
                                                uids.add(resolvedUid)
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

    private fun isJavaHookActive(bitIndex: Int): Boolean {
        var mask = cachedJavaHooksMask
        if (mask == null) {
            mask =
                try {
                    val dbFile = File(DB_PATH)
                    if (dbFile.exists()) {
                        SQLiteDatabase
                            .openDatabase(
                                dbFile.absolutePath,
                                null,
                                SQLiteDatabase.OPEN_READONLY or
                                    SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                            ).use { db ->
                                db
                                    .rawQuery(
                                        "SELECT javaHookMask FROM global_config LIMIT 1",
                                        null,
                                    ).use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            cursor.getLong(0).toUInt()
                                        } else {
                                            0xFFFFFFFFu
                                        }
                                    }
                            }
                    } else {
                        0xFFFFFFFFu
                    }
                } catch (t: Throwable) {
                    0xFFFFFFFFu
                }
            cachedJavaHooksMask = mask
        }
        val activeMask = mask ?: 0xFFFFFFFFu
        return (activeMask and (1u shl bitIndex)) != 0u
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
                            SQLiteDatabase.OPEN_READONLY or
                                SQLiteDatabase.NO_LOCALIZED_COLLATORS,
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
        tryHook("Network.writeToParcel") { hookNetworkWriteToParcel() }
        tryHook("WifiInfo") { hookWifiInfoInSystemServer() }

        tryHook("APEX_Services") { hookApexServices() }
        tryHook("FileObserver") { watchDatabaseFile() }
        return brokenFields
    }

    private fun startNetworkMonitoring() {
        if (!monitoringStarted.compareAndSet(false, true)) return
        val thread =
            Thread(
                {
                    HookLog.i("VpnHide: Network monitoring daemon thread started")
                    var lastIpv4: String? = null
                    var lastIpv6: String? = null
                    var firstRun = true

                    while (true) {
                        try {
                            val cs = getConnectivityService()
                            if (cs != null) {
                                val lp = getPhysicalLinkProperties(cs)
                                var ipv4: String? = null
                                var ipv6: String? = null

                                if (lp != null) {
                                    for (linkAddr in lp.linkAddresses) {
                                        val inetAddr = linkAddr.address
                                        val hostAddress = inetAddr.hostAddress
                                        if (hostAddress != null) {
                                            if (inetAddr is java.net.Inet4Address) {
                                                ipv4 = hostAddress
                                            } else if (inetAddr is java.net.Inet6Address) {
                                                ipv6 = hostAddress.substringBefore('%')
                                            }
                                        }
                                    }
                                }

                                if (firstRun || ipv4 != lastIpv4 || ipv6 != lastIpv6) {
                                    HookLog.i(
                                        "VpnHide: Physical IP changed or initial sync: IPv4=$ipv4, IPv6=$ipv6",
                                    )
                                    sendSpoofIpToKernel(ipv4, ipv6)
                                    lastIpv4 = ipv4
                                    lastIpv6 = ipv6
                                    firstRun = false
                                }
                            }
                            // Stats live only in-memory; dumped on-demand via
                            // watchStatsRequest()
                        } catch (t: Throwable) {
                            HookLog.e(
                                "VpnHide: Error in network monitoring loop: ${t::class.java.simpleName}: ${t.message}",
                            )
                        }

                        try {
                            Thread.sleep(3000)
                        } catch (e: InterruptedException) {
                            HookLog.i(
                                "VpnHide: Network monitoring daemon thread interrupted",
                            )
                            break
                        }
                    }
                },
                "VpnHideNetworkMonitor",
            )
        thread.isDaemon = true
        thread.start()
    }

    private fun sendSpoofIpToKernel(
        ipv4: String?,
        ipv6: String?,
    ) {
        try {
            val file = File("/data/system/vpnhide_physical_ip")
            val v4 = if (ipv4.isNullOrEmpty() || ipv4 == "none") "none" else ipv4
            val v6 = if (ipv6.isNullOrEmpty() || ipv6 == "none") "none" else ipv6
            file.writeText("$v4 $v6\n")
            HookLog.i(
                "VpnHide: Successfully wrote spoof IP to /data/system/vpnhide_physical_ip: IPv4=$v4, IPv6=$v6",
            )
        } catch (t: Throwable) {
            HookLog.e(
                "VpnHide: failed to write spoof IP to file: ${t::class.java.simpleName}: ${t.message}",
            )
        }
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
                    if (path != null) {
                        if (path == dbFile.name || path.startsWith("${dbFile.name}-")) {
                            invalidateTargetUids()
                            cachedJavaHooksMask = null
                        }
                    }
                }
            }
        databaseFileObserver = observer
        observer.startWatching()
    }

    // ------------------------------------------------------------------
    //  Synchronous getNetworkCapabilities Hooks (writeToParcel)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  WifiInfo hooks — fix IP/SSID/BSSID redacted by Android 12+
    //  privacy controls when returning WifiInfo to apps that lack
    //  ACCESS_FINE_LOCATION. These run in system_server on the write
    //  (serialisation) side — equivalent to XPL-EX's createFromParcel
    //  hook on the read side inside the app process.
    // ------------------------------------------------------------------

    private fun hookWifiInfoInSystemServer() {
        val wifiInfoClass =
            try {
                XposedHelpers.findClass("android.net.wifi.WifiInfo", null)
            } catch (t: Throwable) {
                HookLog.e("VpnHide: WifiInfo class not found: ${t.message}")
                return
            }
        hookWifiInfoRedactingCtor(wifiInfoClass)
        hookWifiInfoWriteToParcel(wifiInfoClass)
    }

    /**
     * Android 12+ redacts sensitive WifiInfo fields when creating a copy for an app that lacks
     * ACCESS_FINE_LOCATION: WifiInfo(WifiInfo source, long redactions) Specifically sets
     * mIpAddress=null, mSSID="<unknown ssid>", mBSSID="02:00:00:00:00:00". We restore them from the
     * source object for target callers so the app sees the real WiFi state.
     */
    private fun hookWifiInfoRedactingCtor(wifiInfoClass: Class<*>) {
        try {
            val ctor = wifiInfoClass.getDeclaredConstructor(wifiInfoClass, java.lang.Long.TYPE)
            ctor.isAccessible = true
            XposedBridge.hookMethod(
                ctor,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(4)) return
                        if (!isTargetCaller()) return
                        val redactions = param.args[1] as? Long ?: return
                        if (redactions == 0L) return // nothing was redacted
                        recordIntercept("WifiInfo")

                        val source = param.args[0] ?: return
                        val result = param.thisObject

                        // Restore mIpAddress (InetAddress, nulled on redaction)
                        try {
                            val srcIp = XposedHelpers.getObjectField(source, "mIpAddress")
                            val dstIp = XposedHelpers.getObjectField(result, "mIpAddress")
                            if (dstIp == null && srcIp != null) {
                                XposedHelpers.setObjectField(result, "mIpAddress", srcIp)
                                HookLog.i("VpnHide: WifiInfo ctor – restored mIpAddress")
                            }
                        } catch (_: Throwable) {
                        }

                        // Restore SSID if it was replaced with UNKNOWN_SSID
                        try {
                            val srcSSID =
                                XposedHelpers.getObjectField(source, "mSSID") as? String
                            val dstSSID =
                                XposedHelpers.getObjectField(result, "mSSID") as? String
                            if (dstSSID == "<unknown ssid>" &&
                                srcSSID != null &&
                                srcSSID != "<unknown ssid>"
                            ) {
                                XposedHelpers.setObjectField(result, "mSSID", srcSSID)
                                HookLog.i("VpnHide: WifiInfo ctor – restored SSID")
                            }
                        } catch (_: Throwable) {
                        }

                        // Restore BSSID if replaced with default anonymised MAC
                        try {
                            val srcBSSID =
                                XposedHelpers.getObjectField(source, "mBSSID") as? String
                            val dstBSSID =
                                XposedHelpers.getObjectField(result, "mBSSID") as? String
                            if (dstBSSID == "02:00:00:00:00:00" &&
                                srcBSSID != null &&
                                srcBSSID != "02:00:00:00:00:00"
                            ) {
                                XposedHelpers.setObjectField(result, "mBSSID", srcBSSID)
                                HookLog.i("VpnHide: WifiInfo ctor – restored BSSID")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            HookLog.i("VpnHide: WifiInfo(WifiInfo, long) redacting-ctor hook installed")
        } catch (t: Throwable) {
            // Constructor only exists on Android 12+ (API 31)
            HookLog.i("VpnHide: WifiInfo(WifiInfo, long) ctor not available (pre-S?): ${t.message}")
        }
    }

    /**
     * Belt-and-suspenders fallback: intercept WifiInfo.writeToParcel for target callers and fix any
     * remaining IP=null/0 by pulling the real IPv4 address from the physical network's
     * LinkProperties. Handles both old (int mIpAddress) and new (InetAddress mIpAddress) layouts.
     */
    private fun hookWifiInfoWriteToParcel(wifiInfoClass: Class<*>) {
        val writingFixed = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            wifiInfoClass,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isJavaHookActive(4)) return
                    if (writingFixed.get() == true || !isTargetCaller()) return
                    val wifiInfo = param.thisObject ?: return

                    // Determine whether mIpAddress is an int or InetAddress field
                    val ipField =
                        try {
                            wifiInfoClass.getDeclaredField("mIpAddress").also {
                                it.isAccessible = true
                            }
                        } catch (_: Throwable) {
                            return
                        }

                    val needsFix: Boolean
                    val physIp: java.net.Inet4Address?

                    when {
                        // Android < 29: mIpAddress is an int
                        ipField.type == java.lang.Integer.TYPE -> {
                            needsFix = ipField.getInt(wifiInfo) == 0
                            physIp = if (needsFix) getPhysicalIpv4Address() else null
                        }

                        // Android 29+: mIpAddress is an InetAddress (or null when redacted)
                        java.net.InetAddress::class.java.isAssignableFrom(ipField.type) -> {
                            val addr = ipField.get(wifiInfo) as? java.net.Inet4Address
                            needsFix = addr == null || addr.address.all { it == 0.toByte() }
                            physIp = if (needsFix) getPhysicalIpv4Address() else null
                        }

                        else -> {
                            return
                        }
                    }

                    if (!needsFix || physIp == null) return
                    recordIntercept("WifiInfo")

                    // Clone WifiInfo — try simple copy-ctor first, then redact-none ctor
                    val copy =
                        try {
                            val c = wifiInfoClass.getDeclaredConstructor(wifiInfoClass)
                            c.isAccessible = true
                            c.newInstance(wifiInfo)
                        } catch (_: Throwable) {
                            try {
                                val c =
                                    wifiInfoClass.getDeclaredConstructor(
                                        wifiInfoClass,
                                        java.lang.Long.TYPE,
                                    )
                                c.isAccessible = true
                                c.newInstance(wifiInfo, 0L) // 0 = REDACT_NONE
                            } catch (_: Throwable) {
                                return
                            }
                        }

                    // Write fixed IP into the copy
                    try {
                        val copyIpField = wifiInfoClass.getDeclaredField("mIpAddress")
                        copyIpField.isAccessible = true
                        when {
                            copyIpField.type == java.lang.Integer.TYPE -> {
                                // Convert Inet4Address → little-endian int (Android format)
                                val b = physIp.address
                                val intIp =
                                    ((b[3].toInt() and 0xFF) shl 24) or
                                        ((b[2].toInt() and 0xFF) shl 16) or
                                        ((b[1].toInt() and 0xFF) shl 8) or
                                        (b[0].toInt() and 0xFF)
                                copyIpField.setInt(copy, intIp)
                            }

                            else -> {
                                copyIpField.set(copy, physIp)
                            }
                        }
                    } catch (_: Throwable) {
                        return
                    }

                    val parcel = param.args[0] as android.os.Parcel
                    val flags = param.args[1] as Int
                    writingFixed.set(true)
                    try {
                        // Hook will fire again for `copy` but see writingFixed=true → skip
                        XposedHelpers.callMethod(copy, "writeToParcel", parcel, flags)
                    } finally {
                        writingFixed.set(false)
                    }
                    param.result = null
                    HookLog.i(
                        "VpnHide: WifiInfo.writeToParcel – fixed IP to ${physIp.hostAddress}",
                    )
                }
            },
        )
        HookLog.i("VpnHide: WifiInfo.writeToParcel hook installed")
    }

    /**
     * Get the IPv4 address of the best physical network (WiFi/Cell/Ethernet) by reading
     * LinkProperties from ConnectivityService.
     */
    private fun getPhysicalIpv4Address(): java.net.Inet4Address? {
        return try {
            val cs = getConnectivityService() ?: return null
            getPhysicalLinkProperties(cs)
                ?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address as?
                java.net.Inet4Address
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookNCWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isJavaHookActive(1)) return
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val nc = param.thisObject as NetworkCapabilities
                    val copy = NetworkCapabilities(nc)
                    if (!sanitizeNetworkCapabilities(copy)) return
                    recordIntercept("NetworkCapabilities")

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
                    if (!isJavaHookActive(2)) return
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val ni = param.thisObject as NetworkInfo
                    if (XposedHelpers.getIntField(ni, "mNetworkType") != TYPE_VPN) return
                    recordIntercept("NetworkInfo")

                    val cs = getConnectivityService()
                    val physicalNet = if (cs != null) getPhysicalNetwork(cs) else null
                    val physicalNi =
                        if (cs != null && physicalNet != null) {
                            try {
                                XposedHelpers.callMethod(
                                    cs,
                                    "getNetworkInfo",
                                    physicalNet,
                                ) as?
                                    NetworkInfo
                            } catch (_: Throwable) {
                                null
                            }
                        } else {
                            null
                        }

                    val copy: NetworkInfo
                    if (physicalNi != null) {
                        copy =
                            try {
                                val copyCtor =
                                    NetworkInfo::class.java.getDeclaredConstructor(
                                        NetworkInfo::class.java,
                                    )
                                copyCtor.isAccessible = true
                                copyCtor.newInstance(physicalNi) as NetworkInfo
                            } catch (_: Throwable) {
                                // Fallback to manual creation if copy constructor is not
                                // accessible or fails
                                val ctor =
                                    NetworkInfo::class.java.getDeclaredConstructor(
                                        Integer.TYPE,
                                        Integer.TYPE,
                                        String::class.java,
                                        String::class.java,
                                    )
                                ctor.isAccessible = true
                                val type =
                                    XposedHelpers.getIntField(
                                        physicalNi,
                                        "mNetworkType",
                                    )
                                val subtype =
                                    XposedHelpers.getIntField(physicalNi, "mSubtype")
                                val typeName =
                                    XposedHelpers.getObjectField(
                                        physicalNi,
                                        "mTypeName",
                                    ) as?
                                        String
                                        ?: "MOBILE"
                                val subtypeName =
                                    XposedHelpers.getObjectField(
                                        physicalNi,
                                        "mSubtypeName",
                                    ) as?
                                        String
                                        ?: ""
                                val dummy =
                                    ctor.newInstance(
                                        type,
                                        subtype,
                                        typeName,
                                        subtypeName,
                                    ) as
                                        NetworkInfo
                                XposedHelpers.setObjectField(
                                    dummy,
                                    "mState",
                                    XposedHelpers.getObjectField(physicalNi, "mState"),
                                )
                                XposedHelpers.setObjectField(
                                    dummy,
                                    "mDetailedState",
                                    XposedHelpers.getObjectField(
                                        physicalNi,
                                        "mDetailedState",
                                    ),
                                )
                                XposedHelpers.setBooleanField(
                                    dummy,
                                    "mIsAvailable",
                                    XposedHelpers.getBooleanField(
                                        physicalNi,
                                        "mIsAvailable",
                                    ),
                                )
                                dummy
                            }
                        HookLog.i(
                            "VpnHide: NetworkInfo.writeToParcel – morphed VPN info into physical network info",
                        )
                    } else {
                        val ctor =
                            NetworkInfo::class.java.getDeclaredConstructor(
                                Integer.TYPE,
                                Integer.TYPE,
                                String::class.java,
                                String::class.java,
                            )
                        ctor.isAccessible = true
                        copy = ctor.newInstance(0, 0, "MOBILE", "") as NetworkInfo
                        XposedHelpers.setObjectField(
                            copy,
                            "mState",
                            NetworkInfo.State.DISCONNECTED,
                        )
                        XposedHelpers.setObjectField(
                            copy,
                            "mDetailedState",
                            NetworkInfo.DetailedState.DISCONNECTED,
                        )
                        XposedHelpers.setBooleanField(copy, "mIsAvailable", false)
                        HookLog.i(
                            "VpnHide: NetworkInfo.writeToParcel – morphed VPN info into disconnected MOBILE info (no physical network)",
                        )
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
                }
            },
        )
    }

    private fun hookNetworkWriteToParcel() {
        val writingNetCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            android.net.Network::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isJavaHookActive(3)) return
                    if (writingNetCopy.get() == true) return
                    val target = isTargetCaller()
                    if (!target) return

                    val net = param.thisObject as android.net.Network
                    writingNetCopy.set(true)
                    try {
                        val cs = getConnectivityService() ?: return
                        val nc = getNetworkCapabilitiesSafe(cs, net) ?: return

                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            recordIntercept("Network")
                            val physicalNet = getPhysicalNetwork(cs)
                            if (physicalNet != null) {
                                val parcel = param.args[0] as android.os.Parcel
                                val flags = param.args[1] as Int
                                physicalNet.writeToParcel(parcel, flags)
                                param.result = null
                            }
                        }
                    } finally {
                        writingNetCopy.set(false)
                    }
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
                    if (!isJavaHookActive(0)) return
                    if (writingCopy.get() == true || !isTargetCaller()) return
                    val lp = param.thisObject as LinkProperties
                    val isVpn = lp.interfaceName?.let { isVpnInterfaceName(it) } ?: false
                    if (isVpn) {
                        recordIntercept("LinkProperties")
                        val cs = getConnectivityService()
                        val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
                        if (physicalLp != null) {
                            val parcel = param.args[0] as android.os.Parcel
                            val flags = param.args[1] as Int
                            writingCopy.set(true)
                            try {
                                physicalLp.writeToParcel(parcel, flags)
                            } finally {
                                writingCopy.set(false)
                            }
                            param.result = null
                            return
                        } else {
                            val ctor =
                                LinkProperties::class.java.getDeclaredConstructor(
                                    LinkProperties::class.java,
                                )
                            ctor.isAccessible = true
                            val copy = ctor.newInstance(lp) as LinkProperties
                            if (sanitizeLinkProperties(copy)) {
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
                        }
                    }
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
        XposedHelpers.setLongField(
            copy,
            "mNetworkCapabilities",
            caps or (1L shl NET_CAPABILITY_NOT_VPN),
        )
        try {
            val ti = XposedHelpers.getObjectField(copy, "mTransportInfo")
            if (ti != null && ti.javaClass.name == "android.net.VpnTransportInfo") {
                XposedHelpers.setObjectField(copy, "mTransportInfo", null)
            }
        } catch (_: Throwable) {
        }

        try {
            if (XposedHelpers.getObjectField(copy, "mUnderlyingNetworks") != null) {
                XposedHelpers.setObjectField(copy, "mUnderlyingNetworks", null)
            }
        } catch (_: Throwable) {
        }

        val newTransports = XposedHelpers.getLongField(copy, "mTransportTypes")
        val wifiBit = 1L shl TRANSPORT_WIFI
        val cellBit = 1L shl TRANSPORT_CELLULAR
        val ethBit = 1L shl TRANSPORT_ETHERNET
        val btBit = 1L shl TRANSPORT_BLUETOOTH
        val hasPhysical = (newTransports and (wifiBit or cellBit or ethBit or btBit)) != 0L

        val cs = getConnectivityService()
        val physicalNet = if (cs != null) getPhysicalNetwork(cs) else null
        val physicalNc =
            if (cs != null && physicalNet != null) {
                getNetworkCapabilitiesSafe(cs, physicalNet)
            } else {
                null
            }

        if (!hasPhysical) {
            if (physicalNc != null) {
                val realTransports = XposedHelpers.getLongField(physicalNc, "mTransportTypes")
                XposedHelpers.setLongField(
                    copy,
                    "mTransportTypes",
                    newTransports or (realTransports and vpnBit.inv()),
                )
            } else {
                XposedHelpers.setLongField(copy, "mTransportTypes", newTransports or wifiBit)
            }
        }

        try {
            if (physicalNc != null) {
                val realTi = XposedHelpers.getObjectField(physicalNc, "mTransportInfo")
                XposedHelpers.setObjectField(copy, "mTransportInfo", realTi)
            } else {
                XposedHelpers.setObjectField(copy, "mTransportInfo", null)
            }
        } catch (_: Throwable) {
        }

        try {
            if (physicalNc != null) {
                val realSs = XposedHelpers.getIntField(physicalNc, "mSignalStrength")
                XposedHelpers.setIntField(copy, "mSignalStrength", realSs)
            } else {
                val ss = XposedHelpers.getIntField(copy, "mSignalStrength")
                if (ss == Integer.MIN_VALUE) { // SIGNAL_STRENGTH_UNSPECIFIED
                    XposedHelpers.setIntField(copy, "mSignalStrength", -50)
                }
            }
        } catch (_: Throwable) {
        }

        try {
            if (physicalNc != null) {
                val realDown = XposedHelpers.getIntField(physicalNc, "mLinkDownBandwidthKbps")
                val realUp = XposedHelpers.getIntField(physicalNc, "mLinkUpBandwidthKbps")
                XposedHelpers.setIntField(copy, "mLinkDownBandwidthKbps", realDown)
                XposedHelpers.setIntField(copy, "mLinkUpBandwidthKbps", realUp)
            } else {
                val down = XposedHelpers.getIntField(copy, "mLinkDownBandwidthKbps")
                val up = XposedHelpers.getIntField(copy, "mLinkUpBandwidthKbps")
                if (down == 0 || down > 10_000_000) {
                    XposedHelpers.setIntField(copy, "mLinkDownBandwidthKbps", 150_000) // 150 Mbps
                }
                if (up == 0 || up > 10_000_000) {
                    XposedHelpers.setIntField(copy, "mLinkUpBandwidthKbps", 75_000) // 75 Mbps
                }
            }
        } catch (_: Throwable) {
        }

        return true
    }

    // ------------------------------------------------------------------
    //  Asynchronous Hooks (grats to nekohasekai)
    // ------------------------------------------------------------------

    private val hookedServices =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )

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
                    handleServiceHook(name, classLoader)
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
            val binder =
                XposedHelpers.callStaticMethod(smClass, "getService", name) as?
                    android.os.IBinder
            val classLoader = binder?.javaClass?.classLoader
            if (classLoader != null) handleServiceHook(name, classLoader)
        } catch (t: Throwable) {
        }
    }

    private fun handleServiceHook(
        name: String,
        classLoader: ClassLoader,
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
                XposedHelpers.findClass(
                    "android.net.connectivity.com.android.server.ConnectivityService",
                    classLoader,
                )
            } catch (t: Throwable) {
                try {
                    XposedHelpers.findClass(
                        "com.android.server.ConnectivityService",
                        classLoader,
                    )
                } catch (t2: Throwable) {
                    HookLog.e(
                        "VpnHide: failed to load ConnectivityService from both repackaged and original classes: ${t2.message}",
                    )
                    return
                }
            }

        try {
            XposedBridge.hookAllConstructors(
                csClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        csInstance = param.thisObject
                        HookLog.i("VpnHide: Captured ConnectivityService instance successfully")
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook ConnectivityService constructor: ${t.message}")
        }

        for (method in csClass.declaredMethods) {
            // 1. ThreadLocal Context Injection (Fixed UID fields for modern Android)
            if (method.name == "callCallbackForRequest" ||
                method.name == "sendPendingIntentForRequest"
            ) {
                try {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isJavaHookActive(5)) return
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
                                    var request: android.net.NetworkRequest? = null
                                    var clazz: Class<*>? = nri.javaClass
                                    while (clazz != null && clazz != Any::class.java) {
                                        for (field in clazz.declaredFields) {
                                            if (field.type ==
                                                android.net.NetworkRequest::class
                                                    .java
                                            ) {
                                                try {
                                                    field.isAccessible = true
                                                    request =
                                                        field.get(nri) as?
                                                            android.net.NetworkRequest
                                                    if (request != null) break
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }
                                        if (request != null) break
                                        clazz = clazz.superclass
                                    }

                                    if (request != null &&
                                        request.hasTransport(
                                            android.net.NetworkCapabilities
                                                .TRANSPORT_VPN,
                                        )
                                    ) {
                                        HookLog.i(
                                            "VpnHide: Suppressing VPN callback/intent for target UID $uid",
                                        )
                                        recordIntercept("ConnectivityService")
                                        param.result = null
                                        return
                                    }

                                    currentCallbackUid.set(
                                        uid,
                                    ) // WriteToParcel hooks will now see this!
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
            } else if (method.name.contains("DefaultNetworkCapabilities")) {
                try {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isJavaHookActive(5)) return
                                val uid =
                                    if (method.name.startsWith("copy")) {
                                        param.args.getOrNull(2) as? Int
                                    } else {
                                        param.args.getOrNull(0) as? Int
                                    }
                                if (uid != null && loadTargetUids().contains(uid)) {
                                    val nc = param.result as? NetworkCapabilities ?: return
                                    val copy = NetworkCapabilities(nc)
                                    if (sanitizeNetworkCapabilities(copy)) {
                                        recordIntercept("ConnectivityService")
                                        param.result = copy
                                    }
                                }
                            }
                        },
                    )
                } catch (t: Throwable) {
                }
            }
        }

        try {
            val getActiveNetworkMethod =
                XposedHelpers.findMethodExact(
                    csClass,
                    "getActiveNetwork",
                    *emptyArray<Class<*>>(),
                )
            XposedBridge.hookMethod(
                getActiveNetworkMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (loadTargetUids().contains(callingUid)) {
                            val activeNet = param.result as? android.net.Network ?: return
                            val cs = param.thisObject
                            val nc = getNetworkCapabilitiesSafe(cs, activeNet) ?: return

                            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                recordIntercept("ConnectivityService")
                                val physicalNet = getPhysicalNetwork(cs)
                                if (physicalNet != null) {
                                    param.result = physicalNet
                                } else {
                                    param.result = null
                                }
                            }
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getActiveNetwork: ${t.message}")
        }

        try {
            val getAllNetworksMethod =
                XposedHelpers.findMethodExact(
                    csClass,
                    "getAllNetworks",
                    *emptyArray<Class<*>>(),
                )
            XposedBridge.hookMethod(
                getAllNetworksMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (loadTargetUids().contains(callingUid)) {
                            val networks = param.result as? Array<*> ?: return
                            val filteredList = ArrayList<android.net.Network>()
                            var intercepted = false
                            val token = android.os.Binder.clearCallingIdentity()
                            try {
                                val cs = param.thisObject
                                for (netObj in networks) {
                                    val net = netObj as? android.net.Network ?: continue
                                    val nc = getNetworkCapabilitiesSafe(cs, net) ?: continue

                                    if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                        filteredList.add(net)
                                    } else {
                                        intercepted = true
                                    }
                                }
                            } finally {
                                android.os.Binder.restoreCallingIdentity(token)
                            }
                            if (intercepted) {
                                recordIntercept("ConnectivityService")
                            }

                            val newArray =
                                java.lang.reflect.Array.newInstance(
                                    android.net.Network::class.java,
                                    filteredList.size,
                                ) as
                                    Array<*>
                            for (i in filteredList.indices) {
                                java.lang.reflect.Array
                                    .set(newArray, i, filteredList[i])
                            }
                            param.result = newArray
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getAllNetworks: ${t.message}")
        }

        try {
            val getNetworkForTypeMethod =
                XposedHelpers.findMethodExact(csClass, "getNetworkForType", Integer.TYPE)
            XposedBridge.hookMethod(
                getNetworkForTypeMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (loadTargetUids().contains(callingUid)) {
                            val type = param.args[0] as? Int ?: return
                            if (type == TYPE_VPN) {
                                HookLog.i(
                                    "VpnHide: Suppressing getNetworkForType(TYPE_VPN) for target UID $callingUid",
                                )
                                param.result = null
                            }
                        }
                    }
                },
            )
            HookLog.i("VpnHide: getNetworkForType hook installed")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getNetworkForType: ${t.message}")
        }

        try {
            val getDefaultProxyMethod =
                XposedHelpers.findMethodExact(
                    csClass,
                    "getDefaultProxy",
                    *emptyArray<Class<*>>(),
                )
            XposedBridge.hookMethod(
                getDefaultProxyMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (loadTargetUids().contains(callingUid)) {
                            if (param.result != null) {
                                HookLog.i(
                                    "VpnHide: Suppressing getDefaultProxy() for target UID $callingUid",
                                )
                                param.result = null
                            }
                        }
                    }
                },
            )
            HookLog.i("VpnHide: getDefaultProxy hook installed")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getDefaultProxy: ${t.message}")
        }

        try {
            val getProxyForNetworkMethod =
                XposedHelpers.findMethodExact(
                    csClass,
                    "getProxyForNetwork",
                    android.net.Network::class.java,
                )
            XposedBridge.hookMethod(
                getProxyForNetworkMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (loadTargetUids().contains(callingUid)) {
                            if (param.result != null) {
                                HookLog.i(
                                    "VpnHide: Suppressing getProxyForNetwork() for target UID $callingUid",
                                )
                                param.result = null
                            }
                        }
                    }
                },
            )
            HookLog.i("VpnHide: getProxyForNetwork hook installed")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getProxyForNetwork: ${t.message}")
        }

        HookLog.e("VpnHide: Successfully applied all hooks.")
    }

    companion object {
        @Volatile var csInstance: Any? = null

        private const val TRANSPORT_CELLULAR = 0
        private const val TRANSPORT_WIFI = 1
        private const val TRANSPORT_BLUETOOTH = 2
        private const val TRANSPORT_ETHERNET = 3
        private const val TRANSPORT_VPN = 4
        private const val NET_CAPABILITY_NOT_VPN = 15
        const val TYPE_VPN = 17
        const val TYPE_WIFI = 1
        const val HOOK_STATUS_FILE = "/data/system/vpnhide_hook_active"

        /** Written on-demand when the manager app creates STATS_REQ_FILE. */
        const val STATS_FILE = "/data/system/vpnhide_hook_stats.txt"

        /** Manager app creates this file to trigger an on-demand stats dump from system_server. */
        const val STATS_REQ_FILE = "/data/system/vpnhide_hook_stats_req"
        const val DB_PATH = "/data/system/vpnhide/vpnhide_config.db"

        internal class RollingCounter(
            private val windowMinutes: Int = 30,
        ) {
            private val bucketCounts = IntArray(windowMinutes)
            private val bucketTimes = LongArray(windowMinutes)

            @Synchronized
            fun increment() {
                val nowMs = System.currentTimeMillis()
                val nowMin = nowMs / 60000L
                val idx = (nowMin % windowMinutes).toInt()
                if (bucketTimes[idx] != nowMin) {
                    bucketCounts[idx] = 1
                    bucketTimes[idx] = nowMin
                } else {
                    bucketCounts[idx]++
                }
            }

            @Synchronized
            fun getSum(): Int {
                val nowMs = System.currentTimeMillis()
                val nowMin = nowMs / 60000L
                var sum = 0
                for (i in 0 until windowMinutes) {
                    if (nowMin - bucketTimes[i] < windowMinutes) {
                        sum += bucketCounts[i]
                    }
                }
                return sum
            }

            @Synchronized
            fun clear() {
                bucketCounts.fill(0)
                bucketTimes.fill(0)
            }
        }

        /**
         * All intercept stats live only in memory. Written to disk only when the manager requests
         * it.
         */
        private val hookStats =
            java.util.concurrent.ConcurrentHashMap<
                Int,
                java.util.concurrent.ConcurrentHashMap<String, RollingCounter>,
            >()

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
                    "LinkProperties.mDnses",
                    LinkProperties::class.java,
                    "mDnses",
                ) { java.util.Collection::class.java.isAssignableFrom(it) },
                FieldProbe(
                    "LinkProperties.mDomains",
                    LinkProperties::class.java,
                    "mDomains",
                ) { it == String::class.java },
                FieldProbe("LinkProperties.mMtu", LinkProperties::class.java, "mMtu") {
                    it == java.lang.Integer.TYPE
                },
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
                    runCatching { Class.forName("android.net.TransportInfo") }
                        .map { it.isAssignableFrom(fieldType) }
                        .getOrDefault(false)
                },
                FieldProbe(
                    "NetworkInfo.mNetworkType",
                    NetworkInfo::class.java,
                    "mNetworkType",
                ) { it == Integer.TYPE },
                FieldProbe("NetworkInfo.mState", NetworkInfo::class.java, "mState") {
                    it == NetworkInfo.State::class.java
                },
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
                    arrayOf(
                        Integer.TYPE,
                        Integer.TYPE,
                        String::class.java,
                        String::class.java,
                    ),
                ),
            )

        private val LP_CRITICAL_KEYS =
            setOf("LinkProperties.mIfaceName", "LinkProperties.<init>(LinkProperties)")
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
