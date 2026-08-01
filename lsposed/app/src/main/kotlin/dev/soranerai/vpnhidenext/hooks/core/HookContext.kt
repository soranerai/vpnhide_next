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
    var cachedJavaHooksMask: UInt? = null

    @Volatile
    var appJavaHookMasks: Map<Int, UInt>? = null

    @Volatile
    var statsBucketDurationMs: Long = 60_000L

    // Cache: "$userId:$packageName" -> is package VPN?
    val vpnPackageCache = ConcurrentHashMap<String, Boolean>()

    val hookStats = ConcurrentHashMap<Int, ConcurrentHashMap<String, RollingCounter>>()

    val hookStatsChanged = AtomicBoolean(false)

    @Volatile
    var selfUid: Int = -1
    val uidLock = Any()

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

    fun recordIntercept(hookName: String) {
        val callingUid = Binder.getCallingUid()
        val targetUid =
            if (callingUid == 1000) {
                currentCallbackUid.get() ?: return
            } else {
                callingUid
            }
        if (!loadTargetUids().contains(targetUid)) return
        if (targetUid == selfUid) return

        val appStats = hookStats.computeIfAbsent(targetUid) { ConcurrentHashMap() }
        appStats.computeIfAbsent(hookName) { RollingCounter() }.increment()
        hookStatsChanged.set(true)
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

    fun isVpnInterfaceName(name: String): Boolean {
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
        private val bucketCounts = IntArray(NUM_BUCKETS)
        private val bucketTimes = LongArray(NUM_BUCKETS)

        // Reads HookContext.statsBucketDurationMs live on every call (instead of
        // capturing it once at construction) and stores raw timestamps rather than
        // pre-divided bucket numbers, so a retention-period change is reflected
        // immediately without invalidating/clearing already-recorded data.
        @Synchronized
        fun increment() {
            val durationMs = statsBucketDurationMs
            val nowMs = System.currentTimeMillis()
            val idx = ((nowMs / durationMs) % NUM_BUCKETS).toInt()
            if (bucketTimes[idx] / durationMs != nowMs / durationMs) {
                bucketCounts[idx] = 1
                bucketTimes[idx] = nowMs
            } else {
                bucketCounts[idx]++
            }
        }

        @Synchronized
        fun getSum(): Int {
            val durationMs = statsBucketDurationMs
            val nowMs = System.currentTimeMillis()
            val windowMs = NUM_BUCKETS * durationMs
            var sum = 0
            for (i in 0 until NUM_BUCKETS) {
                if (nowMs - bucketTimes[i] < windowMs) {
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

        companion object {
            const val NUM_BUCKETS = 30
        }
    }
}
