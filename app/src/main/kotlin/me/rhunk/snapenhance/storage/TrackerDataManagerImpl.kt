package me.rhunk.snapenhance.storage

import me.rhunk.snapenhance.common.data.ExportedTrackerData
import me.rhunk.snapenhance.common.data.TrackerDataManager
import me.rhunk.snapenhance.storage.AppDatabase

class TrackerDataManagerImpl(private val db: AppDatabase) : TrackerDataManager {
    override fun getExportedTrackerData(): ExportedTrackerData {
        return ExportedTrackerData(
            rules = db.getTrackerRulesDesc().map { rule ->
                rule.copy(
                    events = db.getTrackerEvents(rule.id),
                    scopes = db.getRuleTrackerScopes(rule.id)
                )
            }
        )
    }

    override fun importTrackerData(data: ExportedTrackerData) {
        db.clearTrackerRules()
        data.rules.forEach { rule ->
            val ruleId = db.newTrackerRule(rule.name, rule.author)
            db.setTrackerRuleState(ruleId, rule.enabled)
            rule.events?.forEach { event ->
                db.addOrUpdateTrackerRuleEvent(
                    ruleId = ruleId,
                    eventType = event.eventType,
                    params = event.params,
                    actions = event.actions
                )
            }
            rule.scopes?.forEach { (scopeId, scopeType) ->
                db.setRuleTrackerScopes(ruleId, scopeType, listOf(scopeId))
            }
        }
    }
}
