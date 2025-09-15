package me.rhunk.snapenhance.core.ui.menu.impl

import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderAddFriendIconId by lazy {
        context.resources.getIdentifier("hova_header_add_friend_icon", "id", "com.snapchat.android")
    }

    private val gearIconId = View.generateViewId()

    override fun init() {
        if (context.config.userInterface.settingsMenu.get() != "legacy") return

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderAddFriendIconId) {
                val parent = event.parent as? RelativeLayout ?: return@subscribe
                if (parent.findViewById<View>(gearIconId) != null) {
                    return@subscribe
                }

                val gearIcon = TextView(parent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                    setOnClickListener {
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                    setPadding(15, 15, 15, 15)
                }

                val layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.LEFT_OF, event.view.id)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                   // marginEnd = this@SettingsGearInjector.context.userInterface.dpToPx(12)
                }

                parent.addView(gearIcon, layoutParams)
            }
        }
    }
}
