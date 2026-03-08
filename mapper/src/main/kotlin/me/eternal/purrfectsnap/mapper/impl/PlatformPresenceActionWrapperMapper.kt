package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.getClassName

class PlatformPresenceActionWrapperMapper : AbstractClassMapper("PlatformPresenceActionWrapper") {
    val classReference = classReference("class")

    init {
        mapper {
            classes.firstOrNull { classDef ->
                classDef.fields.any { field ->
                    field.type == "Lcom/snap/presence/PlatformChatVisibleAction;"
                }
            }?.let { classReference.set(it.getClassName()) }
        }
    }
}
