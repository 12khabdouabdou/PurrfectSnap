package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.events.AbstractHookEvent
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID

class OnSnapInteractionEvent(
    val interactionType: String,
    val conversationId: SnapUUID,
    val messageId: Long
) : AbstractHookEvent()