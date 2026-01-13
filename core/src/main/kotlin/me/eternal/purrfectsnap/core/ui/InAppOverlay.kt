package me.eternal.purrfectsnap.core.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.eternal.purrfectsnap.common.ui.createComposeView
import me.eternal.purrfectsnap.common.util.ktx.copyToClipboard
import me.eternal.purrfectsnap.core.ModContext
import me.eternal.purrfectsnap.core.PurrfectSnap
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.Hooker
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.isDarkTheme
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.exitProcess

typealias CustomComposable = @Composable BoxScope.() -> Unit

class InAppOverlay(
    private val context: ModContext
) {
    companion object {
        fun showCrashOverlay(content: String, throwable: Throwable? = null) {
            // deny network requests
            PurrfectSnap.classCache.apply {
                unifiedGrpcService.hook("unaryCall", HookStage.BEFORE) { param ->
                    param.setResult(null)
                }
                networkApi.hook("submit", HookStage.BEFORE) { param ->
                    param.setResult(null)
                }
            }

            Hooker.ephemeralHook(Activity::class.java, "onPostCreate", HookStage.AFTER) { param ->
                val contentView = param.thisObject<Activity>().findViewById<FrameLayout>(android.R.id.content)
                contentView.children().forEach { it.visibility = View.GONE }
                val screenView = createComposeView(param.thisObject()) {
                    PurrfectOverlayTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(PurrfectOverlayPalette.backgroundGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            PurrfectGlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                title = "PurrfectSnap",
                                subtitle = content,
                                icon = Icons.Outlined.Warning
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                                ) {
                                    if (throwable != null) {
                                        Surface(
                                            onClick = { contentView.context.copyToClipboard(throwable.stackTraceToString()) },
                                            shape = RoundedCornerShape(999.dp),
                                            color = Color.White.copy(alpha = 0.10f),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                        ) {
                                            Text(
                                                "Copy error",
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Surface(
                                        onClick = { exitProcess(1) },
                                        shape = RoundedCornerShape(999.dp),
                                        color = PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.28f),
                                        border = BorderStroke(
                                            1.dp,
                                            Brush.linearGradient(
                                                listOf(
                                                    PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.7f),
                                                    PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.55f),
                                                )
                                            )
                                        )
                                    ) {
                                        Text(
                                            "Exit",
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                            color = Color.White,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                contentView.addView(screenView)
            }
        }
    }

    inner class Toast(
        val composable: @Composable Toast.() -> Unit,
        val durationMs: Int
    ) {
        var shown by mutableStateOf(false)
        var visible by mutableStateOf(false)
    }

    private val toasts = mutableStateListOf<Toast>()
    private val customComposables = mutableStateListOf<CustomComposable>()

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun OverlayContent() {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(color = Color.White))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                toasts.forEach { toast ->
                    val animation by animateFloatAsState(
                        targetValue = if (toast.visible) 1f else 0f,
                        animationSpec = if (toast.visible) tween(durationMillis = 150) else tween(durationMillis = 300),
                        label = "toast"
                    )

                    LaunchedEffect(toast) {
                        toast.visible = true
                        if (toast.durationMs < 0) return@LaunchedEffect
                        delay(toast.durationMs.toLong())
                        toast.visible = false
                        delay(1000)
                        toast.shown = true
                        synchronized(toasts) {
                            if (toasts.isNotEmpty() && toasts.all { it.shown }) toasts.clear()
                        }
                    }

                    val deviceWidth = LocalContext.current.resources.displayMetrics.widthPixels
                    val delayAnimationSpec =  rememberSplineBasedDecay<Float>()
                    val anchors = DraggableAnchors {
                        0 at 0f
                        1 at deviceWidth.toFloat()
                    }
                    val draggableState = remember {
                        AnchoredDraggableState(
                            initialValue = 0,
                            anchors = anchors
                        )
                    }

                    LaunchedEffect(draggableState.currentValue) {
                        if (draggableState.currentValue == 1) {
                            toast.visible = false
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .anchoredDraggable(
                                state = draggableState,
                                orientation = Orientation.Horizontal
                            )
                            .offset { IntOffset(draggableState.offset.roundToInt(), 0) }
                            .graphicsLayer {
                                alpha = animation
                                translationY = -100.dp.toPx() * (1 - animation)
                            }
                    ) {
                        if (animation > 0.01f) {
                            toast.composable(toast)
                        }
                    }
                }

                customComposables.forEach {
                    it()
                }
            }
        }
    }

    private val overlayTag = Random.nextLong()

    private fun injectOverlay(activity: Activity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        activity.runOnUiThread {
            if (root.findViewWithTag<View>(overlayTag) != null) return@runOnUiThread
            root.addView(createComposeView(activity) {
                PurrfectOverlayTheme { OverlayContent() }
            }.apply {
                tag = overlayTag
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        }
    }

    fun onActivityCreate(activity: Activity) {
        injectOverlay(activity)
    }

    fun addCustomComposable(composable: CustomComposable) {
        customComposables.add(composable)
    }

    fun removeCustomComposable(composable: CustomComposable) {
        customComposables.remove(composable)
    }

    @Composable
    private fun DurationProgress(
        duration: Int,
        modifier: Modifier = Modifier
    ) {
        val progress = remember { Animatable(1f) }

        LaunchedEffect(Unit) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            )
        }

        LinearProgressIndicator(
            progress = { progress.value },
            modifier = modifier,
            color = PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.85f),
            trackColor = Color.White.copy(alpha = 0.10f),
        )
    }

    fun showStatusToast(
        icon: ImageVector,
        text: String,
        durationMs: Int = 2000,
        showDuration: Boolean = true,
        maxLines: Int = 3
    ) {
        if (context.config.global.uiSettings.useSystemToasts.get()) {
            if (durationMs > 2500) {
                context.longToast(text)
            } else {
                context.shortToast(text)
            }
            return
        }
        showToast(
            icon = { Icon(icon, contentDescription = "icon", modifier = Modifier.size(32.dp)) },
            text = {
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                    fontSize = 13.sp,
                    color = Color.White
                )
            },
            durationMs = durationMs,
            showDuration = showDuration
        )
    }

    private fun showToast(
        icon: @Composable () -> Unit = {
            Icon(Icons.Outlined.Warning, contentDescription = "icon", modifier = Modifier.size(32.dp))
        },
        text: @Composable () -> Unit = {},
        durationMs: Int = 3000,
        showDuration: Boolean = true,
    ) {
        val activity = context.mainActivity ?: return
        injectOverlay(activity)
        toasts.add(Toast(
            composable = {
                val shape = RoundedCornerShape(18.dp)
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .shadow(
                            elevation = 18.dp,
                            shape = shape,
                            spotColor = PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.22f),
                            ambientColor = PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.16f)
                        )
                        .clip(shape)
                        .background(PurrfectOverlayPalette.cardOverlay, shape)
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.55f),
                                        PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.35f),
                                    )
                                )
                            ),
                            shape
                        ),
                    color = Color.Transparent,
                    contentColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                icon()
                            }
                            text()
                        }
                        if (showDuration && durationMs > 0) {
                            DurationProgress(duration = durationMs, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            durationMs = durationMs
        ))
    }
}
