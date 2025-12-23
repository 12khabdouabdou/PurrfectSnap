package me.eternal.purrfectsnap.core.features.impl.tweaks

import android.media.AudioManager
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class HideActiveMusic: Feature("Hide Active Music") {
    override fun init() {
        if (!context.config.global.hideActiveMusic.get()) return
        onNextActivityCreate {
            AudioManager::class.java.hook("isMusicActive", HookStage.BEFORE) {
                it.setResult(false)
            }
        }
    }
}