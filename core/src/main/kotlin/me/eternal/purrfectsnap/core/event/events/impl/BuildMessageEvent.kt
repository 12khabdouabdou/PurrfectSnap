package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.Event
import me.eternal.purrfectsnap.core.wrapper.impl.Message

class BuildMessageEvent(
    val message: Message
): Event()