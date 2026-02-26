package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.events.AbstractHookEvent
import me.eternal.purrfectsnap.core.wrapper.impl.Message

class ConversationUpdateEvent(
    val conversationId: String,
    val conversation: Any?,
    val messages: List<Message>
) : AbstractHookEvent()