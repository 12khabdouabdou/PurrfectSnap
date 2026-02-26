package me.eternal.purrfectsnap.core.features.impl.ui

import android.view.KeyEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class DefaultVolumeControls : Feature("Default Volume Controls") {
    override fun init() {
        if (!context.config.global.defaultVolumeControls.get()) return
        onNextActivityCreate { activity ->
            activity::class.java.hook("onKeyDown", HookStage.BEFORE) { param ->
                val keyCode = param.arg<Int>(0)
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    param.setResult(false)
                }
            }
        }
    }
}