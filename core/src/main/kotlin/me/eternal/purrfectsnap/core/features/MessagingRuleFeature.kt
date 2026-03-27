package me.eternal.purrfectsnap.core.features

import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.common.data.RuleState

abstract class MessagingRuleFeature(name: String, val ruleType: MessagingRuleType) : Feature(name) {
    private val listeners = mutableListOf<(String, Boolean) -> Unit>()

    fun addStateListener(listener: (conversationId: String, newState: Boolean) -> Unit) {
        listeners.add(listener)
    }

    open fun getRuleState() = context.config.rules.getRuleState(ruleType)

    fun setState(conversationId: String, state: Boolean) {
        context.bridgeClient.setRule(
            context.database.getDMOtherParticipant(conversationId) ?: conversationId,
            ruleType,
            state
        )
        listeners.forEach { it(conversationId, state) }
    }

    fun getState(conversationId: String) =
        context.bridgeClient.getRules(
            context.database.getDMOtherParticipant(conversationId) ?: conversationId
        ).contains(ruleType) && getRuleState() != null

    fun canUseRule(conversationId: String): Boolean {
        if (ruleType.key == "translation" && context.config.messaging.instantTranslation.globalState != true) {
            return false
        }
        val state = getState(conversationId)
        if (context.config.rules.getRuleState(ruleType) == RuleState.BLACKLIST) {
            return !state
        }
        return state
    }

    override fun onBridgeAction(action: String, extras: Map<String, Any>?, callback: (Any?) -> Unit) {
        if (action == "get_state") {
            val conversationId = extras?.get("conversationId") as? String ?: return
            callback(getState(conversationId))
            return
        }
        if (action == "set_state") {
            val conversationId = extras?.get("conversationId") as? String ?: return
            val state = extras["state"] as? Boolean ?: return
            setState(conversationId, state)
            callback(true)
            return
        }
        super.onBridgeAction(action, extras, callback)
    }
}
