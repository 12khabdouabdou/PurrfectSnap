package me.rhunk.snapenhance.core.ui.menu.impl

import android.graphics.PorterDuff
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.R
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderSearchIconId by lazy {
        this.context.resources.getIdentifier("hova_header_search_icon", "id", "com.snapchat.android")
    }

    override fun init() {
        if (this.context.config.userInterface.settingsMenu.get() != "legacy") return

        this.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                val parent = event.parent as? FrameLayout ?: return@subscribe

                if (parent.findViewById<View>(R.id.se_settings_gear_icon) != null) {
                    return@subscribe
                }

                val gearIcon = ImageView(parent.context).apply {
                    id = R.id.se_settings_gear_icon
                    setImageResource(R.drawable.ic_settings_gear)
                    setColorFilter(this@SettingsGearInjector.context.userInterface.colorPrimary, PorterDuff.Mode.SRC_IN)
                    setOnClickListener {
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                    setPadding(15, 15, 15, 15)
                }

                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    marginEnd = this@SettingsGearInjector.context.userInterface.dpToPx(16)
                }

                parent.addView(gearIcon, layoutParams)
            }
        }
    }
}
