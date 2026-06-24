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
import dev.soranerai.vpnhidenext.hooks.handlers.ParcelHooks
import dev.soranerai.vpnhidenext.hooks.handlers.UserManagerHook
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
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
        try {
            val baseClass =
                try {
                    XposedHelpers.findClass(
                        "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
                        classLoader,
                    )
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.findClass(
                            "com.android.server.pm.IPackageManagerBase",
                            classLoader,
                        )
                    } catch (_: Throwable) {
                        XposedHelpers.findClass(
                            "com.android.server.pm.PackageManagerService",
                            classLoader,
                        )
                    }
                }

            val hasMethod = baseClass.declaredMethods.any { it.name == "queryIntentServices" }
            val targetClass: Class<*> =
                if (!hasMethod && baseClass.superclass != Any::class.java && baseClass.superclass != null && baseClass.superclass.name.contains("IPackageManager")) {
                    baseClass.superclass
                } else {
                    baseClass
                }

            val sliceClass =
                XposedHelpers.findClass("android.content.pm.ParceledListSlice", classLoader)

            val isVpnApp =
                fun(
                    packageName: String,
                    pmInstance: Any,
                    userId: Int,
                ): Boolean {
                    HookContext.vpnPackageCache[packageName]?.let {
                        return it
                    }

                    HookContext.isInternalCheck.set(true)
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
                            HookContext.isInternalCheck.remove()
                        }

                    if (succeeded) {
                        HookContext.vpnPackageCache[packageName] = isVpn
                    }
                    return isVpn
                }

            // --- STAGE 1: queryIntentServices ---
            XposedBridge.hookAllMethods(
                targetClass,
                "queryIntentServices",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookContext.isJavaHookActive(5) ||
                            HookContext.isInternalCheck.get() as Boolean ||
                            !HookContext.isTargetCaller()
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
                            val targetUid = if (callingUid == 1000) HookContext.currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    HookContext.getPackagesForUid(param.thisObject, targetUid)
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
                                HookContext.recordIntercept("PackageManager")
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
                        if (!HookContext.isJavaHookActive(5) ||
                            HookContext.isInternalCheck.get() as Boolean ||
                            !HookContext.isTargetCaller()
                        ) {
                            return
                        }
                        if (param.result == null) return

                        val requestedPackage = param.args.getOrNull(0) as? String ?: return
                        val userId = param.args.getOrNull(2) as? Int ?: return

                        if (isVpnApp(requestedPackage, param.thisObject, userId)) {
                            if (HookContext.isOwnApp(param.thisObject, requestedPackage)) return
                            HookContext.recordIntercept("PackageManager")
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
                            if (!HookContext.isJavaHookActive(5) ||
                                HookContext.isInternalCheck.get() as Boolean ||
                                !HookContext.isTargetCaller()
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
                            val targetUid = if (callingUid == 1000) HookContext.currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    HookContext.getPackagesForUid(param.thisObject, targetUid)
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
                                HookContext.recordIntercept("PackageManager")
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
                        if (!HookContext.isJavaHookActive(5) ||
                            HookContext.isInternalCheck.get() as Boolean ||
                            !HookContext.isTargetCaller()
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
                                    val targetUid = if (callingUid == 1000) HookContext.currentCallbackUid.get() else callingUid
                                    val callerPackages =
                                        if (targetUid != null &&
                                            targetUid > 0
                                        ) {
                                            HookContext.getPackagesForUid(param.thisObject, targetUid)
                                        } else {
                                            null
                                        }
                                    if (callerPackages?.contains(packageName) == true) {
                                        return
                                    }
                                }
                                HookContext.recordIntercept("PackageManager")
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
                        if (!HookContext.isJavaHookActive(5) ||
                            HookContext.isInternalCheck.get() as Boolean ||
                            !HookContext.isTargetCaller()
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
                            val targetUid = if (callingUid == 1000) HookContext.currentCallbackUid.get() else callingUid
                            val callerPackages =
                                if (targetUid != null &&
                                    targetUid > 0
                                ) {
                                    HookContext.getPackagesForUid(param.thisObject, targetUid)
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
                                HookContext.recordIntercept("PackageManager")
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

            HookLog.i("VpnHide: All PM hooks successfully applied to ${targetClass.name}")
        } catch (t: Throwable) {
            HookLog.e("VpnHide: Failed to hook PM: ${t::class.java.simpleName}: ${t.message}")
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
                        var coverIface: String? = null

                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                synchronized(HookContext.uidLock) {
                                    HookContext.systemServerTargetUids = uids.toSet()
                                    HookContext.systemServerIfacePrefixes = prefixes.toList()
                                    HookContext.cachedJavaHooksMask = javaHookMask
                                    HookContext.cachedPhysicalIfaceName = coverIface
                                    HookContext.vpnPackageCache.clear()
                                }
                                uids.clear()
                                prefixes.clear()
                                javaHookMask = 0xFFFFFFFFu
                                coverIface = null
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
                            } else if (line.startsWith("cover_iface:")) {
                                val value = line.substringAfter("cover_iface:").trim()
                                coverIface = if (value.isEmpty() || value == "none") null else value
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
                    if (HookContext.hookStatsChanged.compareAndSet(true, false)) {
                        val sb = java.lang.StringBuilder()
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
