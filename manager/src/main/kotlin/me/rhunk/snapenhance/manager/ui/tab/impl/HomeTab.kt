package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
        val infiniteTransition = rememberInfiniteTransition()
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse)
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF181A24), Color(0xFF202136)),
                        startY = 0f, endY = Float.POSITIVE_INFINITY
                    )
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(25.dp))
                Box(contentAlignment = Alignment.Center) {
                    GlowEffect(
                        color = Color(0xFFF5DC5C).copy(alpha = 0.7f),
                        alpha = glowAlpha,
                        radius = 66f
                    )
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 3.dp,
                        shadowElevation = 11.dp,
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Welcome to Snapenhance Manager!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFF4F3EC),
                    letterSpacing = 0.3.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This app lets you setup Snapenhance easily!",
                    fontSize = 15.sp,
                    color = Color(0xFF98A2CF),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("Made with ", fontSize = 13.sp, color = Color(0xFFFFE066))
                    Text("\uD83D\uDC96", fontSize = 14.sp, color = Color.Magenta)
                    Text(" by ΞTΞRNAL & rhunk", fontSize = 13.sp, color = Color(0xFFDDAAFF))
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
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
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .width(255.dp)
                        .height(44.dp),
                    color = Color.White.copy(alpha = 0.14f)
                ) {
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Non-Root",
                            fontWeight = if (!isRoot) FontWeight.Bold else null,
                            color = if (!isRoot) Color(0xFFFFE066) else Color.Gray,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = isRoot,
                            onCheckedChange = { isRoot = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFF5DC5C))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Root",
                            fontWeight = if (isRoot) FontWeight.Bold else null,
                            color = if (isRoot) Color(0xFFFFE066) else Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GlowyRoundButton(
                        text = "Manual Patch",
                        icon = Icons.Default.Handyman,
                        color = Color(0xFFFFEB66),
                        onClick = { navigation?.navigateTo(ManualPatchTab::class) },
                        glowColor = Color(0xAAFFD95B),
                        shadowColor = Color(0x55C9A73A),
                        modifier = Modifier.weight(1f)
                    )
                    GlowyRoundButton(
                        text = "Auto Patch",
                        icon = Icons.Default.AutoFixHigh,
                        color = Color(0xFFDEDEB5),
                        onClick = { navigation?.navigateTo(AutoPatchTab::class) },
                        glowColor = Color(0x99DEDEB5),
                        shadowColor = Color(0x557C8C53),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            SnapEnhanceFloatingNav(this@HomeTab)
        }
    }
}

@Composable
fun SnapEnhanceFloatingNav(tab: Tab) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 18.dp,
            color = Color(0x26FFFFFF),
            modifier = Modifier
                .height(56.dp)
                .widthIn(min = 170.dp, max = 320.dp)
                .clip(RoundedCornerShape(30.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(30.dp))
        ) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = { tab.navigation?.navigateTo(HomeTab::class) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Home, "Home",
                        tint = Color(0xFFFFE169).copy(alpha = 0.92f), modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(
                    onClick = { tab.navigation?.navigateTo(SettingsTab::class) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Settings, "Settings",
                        tint = Color(0xFFDDAAFF).copy(alpha = 0.85f), modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// --- Supporting composables from previous examples ---

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
    modifier: Modifier = Modifier
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        GlowEffect(glowColor, alpha = 1f, radius = 48f)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = color,
            shadowElevation = 10.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 7.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = text,
                    tint = Color.White, modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp,
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
    icon = { Icon(painter = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White) },
    bgColor = bgColor, glow = glow, onClick = onClick
)
@Composable
fun CoolSocialButton(
    icon: ImageVector,
    bgColor: Color,
    glow: Color,
    onClick: () -> Unit
) = CoolSocialButtonBase(
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White) },
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
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glow, alpha = pulse, radius = 20f + 6 * pulse)
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(40.dp).shadow(4.dp, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            shadowElevation = 2.dp,
        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { icon() } }
    }
}
