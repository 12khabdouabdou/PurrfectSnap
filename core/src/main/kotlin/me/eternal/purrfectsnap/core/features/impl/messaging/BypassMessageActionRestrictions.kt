package me.eternal.purrfectsnap.core.features.impl.messaging

import me.eternal.purrfectsnap.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfectsnap.core.features.Feature

class BypassMessageActionRestrictions : Feature("Bypass Message Action Restrictions") {
    override fun init() {
        if (!context.config.messaging.bypassMessageActionRestrictions.get()) return
        onNextActivityCreate {
            context.event.subscribe(BuildMessageEvent::class, priority = 102) { event ->
                event.message.messageMetadata?.apply {
                    isSaveable = true
                    isReactable = true
                }
            }
        }
    }
}