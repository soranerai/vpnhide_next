package dev.soranerai.vpnhidenext.hooks.handlers

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.soranerai.vpnhidenext.HookLog
import dev.soranerai.vpnhidenext.hooks.core.HookContext

object ParcelHooks {
    fun hookNCWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookContext.isJavaHookActive(1)) return
                    if (writingCopy.get() == true || !HookContext.isTargetCaller()) return
                    val nc = param.thisObject as NetworkCapabilities
                    val copy = NetworkCapabilities(nc)
                    if (!sanitizeNetworkCapabilities(copy)) return
                    HookContext.recordIntercept("NetworkCapabilities")

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
    fun hookNIWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkInfo::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookContext.isJavaHookActive(2)) return
                    if (writingCopy.get() == true || !HookContext.isTargetCaller()) return
                    val ni = param.thisObject as NetworkInfo
                    if (XposedHelpers.getIntField(ni, "mNetworkType") != ConnectivityHook.TYPE_VPN) return
                    HookContext.recordIntercept("NetworkInfo")

                    val cs = HookContext.getConnectivityService()
                    val physicalNet = if (cs != null) ConnectivityHook.getPhysicalNetwork(cs) else null
                    val physicalNi =
                        if (cs != null && physicalNet != null) {
                            try {
                                XposedHelpers.callMethod(
                                    cs,
                                    "getNetworkInfo",
                                    physicalNet,
                                ) as? NetworkInfo
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
                                val ctor =
                                    NetworkInfo::class.java.getDeclaredConstructor(
                                        Integer.TYPE,
                                        Integer.TYPE,
                                        String::class.java,
                                        String::class.java,
                                    )
                                ctor.isAccessible = true
                                val type = XposedHelpers.getIntField(physicalNi, "mNetworkType")
                                val subtype = XposedHelpers.getIntField(physicalNi, "mSubtype")
                                val typeName = XposedHelpers.getObjectField(physicalNi, "mTypeName") as? String ?: "MOBILE"
                                val subtypeName = XposedHelpers.getObjectField(physicalNi, "mSubtypeName") as? String ?: ""
                                val dummy = ctor.newInstance(type, subtype, typeName, subtypeName) as NetworkInfo
                                XposedHelpers.setObjectField(dummy, "mState", XposedHelpers.getObjectField(physicalNi, "mState"))
                                XposedHelpers.setObjectField(
                                    dummy,
                                    "mDetailedState",
                                    XposedHelpers.getObjectField(physicalNi, "mDetailedState"),
                                )
                                XposedHelpers.setBooleanField(
                                    dummy,
                                    "mIsAvailable",
                                    XposedHelpers.getBooleanField(physicalNi, "mIsAvailable"),
                                )
                                dummy
                            }
                        HookLog.i("VpnHide: NetworkInfo.writeToParcel – morphed VPN info into physical network info")
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
                        XposedHelpers.setObjectField(copy, "mState", NetworkInfo.State.DISCONNECTED)
                        XposedHelpers.setObjectField(copy, "mDetailedState", NetworkInfo.DetailedState.DISCONNECTED)
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

    fun hookNetworkWriteToParcel() {
        val writingNetCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            android.net.Network::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookContext.isJavaHookActive(3)) return
                    if (writingNetCopy.get() == true) return
                    val target = HookContext.isTargetCaller()
                    if (!target) return

                    val net = param.thisObject as android.net.Network
                    writingNetCopy.set(true)
                    try {
                        val cs = HookContext.getConnectivityService() ?: return
                        val nc = ConnectivityHook.getNetworkCapabilitiesSafe(cs, net) ?: return

                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            HookContext.recordIntercept("Network")
                            val physicalNet = ConnectivityHook.getPhysicalNetwork(cs)
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

    fun hookLPWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            LinkProperties::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookContext.isJavaHookActive(0)) return
                    if (writingCopy.get() == true || !HookContext.isTargetCaller()) return
                    val lp = param.thisObject as LinkProperties
                    val isVpn = lp.interfaceName?.let { HookContext.isVpnInterfaceName(it) } ?: false
                    if (isVpn) {
                        HookContext.recordIntercept("LinkProperties")
                        val cs = HookContext.getConnectivityService()
                        val physicalLp = if (cs != null) ConnectivityHook.getPhysicalLinkProperties(cs) else null
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
                            if (ConnectivityHook.sanitizeLinkProperties(copy)) {
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

    fun sanitizeNetworkCapabilities(copy: NetworkCapabilities): Boolean {
        val hasVpnTransport = copy.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val transportInfo =
            try {
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

        val hasPhysical =
            copy.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                copy.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

        val cs = HookContext.getConnectivityService()
        val physicalNet = if (cs != null) ConnectivityHook.getPhysicalNetwork(cs) else null
        val physicalNc =
            if (cs != null && physicalNet != null) {
                ConnectivityHook.getNetworkCapabilitiesSafe(cs, physicalNet)
            } else {
                null
            }

        if (!hasPhysical) {
            if (physicalNc != null) {
                var addedAny = false
                val physicalTransports =
                    try {
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
                        NetworkCapabilities.TRANSPORT_BLUETOOTH,
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
                if (ss == Integer.MIN_VALUE) {
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
                    XposedHelpers.callMethod(copy, "setLinkDownstreamBandwidthKbps", 150_000)
                }
                if (up == 0 || up > 10_000_000) {
                    XposedHelpers.callMethod(copy, "setLinkUpstreamBandwidthKbps", 75_000)
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
}
