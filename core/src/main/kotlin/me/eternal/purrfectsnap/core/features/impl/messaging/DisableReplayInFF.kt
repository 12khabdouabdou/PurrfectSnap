package me.eternal.purrfectsnap.core.features.impl.messaging

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.util.ktx.setEnumField

class DisableReplayInFF : Feature("DisableReplayInFF") {
    override fun init() {
        val state by context.config.messaging.disableReplayInFF

        onNextActivityCreate(defer = true) {
            findClass("com.snapchat.client.messaging.InteractionInfo")
                .hookConstructor(HookStage.AFTER, { state }) { param ->
                    val instance = param.thisObject<Any>()
                    if (instance.getObjectField("mLongPressActionState").toString() == "REQUEST_SNAP_REPLAY") {
                        instance.setEnumField("mLongPressActionState", "SHOW_CONVERSATION_ACTION_MENU")
                    }
                }
        }
    }
}