package me.eternal.purrfectsnap.core.features.impl.messaging

import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.MessageState
import me.eternal.purrfectsnap.common.util.protobuf.ProtoEditor
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfectsnap.core.features.Feature

class UnlimitedSnapViewTime : Feature("UnlimitedSnapViewTime") {
    override fun init() {
        onNextActivityCreate {
            val state by context.config.messaging.unlimitedSnapViewTime

            context.event.subscribe(BuildMessageEvent::class, { state }, priority = 101) { event ->
                if (event.message.messageState != MessageState.COMMITTED) return@subscribe
                if (event.message.messageContent!!.contentType != ContentType.SNAP) return@subscribe

                val messageContent = event.message.messageContent

                val mediaAttributes = ProtoReader(messageContent!!.content!!).followPath(11, 5, 2) ?: return@subscribe
                if (mediaAttributes.contains(6)) return@subscribe
                messageContent.content = ProtoEditor(messageContent.content!!).apply {
                    edit(11, 5, 2) {
                        remove(8)
                        addBuffer(6, byteArrayOf())
                    }
                }.toByteArray()
            }
        }
    }
}
