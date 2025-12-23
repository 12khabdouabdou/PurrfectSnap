package me.eternal.purrfectsnap.core.wrapper.impl.composer

import de.robv.android.xposed.XposedHelpers
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

class ComposerContext(obj: Any): AbstractWrapper(obj) {
    val componentPath by field<String>("componentPath")
    val moduleName by field<String>("moduleName")
    val componentContext by field<WeakReference<Any?>>("componentContext")

    val viewModel: Any?
        get() = runCatching { XposedHelpers.getObjectField(instanceNonNull(), "innerViewModel") }.getOrNull()
            ?: runCatching { XposedHelpers.getObjectField(instanceNonNull(), "viewModel") }.getOrNull()
            ?: instanceNonNull()::class.java.methods.firstOrNull { it.name == "getViewModel" && it.parameterTypes.isEmpty() }?.invoke(instanceNonNull())

    fun enqueueNextRenderCallback(callback: () -> Unit) {
        val method = instanceNonNull()::class.java.methods.firstOrNull {
            it.name == "onNextLayout"
        }
        method?.invoke(instanceNonNull(), Proxy.newProxyInstance(
            instanceNonNull()::class.java.classLoader,
            arrayOf(method.parameterTypes[0])
        ) { _, _, _ ->
            callback()
        })
    }
}
