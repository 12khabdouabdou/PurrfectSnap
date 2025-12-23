package me.eternal.purrfectsnap.core.ui.menu.impl

import android.view.View
import android.widget.FrameLayout
import me.eternal.purrfectsnap.common.ui.OverlayType
import me.eternal.purrfectsnap.core.ui.menu.AbstractMenu
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getId

class SettingsMenu : AbstractMenu() {
    private val hovaHeaderSearchIconId by lazy {
        context.resources.getId("hova_header_search_icon")
    }

    override fun init() {
        if (context.config.userInterface.settingsMenu.get() != "default") return
        context.androidContext.classLoader.loadClass("com.snap.ui.view.SnapFontTextView").hook("setText", HookStage.BEFORE) { param ->
            val view = param.thisObject<View>()
            if ((view.parent as? FrameLayout)?.findViewById<View>(hovaHeaderSearchIconId) != null) {
                view.post {
                    view.setOnClickListener {
                        context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                }
            }
        }
    }
}
