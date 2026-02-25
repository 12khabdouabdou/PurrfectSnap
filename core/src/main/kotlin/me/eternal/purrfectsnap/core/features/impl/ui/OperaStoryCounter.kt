package me.eternal.purrfectsnap.core.features.impl.ui

import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfectsnap.common.ui.createComposeView
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.children
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.Layer
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.ParamMap
import me.eternal.purrfectsnap.mapper.impl.OperaPageViewControllerMapper

class OperaStoryCounter : Feature("OperaStoryCounter") {
    private val counterState = mutableStateOf("")

    override fun init() {
        if (!this@OperaStoryCounter.context.config.userInterface.storyCounter.get()) return

        this@OperaStoryCounter.context.event.subscribe(AddViewEvent::class) { event ->
            if (event.view is FrameLayout && event.parent.javaClass.superclass?.name?.endsWith("OpenLayout") == true) {
                val viewGroup = event.view as FrameLayout
                
                // Strict check: don't add if already exists in the entire parent hierarchy
                if (event.parent.findViewWithTag<android.view.View>("story_counter") != null) return@subscribe

                if (event.parent.children().none { it.javaClass.name.endsWith("ScalableCircleMaskFrameLayout") }) return@subscribe

                val composeView = createComposeView(viewGroup.context) {
                    if (counterState.value.isNotEmpty()) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0x4C000000), // 30% opacity black
                        ) {
                            Text(
                                text = counterState.value,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                }.apply {
                    tag = "story_counter"
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = this@OperaStoryCounter.context.userInterface.dpToPx(55)
                        marginEnd = this@OperaStoryCounter.context.userInterface.dpToPx(10)
                    }
                }
                viewGroup.addView(composeView)
            }
        }

        onNextActivityCreate {
            this@OperaStoryCounter.context.mappings.useMapper(OperaPageViewControllerMapper::class) {
                arrayOf(onDisplayStateChange, onDisplayStateChangeGesture).forEach { methodName ->
                    classReference.get()?.hook(
                        methodName.get() ?: return@forEach,
                        HookStage.AFTER
                    ) { param ->
                        val viewState = (param.thisObject() as Any).getObjectField(viewStateField.get()!!).toString()

                        if (viewState != "FULLY_DISPLAYED") {
                            return@hook
                        }

                        val operaLayerList = (param.thisObject() as Any).getObjectField(layerListField.get()!!) as ArrayList<*>
                        val mediaParamMap: ParamMap = operaLayerList.map { Layer(it) }.first().paramMap
                        val snapSource = mediaParamMap["SNAP_SOURCE"]?.toString()

                        if (snapSource == "SINGLE_SNAP_STORY" || snapSource == "CHAT" || mediaParamMap.containsKey("CHAT_ID") || mediaParamMap.containsKey("CONVERSATION_ID")) {
                            this@OperaStoryCounter.context.runOnUiThread {
                                counterState.value = ""
                            }
                            return@hook
                        }

                        val currentIndex = mediaParamMap["snap_index_in_story"]?.toString()?.toIntOrNull() 
                            ?: mediaParamMap["SNAP_POSITION_IN_STORY"]?.toString()?.toIntOrNull()
                        val totalCount = mediaParamMap["snap_story_length"]?.toString()?.toIntOrNull()
                            ?: mediaParamMap["NUM_SNAPS_IN_STORY"]?.toString()?.toIntOrNull()

                        if (currentIndex != null && totalCount != null && totalCount > 0) {
                            this@OperaStoryCounter.context.runOnUiThread {
                                counterState.value = "${currentIndex + 1} / $totalCount"
                            }
                        } else {
                            this@OperaStoryCounter.context.runOnUiThread {
                                counterState.value = ""
                            }
                        }
                    }
                }
            }
        }
    }
}
