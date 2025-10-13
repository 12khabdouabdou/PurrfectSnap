package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.common.data.MessagingRuleType
import me.rhunk.snapenhance.core.features.MessagingRuleFeature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.wrapper.impl.Message

class NotificationRule : MessagingRuleFeature(
    "Notification Rule",
    MessagingRuleType.NOTIFICATIONS
) {
    override fun init() {
        val notificationProcessor = findClass("com.snap.messaging.notification.DefaultNotificationProcessor")
        val createMethod = notificationProcessor.methods.first { it.name == "create" && it.parameterCount == 2 }

        createMethod.hook(HookStage.BEFORE) { param ->
            val message = Message(param.arg(0))
            val conversationId = message.messageDescriptor?.conversationId?.toString() ?: return@hook
            if (!canUseRule(conversationId)) {
                param.setResult(null)
            }
        }
    }
}