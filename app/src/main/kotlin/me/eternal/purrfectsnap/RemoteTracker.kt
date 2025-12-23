package me.eternal.purrfectsnap

import me.eternal.purrfectsnap.bridge.logger.TrackerInterface
import me.eternal.purrfectsnap.common.data.ScopedTrackerRule
import me.eternal.purrfectsnap.common.data.TrackerEventsResult
import me.eternal.purrfectsnap.common.data.TrackerRule
import me.eternal.purrfectsnap.common.data.TrackerRuleEvent
import me.eternal.purrfectsnap.common.util.toSerialized
import me.eternal.purrfectsnap.storage.getRuleTrackerScopes
import me.eternal.purrfectsnap.storage.getTrackerEvents
import me.eternal.purrfectsnap.storage.updateFriendScore


class RemoteTracker(
    private val context: RemoteSideContext
): TrackerInterface.Stub() {
    fun init() {}

    override fun getTrackedEvents(eventType: String): String? {
        val events = mutableMapOf<TrackerRule, MutableList<TrackerRuleEvent>>()

        context.database.getTrackerEvents(eventType).forEach { (event, rule) ->
            events.getOrPut(rule) { mutableListOf() }.add(event)
        }

        return TrackerEventsResult(events.mapKeys {
            ScopedTrackerRule(it.key, context.database.getRuleTrackerScopes(it.key.id))
        }).toSerialized()
    }

    override fun updateFriendScore(userId: String, score: Long): Long {
        return context.database.updateFriendScore(userId, score)
    }
}