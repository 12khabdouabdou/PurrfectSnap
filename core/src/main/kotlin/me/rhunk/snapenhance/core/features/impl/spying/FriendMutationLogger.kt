package me.rhunk.snapenhance.core.features.impl.spying

import me.rhunk.snapenhance.common.util.lazyBridge
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.mapper.impl.FriendRelationshipChangerMapper

class FriendMutationLogger : Feature("FriendMutationLogger") {
    private val logger by lazyBridge { context.bridgeClient.getFriendMutationLogger() }

    override fun init() {
        context.mappings.useMapper(FriendRelationshipChangerMapper::class) {
            friendshipRelationshipChangerKtx.get()?.hook(
                addFriendMethod.get()!!,
                HookStage.BEFORE
            ) { param: HookAdapter ->
                val userId = param.arg<String>(1)
                val friendInfo = context.database.getFriendInfo(userId)
                val friendName = friendInfo?.displayName ?: friendInfo?.mutableUsername ?: userId
                logger.logFriendMutation("friend_added", friendName, "You added this friend")
            }

            classReference.get()?.hook(
                runFriendDurableJob.get()!!,
                HookStage.BEFORE
            ) { param: HookAdapter ->
                val userId = param.arg<String>(1)
                val friendInfo = context.database.getFriendInfo(userId)
                val friendName = friendInfo?.displayName ?: friendInfo?.mutableUsername ?: userId
                logger.logFriendMutation("friend_removed", friendName, "You removed this friend")
            }
        }
    }
}