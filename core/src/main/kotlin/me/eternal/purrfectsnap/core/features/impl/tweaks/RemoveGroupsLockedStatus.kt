package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.dataBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hookConstructor

class RemoveGroupsLockedStatus : Feature("Remove Groups Locked Status") {
    override fun init() {
        if (!context.config.messaging.removeGroupsLockedStatus.get()) return
        onNextActivityCreate(defer = true) {
            context.classCache.conversation.hookConstructor(HookStage.AFTER) { param ->
                param.thisObject<Any>().dataBuilder {
                    set("mLockedState", "UNLOCKED")
                }
            }
        }
    }
}