package me.rhunk.snapenhance.core.ui.menu.impl

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu
import me.rhunk.snapenhance.core.util.ktx.getDrawable
import me.rhunk.snapenhance.core.util.ktx.getStyledAttributes

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderAddFriendIconId by lazy {
        this@SettingsGearInjector.context.resources.getIdentifier("hova_header_add_friend_icon", "id", "com.snapchat.android")
    }

    private val gearIconId = View.generateViewId()
    private val logTag = "SettingsGearInjector"

    override fun init() {
        this@SettingsGearInjector.context.log.info("Initializing", logTag)
        if (this@SettingsGearInjector.context.config.userInterface.settingsMenu.get() != "legacy") {
            this@SettingsGearInjector.context.log.info("Settings menu is not legacy, aborting init.", logTag)
            return
        }

        this@SettingsGearInjector.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderAddFriendIconId) {
                this@SettingsGearInjector.context.log.info("AddViewEvent triggered for hova_header_add_friend_icon", logTag)
                val parent = event.parent as? FrameLayout ?: return@subscribe
                if (parent.findViewById<View>(gearIconId) != null) {
                    this@SettingsGearInjector.context.log.info("Gear icon already exists, skipping.", logTag)
                    return@subscribe
                }

                this@SettingsGearInjector.context.log.info("Creating and adding gear icon.", logTag)
                val gearIcon = FrameLayout(parent.context).apply {
                    id = gearIconId
                    setOnClickListener {
                        this@SettingsGearInjector.context.log.info("Gear icon clicked.", logTag)
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }

                    val imageView = ImageView(context).apply {
                        val resources = this@SettingsGearInjector.context.resources
                        val theme = this@SettingsGearInjector.context.androidContext.theme
                        setImageDrawable(resources.getDrawable("svg_settings_32x32", theme))
                        resources.getStyledAttributes("headerButtonOpaqueIconTint", theme).getColorStateList(0)?.let {
                            imageTintList = it
                        }
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            gravity = android.view.Gravity.CENTER
                        }
                    }
                    addView(imageView)
                }

                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                }

                parent.addView(gearIcon, layoutParams)
                this@SettingsGearInjector.context.log.info("Gear icon added to parent. Posting position and size update.", logTag)

                event.view.post {
                    try {
                        this@SettingsGearInjector.context.log.info("Running position and size update.", logTag)
                        val addFriendIcon = event.view
                        gearIcon.background = addFriendIcon.background
                        gearIcon.backgroundTintList = addFriendIcon.backgroundTintList

                        val friendIconParams = addFriendIcon.layoutParams as ViewGroup.MarginLayoutParams
                        val friendIconMarginEnd = friendIconParams.marginEnd
                        val friendIconWidth = addFriendIcon.width
                        val friendIconHeight = addFriendIcon.height

                        this@SettingsGearInjector.context.log.info("Friend icon details: width=$friendIconWidth, height=$friendIconHeight, marginEnd=$friendIconMarginEnd", logTag)

                        val newMargin = friendIconWidth + friendIconMarginEnd + this@SettingsGearInjector.context.userInterface.dpToPx(4)
                        this@SettingsGearInjector.context.log.info("Calculated new marginEnd for gear icon: $newMargin", logTag)

                        (gearIcon.layoutParams as FrameLayout.LayoutParams).apply {
                            height = friendIconHeight
                            width = friendIconHeight // Make it a square
                            marginEnd = newMargin
                        }.also {
                            gearIcon.layoutParams = it
                        }
                        this@SettingsGearInjector.context.log.info("Successfully updated gear icon position and size.", logTag)
                    } catch (t: Throwable) {
                        this@SettingsGearInjector.context.log.error("Failed to position or size gear icon", t, logTag)
                    }
                }
            }
        }
    }
}
