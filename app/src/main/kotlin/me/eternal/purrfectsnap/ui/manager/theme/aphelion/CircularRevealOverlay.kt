package me.eternal.purrfectsnap.ui.manager.theme.aphelion

import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import androidx.compose.ui.platform.LocalHapticFeedback
import me.eternal.purrfectsnap.RemoteSideContext

private const val REVEAL_DURATION_MS = 3200
private const val WAVE_BAND_WIDTH_PX = 300f
private const val BLUR_ZONE_PX = 120f
private const val BLUR_RADIUS = 30f
private const val FADE_ZONE_PX = 80f

// "Explosive Dissipation" Easing: Instant high velocity at start, rapid energy loss, ending in a slow crawl.
private val AphelionEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

@Composable
fun CircularRevealOverlay(
    context: RemoteSideContext,
    request: ThemeRevealRequest,
    onComplete: () -> Unit
) {
    // Safety check: if bitmap was recycled or is null, skip.
    val bitmap = request.oldThemeBitmap ?: run {
        LaunchedEffect(request.id) { onComplete() }
        return
    }
    
    if (bitmap.isRecycled) {
        LaunchedEffect(request.id) { onComplete() }
        return
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current

    // Ensure the reveal is cleared even if navigation happens mid-animation
    DisposableEffect(request.id) {
        onDispose { onComplete() }
    }
    
    val maxRadius = remember(configuration) {
        with(density) {
            val w = configuration.screenWidthDp.dp.toPx()
            val h = configuration.screenHeightDp.dp.toPx()
            sqrt(w * w + h * h)
        }
    }

    val animatedRadius = remember(request.id) { Animatable(0f) }

    LaunchedEffect(request.id) {
        AphelionHaptics.themeRevealTick(context, hapticFeedback)

        animatedRadius.animateTo(
            targetValue = maxRadius + WAVE_BAND_WIDTH_PX,
            animationSpec = tween(durationMillis = REVEAL_DURATION_MS, easing = AphelionEasing)
        )
        onComplete()
    }

    val progress = (animatedRadius.value / (maxRadius + WAVE_BAND_WIDTH_PX)).coerceIn(0f, 1f)

    val infiniteTransition = rememberInfiniteTransition(label = "wave_time")
    val timeValue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 5_000, easing = LinearEasing)),
        label = "wave_time_value"
    )

    val runtimeShader = remember(bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.graphics.RuntimeShader(WaveEdgeShader.AGSL).apply {
                setInputShader("content", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            }
        } else null
    }

    val shaderPaint = remember(runtimeShader, bitmap) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = runtimeShader ?: BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = animatedRadius.value
        val center = request.originCenter

        drawIntoCanvas { canvas ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
                runtimeShader.setFloatUniform("revealRadius", radius)
                runtimeShader.setFloatUniform("revealCenter", center.x, center.y)
                runtimeShader.setFloatUniform("bandWidth", WAVE_BAND_WIDTH_PX)
                runtimeShader.setFloatUniform("time", timeValue)
                runtimeShader.setFloatUniform("uProgress", progress)
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                drawWithBlurReveal(canvas.nativeCanvas, bitmap, radius, center.x, center.y, size.width, size.height)
            } else {
                drawWithClipFade(canvas.nativeCanvas, bitmap, radius, center.x, center.y, size.width, size.height)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun drawWithBlurReveal(
    canvas: android.graphics.Canvas,
    bitmap: android.graphics.Bitmap,
    radius: Float,
    centerX: Float,
    centerY: Float,
    canvasWidth: Float,
    canvasHeight: Float
) {
    val innerRingRadius = (radius - BLUR_ZONE_PX).coerceAtLeast(0f)
    val holePath = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addRect(0f, 0f, canvasWidth, canvasHeight, android.graphics.Path.Direction.CW)
        addCircle(centerX, centerY, radius, android.graphics.Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(holePath)
    canvas.drawRect(0f, 0f, canvasWidth, canvasHeight,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    )
    canvas.restore()

    val ringPath = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addCircle(centerX, centerY, radius, android.graphics.Path.Direction.CW)
        addCircle(centerX, centerY, innerRingRadius, android.graphics.Path.Direction.CW)
    }

    val renderNode = android.graphics.RenderNode("blurRing").apply {
        setPosition(0, 0, canvasWidth.toInt(), canvasHeight.toInt())
        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
    }
    val nodeCanvas = renderNode.beginRecording()
    nodeCanvas.save()
    nodeCanvas.clipPath(ringPath)
    nodeCanvas.drawBitmap(bitmap, 0f, 0f, null)
    nodeCanvas.restore()
    renderNode.endRecording()
    canvas.drawRenderNode(renderNode)

    val shimmerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            centerX, centerY, radius,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(50, 255, 255, 255), android.graphics.Color.TRANSPARENT),
            floatArrayOf((innerRingRadius / radius).coerceIn(0f, 1f), ((radius - BLUR_ZONE_PX * 0.25f) / radius).coerceIn(0f, 1f), 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.save()
    canvas.clipPath(ringPath)
    canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, shimmerPaint)
    canvas.restore()
}

private fun drawWithClipFade(
    canvas: android.graphics.Canvas,
    bitmap: android.graphics.Bitmap,
    radius: Float,
    centerX: Float,
    centerY: Float,
    canvasWidth: Float,
    canvasHeight: Float
) {
    val innerFadeRadius = (radius - FADE_ZONE_PX).coerceAtLeast(0f)
    val holePath = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addRect(0f, 0f, canvasWidth, canvasHeight, android.graphics.Path.Direction.CW)
        addCircle(centerX, centerY, radius, android.graphics.Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(holePath)
    canvas.drawRect(0f, 0f, canvasWidth, canvasHeight,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    )
    canvas.restore()

    val ringPath = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addCircle(centerX, centerY, radius, android.graphics.Path.Direction.CW)
        addCircle(centerX, centerY, innerFadeRadius, android.graphics.Path.Direction.CW)
    }
    val fadePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            centerX, centerY, radius,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(80, 255, 255, 255)),
            floatArrayOf((innerFadeRadius / radius).coerceIn(0f, 1f), 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.save()
    canvas.clipPath(ringPath)
    canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, fadePaint)
    canvas.restore()
}
