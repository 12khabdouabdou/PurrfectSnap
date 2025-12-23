package me.eternal.purrfectsnap.core.event.events.impl

import me.eternal.purrfectsnap.core.event.events.AbstractHookEvent
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.Hooker
import me.eternal.purrfectsnap.core.wrapper.impl.MessageContent
import me.eternal.purrfectsnap.core.wrapper.impl.MessageDestinations

class SendMessageWithContentEvent(
    val destinations: MessageDestinations,
    val messageContent: MessageContent,
    private val callback: Any
) : AbstractHookEvent() {

    fun addCallbackResult(methodName: String, block: (args: Array<Any?>) -> Unit) {
        Hooker.ephemeralHookObjectMethod(
            callback::class.java,
            callback,
            methodName,
            HookStage.BEFORE
        ) { block(it.args()) }
    }
}