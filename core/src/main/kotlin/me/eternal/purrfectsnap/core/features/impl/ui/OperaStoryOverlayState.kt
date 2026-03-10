package me.eternal.purrfectsnap.core.features.impl.ui

import me.eternal.purrfectsnap.core.ModContext
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.Layer
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.ParamMap
import me.eternal.purrfectsnap.mapper.impl.OperaPageViewControllerMapper
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import java.util.ArrayList
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Shared state for Opera Story overlay (snap jump for batch download).
 * Provides story index, total count, and hook setup for display state changes.
 */
class OperaStoryOverlayState {
    val counterState = mutableStateOf("")
    val sourceState = mutableStateOf("")
    val currentIndexState = mutableIntStateOf(-1)
    val totalCountState = mutableIntStateOf(0)

    fun setupDisplayStateHook(
        context: ModContext,
        showCounter: Boolean,
        showSourceIndicator: Boolean,
        onSnapFullyDisplayed: ((Int) -> Unit)?,
        onClearState: (() -> Unit)? = null
    ) {
        context.mappings.useMapper(OperaPageViewControllerMapper::class) {
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

                    if (mediaParamMap.containsKey("MESSAGE_ID")) {
                        context.runOnUiThread {
                            counterState.value = ""
                            sourceState.value = ""
                            currentIndexState.intValue = -1
                            totalCountState.intValue = 0
                            onClearState?.invoke()
                        }
                        return@hook
                    }

                    if (snapSource == "SINGLE_SNAP_STORY") {
                        context.runOnUiThread {
                            counterState.value = ""
                            sourceState.value = ""
                            currentIndexState.intValue = -1
                            totalCountState.intValue = 0
                            onClearState?.invoke()
                        }
                        return@hook
                    }

                    val currentIndex = mediaParamMap["snap_index_in_story"]?.toString()?.toIntOrNull()
                        ?: mediaParamMap["SNAP_POSITION_IN_STORY"]?.toString()?.toIntOrNull()
                    val totalCount = mediaParamMap["snap_story_length"]?.toString()?.toIntOrNull()
                        ?: mediaParamMap["NUM_SNAPS_IN_STORY"]?.toString()?.toIntOrNull()

                    var mediaOrigin = ""
                    if (showSourceIndicator) {
                        val snapRecord = mediaParamMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString() ?: ""
                        mediaOrigin = if (snapRecord.contains("mediaOrigins=")) {
                            if (snapRecord.contains("mediaOrigins=[CAMERA]")) "CAMERA" else "GALLERY"
                        } else ""
                    }

                    context.runOnUiThread {
                        counterState.value = if (showCounter && currentIndex != null && totalCount != null && totalCount > 0) {
                            "${currentIndex + 1} / $totalCount"
                        } else ""
                        sourceState.value = mediaOrigin
                        currentIndexState.intValue = currentIndex ?: -1
                        totalCountState.intValue = totalCount ?: 0

                        onSnapFullyDisplayed?.let { callback ->
                            if (currentIndex != null) callback(currentIndex)
                        }
                    }
                }
            }
        }
    }
}
