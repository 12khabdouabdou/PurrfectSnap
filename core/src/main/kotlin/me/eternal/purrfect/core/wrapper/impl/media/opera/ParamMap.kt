package me.eternal.purrfect.core.wrapper.impl.media.opera

import me.eternal.purrfect.common.util.ktx.findFields
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.wrapper.AbstractWrapper
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

@Suppress("UNCHECKED_CAST")
class ParamMap(obj: Any?) : AbstractWrapper(obj) {
    companion object {
        private val classFieldCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())
    }

    val paramMapField: Field? get() {
        val targetClass = instance?.javaClass ?: return null
        classFieldCache[targetClass]?.let { return it }
        val field = targetClass.findFields(once = true) {
            Map::class.java.isAssignableFrom(it.type)
        }.firstOrNull() ?: targetClass.findFields(once = true) {
            runCatching { it.get(instance) }.getOrNull() is Map<*, *>
        }.firstOrNull()
        if (field != null) {
            classFieldCache[targetClass] = field
        }
        return field
    }

    val concurrentHashMap: MutableMap<Any, Any>
        get() {
            val fieldName = paramMapField?.name ?: return mutableMapOf()
            val rawValue = runCatching { instance?.getObjectField(fieldName) }.getOrNull()
            return (rawValue as? MutableMap<Any, Any>) ?: mutableMapOf()
        }

    operator fun get(key: String): Any? {
        val map = concurrentHashMap
        return map.keys.firstOrNull { k: Any -> k.toString() == key }?.let { map[it] }
    }

    fun put(key: String, value: Any) {
        val map = concurrentHashMap
        val keyObject = map.keys.firstOrNull { k: Any -> k.toString() == key } ?: key
        map[keyObject] = value
    }

    fun containsKey(key: String): Boolean {
        val map = concurrentHashMap
        return map.keys.any { k: Any -> k.toString() == key }
    }

    fun getStoryIdentity(): String? {
        return this["STORY_ID"]?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: this["TOPIC_SNAP_CREATOR_USER_ID"]?.toString()
                ?.takeIf { it.isNotBlank() && it != "null" }
            ?: this["STORY_SNAP_ID"]?.toString()
                ?.substringBefore("_")
                ?.takeIf { it.isNotBlank() && it != "null" }
            ?: this["PLAYLIST_V2_GROUP"]?.toString()
                ?.substringAfter("storyUserId=", "")
                ?.substringBefore(",")
                ?.takeIf { it.isNotBlank() && it != "null" }
            ?: this["PLAYABLE_STORY_SNAP_RECORD"]?.toString()
                ?.substringAfter("storyUserId=", "")
                ?.substringBefore(",")
                ?.takeIf { it.isNotBlank() && it != "null" }
    }

    fun getStorySnapIndex(): Int? {
        return (this["STORY_SNAP_INDEX"] as? Int)
            ?: (this["snap_index_in_story"]?.toString()?.toIntOrNull())
            ?: (this["SNAP_POSITION_IN_STORY"]?.toString()?.toIntOrNull())
            ?: (this["REPLAYABLE_STORY_SNAP_RECORD"]?.toString()?.substringAfter("snapIndex=", "")?.substringBefore(",")?.toIntOrNull())
    }

    fun getStorySnapTotal(): Int {
        return (this["STORY_SNAP_TOTAL"] as? Int)
            ?: (this["snap_story_length"]?.toString()?.toIntOrNull())
            ?: (this["NUM_SNAPS_IN_STORY"]?.toString()?.toIntOrNull())
            ?: 0
    }

    override fun toString(): String {
        return concurrentHashMap.toString()
    }
}
