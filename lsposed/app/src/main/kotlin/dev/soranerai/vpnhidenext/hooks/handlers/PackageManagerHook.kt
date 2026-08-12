package dev.soranerai.vpnhidenext.hooks.handlers

import android.content.Intent
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.soranerai.vpnhidenext.HookLog
import dev.soranerai.vpnhidenext.hooks.core.HookContext

object PackageManagerHook {
    private class CallerContext(
        val uid: Int,
        val callerPackages: Array<String>?,
    ) {
        // A missing package list is not proof that the caller owns nothing.
        // It means that applying a hiding policy would be unsafe.
        val identityKnown: Boolean
            get() = uid > 0 && callerPackages != null

        fun owns(packageName: String): Boolean = callerPackages?.contains(packageName) == true
    }

    // Bit 5 = hide VPN apps ("Hiding VPN apps" in the Hook Isolation screen);
    // bit 7 = hide our own package ("Hiding VPNHide Next itself"). Both bits
    // reuse the same hook install points below, so the bit checks live at
    // each call site rather than baked into this shared precondition check.
    private fun guardCheck(
        param: XC_MethodHook.MethodHookParam,
        context: HookContext.HookCallContext? = null,
    ): CallerContext? {
        if (HookContext.isInternalCheck.get() == true) {
            return null
        }

        val targetUid =
            context?.uid ?: run {
                val fallback = HookContext.captureHookContext(5, 7) ?: return null
                fallback.uid
            }
        val callerPackages =
            if (targetUid > 0) {
                HookContext.getPackagesForUid(param.thisObject, targetUid)
            } else {
                null
            }
        return CallerContext(targetUid, callerPackages)
    }

    private fun isVpnApp(
        packageName: String,
        pmInstance: Any,
        userId: Int,
    ): Boolean {
        val cacheKey = "$userId:$packageName"
        HookContext.vpnPackageCache[cacheKey]?.let {
            return it
        }

        HookContext.isInternalCheck.set(true)
        val token = Binder.clearCallingIdentity()
        var succeeded = false
        val isVpn =
            try {
                val vpnCheckIntent =
                    Intent("android.net.VpnService").apply {
                        `package` = packageName
                    }
                val flags =
                    if (Build.VERSION.SDK_INT >= 33) 0L else 0
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
                Binder.restoreCallingIdentity(token)
                HookContext.isInternalCheck.remove()
            }

        if (succeeded) {
            HookContext.vpnPackageCache[cacheKey] = isVpn
        }
        return isVpn
    }

    /**
     * A single visibility decision shared by every PackageManager hook.
     * Unknown caller identity and unknown VPN classification both fail open:
     * a false negative is preferable to breaking a VPN application's own
     * service lookup.
     */
    private fun shouldHideVpnPackage(
        caller: CallerContext,
        packageName: String,
        pmInstance: Any,
        userId: Int,
    ): Boolean {
        if (!caller.identityKnown || caller.owns(packageName)) return false
        return isVpnApp(packageName, pmInstance, userId)
    }

    private fun filterParceledList(
        param: XC_MethodHook.MethodHookParam,
        sliceClass: Class<*>,
        userId: Int,
        caller: CallerContext,
        hideVpnApps: Boolean,
        hideSelf: Boolean,
        logTag: String,
        itemToPackageName: (Any) -> String?,
    ) {
        val result = param.result ?: return
        val isParceledSlice = result.javaClass.name == "android.content.pm.ParceledListSlice"
        val list =
            if (isParceledSlice) {
                try {
                    XposedHelpers.callMethod(result, "getList") as? List<*>
                } catch (e: Throwable) {
                    null
                }
            } else {
                result as? List<*>
            }

        if (list.isNullOrEmpty()) return

        val filteredList =
            list.filter { item ->
                if (item == null) return@filter true
                val packageName = itemToPackageName(item) ?: return@filter true
                val hiddenAsVpn =
                    hideVpnApps && shouldHideVpnPackage(caller, packageName, param.thisObject, userId)
                val hiddenAsSelf =
                    hideSelf && caller.identityKnown &&
                        packageName == HookContext.OWN_PACKAGE_NAME && !caller.owns(packageName)
                !hiddenAsVpn && !hiddenAsSelf
            }

        if (filteredList.size != list.size) {
            HookContext.recordIntercept("PackageManager", caller.uid)
            if (isParceledSlice) {
                param.result = XposedHelpers.newInstance(sliceClass, filteredList)
            } else {
                param.result = filteredList
            }
            HookLog.i(
                "VpnHide: Filtered ${list.size - filteredList.size} items from $logTag (Original: ${list.size})",
            )
        }
    }

    fun hookPackageManager(classLoader: ClassLoader) {
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
                if (!hasMethod && baseClass.superclass != Any::class.java && baseClass.superclass != null &&
                    baseClass.superclass.name.contains("IPackageManager")
                ) {
                    baseClass.superclass
                } else {
                    baseClass
                }

            val sliceClass =
                XposedHelpers.findClass("android.content.pm.ParceledListSlice", classLoader)

            // --- STAGE 1: queryIntentServices ---
            XposedBridge.hookAllMethods(
                targetClass,
                "queryIntentServices",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = HookContext.captureHookContext(5) ?: return
                        val hideVpnApps = true
                        val callerCtx = guardCheck(param, context) ?: return
                        val intent = param.args.getOrNull(0) as? Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") == true
                        ) {
                            val userId = param.args.lastOrNull() as? Int ?: 0
                            filterParceledList(
                                param = param,
                                sliceClass = sliceClass,
                                userId = userId,
                                caller = callerCtx,
                                hideVpnApps = hideVpnApps,
                                hideSelf = false,
                                logTag = "queryIntentServices",
                            ) { item ->
                                try {
                                    val serviceInfo = XposedHelpers.getObjectField(item, "serviceInfo")
                                    XposedHelpers.getObjectField(serviceInfo, "packageName") as? String
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                        }
                    }
                },
            )

            // --- STAGE 2: getPackageInfo — hides VPN apps (bit 5) and/or our own package (bit 7)
            XposedBridge.hookAllMethods(
                targetClass,
                "getPackageInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (HookContext.isInternalCheck.get() == true) return
                        val context = HookContext.captureHookContext(5, 7) ?: return
                        val uid = context.uid
                        val hideVpnApps = HookContext.isJavaHookActive(5, uid)
                        val hideSelf = HookContext.isJavaHookActive(7, uid)
                        if (param.result == null) return

                        val requestedPackage = param.args.getOrNull(0) as? String ?: return
                        val userId = param.args.getOrNull(2) as? Int ?: return

                        val callerCtx = guardCheck(param, context) ?: return
                        val hiddenAsVpn =
                            hideVpnApps && shouldHideVpnPackage(callerCtx, requestedPackage, param.thisObject, userId)
                        val hiddenAsSelf =
                            hideSelf && callerCtx.identityKnown &&
                                requestedPackage == HookContext.OWN_PACKAGE_NAME && !callerCtx.owns(requestedPackage)
                        if (hiddenAsVpn || hiddenAsSelf) {
                            HookContext.recordIntercept("PackageManager", context.uid)
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
                            val context = HookContext.captureHookContext(5, 7) ?: return
                            val uid = context.uid
                            val hideVpnApps = HookContext.isJavaHookActive(5, uid)
                            val hideSelf = HookContext.isJavaHookActive(7, uid)
                            val callerCtx = guardCheck(param, context) ?: return
                            val userId = param.args.getOrNull(1) as? Int ?: 0
                            filterParceledList(
                                param = param,
                                sliceClass = sliceClass,
                                userId = userId,
                                caller = callerCtx,
                                hideVpnApps = hideVpnApps,
                                hideSelf = hideSelf,
                                logTag = methodName,
                            ) { item ->
                                try {
                                    XposedHelpers.getObjectField(item, "packageName") as? String
                                } catch (_: Throwable) {
                                    null
                                }
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
                        val context = HookContext.captureHookContext(5) ?: return
                        val hideVpnApps = true
                        val callerCtx = guardCheck(param, context) ?: return
                        val intent = param.args.getOrNull(0) as? Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") == true
                        ) {
                            val userId = param.args.lastOrNull() as? Int ?: 0
                            val result = param.result
                            if (result != null) {
                                val packageName =
                                    try {
                                        val serviceInfo = XposedHelpers.getObjectField(result, "serviceInfo")
                                        XposedHelpers.getObjectField(serviceInfo, "packageName") as? String
                                    } catch (_: Throwable) {
                                        null
                                    }
                                if (packageName == null ||
                                    !shouldHideVpnPackage(callerCtx, packageName, param.thisObject, userId)
                                ) {
                                    return
                                }
                                HookContext.recordIntercept("PackageManager", context.uid)
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
                        val context = HookContext.captureHookContext(5) ?: return
                        val hideVpnApps = true
                        val callerCtx = guardCheck(param, context) ?: return
                        val intent = param.args.getOrNull(0) as? Intent ?: return

                        if (intent.action == "android.net.VpnService" ||
                            intent.component?.className?.contains("VpnService") == true
                        ) {
                            val userId = param.args.lastOrNull() as? Int ?: 0
                            filterParceledList(
                                param = param,
                                sliceClass = sliceClass,
                                userId = userId,
                                caller = callerCtx,
                                hideVpnApps = hideVpnApps,
                                hideSelf = false,
                                logTag = "queryIntentActivities",
                            ) { item ->
                                try {
                                    val activityInfo = XposedHelpers.getObjectField(item, "activityInfo")
                                    XposedHelpers.getObjectField(activityInfo, "packageName") as? String
                                } catch (_: Throwable) {
                                    null
                                }
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
}
