package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.events.AbstractHookEvent

class UnaryCallEvent(
    val uri: String,
    var buffer: ByteArray
): AbstractHookEvent() {
    val callbacks = mutableListOf<(UnaryCallEvent) -> Unit>()

    fun addResponseCallback(responseCallback: UnaryCallEvent.() -> Unit) {
        callbacks.add(responseCallback)
    }
}