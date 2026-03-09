package me.eternal.purrfectsnap.core.features.impl.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.children
import java.lang.ref.WeakReference

/**
 * Provides snap jump logic for Story Snap List Download batch downloads.
 * Initializes when storySnapListDownload is enabled to enable programmatic navigation between snaps.
 */
class OperaStoryOverlay : Feature("OperaStoryOverlay") {
    private val overlayState = OperaStoryOverlayState()
    private var storyFrameLayout = WeakReference<ViewGroup>(null)
    private lateinit var snapJump: OperaStorySnapJump

    override fun init() {
        val storySnapListDownload = context.config.downloader.storySnapListDownload.get()

        if (!storySnapListDownload) return

        snapJump = OperaStorySnapJump(context, overlayState) { storyFrameLayout.get() }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view is FrameLayout && event.parent.javaClass.superclass?.name?.endsWith("OpenLayout") == true) {
                val viewGroup = event.view as FrameLayout

                if (viewGroup.findViewWithTag<View>("story_counter") != null ||
                    event.parent.findViewWithTag<View>("story_counter") != null) return@subscribe

                if (event.parent.children().none { it.javaClass.name.endsWith("ScalableCircleMaskFrameLayout") }) return@subscribe

                storyFrameLayout = WeakReference(viewGroup)
            }
        }

        onNextActivityCreate {
            overlayState.setupDisplayStateHook(
                context = context,
                showCounter = false,
                showSourceIndicator = false,
                onSnapFullyDisplayed = {
                    if (snapJump.isJumping()) {
                        snapJump.onSnapFullyDisplayed(it)
                    }
                },
                onClearState = { snapJump.removeJumpOverlay() }
            )
        }
    }

    fun requestJumpToSnap(targetIndex: Int, totalCountOverride: Int? = null): Boolean =
        snapJump.requestJumpToSnap(targetIndex, totalCountOverride)
}
