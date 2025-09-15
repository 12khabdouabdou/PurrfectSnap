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
    private val logTag = "SettingsGearInjector"

    override fun init() {
        context.log.info("Initializing", logTag)
        if (context.config.userInterface.settingsMenu.get() != "legacy") {
            context.log.info("Settings menu is not legacy, aborting init.", logTag)
            return
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderAddFriendIconId) {
                context.log.info("AddViewEvent triggered for hova_header_add_friend_icon", logTag)
                val parent = event.parent as? FrameLayout ?: return@subscribe
                if (parent.findViewById<View>(gearIconId) != null) {
                    context.log.info("Gear icon already exists, skipping.", logTag)
                    return@subscribe
                }

                context.log.info("Creating and adding gear icon.", logTag)
                val gearIcon = TextView(parent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                    setOnClickListener {
                        context.log.info("Gear icon clicked.", logTag)
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
                context.log.info("Gear icon added to parent. Posting position update.", logTag)

                event.view.post {
                    try {
                        context.log.info("Running position update.", logTag)
                        val addFriendIcon = event.view
                        val friendIconParams = addFriendIcon.layoutParams as ViewGroup.MarginLayoutParams
                        val friendIconMarginEnd = friendIconParams.marginEnd
                        val friendIconWidth = addFriendIcon.width

                        context.log.info("Friend icon details: width=$friendIconWidth, marginEnd=$friendIconMarginEnd, paddingStart=${addFriendIcon.paddingStart}", logTag)

                        val newMargin = friendIconWidth + friendIconMarginEnd + (addFriendIcon.paddingStart / 2)
                        context.log.info("Calculated new marginEnd for gear icon: $newMargin", logTag)

                        (gearIcon.layoutParams as FrameLayout.LayoutParams).apply {
                            marginEnd = newMargin
                        }.also {
                            gearIcon.layoutParams = it
                        }
                        context.log.info("Successfully updated gear icon position.", logTag)
                    } catch (t: Throwable) {
                        context.log.error("Failed to position gear icon", t, logTag)
                    }
                }
            }
        }
    }
}
