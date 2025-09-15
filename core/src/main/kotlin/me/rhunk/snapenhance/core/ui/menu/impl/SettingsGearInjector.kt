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

    private fun logInfo(message: String) {
        context.logger.log("SettingsGearInjector", message)
    }

    override fun init() {
        logInfo("Initializing")
        if (context.config.userInterface.settingsMenu.get() != "legacy") {
            logInfo("Settings menu is not legacy, aborting init.")
            return
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderAddFriendIconId) {
                logInfo("AddViewEvent triggered for hova_header_add_friend_icon")
                val parent = event.parent as? FrameLayout ?: return@subscribe
                if (parent.findViewById<View>(gearIconId) != null) {
                    logInfo("Gear icon already exists, skipping.")
                    return@subscribe
                }

                logInfo("Creating and adding gear icon.")
                val gearIcon = TextView(parent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                    setOnClickListener {
                        logInfo("Gear icon clicked.")
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
                logInfo("Gear icon added to parent. Posting position update.")

                event.view.post {
                    try {
                        logInfo("Running position update.")
                        val addFriendIcon = event.view
                        val friendIconParams = addFriendIcon.layoutParams as ViewGroup.MarginLayoutParams
                        val friendIconMarginEnd = friendIconParams.marginEnd
                        val friendIconWidth = addFriendIcon.width

                        logInfo("Friend icon details: width=$friendIconWidth, marginEnd=$friendIconMarginEnd, paddingStart=${addFriendIcon.paddingStart}")

                        val newMargin = friendIconWidth + friendIconMarginEnd + (addFriendIcon.paddingStart / 2)
                        logInfo("Calculated new marginEnd for gear icon: $newMargin")

                        (gearIcon.layoutParams as FrameLayout.LayoutParams).apply {
                            marginEnd = newMargin
                        }.also {
                            gearIcon.layoutParams = it
                        }
                        logInfo("Successfully updated gear icon position.")
                    } catch (t: Throwable) {
                        context.logger.logError("SettingsGearInjector", t)
                    }
                }
            }
        }
    }
}
