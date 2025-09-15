package me.rhunk.snapenhance.core.ui.menu.impl

import android.widget.FrameLayout
import android.widget.ImageView
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderSearchIconId by lazy {
        context.resources.getIdentifier("hova_header_search_icon", "id", "com.snapchat.android")
    }

    private val gearIconId by lazy {
        context.resources.getIdentifier("ic_settings_gear", "drawable", Constants.SNAPENHANCE_PACKAGE_NAME)
    }

    override fun init() {
        if (context.config.userInterface.settingsMenu.get() != "legacy") return

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                val parent = event.parent as? FrameLayout ?: return@subscribe
                val gearIcon = ImageView(parent.context).apply {
                    id = hovaHeaderSearchIconId + 1
                    setImageResource(gearIconId)
                    setOnClickListener {
                        context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                }
                parent.addView(gearIcon, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    marginEnd = 100
                })
            }
        }
    }
}
