package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.dataBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hookConstructor

class DisableSnapModeRestrictions: Feature("Disable Snap Mode Restrictions") {
    override fun init() {
        if (!context.config.messaging.disableSnapModeRestrictions.get()) return

        findClass("com.snapchat.client.messaging.SnapModeInfo").hookConstructor(HookStage.AFTER) { param ->
            param.thisObject<Any>().dataBuilder {
                set("mSelfDestructSnapDurationMs", null)
            }
        }
    }
}