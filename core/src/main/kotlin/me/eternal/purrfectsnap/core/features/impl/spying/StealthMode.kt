package me.eternal.purrfectsnap.core.features.impl.spying

import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.core.event.events.impl.OnSnapInteractionEvent
import me.eternal.purrfectsnap.core.features.MessagingRuleFeature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID
import java.util.concurrent.CopyOnWriteArraySet

class StealthMode : MessagingRuleFeature("StealthMode", MessagingRuleType.STEALTH) {
    private val displayedMessageQueue = CopyOnWriteArraySet<Long>()
    private val snapInteractionQueue = CopyOnWriteArraySet<Long>()

    fun addDisplayedMessageException(clientMessageId: Long) {
        displayedMessageQueue.add(clientMessageId)
    }

    fun addSnapInteractionException(messageId: Long) {
        snapInteractionQueue.add(messageId)
    }


    override fun init() {
        if (getRuleState() == null) return
        val isConversationInStealthMode: (SnapUUID) -> Boolean = { canUseRule(it.toString()) }

        arrayOf("mediaMessagesDisplayed", "displayedMessages").forEach { methodName: String ->
            context.classCache.conversationManager.hook(methodName, HookStage.BEFORE) { param ->
                if (displayedMessageQueue.removeIf { param.arg<Long>(1) == it }) return@hook
                if (isConversationInStealthMode(SnapUUID(param.arg(0)))) {
                    param.setResult(null)
                }
            }
        }

        context.event.subscribe(OnSnapInteractionEvent::class) { event ->
            if (snapInteractionQueue.removeIf { event.messageId == it }) return@subscribe
            if (isConversationInStealthMode(event.conversationId)) {
                event.canceled = true
            }
        }
    }
}
