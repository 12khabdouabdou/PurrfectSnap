package me.rhunk.snapenhance.ui.util

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Returns true if the user has disabled animator duration scale at the system level.
 * This respects Accessibility/Developer settings where motion is reduced or disabled.
 */
fun prefersReducedMotion(context: Context): Boolean {
    return runCatching {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        scale == 0f
    }.getOrDefault(false)
}

@Composable
fun rememberPrefersReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf(prefersReducedMotion(context)) }
    // One-shot read; if you need to observe changes live, add a ContentObserver.
    LaunchedEffect(Unit) {
        state.value = prefersReducedMotion(context)
    }
    return state.value
}

object Motion
{
    @Composable
    fun tween(durationMillis: Int, easing: Easing = androidx.compose.animation.core.FastOutSlowInEasing): FiniteAnimationSpec<Int> {
        val reduced = rememberPrefersReducedMotion()
        val d = if (reduced) 0 else durationMillis
        return tween(durationMillis = d, easing = easing)
    }

    @Composable
    fun tweenFloat(durationMillis: Int, easing: Easing = androidx.compose.animation.core.FastOutSlowInEasing): FiniteAnimationSpec<Float> {
        val reduced = rememberPrefersReducedMotion()
        val d = if (reduced) 0 else durationMillis
        return tween(durationMillis = d, easing = easing)
    }

    @Composable
    fun springDefault(): FiniteAnimationSpec<Float> {
        return spring(stiffness = Spring.StiffnessMedium)
    }
}

/**
 * Apply a subtle scale-down on press for clickable components (cards, buttons, tiles).
 * Pass the same [interactionSource] into the clickable component to synchronize state.
 */
@Composable
fun Modifier.scaleOnPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    scaleDown: Float = 0.98f
): Modifier {
    interactionSource: InteractionSource,
    scaleDown: Float
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed) scaleDown else 1f
    val spec = Motion.tweenFloat(150)
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = spec,
        label = "pressScale"
    )
    return this.then(Modifier.graphicsLayer {
        scaleX = animated
        scaleY = animated
    })
}
