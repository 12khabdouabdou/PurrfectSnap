package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.getClassName

class ChatEventDispatcherMapper : AbstractClassMapper("ChatEventDispatcher")  {
    val classReference = classReference("class")

    init {
        mapper {
            for (clazz in classes) {
                if (clazz.methods.firstOrNull { it.name == "onChatItemDoubleClickEvent" } == null) continue
                classReference.set(clazz.getClassName())
                return@mapper
            }
        }
    }
}