package me.rhunk.snapenhance.core.util

import de.robv.android.xposed.XC_MethodHook
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.Hooker
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class CallbackBuilder(
    private val callbackClass: Class<*>
) {

    internal class Override(
        val methodName: String,
        val shouldUnhook: Boolean = true,
        val callback: (HookAdapter) -> Unit
    )

    private val methodOverrides = mutableListOf<Override>()

    fun override(
        methodName: String,
        shouldUnhook: Boolean = true,
        callback: (HookAdapter) -> Unit = {}
    ): CallbackBuilder {
        methodOverrides.add(Override(methodName, shouldUnhook, callback))
        return this
    }

    fun build(): Any {
        // get the first param of the first constructor to get the class of the invoker
        val ctor = callbackClass.constructors.firstOrNull()
            ?: error("No public constructors available for ${callbackClass.name}")
        val rxEmitter: Class<*> = ctor.parameterTypes.firstOrNull()
            ?: error("Callback constructor must have at least one parameter for emitter: ${callbackClass.name}")

        // get the emitter field based on the class
        val rxEmitterField: Field = callbackClass.fields.firstOrNull { field: Field ->
            field.type.isAssignableFrom(rxEmitter)
        } ?: error("No suitable emitter field found on ${callbackClass.name}")

        // ensure accessible for reflection reads
        if (!rxEmitterField.canAccess(null)) rxEmitterField.isAccessible = true

        // create empty callback instance and snapshot its identity
        val callbackInstance = createEmptyObject(ctor)!!
        val callbackInstanceHashCode: Int = callbackInstance.hashCode()
        val callbackInstanceClass = callbackInstance.javaClass

        val unhooks = mutableListOf<XC_MethodHook.Unhook>()

        callbackInstanceClass.methods.forEach { method ->
            if (method.declaringClass != callbackInstanceClass) return@forEach
            if (Modifier.isPrivate(method.modifiers)) return@forEach

            // default hook that unhooks the callback and returns null
            val defaultHook: (HookAdapter) -> Boolean = defaultHook@{ adapter ->
                // ensure the callback was created by the CallbackBuilder
                val owner = adapter.thisObject()
                if (owner != null && rxEmitterField.get(owner) != null) return@defaultHook false
                if ((owner as Any).hashCode() != callbackInstanceHashCode) return@defaultHook false
                adapter.setResult(null)
                true
            }

            // start with default behavior
            var effectiveHook: (HookAdapter) -> Unit = { adapter ->
                defaultHook(adapter)
            }

            // override the default hook if method name matches
            val overrideEntry = methodOverrides.firstOrNull { ov -> ov.methodName == method.name }
            if (overrideEntry != null) {
                effectiveHook = { adapter ->
                    if (defaultHook(adapter)) {
                        overrideEntry.callback(adapter)
                        if (overrideEntry.shouldUnhook) {
                            unhooks.forEach { u -> u.unhook() }
                        }
                    }
                }
            }

            // Avoid any type inference ambiguity at call site; keep explicit functional type
            unhooks.add(Hooker.hook(method, HookStage.BEFORE, effectiveHook))
        }

        return callbackInstance
    }

    companion object {
        fun createEmptyObject(constructor: Constructor<*>): Any? {
            // compute the args for the constructor with null or default primitive values
            val args: Array<Any?> = constructor.parameterTypes.map { type: Class<*> ->
                if (type.isPrimitive) {
                    when (type.name) {
                        "boolean" -> false
                        "byte" -> 0.toByte()
                        "char" -> 0.toChar()
                        "short" -> 0.toShort()
                        "int" -> 0
                        "long" -> 0L
                        "float" -> 0f
                        "double" -> 0.0
                        else -> null
                    }
                } else {
                    null
                }
            }.toTypedArray()
            return constructor.newInstance(*args)
        }
    }
}
