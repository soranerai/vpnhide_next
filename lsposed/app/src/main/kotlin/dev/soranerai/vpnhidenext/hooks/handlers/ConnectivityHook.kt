package dev.soranerai.vpnhidenext.hooks.handlers

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.soranerai.vpnhidenext.HookLog
import dev.soranerai.vpnhidenext.hooks.core.HookContext

object ConnectivityHook {
    fun hookConnectivityService(classLoader: ClassLoader) {
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
                        HookContext.csInstance = param.thisObject
                        HookLog.i("VpnHide: Captured ConnectivityService instance successfully")
                    }
                },
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to hook ConnectivityService constructor: ${t.message}")
        }

        for (method in csClass.declaredMethods) {
            // 1. ThreadLocal Context Injection
            if (method.name == "callCallbackForRequest" ||
                method.name == "sendPendingIntentForRequest"
            ) {
                try {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!HookContext.isJavaHookActive(4)) return
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

                                if (uid != -1 && HookContext.loadTargetUids().contains(uid)) {
                                    var request: android.net.NetworkRequest? = null
                                    var clazz: Class<*>? = nri.javaClass
                                    while (clazz != null && clazz != Any::class.java) {
                                        for (field in clazz.declaredFields) {
                                            if (field.type == android.net.NetworkRequest::class.java) {
                                                try {
                                                    field.isAccessible = true
                                                    request = field.get(nri) as? android.net.NetworkRequest
                                                    if (request != null) break
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }
                                        if (request != null) break
                                        clazz = clazz.superclass
                                    }

                                    if (request != null &&
                                        request.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                    ) {
                                        HookLog.i("VpnHide: Suppressing VPN callback/intent for target UID $uid")
                                        HookContext.recordIntercept("ConnectivityService")
                                        param.result = null
                                        return
                                    }

                                    HookContext.currentCallbackUid.set(uid)
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                HookContext.currentCallbackUid.remove()
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
                                if (!HookContext.isJavaHookActive(5)) return
                                val uid =
                                    if (method.name.startsWith("copy")) {
                                        param.args.getOrNull(2) as? Int
                                    } else {
                                        param.args.getOrNull(0) as? Int
                                    }
                                if (uid != null && HookContext.loadTargetUids().contains(uid)) {
                                    val nc = param.result as? NetworkCapabilities ?: return
                                    val copy = NetworkCapabilities(nc)
                                    if (ParcelHooks.sanitizeNetworkCapabilities(copy)) {
                                        HookContext.recordIntercept("ConnectivityService")
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
                        if (!HookContext.isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (HookContext.loadTargetUids().contains(callingUid)) {
                            if (param.result != null) {
                                HookLog.i("VpnHide: Suppressing getDefaultProxy() for target UID $callingUid")
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
                        if (!HookContext.isJavaHookActive(5)) return
                        val callingUid = Binder.getCallingUid()
                        if (HookContext.loadTargetUids().contains(callingUid)) {
                            if (param.result != null) {
                                HookLog.i("VpnHide: Suppressing getProxyForNetwork() for target UID $callingUid")
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
            val networkMethod =
                if (method == "getNetworkForType") {
                    findMethodInHierarchy(csClass, method, Integer.TYPE)
                } else {
                    findMethodInHierarchy(csClass, method)
                } ?: throw NoSuchMethodException(method)

            XposedBridge.hookMethod(
                networkMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookContext.isJavaHookActive(5)) return
                        HookContext.csInstance = param.thisObject
                        val callingUid = Binder.getCallingUid()
                        if (!HookContext.loadTargetUids().contains(callingUid)) return
                        sanitizer(param)
                    }
                },
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
        HookContext.recordIntercept("ConnectivityService")
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
        HookContext.recordIntercept("ConnectivityService")
        param.result = filtered.toTypedArray()
        HookLog.i("VpnHide: filtered ${networks.size - filtered.size} VPN Network handle(s) for uid=${Binder.getCallingUid()}")
    }

    private fun sanitizeNetworkForTypeResult(param: XC_MethodHook.MethodHookParam) {
        val type = param.args.getOrNull(0) as? Int ?: return
        if (type != TYPE_VPN || param.result == null) return
        param.result = null
        HookLog.i("VpnHide: suppressed getNetworkForType(TYPE_VPN) for uid=${Binder.getCallingUid()}")
    }

    fun isVpnNetwork(
        cs: Any,
        network: android.net.Network,
    ): Boolean {
        val nc = getNetworkCapabilitiesSafe(cs, network) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun sanitizeLinkProperties(copy: LinkProperties): Boolean {
        val cs = HookContext.getConnectivityService()
        val physicalLp = if (cs != null) getPhysicalLinkProperties(cs) else null
        return sanitizeLinkProperties(copy, cs, physicalLp)
    }

    fun sanitizeLinkProperties(
        copy: LinkProperties,
        cs: Any?,
        physicalLp: LinkProperties?,
    ): Boolean {
        var modified = false
        val targetIface = getActivePhysicalInterfaceName(cs, physicalLp)

        val ifaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (ifaceName != null && HookContext.isVpnInterfaceName(ifaceName)) {
            XposedHelpers.setObjectField(copy, "mIfaceName", targetIface)
            modified = true
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val routesField = XposedHelpers.getObjectField(copy, "mRoutes") as? MutableList<RouteInfo>
            if (routesField != null) {
                val newRoutes = ArrayList<RouteInfo>()
                for (route in routesField) {
                    val routeIface = route.`interface`
                    if (routeIface != null && HookContext.isVpnInterfaceName(routeIface)) {
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
            val linkAddresses = XposedHelpers.getObjectField(copy, "mLinkAddresses") as? MutableList<Any>
            if (linkAddresses != null) {
                if (physicalLp != null) {
                    @Suppress("UNCHECKED_CAST")
                    val physicalAddresses = XposedHelpers.getObjectField(physicalLp, "mLinkAddresses") as? List<Any>
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
                    val stackedModified = sanitizeLinkProperties(stackedCopy, cs, physicalLp)
                    val stackedIface = XposedHelpers.getObjectField(stackedCopy, "mIfaceName") as? String
                    if (stackedIface == null && stackedCopy.routes.isEmpty()) {
                        if (stackedModified || HookContext.isVpnInterfaceName(key)) {
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

        try {
            @Suppress("UNCHECKED_CAST")
            val dnsField = XposedHelpers.getObjectField(copy, "mDnses") as? MutableCollection<Any>
            if (dnsField != null) {
                if (physicalLp != null) {
                    @Suppress("UNCHECKED_CAST")
                    val physicalDnses = XposedHelpers.getObjectField(physicalLp, "mDnses") as? Collection<Any>
                    if (physicalDnses != null) {
                        dnsField.clear()
                        dnsField.addAll(physicalDnses)
                        modified = true
                    }
                } else {
                    dnsField.clear()
                    dnsField.add(java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
                    dnsField.add(java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4)))
                    modified = true
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mDnses: ${t.message}")
        }

        try {
            val domains = XposedHelpers.getObjectField(copy, "mDomains") as? String
            if (!domains.isNullOrEmpty()) {
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

        try {
            val targetMtu = if (physicalLp != null) XposedHelpers.getIntField(physicalLp, "mMtu") else 1500
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

    fun getNetworkCapabilitiesSafe(
        cs: Any,
        net: android.net.Network,
    ): NetworkCapabilities? {
        val token = Binder.clearCallingIdentity()
        try {
            return try {
                XposedHelpers.callMethod(cs, "getNetworkCapabilities", net, "android", null) as? NetworkCapabilities
            } catch (_: Throwable) {
                XposedHelpers.callMethod(cs, "getNetworkCapabilities", net) as? NetworkCapabilities
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun getNetworkScore(
        nc: NetworkCapabilities,
        lp: LinkProperties?,
    ): Int {
        var score = 0
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            score += 10000
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            score += 8000
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            score += 5000
        }

        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            score += 10000
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 2000
        }
        if (lp != null && lp.dnsServers.isNotEmpty()) {
            score += 3000
        }
        return score
    }

    fun getBestPhysicalNetwork(cs: Any): android.net.Network? {
        val networks = XposedHelpers.callMethod(cs, "getAllNetworks") as? Array<*> ?: return null
        var bestNet: android.net.Network? = null
        var bestScore = Int.MIN_VALUE

        for (netObj in networks) {
            val net = netObj as? android.net.Network ?: continue
            val nc = getNetworkCapabilitiesSafe(cs, net) ?: continue

            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }

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

    fun getPhysicalLinkProperties(cs: Any): LinkProperties? {
        val token = Binder.clearCallingIdentity()
        try {
            val bestNet = getBestPhysicalNetwork(cs)
            if (bestNet != null) {
                val lp = XposedHelpers.callMethod(cs, "getLinkProperties", bestNet) as? LinkProperties
                if (lp != null) return lp
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get physical link properties: ${t.message}")
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return null
    }

    fun getPhysicalNetwork(cs: Any): android.net.Network? {
        val token = Binder.clearCallingIdentity()
        try {
            return getBestPhysicalNetwork(cs)
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get physical network: ${t.message}")
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return null
    }

    fun getActivePhysicalInterfaceName(
        cs: Any? = null,
        lp: LinkProperties? = null,
    ): String {
        HookContext.cachedPhysicalIfaceName?.let { return it }
        val actualCs = cs ?: HookContext.getConnectivityService()
        if (actualCs != null) {
            val actualLp = lp ?: getPhysicalLinkProperties(actualCs)
            val iface = actualLp?.interfaceName
            if (iface != null) {
                return iface
            }
        }
        return "wlan0"
    }

    fun getPhysicalIpv4Address(): java.net.Inet4Address? {
        return try {
            val cs = HookContext.getConnectivityService() ?: return null
            getPhysicalLinkProperties(cs)
                ?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address as? java.net.Inet4Address
        } catch (_: Throwable) {
            null
        }
    }

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

    const val TYPE_VPN = 17
}
