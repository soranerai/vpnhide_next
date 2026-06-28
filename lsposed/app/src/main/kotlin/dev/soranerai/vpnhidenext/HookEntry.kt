@file:Suppress("DEPRECATION")

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
import dev.soranerai.vpnhidenext.hooks.core.HookContext
import dev.soranerai.vpnhidenext.hooks.handlers.ConnectivityHook
import dev.soranerai.vpnhidenext.hooks.handlers.PackageManagerHook
import dev.soranerai.vpnhidenext.hooks.handlers.ParcelHooks
import dev.soranerai.vpnhidenext.hooks.handlers.UserManagerHook
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : IXposedHookLoadPackage {
    private val hookInstalled = AtomicBoolean(false)

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

    private fun installSystemServerHooks(): List<String> {
        val brokenFields = runReflectionSmokeCheck()

        fun anyBroken(critical: Set<String>): Boolean = brokenFields.any { it.substringBefore(':') in critical }

        if (!anyBroken(LP_CRITICAL_KEYS)) tryHook("LP.writeToParcel") { ParcelHooks.hookLPWriteToParcel() }
        tryHook("NC.writeToParcel") { ParcelHooks.hookNCWriteToParcel() }
        if (!anyBroken(NI_CRITICAL_KEYS)) tryHook("NI.writeToParcel") { ParcelHooks.hookNIWriteToParcel() }
        tryHook("Network.writeToParcel") { ParcelHooks.hookNetworkWriteToParcel() }

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
                        val uids = HookContext.systemServerTargetUids
                        val stack = HookContext.callingUidStack.get() ?: return
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
                        val stack = HookContext.callingUidStack.get()
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

    // Hook and hide any VPN app from the LSPosed targets automatically
    private fun hookPackageManager(classLoader: ClassLoader) {
        PackageManagerHook.hookPackageManager(classLoader)
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
            val sb = StringBuilder()
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
            var lastJavaStatsClearGen = -1
            while (!Thread.currentThread().isInterrupted) {
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
                        var coverIface: String? = null
                        var statsClearGen: Int? = null

                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                synchronized(HookContext.uidLock) {
                                    HookContext.systemServerTargetUids = uids.toSet()
                                    HookContext.systemServerIfacePrefixes = prefixes.toList()
                                    HookContext.cachedJavaHooksMask = javaHookMask
                                    HookContext.cachedPhysicalIfaceName = coverIface
                                    HookContext.vpnPackageCache.clear()
                                    statsClearGen?.let { gen ->
                                        if (lastJavaStatsClearGen == -1) {
                                            lastJavaStatsClearGen = gen
                                        } else if (gen > lastJavaStatsClearGen) {
                                            HookContext.hookStats.clear()
                                            lastJavaStatsClearGen = gen
                                            HookLog.i("VpnHide: cleared java stats (gen=$gen)")
                                        }
                                    }
                                }
                                uids.clear()
                                prefixes.clear()
                                javaHookMask = 0xFFFFFFFFu
                                coverIface = null
                                statsClearGen = null
                                continue
                            }

                            if (line.startsWith("java_hook_mask:")) {
                                javaHookMask = line.substringAfter("java_hook_mask:").trim().toUIntOrNull() ?: 0xFFFFFFFFu
                            } else if (line.startsWith("java_stats_clear_gen:")) {
                                statsClearGen = line.substringAfter("java_stats_clear_gen:").trim().toIntOrNull()
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
                            } else if (line.startsWith("cover_iface:")) {
                                val value = line.substringAfter("cover_iface:").trim()
                                coverIface = if (value.isEmpty() || value == "none") null else value
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    HookLog.e("VpnHide: config reader error: ${t.message}")
                    try {
                        Thread.sleep(5000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (_: Throwable) {
                    }
                }
            }
        }, "VpnHideConfigReader").start()
    }

    private fun startStatsWriter() {
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(2000)
                    if (HookContext.hookStatsChanged.compareAndSet(true, false)) {
                        val sb = StringBuilder()
                        sb.append("stats:")
                        for ((uid, appStats) in HookContext.hookStats) {
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
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    HookLog.e("VpnHide: stats writer error: ${t.message}")
                }
            }
        }, "VpnHideStatsWriter").start()
    }

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
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (name != "connectivity" && name != "package" && name != "user") return
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
                tryHook("ConnectivityService.networkLogic") {
                    ConnectivityHook.hookConnectivityService(classLoader)
                }
            }

            "package" -> {
                HookLog.i("VpnHide: Installing PackageManager hooks via APEX/ServiceManager loader...")
                tryHook("PackageManager.queryIntentServices") { hookPackageManager(classLoader) }
            }

            "user" -> {
                HookLog.i("VpnHide: Installing UserManager hooks...")
                tryHook("UserManagerService.profiles") {
                    UserManagerHook.hookUserManagerService(classLoader)
                }
            }
        }
    }

    companion object {
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
