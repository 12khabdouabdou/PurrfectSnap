package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.events.AbstractHookEvent

class NativeUnaryCallEvent(
    val uri: String,
    var buffer: ByteArray
) : AbstractHookEvent()