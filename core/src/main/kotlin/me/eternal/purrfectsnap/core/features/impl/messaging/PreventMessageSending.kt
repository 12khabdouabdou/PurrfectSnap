package me.eternal.purrfectsnap.core.features.impl.messaging

import me.eternal.purrfectsnap.common.data.NotificationType
import me.eternal.purrfectsnap.common.util.protobuf.ProtoEditor
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfectsnap.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfectsnap.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class PreventMessageSending : Feature("Prevent message sending") {
    override fun init() {
        val preventMessageSending by context.config.messaging.preventMessageSending

        fun handleUpdateContentMessage(uri: String, buffer: ByteArray): ByteArray? {
            if (uri != "/messagingcoreservice.MessagingCoreService/UpdateContentMessage") return null
            return ProtoEditor(buffer).apply {
                edit(3) {
                    // replace replayed to read receipt
                    if (firstOrNull(13) != null) {
                        remove(13)
                        addBuffer(4, byteArrayOf())
                    }
                }
            }.toByteArray()
        }

        fun handleCreateContentMessage(uri: String, buffer: ByteArray): Boolean {
            if (uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return false
            val reader = ProtoReader(buffer)
            // check for missed audio/video call in MessageContent (field 4)
            val contentReader = reader.followPath(4) ?: return false

            // try both possible field IDs based on SnapEnums and ContentType.java
            val contentType = contentReader.getVarInt(2)

            val isMissedAudio = contentType == 13L || contentType == 18L
            val isMissedVideo = contentType == 12L || contentType == 17L

            if (isMissedAudio && preventMessageSending.contains("abandon_audio")) return true
            if (isMissedVideo && preventMessageSending.contains("abandon_video")) return true

            return false
        }

        arrayOf(NativeUnaryCallEvent::class, UnaryCallEvent::class).forEach { eventClass ->
            context.event.subscribe(eventClass) { event ->
                val uri = if (event is NativeUnaryCallEvent) event.uri else (event as UnaryCallEvent).uri
                if (!uri.startsWith("/messagingcoreservice.MessagingCoreService/")) return@subscribe

                if (handleCreateContentMessage(uri, event.buffer)) {
                    event.canceled = true
                }
                handleUpdateContentMessage(uri, event.buffer)?.let { event.buffer = it }
            }
        }

        context.classCache.conversationManager.hook("updateMessage", HookStage.BEFORE) { param ->
            val messageUpdate = param.arg<Any>(2).toString()
            if (messageUpdate == "SCREENSHOT" && preventMessageSending.contains("chat_screenshot")) {
                param.setResult(null)
            }

            if (messageUpdate == "SCREEN_RECORD" && preventMessageSending.contains("chat_screen_record")) {
                param.setResult(null)
            }

            if ((messageUpdate == "REPLAY" || messageUpdate == "replay") && preventMessageSending.contains("snap_replay")) {
                param.setResult(null)
            }
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            val contentType = event.messageContent.contentType
            val associatedType = NotificationType.fromContentType(contentType ?: return@subscribe) ?: return@subscribe

            if (preventMessageSending.contains(associatedType.key)) {
                event.canceled = true
            }
        }
    }
}
