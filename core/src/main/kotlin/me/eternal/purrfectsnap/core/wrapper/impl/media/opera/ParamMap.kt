package me.eternal.purrfectsnap.core.wrapper.impl.media.opera

import me.eternal.purrfectsnap.common.util.ktx.findFields
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNCHECKED_CAST")
class ParamMap(obj: Any?) : AbstractWrapper(obj) {
    val paramMapField: Field by lazy {
        instanceNonNull()::class.java.findFields(once = true) {
            it.type == ConcurrentHashMap::class.java || runCatching { it.get(instance) }.getOrNull() is ConcurrentHashMap<*, *>
        }.firstOrNull() ?: throw RuntimeException("Could not find paramMap field")
    }

    val concurrentHashMap: ConcurrentHashMap<Any, Any>
        get() = instanceNonNull().getObjectField(paramMapField.name) as ConcurrentHashMap<Any, Any>

    operator fun get(key: String): Any? {
        return concurrentHashMap.keys.firstOrNull{ k: Any -> k.toString() == key }?.let { concurrentHashMap[it] }
    }

    fun put(key: String, value: Any) {
        val keyObject = concurrentHashMap.keys.firstOrNull { k: Any -> k.toString() == key } ?: key
        concurrentHashMap[keyObject] = value
    }

    fun containsKey(key: String): Boolean {
        return concurrentHashMap.keys.any { k: Any -> k.toString() == key }
    }

    override fun toString(): String {
        return concurrentHashMap.toString()
    }
}
