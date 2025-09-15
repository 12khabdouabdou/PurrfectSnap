package me.rhunk.snapenhance.core.ui.menu.impl

import android.graphics.PorterDuff
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderSearchIconId by lazy {
        this.context.resources.getIdentifier("hova_header_search_icon", "id", "com.snapchat.android")
    }

    private val gearIconId by lazy {
        val id = this.context.resources.getIdentifier("ic_settings_gear", "drawable", Constants.SE_PACKAGE_NAME)
        this.context.log.verbose("SettingsGearInjector: gearIconId = $id")
        id
    }

    override fun init() {
        this.context.log.verbose("SettingsGearInjector: init called")
        if (this.context.config.userInterface.settingsMenu.get() != "legacy") {
            this.context.log.verbose("SettingsGearInjector: legacy mode not enabled, returning.")
            return
        }
        this.context.log.verbose("SettingsGearInjector: legacy mode enabled, subscribing to AddViewEvent")

        this.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: hova_header_search_icon found!")
                val parent = event.parent as? FrameLayout
                if (parent == null) {
                    this@SettingsGearInjector.context.log.error("SettingsGearInjector: parent is not a FrameLayout! It is ${event.parent?.javaClass?.name}")
                    return@subscribe
                }
                this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: parent is a FrameLayout.")

                if (parent.findViewById<View>(hovaHeaderSearchIconId + 1) != null) {
                    this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: gear icon already exists.")
                    return@subscribe
                }
                this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: gear icon not found, creating it.")

                val gearIcon = ImageView(parent.context).apply {
                    id = hovaHeaderSearchIconId + 1
                    setImageResource(gearIconId)
                    setColorFilter(this@SettingsGearInjector.context.userInterface.colorPrimary, PorterDuff.Mode.SRC_IN)
                    setOnClickListener {
                        this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: gear icon clicked!")
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

                this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: adding gear icon to parent.")
                parent.addView(gearIcon, layoutParams)
                this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: gear icon added to parent.")

                parent.post {
                    val addedIcon = parent.findViewById<View>(hovaHeaderSearchIconId + 1)
                    if (addedIcon != null) {
                        this@SettingsGearInjector.context.log.verbose("SettingsGearInjector: post-layout check, icon is still in parent. Is visible: ${addedIcon.visibility == View.VISIBLE}, width: ${addedIcon.width}, height: ${addedIcon.height}")
                    } else {
                        this@SettingsGearInjector.context.log.error("SettingsGearInjector: post-layout check, icon is GONE from parent.")
                    }
                }
            }
        }
    }
}
