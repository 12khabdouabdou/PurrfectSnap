package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var isRoot by remember { mutableStateOf(false) }

        // Animate glow pulses
        val infiniteTransition = rememberInfiniteTransition()
        val bgOffset by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse)
        )
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse)
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF15161B), Color(0xFF222437), Color(0xFF191A1D)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(22.dp))
                Box(contentAlignment = Alignment.Center) {
                    GlowEffect(
                        color = Color(0xFFF5DC5C).copy(alpha = 0.7f),
                        alpha = glowAlpha,
                        radius = 66f + bgOffset * 6f
                    )
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 3.dp,
                        shadowElevation = 11.dp,
                        color = Color.White.copy(alpha = 0.07f)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(11.dp))
                Text(
                    "Welcome to Snapenhance Manager!",
                    fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFF4F3EC),
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "All your SnapEnhance tools and setup in one beautiful place 🚀",
                    fontSize = 14.5.sp,
                    color = Color(0xFFB8BAE4),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("Made with ", fontSize = 13.5.sp, color = Color(0xFFF5E379))
                    Text("\uD83D\uDC96", fontSize = 15.sp, color = Color.Magenta)
                    Text(" by ΞTΞRNAL & rhunk", fontSize = 13.5.sp, color = Color(0xFFDDAAFF))
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(0.70f)
                ) {
                    CoolSocialButton(
                        painterResource(R.drawable.ic_telegram),
                        bgColor = Color(0xFF299EFF),
                        glow = Color(0x66299EFF),
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/YOUR_TELEGRAM_LINK"))) }
                    )
                    CoolSocialButton(
                        painterResource(R.drawable.ic_github),
                        bgColor = Color(0xFFA088FA),
                        glow = Color(0x88A088FA),
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/particle-box/SnapEnhance"))) }
                    )
                    CoolSocialButton(
                        icon = Icons.Outlined.FavoriteBorder,
                        bgColor = Color(0xFFF83759),
                        glow = Color(0x66F83759),
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://your.donate.url"))) }
                    )
                }
                Spacer(Modifier.height(18.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(25.dp))
                        .width(270.dp)
                        .height(46.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Non-Root", fontWeight = if (!isRoot) FontWeight.Bold else null,
                            color = if (!isRoot) Color(0xFFFFE066) else Color.Gray, fontSize = 16.sp
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
                            color = if (isRoot) Color(0xFFFFE066) else Color.Gray, fontSize = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(17.dp))
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
                        shadowColor = Color(0x55C9A73A),
                        compact = true
                    )
                    GlowyRoundButton(
                        text = "Auto Patch",
                        icon = Icons.Default.AutoFixHigh,
                        color = Color(0xFFDEDEB5),
                        onClick = { navigation.navigateTo(AutoPatchTab::class) },
                        glowColor = Color(0x99DEDEB5),
                        shadowColor = Color(0x557C8C53),
                        compact = true
                    )
                }
                Spacer(Modifier.height(55.dp))
            }

            // Only the custom floating nav bar (not the default one)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    shadowElevation = 25.dp,
                    color = Color(0x22FFFFFF),
                    modifier = Modifier
                        .height(60.dp)
                        .widthIn(min = 180.dp, max = 320.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(30.dp))
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
                                tint = Color(0xFFFFE169).copy(alpha = 0.92f), modifier = Modifier.size(27.dp)
                            )
                        }
                        IconButton(
                            onClick = { navigation.navigateTo(SettingsTab::class) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Settings, "Settings",
                                tint = Color(0xFFDDAAFF).copy(alpha = 0.85f), modifier = Modifier.size(25.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- COMPOSABLES ---

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

@Composable
fun GlowyRoundButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    glowColor: Color,
    shadowColor: Color,
    compact: Boolean = false // << NEW! Use compact spacing.
) {
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glowColor, alpha = 1f, radius = if (compact) 58f else 78f)
        Surface(
            shape = RoundedCornerShape(if (compact) 16.dp else 24.dp),
            color = color,
            shadowElevation = 14.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier
                .width(if (compact) 125.dp else 155.dp)
                .height(if (compact) 78.dp else 122.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = if (compact) 8.dp else 18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = text,
                    tint = Color.White, modifier = Modifier.size(if (compact) 28.dp else 39.dp)
                )
                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                Text(
                    text,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = if (compact) 15.sp else 18.sp,
                    letterSpacing = 0.0.sp
                )
            }
        }
    }
}

@Composable
fun CoolSocialButton(
    icon: Painter,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) = CoolSocialButtonBase(
    icon = { Icon(painter = icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = Color.White) },
    bgColor = bgColor, glow = glow, onClick = onClick
)

@Composable
fun CoolSocialButton(
    icon: ImageVector,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) = CoolSocialButtonBase(
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = Color.White) },
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
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glow, alpha = pulse, radius = 30f + 6 * pulse)
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(46.dp).shadow(6.dp, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            shadowElevation = 2.dp,
        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { icon() } }
    }
}
