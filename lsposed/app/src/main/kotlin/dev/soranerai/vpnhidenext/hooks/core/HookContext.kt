package dev.soranerai.vpnhidenext.hooks.core

import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XposedHelpers
import dev.soranerai.vpnhidenext.HookLog
import dev.soranerai.vpnhidenext.generated.IfaceLists
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HookContext {
    const val OWN_PACKAGE_NAME = "dev.soranerai.vpnhidenext"

    @Volatile
    var csInstance: Any? = null

    @Volatile
    var cachedPhysicalIfaceName: String? = null

    // ThreadLocal context to track the target UID during system_server push callbacks
    val currentCallbackUid = ThreadLocal<Int>()

    // ThreadLocal stack to track calling UIDs across Binder.clearCallingIdentity / restoreCallingIdentity
    val callingUidStack = ThreadLocal.withInitial { ArrayList<Int>() }

    val isInternalCheck = ThreadLocal.withInitial { false }

    @Volatile
    var systemServerTargetUids: Set<Int>? = null

    @Volatile
    var systemServerIfacePrefixes: List<String>? = null

    @Volatile
    var systemServerActiveVpnIfaces: Set<String>? = null

    @Volatile
    var cachedJavaHooksMask: UInt? = null

    @Volatile
    var appJavaHookMasks: Map<Int, UInt>? = null

    // Cache: "$userId:$packageName" -> is package VPN?
    val vpnPackageCache = ConcurrentHashMap<String, Boolean>()

    val hookStats = ConcurrentHashMap<Int, ConcurrentHashMap<String, RollingCounter>>()

    val hookStatsChanged = AtomicBoolean(false)

    @Volatile
    var selfUid: Int = -1
    val uidLock = Any()

    /**
     * The result of the cheap, common part of a framework-hook callback.
     * Keeping the effective UID here prevents the callback from resolving
     * Binder identity again when it records an interception.
     */
    data class HookCallContext(
        val uid: Int,
    )

    /**
     * Fast path shared by hooks whose target is the Binder caller. A null
     * result means that the hook is disabled for this UID or that the UID is
     * not in the current target snapshot.
     */
    fun captureHookContext(bitIndex: Int): HookCallContext? = captureHookContextForMask(1u shl bitIndex)

    fun captureHookContext(
        firstBitIndex: Int,
        secondBitIndex: Int,
    ): HookCallContext? = captureHookContextForMask((1u shl firstBitIndex) or (1u shl secondBitIndex))

    private fun captureHookContextForMask(bitMask: UInt): HookCallContext? {
        val uid = resolveEffectiveUid()
        val mask = appJavaHookMasks?.get(uid) ?: cachedJavaHooksMask ?: 0xFFFFFFFFu
        if ((mask and bitMask) == 0u) return null
        if (!loadTargetUids().contains(uid)) return null
        return HookCallContext(uid)
    }

    fun getInheritedCallingUid(): Int? {
        val stack = callingUidStack.get()
        if (stack != null && stack.isNotEmpty()) {
            val uid = stack[stack.size - 1]
            if (uid != 1000) return uid
        }
        return null
    }

    fun isTargetCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 1000) { // system_server is pushing data
            val cbUid = currentCallbackUid.get() ?: getInheritedCallingUid()
            if (cbUid != null) {
                return loadTargetUids().contains(cbUid)
            }
        }
        return loadTargetUids().contains(callingUid)
    }

    // Same uid resolution as isTargetCaller(), exposed so call sites can reuse it for isJavaHookActive().
    fun resolveEffectiveUid(): Int {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 1000) {
            val cbUid = currentCallbackUid.get() ?: getInheritedCallingUid()
            if (cbUid != null) return cbUid
        }
        return callingUid
    }

    fun isJavaHookActive(
        bitIndex: Int,
        uid: Int,
    ): Boolean {
        val override = appJavaHookMasks?.get(uid)
        val mask = override ?: cachedJavaHooksMask ?: 0xFFFFFFFFu
        return (mask and (1u shl bitIndex)) != 0u
    }

    fun recordIntercept(
        hookName: String,
        targetUid: Int,
    ) {
        if (targetUid == selfUid) return

        val appStats = hookStats.computeIfAbsent(targetUid) { ConcurrentHashMap() }
        appStats.computeIfAbsent(hookName) { RollingCounter() }.increment()
        hookStatsChanged.set(true)
    }

    /**
     * Compatibility overload for deeper sanitizers that do not yet receive
     * the callback context. It still avoids the second target-set lookup;
     * hot callbacks should use the UID-aware overload above.
     */
    fun recordIntercept(hookName: String) {
        val callingUid = Binder.getCallingUid()
        val targetUid = if (callingUid == 1000) currentCallbackUid.get() ?: return else callingUid
        recordIntercept(hookName, targetUid)
    }

    fun loadTargetUids(): Set<Int> {
        if (selfUid == -1) {
            val pm = getIPackageManager()
            if (pm != null) {
                synchronized(uidLock) {
                    if (selfUid == -1) {
                        selfUid = getPackageUid(pm, OWN_PACKAGE_NAME, 0)
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
            return uids.toSet()
        }
    }

    fun loadIfacePrefixes(): List<String> = systemServerIfacePrefixes ?: emptyList()

    fun loadActiveVpnInterfaces(): Set<String> = systemServerActiveVpnIfaces ?: emptySet()

    fun isVpnInterfaceName(name: String): Boolean {
        if (loadActiveVpnInterfaces().any { it.equals(name, ignoreCase = true) }) return true
        if (IfaceLists.isVpnIface(name)) return true
        val prefixes = loadIfacePrefixes()
        for (prefix in prefixes) {
            if (name.startsWith(prefix, ignoreCase = true)) return true
        }
        return false
    }

    fun getIPackageManager(): Any? =
        try {
            val appGlobals = XposedHelpers.findClass("android.app.AppGlobals", null)
            XposedHelpers.callStaticMethod(appGlobals, "getPackageManager")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to get IPackageManager: ${t.message}")
            null
        }

    fun getPackageUid(
        pm: Any,
        pkg: String,
        userId: Int,
    ): Int {
        val token = Binder.clearCallingIdentity()
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
            Binder.restoreCallingIdentity(token)
        }
    }

    fun getPackagesForUid(
        pm: Any,
        uid: Int,
    ): Array<String>? {
        val token = Binder.clearCallingIdentity()
        try {
            return try {
                XposedHelpers.callMethod(pm, "getPackagesForUid", uid) as? Array<String>
            } catch (t: Throwable) {
                null
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun isOwnApp(
        pm: Any,
        packageName: String,
    ): Boolean {
        val callingUid = Binder.getCallingUid()
        val targetUid = if (callingUid == 1000) currentCallbackUid.get() else callingUid
        if (targetUid == null || targetUid <= 0) return false
        val callerPackages = getPackagesForUid(pm, targetUid) ?: return false
        return callerPackages.contains(packageName)
    }

    fun invalidateTargetUids() {
        systemServerTargetUids = null
        systemServerIfacePrefixes = null
        systemServerActiveVpnIfaces = null
        vpnPackageCache.clear()
    }

    fun getConnectivityService(): Any? {
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

    class RollingCounter {
        private var pending = 0

        @Synchronized
        fun increment() {
            if (pending < Int.MAX_VALUE) pending++
        }

        @Synchronized
        fun drain(): Int {
            val value = pending
            pending = 0
            return value
        }

        @Synchronized
        fun restore(value: Int) {
            pending = (pending.toLong() + value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        @Synchronized
        fun clear() {
            pending = 0
        }
    }
}
