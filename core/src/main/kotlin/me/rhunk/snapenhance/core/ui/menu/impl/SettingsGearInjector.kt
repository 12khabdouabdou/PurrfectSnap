package me.rhunk.snapenhance.core.ui.menu.impl

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private val gearIconContainerId = View.generateViewId()

    override fun init() {
        if (this.context.config.userInterface.settingsMenu.get() != "legacy") return

        this.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view.id == hovaHeaderSearchIconId) {
                val directParent = event.parent as? FrameLayout ?: return@subscribe
                val searchIcon = event.view

                if (directParent.findViewById<View>(gearIconContainerId) != null) {
                    return@subscribe
                }

                // Disable clipping on parent and grandparent
                directParent.clipChildren = false
                (directParent.parent as? ViewGroup)?.clipChildren = false

                val gearIcon = TextView(directParent.context).apply {
                    id = gearIconId
                    text = "⚙️"
                    textSize = 28f
                    setTextColor(this@SettingsGearInjector.context.userInterface.colorPrimary)
                }

                val gearContainer = FrameLayout(directParent.context).apply {
                    id = gearIconContainerId
                    isClickable = true
                    setOnClickListener {
                        this@SettingsGearInjector.context.bridgeClient.openOverlay(OverlayType.SETTINGS)
                    }
                    addView(gearIcon, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    ))
                    // Make invisible initially
                    visibility = View.INVISIBLE
                }

                directParent.addView(gearContainer)

                directParent.post {
                    val searchIconWidth = searchIcon.width
                    val margin = this@SettingsGearInjector.context.userInterface.dpToPx(8)

                    gearContainer.layoutParams = FrameLayout.LayoutParams(searchIcon.layoutParams.width, searchIcon.layoutParams.height).apply {
                        x = -(searchIconWidth + margin).toFloat()
                        y = searchIcon.y
                    }
                    // Make visible after positioning
                    gearContainer.visibility = View.VISIBLE
                }

                (directParent.parent as? ViewGroup)?.setOnTouchListener { _, motionEvent ->
                    if (gearContainer.visibility != View.VISIBLE) return@setOnTouchListener false

                    val location = IntArray(2)
                    gearContainer.getLocationOnScreen(location)
                    val left = location[0]
                    val top = location[1]
                    val right = left + gearContainer.width
                    val bottom = top + gearContainer.height

                    if (motionEvent.rawX > left && motionEvent.rawX < right &&
                        motionEvent.rawY > top && motionEvent.rawY < bottom) {
                        if (motionEvent.action == MotionEvent.ACTION_UP) {
                            gearContainer.performClick()
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
