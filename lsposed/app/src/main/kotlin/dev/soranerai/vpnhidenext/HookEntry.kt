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

    // ThreadLocal stack to track calling UIDs across Binder.clearCallingIdentity / restoreCallingIdentity
    private val callingUidStack = ThreadLocal.withInitial { ArrayList<Int>() }

    private fun getInheritedCallingUid(): Int? {
        val stack = callingUidStack.get()
        if (stack != null && stack.isNotEmpty()) {
            val uid = stack[stack.size - 1]
            if (uid != 1000) return uid
        }
        return null
    }

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
        hookStatsChanged.set(true)
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
    ): Int {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return try {
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
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun loadTargetUids(): Set<Int> {
        if (selfUid == -1) {
            val pm = getIPackageManager()
            if (pm != null) {
                synchronized(uidLock) {
                    if (selfUid == -1) {
                        selfUid = getPackageUid(pm, "dev.soranerai.vpnhidenext", 0)
                        if (selfUid != -1) {
                            systemServerTargetUids = null
                        }
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
            if (selfUid != -1) uids.add(selfUid)
            return uids
        }
    }

    private fun isTargetCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 1000) { // system_server is pushing data
            val cbUid = currentCallbackUid.get() ?: getInheritedCallingUid()
            if (cbUid != null) {
                return loadTargetUids().contains(cbUid)
            }
        }
        return loadTargetUids().contains(callingUid)
    }

    private fun isJavaHookActive(bitIndex: Int): Boolean {
        val mask = cachedJavaHooksMask ?: 0xFFFFFFFFu
        return (mask and (1u shl bitIndex)) != 0u
    }

    @Volatile private var systemServerIfacePrefixes: List<String>? = null

    private fun loadIfacePrefixes(): List<String> = systemServerIfacePrefixes ?: emptyList()

    private fun invalidateTargetUids() {
        systemServerTargetUids = null
        systemServerIfacePrefixes = null
        vpnPackageCache.clear()
    }

    private fun installSystemServerHooks(): List<String> {
        val brokenFields = runReflectionSmokeCheck()

        fun anyBroken(critical: Set<String>): Boolean = brokenFields.any { it.substringBefore(':') in critical }

        if (!anyBroken(LP_CRITICAL_KEYS)) tryHook("LP.writeToParcel") { hookLPWriteToParcel() }
        tryHook("NC.writeToParcel") { hookNCWriteToParcel() }
        if (!anyBroken(NI_CRITICAL_KEYS)) tryHook("NI.writeToParcel") { hookNIWriteToParcel() }
        tryHook("Network.writeToParcel") { hookNetworkWriteToParcel() }

        tryHook("APEX_Services") { hookApexServices() }
        tryHook("ConfigReader") {
            startConfigReader()
            startStatsWriter()
        }
        tryHook("Binder.identityTracking") { hookBinderIdentityTracking() }
        return brokenFields
    }

    private fun hookBinderIdentityTracking() {
        val binderClass = Binder::class.java
        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(binderClass, "clearCallingIdentity", *emptyArray<Class<*>>()),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callingUid = Binder.getCallingUid()
                        val uids = systemServerTargetUids
                        val stack = callingUidStack.get() ?: return
                        if (stack.isEmpty()) {
                            if (callingUid != 1000 && uids != null && uids.contains(callingUid)) {
                                stack.add(callingUid)
                            } else {
                                stack.add(1000)
                            }
                        } else {
                            stack.add(stack[stack.size - 1])
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook clearCallingIdentity: ${t.message}")
        }

        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(binderClass, "restoreCallingIdentity", java.lang.Long.TYPE),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stack = callingUidStack.get()
                        if (stack != null && stack.isNotEmpty()) {
                            stack.removeAt(stack.size - 1)
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook restoreCallingIdentity: ${t.message}")
        }
    }

    private val isInternalCheck = ThreadLocal.withInitial { false }

    // Cache: packageName -> is package VPN?
    private val vpnPackageCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Suppress("UNCHECKED_CAST")
    private fun getPackagesForUid(
        pm: Any,
        uid: Int,
    ): Array<String>? {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return try {
                XposedHelpers.callMethod(pm, "getPackagesForUid", uid) as? Array<String>
            } catch (t: Throwable) {
                null
            }
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun isOwnApp(
        pm: Any,
        packageName: String,
    ): Boolean {
        val callingUid = Binder.getCallingUid()
        val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
        if (targetUid == null || targetUid <= 0) return false
        val callerPackages = getPackagesForUid(pm, targetUid) ?: return false
        return callerPackages.contains(packageName)
    }

    private fun hookUserManagerService(classLoader: ClassLoader) {
        val targetClass =
            try {
                XposedHelpers.findClass(
                    "com.android.server.pm.UserManagerService",
                    classLoader,
                )
            } catch (e: Throwable) {
                HookLog.e("VpnHide: failed to load UserManagerService class: ${e.message}")
                return
            }

        fun isManagedProfileInternal(
            serviceInstance: Any,
            userId: Int,
        ): Boolean {
            if (userId <= 0) return false
            val token = Binder.clearCallingIdentity()
            isInternalCheck.set(true)
            try {
                return XposedHelpers.callMethod(serviceInstance, "isManagedProfile", userId) as? Boolean ?: false
            } catch (t: Throwable) {
                return false
            } finally {
                isInternalCheck.remove()
                Binder.restoreCallingIdentity(token)
            }
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getUserInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userInfo = param.result
                        val userId = if (userInfo != null) XposedHelpers.getIntField(userInfo, "id") else null
                        val stackTrace = if (callingUid == 1000) "\n" + android.util.Log.getStackTraceString(Throwable()) else ""
                        HookLog.i(
                            "VpnHide: getUserInfo(userId=$userId) called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}$stackTrace",
                        )

                        if (userInfo != null && userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            recordIntercept("UserManager")
                            var flags = XposedHelpers.getIntField(userInfo, "flags")
                            flags = flags and 0x00000020.inv() // FLAG_MANAGED_PROFILE
                            flags = flags and 0x00001000.inv() // FLAG_PROFILE
                            XposedHelpers.setIntField(userInfo, "flags", flags)
                            try {
                                XposedHelpers.setObjectField(userInfo, "userType", "android.os.usertype.full.SECONDARY")
                            } catch (_: Throwable) {
                            }
                            HookLog.i(
                                "VpnHide: Spoofed getUserInfo(userId=$userId) flags/userType to hide managed profile for uid $callingUid",
                            )
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getUserInfo: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "isProfile",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        HookLog.i(
                            "VpnHide: isProfile(userId=$userId) called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            recordIntercept("UserManager")
                            param.result = false
                            HookLog.i("VpnHide: Spoofed isProfile(userId=$userId) to false for uid $callingUid")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook isProfile: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getProfiles",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        HookLog.i(
                            "VpnHide: getProfiles called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}",
                        )

                        val result = param.result as? List<*> ?: return
                        if (result.isEmpty()) return

                        val targetUid = if (callingUid == 1000) (currentCallbackUid.get() ?: callingUid) else callingUid
                        val targetUserId = targetUid / 100000

                        val filteredList =
                            result.filter { item ->
                                if (item == null) return@filter true
                                val itemId =
                                    try {
                                        XposedHelpers.getObjectField(item, "id") as? Int
                                    } catch (_: Throwable) {
                                        null
                                    }
                                itemId == null || itemId == targetUserId
                            }

                        if (filteredList.size != result.size) {
                            recordIntercept("UserManager")
                            param.result = filteredList
                            HookLog.i(
                                "VpnHide: Filtered ${result.size - filteredList.size} managed profile(s) from getProfiles (Original: ${result.size}) for uid $callingUid",
                            )
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getProfiles: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getProfileIds",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        HookLog.i(
                            "VpnHide: getProfileIds called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}",
                        )

                        val result = param.result as? IntArray ?: return
                        if (result.isEmpty()) return

                        val targetUid = if (callingUid == 1000) (currentCallbackUid.get() ?: callingUid) else callingUid
                        val targetUserId = targetUid / 100000

                        val filteredList =
                            result.filter { itemId ->
                                itemId == targetUserId
                            }

                        if (filteredList.size != result.size) {
                            recordIntercept("UserManager")
                            param.result = filteredList.toIntArray()
                            HookLog.i(
                                "VpnHide: Filtered ${result.size - filteredList.size} managed profile(s) from getProfileIds (Original: ${result.size}) for uid $callingUid",
                            )
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getProfileIds: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getProfileParent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        val stackTrace = if (callingUid == 1000) "\n" + android.util.Log.getStackTraceString(Throwable()) else ""
                        HookLog.i(
                            "VpnHide: getProfileParent(userId=$userId) called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}$stackTrace",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            recordIntercept("UserManager")
                            param.result = null
                            HookLog.i("VpnHide: Spoofed getProfileParent(userId=$userId) to null for uid $callingUid")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getProfileParent: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getProfileParentId",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(6) || isInternalCheck.get() == true) return
                        if (!isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        HookLog.i(
                            "VpnHide: getProfileParentId(userId=$userId) called by uid $callingUid, cbUid=${currentCallbackUid.get()}, inheritedUid=${getInheritedCallingUid()}",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            recordIntercept("UserManager")
                            param.result = userId
                            HookLog.i("VpnHide: Spoofed getProfileParentId(userId=$userId) to $userId for uid $callingUid")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook getProfileParentId: ${t.message}")
        }
    }

    // Hook and hide any VPN app from the LSPosed targets automatically
    private fun hookPackageManager(classLoader: ClassLoader) {
        try {
            var targetClass: Class<*>? =
                try {
                    XposedHelpers.findClass(
                        "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
                        classLoader,
                    )
                } catch (e: Throwable) {
                    null
                }

            if (targetClass == null) {
                targetClass =
                    try {
                        XposedHelpers.findClass(
                            "com.android.server.pm.IPackageManagerBase",
                            classLoader,
                        )
                    } catch (e: Throwable) {
                        XposedHelpers.findClass(
                            "com.android.server.pm.PackageManagerService",
                            classLoader,
                        )
                    }
            }

            val hasMethod = targetClass.declaredMethods.any { it.name == "queryIntentServices" }
            if (!hasMethod && targetClass.superclass != Any::class.java) {
                val superBase = targetClass.superclass
                if (superBase != null && superBase.name.contains("IPackageManager")) {
                    targetClass = superBase
                }
            }

            val sliceClass =
                XposedHelpers.findClass("android.content.pm.ParceledListSlice", classLoader)

            val isVpnApp =
                fun(
                    packageName: String,
                    pmInstance: Any,
                    userId: Int,
                ): Boolean {
                    vpnPackageCache[packageName]?.let {
                        return it
                    }

                    isInternalCheck.set(true)
                    val token = android.os.Binder.clearCallingIdentity()
                    var succeeded = false
                    val isVpn =
                        try {
                            val vpnCheckIntent =
                                android.content.Intent("android.net.VpnService").apply {
                                    `package` = packageName
                                }
                            val flags =
                                if (android.os.Build.VERSION.SDK_INT >= 33) 0L else 0
                            val sliceResult =
                                XposedHelpers.callMethod(
                                    pmInstance,
                                    "queryIntentServices",
                                    vpnCheckIntent,
                                    null as String?,
                                    flags,
                                    userId,
                                )
                            if (sliceResult != null) {
                                val vpnServices =
                                    try {
                                        XposedHelpers.callMethod(sliceResult, "getList") as?
                                            List<*>
                                    } catch (e: Throwable) {
                                        null
                                    }
                                if (vpnServices != null) {
                                    succeeded = true
                                    vpnServices.isNotEmpty()
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        } catch (t: Throwable) {
                            HookLog.e("VpnHide: error checking isVpnApp for $packageName: ${t.message}")
                            false
                        } finally {
                            android.os.Binder.restoreCallingIdentity(token)
                            isInternalCheck.remove()
                        }

                    if (succeeded) {
                        vpnPackageCache[packageName] = isVpn
                    }
                    return isVpn
                }

            // --- STAGE 1: queryIntentServices ---
            XposedBridge.hookAllMethods(
                targetClass,
                "queryIntentServices",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5) ||
                            isInternalCheck.get() as Boolean ||
                            !isTargetCaller()
                        ) {
                            return
                        }

                        val intent =
                            param.args.getOrNull(0) as? android.content.Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") ==
                            true
                        ) {
                            val result = param.result ?: return

                            val list =
                                if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
                                    try {
                                        XposedHelpers.callMethod(result, "getList") as? List<*>
                                    } catch (e: Throwable) {
                                        null
                                    }
                                } else {
                                    result as? List<*>
                                }

                            if (list.isNullOrEmpty()) return

                            val callingUid = Binder.getCallingUid()
                            val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    getPackagesForUid(param.thisObject, targetUid)
                                } else {
                                    null
                                }

                            val userId = param.args.lastOrNull() as? Int ?: 0
                            val filteredList =
                                list.filter { item ->
                                    val packageName =
                                        try {
                                            val serviceInfo = XposedHelpers.getObjectField(item, "serviceInfo")
                                            XposedHelpers.getObjectField(serviceInfo, "packageName") as? String
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    if (packageName != null) {
                                        val isOwn = callerPackages?.contains(packageName) == true
                                        isOwn || !isVpnApp(packageName, param.thisObject, userId)
                                    } else {
                                        true
                                    }
                                }

                            if (filteredList.size != list.size) {
                                recordIntercept("PackageManager")
                                if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
                                    param.result = XposedHelpers.newInstance(sliceClass, filteredList)
                                } else {
                                    param.result = filteredList
                                }
                                HookLog.i(
                                    "VpnHide: Filtered ${list.size - filteredList.size} VPN services (Original: ${list.size})",
                                )
                            }
                        }
                    }
                },
            )

            // --- STAGE 2: getPackageInfo
            XposedBridge.hookAllMethods(
                targetClass,
                "getPackageInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5) ||
                            isInternalCheck.get() as Boolean ||
                            !isTargetCaller()
                        ) {
                            return
                        }
                        if (param.result == null) return

                        val requestedPackage = param.args.getOrNull(0) as? String ?: return
                        val userId = param.args.getOrNull(2) as? Int ?: return

                        if (isVpnApp(requestedPackage, param.thisObject, userId)) {
                            if (isOwnApp(param.thisObject, requestedPackage)) return
                            recordIntercept("PackageManager")
                            param.result = null
                            HookLog.i(
                                "VpnHide: Spoofed getPackageInfo as Not Found for $requestedPackage",
                            )
                        }
                    }
                },
            )

            // --- STAGE 3: getInstalledPackages and getInstalledApplications
            val listMethods = arrayOf("getInstalledPackages", "getInstalledApplications")
            for (methodName in listMethods) {
                XposedBridge.hookAllMethods(
                    targetClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isJavaHookActive(5) ||
                                isInternalCheck.get() as Boolean ||
                                !isTargetCaller()
                            ) {
                                return
                            }

                            val result = param.result ?: return
                            if (result.javaClass.name != "android.content.pm.ParceledListSlice") {
                                return
                            }

                            val list =
                                try {
                                    XposedHelpers.callMethod(result, "getList") as? List<*>
                                } catch (e: Throwable) {
                                    null
                                }

                            if (list.isNullOrEmpty()) return

                            val callingUid = Binder.getCallingUid()
                            val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    getPackagesForUid(param.thisObject, targetUid)
                                } else {
                                    null
                                }

                            val userId = param.args.getOrNull(1) as? Int ?: 0

                            val filteredList =
                                list.filter { item ->
                                    val packageName =
                                        try {
                                            XposedHelpers.getObjectField(
                                                item,
                                                "packageName",
                                            ) as?
                                                String
                                        } catch (e: Throwable) {
                                            null
                                        }

                                    if (packageName != null) {
                                        val isOwn = callerPackages?.contains(packageName) == true
                                        isOwn || !isVpnApp(packageName, param.thisObject, userId)
                                    } else {
                                        true
                                    }
                                }

                            if (filteredList.size != list.size) {
                                recordIntercept("PackageManager")
                                param.result =
                                    XposedHelpers.newInstance(sliceClass, filteredList)
                                HookLog.i(
                                    "VpnHide: Filtered ${list.size - filteredList.size} VPN apps from $methodName",
                                )
                            }
                        }
                    },
                )
            }

            // --- STAGE 4: resolveService ---
            XposedBridge.hookAllMethods(
                targetClass,
                "resolveService",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5) ||
                            isInternalCheck.get() as Boolean ||
                            !isTargetCaller()
                        ) {
                            return
                        }

                        val intent =
                            param.args.getOrNull(0) as? android.content.Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") == true
                        ) {
                            val result = param.result
                            if (result != null) {
                                val packageName =
                                    try {
                                        val serviceInfo = XposedHelpers.getObjectField(result, "serviceInfo")
                                        XposedHelpers.getObjectField(serviceInfo, "packageName") as? String
                                    } catch (_: Throwable) {
                                        null
                                    }
                                if (packageName != null) {
                                    val callingUid = Binder.getCallingUid()
                                    val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
                                    val callerPackages =
                                        if (targetUid != null &&
                                            targetUid > 0
                                        ) {
                                            getPackagesForUid(param.thisObject, targetUid)
                                        } else {
                                            null
                                        }
                                    if (callerPackages?.contains(packageName) == true) {
                                        return
                                    }
                                }
                                recordIntercept("PackageManager")
                                param.result = null
                                HookLog.i("VpnHide: Blocked resolveService for VpnService")
                            }
                        }
                    }
                },
            )

            // --- STAGE 5: queryIntentActivities ---
            XposedBridge.hookAllMethods(
                targetClass,
                "queryIntentActivities",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5) ||
                            isInternalCheck.get() as Boolean ||
                            !isTargetCaller()
                        ) {
                            return
                        }

                        val intent =
                            param.args.getOrNull(0) as? android.content.Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") == true
                        ) {
                            val result = param.result ?: return

                            val list =
                                if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
                                    try {
                                        XposedHelpers.callMethod(result, "getList") as? List<*>
                                    } catch (e: Throwable) {
                                        null
                                    }
                                } else {
                                    result as? List<*>
                                }

                            if (list.isNullOrEmpty()) return

                            val callingUid = Binder.getCallingUid()
                            val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    getPackagesForUid(param.thisObject, targetUid)
                                } else {
                                    null
                                }

                            val userId = param.args.lastOrNull() as? Int ?: 0
                            val filteredList =
                                list.filter { item ->
                                    val packageName =
                                        try {
                                            val activityInfo = XposedHelpers.getObjectField(item, "activityInfo")
                                            XposedHelpers.getObjectField(activityInfo, "packageName") as? String
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    if (packageName != null) {
                                        val isOwn = callerPackages?.contains(packageName) == true
                                        isOwn || !isVpnApp(packageName, param.thisObject, userId)
                                    } else {
                                        true
                                    }
                                }

                            if (filteredList.size != list.size) {
                                recordIntercept("PackageManager")
                                if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
                                    param.result = XposedHelpers.newInstance(sliceClass, filteredList)
                                } else {
                                    param.result = filteredList
                                }
                                HookLog.i(
                                    "VpnHide: Filtered ${list.size - filteredList.size} VPN activities via queryIntentActivities (Original: ${list.size})",
                                )
                            }
                        }
                    }
                },
            )

            HookLog.i(
                "VpnHide: All PM hooks successfully applied to ${targetClass.name}",
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: Failed to hook PM: ${t::class.java.simpleName}: ${t.message}")
        }
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
            val devFile = File("/dev/vpnhide_ctrl")
            if (devFile.exists()) {
                val flatStatus = sb.toString().replace('\n', ';')
                devFile.outputStream().use { os ->
                    os.write("status:$flatStatus".toByteArray())
                }
                HookLog.i(
                    "VpnHide: wrote hook status to /dev/vpnhide_ctrl (version=$version, boot_id=$bootId, " +
                        "sdk=$sdk, broken=${brokenFields.size})",
                )
            } else {
                HookLog.i("VpnHide: /dev/vpnhide_ctrl not available to write status")
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to write hook status: ${t.message}")
        }
    }

    private fun startConfigReader() {
        Thread({
            while (true) {
                try {
                    val file = File("/dev/vpnhide_ctrl")
                    if (!file.exists()) {
                        Thread.sleep(5000)
                        continue
                    }
                    file.bufferedReader().use { reader ->
                        var javaHookMask = 0xFFFFFFFFu
                        val uids = mutableSetOf<Int>()
                        val prefixes = mutableListOf<String>()

                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                synchronized(uidLock) {
                                    systemServerTargetUids = uids.toSet()
                                    systemServerIfacePrefixes = prefixes.toList()
                                    cachedJavaHooksMask = javaHookMask
                                    vpnPackageCache.clear()
                                }
                                uids.clear()
                                prefixes.clear()
                                javaHookMask = 0xFFFFFFFFu
                                continue
                            }

                            if (line.startsWith("java_hook_mask:")) {
                                javaHookMask = line.substringAfter("java_hook_mask:").trim().toUIntOrNull() ?: 0xFFFFFFFFu
                            } else if (line.startsWith("lsposed_targets:")) {
                                val targetStr = line.substringAfter("lsposed_targets:").trim()
                                if (targetStr.isNotEmpty()) {
                                    targetStr.split(" ").forEach { uidStr ->
                                        uidStr.toIntOrNull()?.let { uids.add(it) }
                                    }
                                }
                            } else if (line.startsWith("iface_prefixes:")) {
                                val prefixStr = line.substringAfter("iface_prefixes:").trim()
                                if (prefixStr.isNotEmpty()) {
                                    prefixStr.split(" ").forEach { prefixes.add(it) }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    HookLog.e("VpnHide: config reader error: ${t.message}")
                    try {
                        Thread.sleep(5000)
                    } catch (_: Throwable) {
                    }
                }
            }
        }, "VpnHideConfigReader").start()
    }

    private fun startStatsWriter() {
        Thread({
            while (true) {
                try {
                    Thread.sleep(2000)
                    if (hookStatsChanged.compareAndSet(true, false)) {
                        val sb = java.lang.StringBuilder()
                        sb.append("stats:")
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
                        val devFile = File("/dev/vpnhide_ctrl")
                        if (devFile.exists()) {
                            devFile.outputStream().use { os ->
                                os.write(sb.toString().toByteArray())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    HookLog.e("VpnHide: stats writer error: ${t.message}")
                }
            }
        }, "VpnHideStatsWriter").start()
    }

    // ------------------------------------------------------------------
    //  Synchronous getNetworkCapabilities Hooks (writeToParcel)
    // ------------------------------------------------------------------

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
        val hasVpnTransport = copy.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val transportInfo = try {
            XposedHelpers.callMethod(copy, "getTransportInfo")
        } catch (_: Throwable) {
            null
        }
        val hasVpnInfo = transportInfo?.javaClass?.name == "android.net.VpnTransportInfo"

        if (!hasVpnTransport && !hasVpnInfo) return false

        if (hasVpnTransport) {
            XposedHelpers.callMethod(copy, "removeTransportType", NetworkCapabilities.TRANSPORT_VPN)
        }
        XposedHelpers.callMethod(copy, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (hasVpnInfo) {
            clearTransportInfo(copy)
        }

        try {
            if (XposedHelpers.getObjectField(copy, "mUnderlyingNetworks") != null) {
                XposedHelpers.setObjectField(copy, "mUnderlyingNetworks", null)
            }
        } catch (_: Throwable) {
        }

        val hasPhysical = copy.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

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
                var addedAny = false
                val physicalTransports = try {
                    XposedHelpers.callMethod(physicalNc, "getTransportTypes") as? IntArray
                } catch (_: Throwable) {
                    null
                }
                if (physicalTransports != null) {
                    for (t in physicalTransports) {
                        if (t != NetworkCapabilities.TRANSPORT_VPN) {
                            XposedHelpers.callMethod(copy, "addTransportType", t)
                            addedAny = true
                        }
                    }
                } else {
                    for (t in listOf(
                        NetworkCapabilities.TRANSPORT_WIFI,
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        NetworkCapabilities.TRANSPORT_ETHERNET,
                        NetworkCapabilities.TRANSPORT_BLUETOOTH
                    )) {
                        if (physicalNc.hasTransport(t)) {
                            XposedHelpers.callMethod(copy, "addTransportType", t)
                            addedAny = true
                        }
                    }
                }
                if (!addedAny) {
                    XposedHelpers.callMethod(copy, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
                }
            } else {
                XposedHelpers.callMethod(copy, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        try {
            if (physicalNc != null) {
                val realTi = XposedHelpers.callMethod(physicalNc, "getTransportInfo")
                val transportInfoClass = Class.forName("android.net.TransportInfo")
                XposedHelpers.callMethod(
                    copy,
                    "setTransportInfo",
                    arrayOf(transportInfoClass),
                    realTi,
                )
            } else {
                clearTransportInfo(copy)
            }
        } catch (_: Throwable) {
        }

        try {
            if (physicalNc != null) {
                val realSs = XposedHelpers.callMethod(physicalNc, "getSignalStrength") as Int
                XposedHelpers.callMethod(copy, "setSignalStrength", realSs)
            } else {
                val ss = XposedHelpers.callMethod(copy, "getSignalStrength") as Int
                if (ss == Integer.MIN_VALUE) { // SIGNAL_STRENGTH_UNSPECIFIED
                    XposedHelpers.callMethod(copy, "setSignalStrength", -50)
                }
            }
        } catch (_: Throwable) {
        }

        try {
            if (physicalNc != null) {
                val realDown = XposedHelpers.callMethod(physicalNc, "getLinkDownstreamBandwidthKbps") as Int
                val realUp = XposedHelpers.callMethod(physicalNc, "getLinkUpstreamBandwidthKbps") as Int
                XposedHelpers.callMethod(copy, "setLinkDownstreamBandwidthKbps", realDown)
                XposedHelpers.callMethod(copy, "setLinkUpstreamBandwidthKbps", realUp)
            } else {
                val down = XposedHelpers.callMethod(copy, "getLinkDownstreamBandwidthKbps") as Int
                val up = XposedHelpers.callMethod(copy, "getLinkUpstreamBandwidthKbps") as Int
                if (down == 0 || down > 10_000_000) {
                    XposedHelpers.callMethod(copy, "setLinkDownstreamBandwidthKbps", 150_000) // 150 Mbps
                }
                if (up == 0 || up > 10_000_000) {
                    XposedHelpers.callMethod(copy, "setLinkUpstreamBandwidthKbps", 75_000) // 75 Mbps
                }
            }
        } catch (_: Throwable) {
        }

        return true
    }

    private fun clearTransportInfo(copy: NetworkCapabilities) {
        try {
            val transportInfoClass = Class.forName("android.net.TransportInfo")
            XposedHelpers.callMethod(
                copy,
                "setTransportInfo",
                arrayOf(transportInfoClass),
                *arrayOfNulls<Any>(1),
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    //  Asynchronous Hooks (grats to nekohasekai)
    // ------------------------------------------------------------------

    private val hookedServices =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )

    private fun findMethodInHierarchy(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): java.lang.reflect.Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            try {
                val method = XposedHelpers.findMethodExact(c, name, *params)
                method.isAccessible = true
                return method
            } catch (_: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    private fun hookApexServices() {
        val smClass = XposedHelpers.findClass("android.os.ServiceManager", null)

        XposedBridge.hookAllMethods(
            smClass,
            "addService",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    val binder = param.args.getOrNull(1) as? android.os.IBinder ?: return
                    val classLoader =
                        binder.javaClass.classLoader
                            ?: Thread.currentThread().contextClassLoader
                            ?: ClassLoader.getSystemClassLoader()
                    HookLog.i(
                        "VpnHide: addService intercepted: name=$name " +
                            "binderClass=${binder.javaClass.name} " +
                            "classLoader=${classLoader.javaClass.name}",
                    )
                    handleServiceHook(name, classLoader)
                }
            },
        )

        XposedBridge.hookAllMethods(
            smClass,
            "getService",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (name != "connectivity" && name != "package" && name != "user") return
                    val binder = param.result as? android.os.IBinder ?: return
                    val classLoader =
                        binder.javaClass.classLoader
                            ?: Thread.currentThread().contextClassLoader
                            ?: ClassLoader.getSystemClassLoader()
                    HookLog.i(
                        "VpnHide: getService intercepted: name=$name " +
                            "binderClass=${binder.javaClass.name} " +
                            "classLoader=${classLoader.javaClass.name}",
                    )
                    handleServiceHook(name, classLoader)
                }
            },
        )

        checkAndHookExistingService("connectivity", smClass)
        checkAndHookExistingService("package", smClass)
        checkAndHookExistingService("user", smClass)

        // Polling loop in background thread to handle early boot timing races
        Thread({
            var attempts = 0
            while (attempts < 20) {
                val hasConnectivity = hookedServices.any { it.startsWith("connectivity@") }
                val hasPackage = hookedServices.any { it.startsWith("package@") }
                val hasUser = hookedServices.any { it.startsWith("user@") }
                if (hasConnectivity && hasPackage && hasUser) {
                    break
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
                if (!hasConnectivity) {
                    checkAndHookExistingService("connectivity", smClass)
                }
                if (!hasPackage) {
                    checkAndHookExistingService("package", smClass)
                }
                if (!hasUser) {
                    checkAndHookExistingService("user", smClass)
                }
                attempts++
            }
        }, "VpnHideServicePoller").start()
    }

    private fun checkAndHookExistingService(
        name: String,
        smClass: Class<*>,
    ) {
        try {
            val binder =
                XposedHelpers.callStaticMethod(smClass, "getService", name) as?
                    android.os.IBinder
            if (binder == null) {
                HookLog.i("VpnHide: checkAndHookExistingService($name): binder is null")
                return
            }
            val classLoader =
                binder.javaClass.classLoader
                    ?: Thread.currentThread().contextClassLoader
                    ?: ClassLoader.getSystemClassLoader()
            HookLog.i(
                "VpnHide: checkAndHookExistingService($name): " +
                    "binderClass=${binder.javaClass.name} " +
                    "classLoader=${classLoader.javaClass.name}",
            )
            handleServiceHook(name, classLoader)
        } catch (t: Throwable) {
            HookLog.e("VpnHide: checkAndHookExistingService($name) failed: ${t::class.java.simpleName}: ${t.message}")
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

            "package" -> {
                HookLog.i(
                    "VpnHide: Installing PackageManager hooks via APEX/ServiceManager loader...",
                )
                tryHook("PackageManager.queryIntentServices") { hookPackageManager(classLoader) }
            }

            "user" -> {
                HookLog.i("VpnHide: Installing UserManager hooks...")
                tryHook("UserManagerService.profiles") { hookUserManagerService(classLoader) }
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
                                if (!isJavaHookActive(4)) return
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
            installConnectivityServiceNetworkHooks(csClass)
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to install ConnectivityService network hooks: ${t.message}")
        }

        try {
            val getDefaultProxyMethod =
                findMethodInHierarchy(
                    csClass,
                    "getDefaultProxy",
                ) ?: throw NoSuchMethodException("getDefaultProxy")
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
                findMethodInHierarchy(
                    csClass,
                    "getProxyForNetwork",
                    android.net.Network::class.java,
                ) ?: throw NoSuchMethodException("getProxyForNetwork")
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
    }

    private fun isVpnNetwork(cs: Any, network: android.net.Network): Boolean {
        val nc = getNetworkCapabilitiesSafe(cs, network) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun installConnectivityServiceNetworkHooks(csClass: Class<*>) {
        hookConnectivityNetworkMethod(csClass, "getActiveNetwork", ::sanitizeActiveNetworkResult)
        hookConnectivityNetworkMethod(csClass, "getAllNetworks", ::sanitizeAllNetworksResult)
        hookConnectivityNetworkMethod(csClass, "getNetworkForType", ::sanitizeNetworkForTypeResult)
    }

    private fun hookConnectivityNetworkMethod(
        csClass: Class<*>,
        method: String,
        sanitizer: (XC_MethodHook.MethodHookParam) -> Unit,
    ) {
        try {
            val networkMethod = if (method == "getNetworkForType") {
                findMethodInHierarchy(csClass, method, Integer.TYPE)
            } else {
                findMethodInHierarchy(csClass, method)
            } ?: throw NoSuchMethodException(method)

            XposedBridge.hookMethod(
                networkMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isJavaHookActive(5)) return
                        csInstance = param.thisObject
                        val callingUid = Binder.getCallingUid()
                        if (!loadTargetUids().contains(callingUid)) return
                        sanitizer(param)
                    }
                }
            )
            HookLog.i("VpnHide: $method hook installed")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook $method: ${t.message}")
        }
    }

    private fun sanitizeActiveNetworkResult(param: XC_MethodHook.MethodHookParam) {
        val network = param.result as? android.net.Network ?: return
        val cs = param.thisObject ?: return
        if (!isVpnNetwork(cs, network)) return
        recordIntercept("ConnectivityService")
        val replacement = getPhysicalNetwork(cs)
        if (replacement == null) {
            HookLog.i("VpnHide: kept active VPN Network handle for uid=${Binder.getCallingUid()}; no physical replacement")
            param.result = null
            return
        }
        param.result = replacement
        HookLog.i("VpnHide: replaced active VPN Network handle for uid=${Binder.getCallingUid()}")
    }

    private fun sanitizeAllNetworksResult(param: XC_MethodHook.MethodHookParam) {
        val networks = (param.result as? Array<*>)?.filterIsInstance<android.net.Network>() ?: return
        val cs = param.thisObject ?: return
        val filtered = networks.filterNot { isVpnNetwork(cs, it) }
        if (filtered.size == networks.size) return
        recordIntercept("ConnectivityService")
        param.result = filtered.toTypedArray()
        HookLog.i("VpnHide: filtered ${networks.size - filtered.size} VPN Network handle(s) for uid=${Binder.getCallingUid()}")
    }

    private fun sanitizeNetworkForTypeResult(param: XC_MethodHook.MethodHookParam) {
        val type = param.args.getOrNull(0) as? Int ?: return
        if (type != TYPE_VPN || param.result == null) return
        param.result = null
        HookLog.i("VpnHide: suppressed getNetworkForType(TYPE_VPN) for uid=${Binder.getCallingUid()}")
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

        private val hookStatsChanged = AtomicBoolean(false)

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
