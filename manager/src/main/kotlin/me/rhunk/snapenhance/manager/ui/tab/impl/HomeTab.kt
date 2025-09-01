package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var isRoot by remember { mutableStateOf(false) }

        // Animated gradient
        val infiniteTransition = rememberInfiniteTransition()
        val bgOffset by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse)
        )
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse)
        )

        // Smooth content reveal
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(120)
            visible = true
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF17181C), Color(0xFF222437), Color(0xFF09090B)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        ) {
            AnimatedVisibility(visible) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(36.dp))
                    // Glowing "glass" logo
                    Box(contentAlignment = Alignment.Center) {
                        GlowEffect(
                            color = Color(0xFFF5DC5C).copy(alpha = 0.8f),
                            alpha = glowAlpha,
                            radius = 92f + bgOffset * 10f
                        )
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            tonalElevation = 6.dp,
                            shadowElevation = 20.dp,
                            color = Color.White.copy(alpha = 0.07f)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.logo),
                                contentDescription = "Logo",
                                modifier = Modifier.size(110.dp).clip(RoundedCornerShape(26.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    // Modern, scaling text
                    Text(
                        "Welcome to Snapenhance Manager!",
                        fontSize = 25.sp, fontWeight = FontWeight.Black,
                        color = Color(0xFFF4F3EC),
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Text(
                        "This lets you set up Snapenhance easily",
                        fontSize = 17.sp, color = Color(0xFFB8BAE4), fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text("Made with ", fontSize = 15.sp, color = Color(0xFFF5E379))
                        Text("\uD83D\uDC96", fontSize = 16.sp, color = Color.Red)
                        Text(" by ΞTΞRNAL & rhunk", fontSize = 15.sp, color = Color(0xFFDDAAFF))
                    }

                    Spacer(Modifier.height(30.dp))
                    // Social Buttons
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        CoolSocialButton(
                            painterResource(R.drawable.ic_telegram),
                            bgColor = Color(0xFF299EFF),
                            glow = Color(0x66299EFF),
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/snapenhance_chat"))) }
                        )
                        CoolSocialButton(
                            painterResource(R.drawable.ic_github),
                            bgColor = Color(0xFFA088FA),
                            glow = Color(0x88A088FA),
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance"))) }
                        )
                        CoolSocialButton(
                            icon = Icons.Outlined.FavoriteBorder,
                            bgColor = Color(0xFFF83759),
                            glow = Color(0x66F83759),
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance?tab=readme-ov-file#donate"))) }
                        )
                    }

                    Spacer(Modifier.height(38.dp))
                    // Toggle root/non-root
                    Surface(
                        modifier = Modifier.clip(RoundedCornerShape(26.dp)),
                        color = Color.White.copy(alpha = 0.10f),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Non-Root", fontWeight = if (!isRoot) FontWeight.Bold else null,
                                color = if (!isRoot) Color(0xFFFFE066) else Color.Gray, fontSize = 17.sp
                            )
                            Spacer(Modifier.width(16.dp))
                            Switch(
                                checked = isRoot,
                                onCheckedChange = { isRoot = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF5DC5C))
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Root", fontWeight = if (isRoot) FontWeight.Bold else null,
                                color = if (isRoot) Color(0xFFFFE066) else Color.Gray, fontSize = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(42.dp))
                    // Main action buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        GlowyRoundButton(
                            text = "Manual Patch",
                            icon = Icons.Default.Handyman,
                            color = Color(0xFFFFEB66),
                            onClick = { navigation.navigateTo(ManualPatchTab::class) },
                            glowColor = Color(0xAAFFD95B),
                            shadowColor = Color(0x55C9A73A)
                        )
                        GlowyRoundButton(
                            text = "Auto Patch",
                            icon = Icons.Default.AutoFixHigh,
                            color = Color(0xFFDEDEB5),
                            onClick = { navigation.navigateTo(AutoPatchTab::class) },
                            glowColor = Color(0x99DEDEB5),
                            shadowColor = Color(0x557C8C53)
                        )
                    }
                    Spacer(Modifier.height(80.dp)) // For bottom bar space
                }
            }

            // Bottom Floating Nav (modern glass)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 30.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    shadowElevation = 31.dp,
                    color = Color(0x22FFFFFF),
                    modifier = Modifier
                        .height(68.dp)
                        .widthIn(min = 220.dp, max = 350.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(30.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xAA202230),
                                    Color(0x669290A6),
                                    Color(0x33000000)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(800f, 400f)
                            )
                        )
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(
                            onClick = { navigation.navigateTo(HomeTab::class) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Home, "Home",
                                tint = Color(0xFFFFE169).copy(alpha = 0.92f), modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            onClick = { navigation.navigateTo(SettingsTab::class) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Settings, "Settings",
                                tint = Color(0xFFDDAAFF).copy(alpha = 0.85f), modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Extra beautiful button and effect composables below --- //

@Composable
fun GlowEffect(color: Color, alpha: Float, radius: Float) {
    Spacer(
        modifier = Modifier
            .size((radius * 2).dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha * 0.8f), Color.Transparent),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(size.width / 2, size.height / 2),
                )
            }
    )
}

// Modern glass+glow button (main patch buttons)
@Composable
fun GlowyRoundButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    glowColor: Color,
    shadowColor: Color
) {
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glowColor, alpha = 1f, radius = 82f)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = color,
            shadowElevation = 16.dp,
            tonalElevation = 3.dp,
            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.16f)),
            modifier = Modifier
                .width(155.dp)
                .height(122.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = text,
                    tint = Color.White, modifier = Modifier.size(39.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp,
                    letterSpacing = 0.0.sp
                )
            }
        }
    }
}

// Modern animated social icon button
@Composable
fun CoolSocialButton(
    icon: Painter,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) = CoolSocialButtonBase(
    icon = { Icon(painter = icon, contentDescription = null, modifier = Modifier.size(29.dp), tint = Color.White) },
    bgColor = bgColor, glow = glow, onClick = onClick
)

@Composable
fun CoolSocialButton(
    icon: ImageVector,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) = CoolSocialButtonBase(
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(29.dp), tint = Color.White) },
    bgColor = bgColor, glow = glow, onClick = onClick
)

@Composable
private fun CoolSocialButtonBase(
    icon: @Composable () -> Unit,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glow, alpha = pulse, radius = 39f + 9 * pulse)
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(56.dp).shadow(8.dp, CircleShape).clickable(onClick = onClick),
            shadowElevation = 2.dp,
        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { icon() } }
    }
}
