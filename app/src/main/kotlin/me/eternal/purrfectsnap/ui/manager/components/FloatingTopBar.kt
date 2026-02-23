package me.eternal.purrfectsnap.ui.manager.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.PurrfectMarqueeText
import me.eternal.purrfectsnap.ui.util.Motion

/**
 * Styling configuration for the FloatingTopBar.
 */
@Immutable
data class FloatingTopBarColors(
    val container: Color,
    val borderStart: Color,
    val borderEnd: Color
)

@Composable
fun rememberDefaultFloatingTopBarColors(): FloatingTopBarColors {
    return remember {
        FloatingTopBarColors(
            container = Color.White.copy(alpha = 0.12f), 
            borderStart = PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
            borderEnd = PurrfectPalette.glowSecondary.copy(alpha = 0.4f)
        )
    }
}

/**
 * A premium, morphing top bar that transitions from a "Floating Island" pill 
 * to an "Infinity" sticky bar during scroll.
 * 
 * DESIGN FEATURES:
 * - Geometric Morphing: Pill shape to top-bleeding sticky bar.
 * - Path-based Borders: Custom rounded path that surgically removes the top edge during stickiness.
 * - Under-Glass Glow: A refractive dissolve layer that anchors the header to content.
 * - Edge-Focus: Icons shift horizontally toward edges to maximize title space.
 */
@Composable
fun FloatingTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollOffset: Int = 0,
    containerAlpha: Float = 1f,
    actions: @Composable RowScope.() -> Unit = {},
    colors: FloatingTopBarColors = rememberDefaultFloatingTopBarColors()
) {
    val haptic = LocalHapticFeedback.current
    
    // Use standardized morph threshold
    val focusFactor = (scrollOffset.toFloat() / Motion.HEADER_MORPH_THRESHOLD).coerceIn(0f, 1f)

    // Haptic "Snap" when header hits full expansion/stickiness
    var hasSnapped by remember { mutableStateOf(false) }
    LaunchedEffect(focusFactor) {
        if (focusFactor >= 1f && !hasSnapped) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hasSnapped = true
        } else if (focusFactor < 0.9f) {
            hasSnapped = false
        }
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // --- GEOMETRIC MORPHING MATH ---
    // 1. Height: 64dp content area when floating -> 56dp when sticky
    val headerHeight = lerp(64.dp, 56.dp, focusFactor)
    
    // 2. Padding Morph: 
    // Static: Island (14dp sides, sits BELOW status bar with 12dp margin)
    // Infinity: Full (0dp sides, covers status bar area entirely)
    val sidePadding = lerp(14.dp, 0.dp, focusFactor)
    val containerTopPadding = lerp(statusBarHeight + 4.dp, 0.dp, focusFactor)
    val internalTopPadding = lerp(0.dp, statusBarHeight, focusFactor)
    val internalVerticalPadding = lerp(8.dp, 0.dp, focusFactor)

    // 3. Corner Morph: Round pill (26dp all) -> Bottom-rounded sticky bar (28dp bottom)
    val topCorners = lerp(26.dp, 0.dp, focusFactor)
    val bottomCorners = lerp(26.dp, 28.dp, focusFactor)
    val shape = RoundedCornerShape(
        topStart = topCorners, 
        topEnd = topCorners, 
        bottomStart = bottomCorners, 
        bottomEnd = bottomCorners
    )

    // 4. Content Animation: Subtitle falls in/out, icons scale and shift
    val subtitleAlpha = (1f - (focusFactor * 2.5f)).coerceIn(0f, 1f)
    val subtitleTranslationY = lerp(0.dp, (-10).dp, focusFactor)
    val iconScale = 1f - (0.12f * focusFactor)
    val horizontalShift = (6 * focusFactor).dp 

    val borderPath = remember { Path() }
    val uPath = remember { Path() }

    Box(modifier = modifier.fillMaxWidth().zIndex(10f)) {
        // --- 1. REFRACTIVE BACKGROUND (UNDER-GLASS) ---
        // Covers full area (Internal Padding + Content Height + Dissolve Tail)
        val totalHeaderArea = internalTopPadding + headerHeight
        val refractiveColor = Color(0xFF241F52) // Aligned with Palette base
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
                .padding(top = containerTopPadding)
                .height(totalHeaderArea + 32.dp) // 32dp smooth dissolve tail
                .background(
                    Brush.verticalGradient(
                        0.0f to refractiveColor.copy(alpha = 0.95f * focusFactor),
                        0.6f to refractiveColor.copy(alpha = 0.85f * focusFactor),
                        1.0f to Color.Transparent
                    )
                )
        )

        // --- 2. MAIN HEADER SURFACE ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
                .padding(top = containerTopPadding)
                .graphicsLayer { 
                    alpha = containerAlpha
                },
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = (4 * focusFactor).dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1B152E).copy(alpha = 0.85f + (0.1f * focusFactor)),
                                refractiveColor.copy(alpha = 0.85f + (0.1f * focusFactor))
                            )
                        )
                    )
                    .drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        val brush = Brush.linearGradient(listOf(colors.borderStart, colors.borderEnd))
                        val tr = topCorners.toPx()
                        val br = bottomCorners.toPx()
                        
                        if (focusFactor > 0.9f) {
                            uPath.reset()
                            uPath.apply {
                                moveTo(0f, 0f)
                                lineTo(0f, size.height - br)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2*br, 2*br, size.height), 180f, -90f, false)
                                lineTo(size.width - br, size.height)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*br, size.height - 2*br, size.width, size.height), 90f, -90f, false)
                                lineTo(size.width, 0f)
                            }
                            drawPath(uPath, brush, style = Stroke(strokeWidth))
                        } else {
                            borderPath.reset()
                            borderPath.apply {
                                moveTo(tr, 0f)
                                lineTo(size.width - tr, 0f)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*tr, 0f, size.width, 2*tr), 270f, 90f, false)
                                lineTo(size.width, size.height - br)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*br, size.height - 2*br, size.width, size.height), 0f, 90f, false)
                                lineTo(br, size.height)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2*br, 2*br, size.height), 90f, 90f, false)
                                lineTo(0f, tr)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2*tr, 2*tr), 180f, 90f, false)
                            }
                            drawPath(borderPath, brush, style = Stroke(strokeWidth))
                        }
                    }
            ) {
                // --- 3. ROW CONTENT ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = internalTopPadding)
                        .padding(horizontal = 16.dp, vertical = internalVerticalPadding)
                        .height(headerHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack, 
                            modifier = Modifier
                                .size(44.dp)
                                .graphicsLayer { 
                                    scaleX = iconScale
                                    scaleY = iconScale
                                    translationX = -horizontalShift.toPx()
                                }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!subtitle.isNullOrBlank() && subtitleAlpha > 0.01f) {
                            PurrfectMarqueeText(
                                text = subtitle,
                                color = PurrfectPalette.textSecondary.copy(alpha = subtitleAlpha),
                                style = TextStyle(fontSize = 13.sp),
                                textAlign = TextAlign.Start,
                                contentAlignment = Alignment.CenterStart,
                                enabled = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { 
                                        translationY = subtitleTranslationY.toPx()
                                        alpha = subtitleAlpha
                                    }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .graphicsLayer { 
                                scaleX = iconScale
                                scaleY = iconScale
                                translationX = horizontalShift.toPx()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

/**
 * Extension to safely copy color with clamped alpha.
 */
private fun Color.coerceCopy(alpha: Float): Color {
    return this.copy(alpha = alpha.coerceIn(0f, 1f))
}
