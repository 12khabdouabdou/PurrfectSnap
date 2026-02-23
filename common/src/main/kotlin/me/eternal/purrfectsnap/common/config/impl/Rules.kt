package me.eternal.purrfectsnap.common.config.impl

import me.eternal.purrfectsnap.common.config.ConfigContainer
import me.eternal.purrfectsnap.common.config.PropertyValue
import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.common.data.RuleState


class Rules : ConfigContainer() {
    private val rules = mutableMapOf<MessagingRuleType, PropertyValue<String>>()

    fun getRuleState(ruleType: MessagingRuleType): RuleState? {
        return rules[ruleType]?.getNullable()?.let { RuleState.getByName(it) }
    }

    init {
        MessagingRuleType.entries.filter { it.listMode }.forEach { ruleType ->
            rules[ruleType] = unique(ruleType.key,"whitelist", "blacklist") {
                customTranslationPath = "rules.properties.${ruleType.key}"
                customOptionTranslationPath = "rules.modes"
                addNotices(*ruleType.configNotices)
                requireRestart()
            }.apply {
                set(ruleType.defaultValue)
            }
        }
    }
}
