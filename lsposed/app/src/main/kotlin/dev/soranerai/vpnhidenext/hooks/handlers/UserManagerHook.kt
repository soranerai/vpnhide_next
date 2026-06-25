package dev.soranerai.vpnhidenext.hooks.handlers

import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.soranerai.vpnhidenext.HookLog
import dev.soranerai.vpnhidenext.hooks.core.HookContext

object UserManagerHook {
    fun hookUserManagerService(classLoader: ClassLoader) {
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
            HookContext.isInternalCheck.set(true)
            try {
                return XposedHelpers.callMethod(serviceInstance, "isManagedProfile", userId) as? Boolean ?: false
            } catch (t: Throwable) {
                return false
            } finally {
                HookContext.isInternalCheck.remove()
                Binder.restoreCallingIdentity(token)
            }
        }

        try {
            XposedBridge.hookAllMethods(
                targetClass,
                "getUserInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userInfo = param.result
                        val userId = if (userInfo != null) XposedHelpers.getIntField(userInfo, "id") else null
                        val stackTrace = if (callingUid == 1000) "\n" + android.util.Log.getStackTraceString(Throwable()) else ""
                        HookLog.i(
                            "VpnHide: getUserInfo(userId=$userId) called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}$stackTrace",
                        )

                        if (userInfo != null && userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            HookContext.recordIntercept("UserManager")
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
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        HookLog.i(
                            "VpnHide: isProfile(userId=$userId) called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            HookContext.recordIntercept("UserManager")
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
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        HookLog.i(
                            "VpnHide: getProfiles called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}",
                        )

                        val result = param.result as? List<*> ?: return
                        if (result.isEmpty()) return

                        val targetUid = if (callingUid == 1000) (HookContext.currentCallbackUid.get() ?: callingUid) else callingUid
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
                            HookContext.recordIntercept("UserManager")
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
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        HookLog.i(
                            "VpnHide: getProfileIds called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}",
                        )

                        val result = param.result as? IntArray ?: return
                        if (result.isEmpty()) return

                        val targetUid = if (callingUid == 1000) (HookContext.currentCallbackUid.get() ?: callingUid) else callingUid
                        val targetUserId = targetUid / 100000

                        val filteredList =
                            result.filter { itemId ->
                                itemId == targetUserId
                            }

                        if (filteredList.size != result.size) {
                            HookContext.recordIntercept("UserManager")
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
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        val stackTrace = if (callingUid == 1000) "\n" + android.util.Log.getStackTraceString(Throwable()) else ""
                        HookLog.i(
                            "VpnHide: getProfileParent(userId=$userId) called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}$stackTrace",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            HookContext.recordIntercept("UserManager")
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
                        if (!HookContext.isJavaHookActive(6) || HookContext.isInternalCheck.get() == true) return
                        if (!HookContext.isTargetCaller()) return

                        val callingUid = Binder.getCallingUid()
                        val userId = param.args.getOrNull(0) as? Int
                        HookLog.i(
                            "VpnHide: getProfileParentId(userId=$userId) called by uid $callingUid, cbUid=${HookContext.currentCallbackUid.get()}, inheritedUid=${HookContext.getInheritedCallingUid()}",
                        )

                        if (userId != null && isManagedProfileInternal(param.thisObject, userId)) {
                            HookContext.recordIntercept("UserManager")
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
}
