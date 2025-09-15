package me.rhunk.snapenhance.core.ui.menu.impl

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderAddFriendIconId by lazy {
        context.resources.getIdentifier("hova_header_add_friend_icon", "id", "com.snapchat.android")
    }

    private val gearIconId = View.generateViewId()

    private fun log(message: String) {
        context.logger.log("SettingsGearInjector", message)
    }

    override fun init() {
        log("Initializing")
        if (context.config.userInterface.settingsMenu.get() != "legacy") {
            log("Settings menu is not legacy, aborting init.")
            return
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderAddFriendIconId) {
                log("AddViewEvent triggered for hova_header_add_friend_icon")
                val parent = event.parent as? FrameLayout ?: return@subscribe
                if (parent.findViewById<View>(gearIconId) != null) {
                    log("Gear icon already exists, skipping.")
                    return@subscribe
                }

                log("Creating and adding gear icon.")
                val gearIcon = TextView(parent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                    setOnClickListener {
                        log("Gear icon clicked.")
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                    setPadding(15, 15, 15, 15)
                }

                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                }

                parent.addView(gearIcon, layoutParams)
                log("Gear icon added to parent. Posting position update.")

                event.view.post {
                    try {
                        log("Running position update.")
                        val addFriendIcon = event.view
                        val friendIconParams = addFriendIcon.layoutParams as ViewGroup.MarginLayoutParams
                        val friendIconMarginEnd = friendIconParams.marginEnd
                        val friendIconWidth = addFriendIcon.width

                        log("Friend icon details: width=$friendIconWidth, marginEnd=$friendIconMarginEnd, paddingStart=${addFriendIcon.paddingStart}")

                        val newMargin = friendIconWidth + friendIconMarginEnd + (addFriendIcon.paddingStart / 2)
                        log("Calculated new marginEnd for gear icon: $newMargin")

                        (gearIcon.layoutParams as FrameLayout.LayoutParams).apply {
                            marginEnd = newMargin
                        }.also {
                            gearIcon.layoutParams = it
                        }
                        log("Successfully updated gear icon position.")
                    } catch (t: Throwable) {
                        context.logger.log("SettingsGearInjector", "Failed to position gear icon", t)
                    }
                }
            }
        }
    }
}
