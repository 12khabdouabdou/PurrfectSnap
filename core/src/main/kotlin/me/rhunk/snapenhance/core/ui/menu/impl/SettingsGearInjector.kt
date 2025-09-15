package me.rhunk.snapenhance.core.ui.menu.impl

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import me.rhunk.snapenhance.common.ui.OverlayType
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.ui.menu.AbstractMenu

class SettingsGearInjector : AbstractMenu() {
    private val hovaHeaderSearchIconId by lazy {
        this.context.resources.getIdentifier("hova_header_search_icon", "id", "com.snapchat.android")
    }

    private val gearIconId = View.generateViewId()

    override fun init() {
        if (this.context.config.userInterface.settingsMenu.get() != "legacy") return

        this.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                val parent = event.parent as? FrameLayout ?: return@subscribe
                val searchIcon = event.view

                if (parent.findViewById<View>(gearIconId) != null) {
                    return@subscribe
                }

                parent.clipChildren = false

                val gearIcon = TextView(parent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                    isClickable = true
                    setOnClickListener {
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                    setPadding(15, 15, 15, 15)
                }

                parent.addView(gearIcon, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))

                parent.post {
                    val searchIconWidth = searchIcon.width
                    val margin = this@SettingsGearInjector.context.userInterface.dpToPx(8)
                    gearIcon.x = -(searchIconWidth + margin).toFloat()
                    gearIcon.y = searchIcon.y + (searchIcon.height - gearIcon.height) / 2
                }

                parent.setOnTouchListener { _, motionEvent ->
                    val gearIconLocation = IntArray(2)
                    gearIcon.getLocationOnScreen(gearIconLocation)
                    val gearIconLeft = gearIconLocation[0]
                    val gearIconTop = gearIconLocation[1]
                    val gearIconRight = gearIconLeft + gearIcon.width
                    val gearIconBottom = gearIconTop + gearIcon.height

                    if (motionEvent.rawX > gearIconLeft && motionEvent.rawX < gearIconRight &&
                        motionEvent.rawY > gearIconTop && motionEvent.rawY < gearIconBottom) {
                        if (motionEvent.action == MotionEvent.ACTION_UP) {
                            gearIcon.performClick()
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
