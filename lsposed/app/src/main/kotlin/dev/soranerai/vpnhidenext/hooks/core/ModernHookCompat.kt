package dev.soranerai.vpnhidenext.hooks.core

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small source-compatibility layer for the existing hook handlers.
 *
 * The public surface deliberately resembles only the subset of the old API
 * used by this module. Registration and invocation are entirely implemented
 * with libxposed API 102; no legacy Xposed class is referenced here.
 */
object ModernHookCompat {
    @Volatile
    private var api: XposedInterface? = null

    fun install(xposed: XposedInterface) {
        api = xposed
    }

    fun requireApi(): XposedInterface = api ?: error("Modern Xposed API is not attached")
}

open class MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit

    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        private val chain: XposedInterface.Chain,
    ) {
        val thisObject: Any? get() = chain.getThisObject()
        val args: MutableList<Any?> = chain.getArgs().toMutableList()

        private var resultWasSet = false
        var result: Any?
            get() = resultValue
            set(value) {
                resultWasSet = true
                resultValue = value
            }

        private var resultValue: Any? = null

        internal fun proceed(): Any? {
            if (resultWasSet) return resultValue
            resultValue = chain.proceed(args.toTypedArray())
            return resultValue
        }
    }
}

object XposedBridge {
    fun hookMethod(
        method: Method,
        callback: MethodHook,
    ) = hook(method, callback)

    fun hookAllMethods(
        clazz: Class<*>,
        name: String,
        callback: MethodHook,
    ) {
        clazz.declaredMethods.filter { it.name == name }.forEach { hook(it, callback) }
    }

    fun hookAllConstructors(
        clazz: Class<*>,
        callback: MethodHook,
    ) {
        clazz.declaredConstructors.forEach { hook(it, callback) }
    }

    fun log(message: String) {
        ModernHookCompat.requireApi().log(android.util.Log.DEBUG, "VpnHide", message)
    }

    private fun hook(
        executable: java.lang.reflect.Executable,
        callback: MethodHook,
    ) {
        ModernHookCompat.requireApi().hook(executable).intercept(
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val param = MethodHook.MethodHookParam(chain)
                    callback.beforeHookedMethod(param)
                    param.proceed()
                    callback.afterHookedMethod(param)
                    return param.result
                }
            },
        )
    }
}

object XposedHelpers {
    fun findClass(
        name: String,
        classLoader: ClassLoader?,
    ): Class<*> = Class.forName(name, false, classLoader ?: ClassLoader.getSystemClassLoader())

    fun findMethodExact(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method = findMethod(clazz, name, parameterTypes.toList())

    fun findField(
        clazz: Class<*>,
        name: String,
    ): Field = findInHierarchy(clazz) { it.getDeclaredField(name) }

    fun findAndHookMethod(
        clazz: Class<*>,
        name: String,
        vararg args: Any,
    ) {
        require(args.isNotEmpty())
        val callback = args.last() as MethodHook
        val parameterTypes = args.dropLast(1).map { it as Class<*> }.toTypedArray()
        XposedBridge.hookMethod(findMethodExact(clazz, name, *parameterTypes), callback)
    }

    fun callMethod(
        receiver: Any,
        name: String,
        vararg args: Any?,
    ): Any? = findCompatibleMethod(receiver.javaClass, name, args.toList()).invokeAccessible(receiver, args)

    fun callStaticMethod(
        clazz: Class<*>,
        name: String,
        vararg args: Any?,
    ): Any? = findCompatibleMethod(clazz, name, args.toList()).invokeAccessible(null, args)

    fun newInstance(
        clazz: Class<*>,
        vararg args: Any?,
    ): Any = findCompatibleConstructor(clazz, args.toList()).apply { isAccessible = true }.newInstance(*args)

    fun getObjectField(
        receiver: Any?,
        name: String,
    ): Any? = receiver?.let { findField(it.javaClass, name).getAccessible(it) }

    fun getIntField(
        receiver: Any,
        name: String,
    ): Int = findField(receiver.javaClass, name).getAccessible(receiver) as Int

    fun getBooleanField(
        receiver: Any,
        name: String,
    ): Boolean = findField(receiver.javaClass, name).getAccessible(receiver) as Boolean

    fun setObjectField(
        receiver: Any,
        name: String,
        value: Any?,
    ) = findField(receiver.javaClass, name).setAccessible(receiver, value)

    fun setIntField(
        receiver: Any,
        name: String,
        value: Int,
    ) = findField(receiver.javaClass, name).setAccessible(receiver, value)

    fun setBooleanField(
        receiver: Any,
        name: String,
        value: Boolean,
    ) = findField(receiver.javaClass, name).setAccessible(receiver, value)

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        parameterTypes: List<Class<*>>,
    ): Method = findInHierarchy(clazz) { it.getDeclaredMethod(name, *parameterTypes.toTypedArray()) }

    private fun findCompatibleMethod(
        clazz: Class<*>,
        name: String,
        args: List<Any?>,
    ): Method =
        allMethods(clazz).firstOrNull { it.name == name && compatible(it.parameterTypes, args) }
            ?: throw NoSuchMethodException("$name(${args.size}) on ${clazz.name}")

    private fun findCompatibleConstructor(
        clazz: Class<*>,
        args: List<Any?>,
    ): Constructor<*> =
        clazz.declaredConstructors.firstOrNull { compatible(it.parameterTypes, args) }
            ?: throw NoSuchMethodException("constructor(${args.size}) on ${clazz.name}")

    private fun compatible(
        types: Array<Class<*>>,
        args: List<Any?>,
    ): Boolean =
        types.size == args.size &&
            types.zip(args).all { (type, value) ->
                value == null || box(type).isInstance(value)
            }

    private fun box(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }

    private fun allMethods(clazz: Class<*>): Sequence<Method> =
        sequence {
            var current: Class<*>? = clazz
            while (current != null) {
                yieldAll(current.declaredMethods.asSequence())
                current = current.superclass
            }
        }

    private fun <T> findInHierarchy(
        clazz: Class<*>,
        lookup: (Class<*>) -> T,
    ): T {
        var current: Class<*>? = clazz
        var failure: Throwable? = null
        while (current != null) {
            try {
                return lookup(current)
            } catch (t: NoSuchFieldException) {
                failure = t
            } catch (t: NoSuchMethodException) {
                failure = t
            }
            current = current.superclass
        }
        throw failure ?: NoSuchMethodException("member on ${clazz.name}")
    }

    private fun Method.invokeAccessible(
        receiver: Any?,
        args: Array<out Any?>,
    ): Any? {
        isAccessible = true
        return invoke(receiver, *args)
    }

    private fun Field.getAccessible(receiver: Any): Any? {
        isAccessible = true
        return get(receiver)
    }

    private fun Field.setAccessible(
        receiver: Any,
        value: Any?,
    ) {
        isAccessible = true
        set(receiver, value)
    }
}
