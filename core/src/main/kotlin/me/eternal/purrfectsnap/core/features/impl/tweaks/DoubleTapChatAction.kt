package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.MessageUpdate
import me.eternal.purrfectsnap.common.util.ktx.copyToClipboard
import me.eternal.purrfectsnap.common.util.ktx.findFieldsToString
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.wrapper.impl.getMessageText
import me.eternal.purrfectsnap.mapper.impl.ChatEventDispatcherMapper

class DoubleTapChatAction: Feature("Double Tap Chat Action") {
    override fun init() {
        var action = context.config.messaging.doubleTapChatAction.getNullable() ?: return

        context.mappings.useMapper(ChatEventDispatcherMapper::class) {
            classReference.getAsClass()?.hook("onChatItemDoubleClickEvent", HookStage.BEFORE) { param ->
                param.setResult(null)
                val event = param.arg<Any>(0)
                val viewModel = event.javaClass.findFieldsToString(event, once = true) { field, value -> value.contains("ChatViewModel") }.firstOrNull()?.get(event)?.toString() ?: return@hook

                val (conversationId, _, clientMessageId) = viewModel.substringAfter("messageId=").substringBefore(",").split(":").takeIf { it.size == 3 } ?: return@hook

                val messageId = clientMessageId.toLongOrNull() ?: return@hook

                if (action == "like_message") {
                    context.feature(Messaging::class).conversationManager?.reactToMessage(
                        conversationId,
                        messageId,
                        intentionType = 1L,
                        onError = {},
                        onSuccess = {}
                    )
                }

                if (action == "copy_text") {
                    var messageContent = context.database.getConversationMessageFromId(messageId)?.messageContent ?: return@hook
                    var proto = ProtoReader(messageContent).followPath(4, 4) ?: return@hook
                    context.androidContext.copyToClipboard(proto.getBuffer().getMessageText(ContentType.fromMessageContainer(proto) ?: ContentType.CHAT) ?: return@hook, "Chat Message")
                }

                if (action == "delete_message" || action == "mark_as_read") {
                    context.feature(Messaging::class).conversationManager?.updateMessage(
                        conversationId,
                        messageId,
                        if (action == "delete_message") MessageUpdate.ERASE else MessageUpdate.READ,
                        onResult = {}
                    )
                }

                if (action == "custom_emoji_reaction") {
                    context.feature(Messaging::class).conversationManager?.reactToMessage(
                        conversationId,
                        messageId,
                        emoji = context.config.messaging.doubleTapChatActionCustomEmoji.getNullable()?.takeIf { it.isNotEmpty() } ?: "\uD83D\uDC4D",
                        onError = {},
                        onSuccess = {}
                    )
                }
            }
        }
    }
}