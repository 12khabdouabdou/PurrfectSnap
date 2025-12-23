package me.eternal.purrfectsnap.core.wrapper.impl.composer

import me.eternal.purrfectsnap.core.PurrfectSnap
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.lang.reflect.Proxy

fun createComposerFunction(block: (args: Array<*>) -> Any?): Any {
    val adapter = PurrfectSnap.classCache.composerFunctionActionAdapter
        ?: throw IllegalStateException("composerFunctionActionAdapter not available")
    val action = PurrfectSnap.classCache.composerAction
        ?: throw IllegalStateException("composerAction not available")

    return adapter.constructors.first().newInstance(
        Proxy.newProxyInstance(
            action.classLoader,
            arrayOf(action),
        ) { _, _, args ->
            block(args?.get(0) as Array<*>)
        }
    )
}

class ComposerViewNode(obj: Long) : AbstractWrapper(obj) {
    companion object {
        fun fromNode(composerViewNode: Any?): ComposerViewNode? {
            return (composerViewNode?.javaClass?.methods?.firstOrNull {
                it.name == "getNativeHandle"
            }?.invoke(composerViewNode) as? Long)?.let { ComposerViewNode(it) } ?: return null
        }
    }

    fun getAttribute(name: String): Any? {
        return PurrfectSnap.classCache.nativeBridge?.methods?.firstOrNull {
            it.name == "getValueForAttribute"
        }?.invoke(null, instanceNonNull(), name)
    }

    fun setAttribute(name: String, value: Any) {
        PurrfectSnap.classCache.nativeBridge?.methods?.firstOrNull {
            it.name == "setValueForAttribute"
        }?.invoke(null, instanceNonNull(), name, value, false)
    }

    fun getChildren(): List<ComposerViewNode> {
        val children = PurrfectSnap.classCache.nativeBridge?.methods?.firstOrNull {
            it.name == "getRetainedViewNodeChildren"
        }?.invoke(null, instanceNonNull(), 1) as? LongArray ?: return emptyList()
        return children.map { ComposerViewNode(it) }
    }

    fun getClassName(): String {
        return PurrfectSnap.classCache.nativeBridge?.methods?.firstOrNull {
            it.name == "getViewClassName"
        }?.invoke(null, instanceNonNull())?.toString() ?: ""
    }

    override fun toString(): String {
        return PurrfectSnap.classCache.nativeBridge?.methods?.firstOrNull {
            it.name == "getViewNodeDebugDescription"
        }?.invoke(null, instanceNonNull())?.toString() ?: ""
    }
}
