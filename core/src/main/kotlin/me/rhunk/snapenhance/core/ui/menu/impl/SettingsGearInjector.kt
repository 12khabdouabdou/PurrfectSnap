package me.rhunk.snapenhance.core.ui.menu.impl

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
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

    private fun getGearIconDrawable(): Drawable? {
        return try {
            val managerContext = this.context.androidContext.createPackageContext(Constants.SE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
            val resId = managerContext.resources.getIdentifier("ic_settings_gear", "drawable", Constants.SE_PACKAGE_NAME)
            if (resId == 0) return null
            managerContext.getDrawable(resId)
        } catch (e: Exception) {
            this.context.log.error("Failed to get gear icon drawable", e)
            null
        }
    }

    override fun init() {
        if (this.context.config.userInterface.settingsMenu.get() != "legacy") return

        this.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                val parent = event.parent as? FrameLayout ?: return@subscribe

                val gearIconDrawable = getGearIconDrawable() ?: return@subscribe

                if (parent.findViewById<View>(hovaHeaderSearchIconId + 1) != null) {
                    return@subscribe
                }

                val gearIcon = ImageView(parent.context).apply {
                    id = hovaHeaderSearchIconId + 1
                    setImageDrawable(gearIconDrawable)
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
