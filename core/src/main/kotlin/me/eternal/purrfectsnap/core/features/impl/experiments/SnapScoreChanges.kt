package me.eternal.purrfectsnap.core.features.impl.experiments

import android.view.ViewGroup
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.getComposerContext
import me.eternal.purrfectsnap.core.ui.getComposerViewNode
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID

class SnapScoreChanges: Feature("Snap Score Changes") {
    private val scores = mutableMapOf<String, Long>()
    private var lastViewedUserId: String? = null

    override fun init() {
        if (!context.config.experimental.snapScoreChanges.get()) return

        context.event.subscribe(UnaryCallEvent::class) { event ->
            if (event.uri != "/com.snapchat.atlas.gw.AtlasGw/GetFriendsUserScore") return@subscribe

            event.addResponseCallback {
                synchronized(scores) {
                    ProtoReader(buffer).eachBuffer(1) {
                        val friendUUID = getByteArray(1) ?: return@eachBuffer
                        val score = getVarInt(2) ?: return@eachBuffer

                        scores[SnapUUID(friendUUID).toString()] = score
                    }
                }
            }
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.viewClassName.endsWith("UnifiedProfileFlatlandProfileViewTopViewFrameLayout")) {
                val composerView = (event.view as ViewGroup).getChildAt(0) ?: return@subscribe
                val composerContext = composerView.getComposerContext() ?: return@subscribe

                lastViewedUserId = composerContext.viewModel?.getObjectField("_userId")?.toString()
            }

            if (event.viewClassName.endsWith("ProfileFlatlandFriendSnapScoreIdentityPillDialogView")) {
                event.view.post {
                    event.view.getComposerContext()!!.enqueueNextRenderCallback {
                        val composerViewNode = event.view.getComposerViewNode() ?: return@enqueueNextRenderCallback
                        val surface = composerViewNode.getChildren().getOrNull(1) ?: return@enqueueNextRenderCallback

                        val snapTextView = surface.getChildren().lastOrNull {
                            it.getClassName() == "com.snap.composer.views.ComposerSnapTextView"
                        } ?: return@enqueueNextRenderCallback


                        val currentFriendScore = scores[lastViewedUserId] ?: (event.view.getComposerContext()?.viewModel?.getObjectField("_friendSnapScore") as? Double)?.toLong() ?: return@enqueueNextRenderCallback

                        val oldSnapScore = context.bridgeClient.getTracker().updateFriendScore(
                            lastViewedUserId ?: return@enqueueNextRenderCallback,
                            currentFriendScore
                        )

                        val diff = currentFriendScore - oldSnapScore

                        snapTextView.setAttribute("value", "${if (oldSnapScore != -1L && diff > 0) "\uD83D\uDCC8 +$diff !\n\n" else ""}Last Checked Score: ${oldSnapScore.takeIf { it != -1L } ?: "N/A"}")
                        event.view.postInvalidate()
                    }
                    event.view.postInvalidate()
                }
            }
        }
    }
}