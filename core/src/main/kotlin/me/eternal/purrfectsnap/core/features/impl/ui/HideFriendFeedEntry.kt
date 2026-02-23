package me.eternal.purrfectsnap.core.features.impl.ui

import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.common.data.RuleState

import me.eternal.purrfectsnap.core.features.MessagingRuleFeature
import me.eternal.purrfectsnap.core.util.dataBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID
import me.eternal.purrfectsnap.mapper.impl.CallbackMapper

class HideFriendFeedEntry : MessagingRuleFeature("HideFriendFeedEntry", ruleType = MessagingRuleType.HIDE_FRIEND_FEED) {
    private fun createDeletedFeedEntry(conversationIdInstance: Any) = findClass("com.snapchat.client.messaging.DeletedFeedEntry").dataBuilder {
        from("mFeedEntryIdentifier") {
            set("mConversationId", conversationIdInstance)
        }
        set("mReason", "CLEAR_CONVERSATION")
    }

    private fun filterFriendFeed(entries: ArrayList<Any>, deletedEntries: ArrayList<Any>? = null) {
        entries.removeIf { feedEntry ->
            val conversationIdInstance = feedEntry.getObjectField("mConversationId") ?: return@removeIf false
            if (canUseRule(SnapUUID(conversationIdInstance).toString())) {
                deletedEntries?.add(createDeletedFeedEntry(conversationIdInstance)!!)
                true
            } else {
                false
            }
        }
    }

    override fun init() {
        if (!context.config.userInterface.hideFriendFeedEntry.get()) return

        context.mappings.useMapper(CallbackMapper::class) {
            arrayOf(
                "FetchAndSyncFeedWithConversationIdsCallback" to "onFetchAndSyncFeedComplete",
                "FetchFeedCallback" to "onFetchFeedComplete",
                "FetchFeedEntriesCallback" to "onFetchFeedEntriesComplete",
                "QueryFeedCallback" to "onQueryFeedComplete",
                "FeedManagerDelegate" to "onFeedEntriesUpdated",
                "FeedManagerDelegate" to "onInternalSyncFeed",
            ).forEach { (callbackName, methodName) ->
                findClass(callbacks.get()!![callbackName] ?: return@forEach).hook(methodName, HookStage.BEFORE) { param ->
                    filterFriendFeed(param.arg(0))
                }
            }

            callbacks.getAsMap()?.entries?.firstOrNull { it.key.startsWith("FetchAndSyncFeed") && it.key.endsWith("Callback") }
                ?.value
                ?.let { findClass(it) }
                ?.hook("onFetchAndSyncFeedComplete", HookStage.BEFORE) { param ->
                    val deletedConversations: ArrayList<Any> = param.arg(2)
                    filterFriendFeed(param.arg(0), deletedConversations)

                    if (deletedConversations.any {
                            val uuid = SnapUUID(it.getObjectField("mFeedEntryIdentifier")?.getObjectField("mConversationId")).toString()
                            context.database.getFeedEntryByConversationId(uuid) != null
                        }) {
                        param.setArg(4, true)
                    }
                } ?: context.log.warn("Failed to hook FetchAndSyncFeedCallback")
            callbacks.getClass("SyncFeedCallback")
                ?.hook("onSyncFeedComplete", HookStage.BEFORE) { param ->
                    filterFriendFeed(param.arg(0), param.arg(2))
                } ?: context.log.warn("Failed to hook SyncFeedCallback")
        }
    }

    override fun getRuleState() = RuleState.WHITELIST
}
