package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {
    override fun init(activity: ComponentActivity) {
        super.init(activity)
        registerNestedTab(ManualPatchTab::class)
        registerNestedTab(AutoPatchTab::class)
        registerNestedTab(SettingsTab::class)
    }

    @Composable
    override fun Content() {
        var isRoot by remember { mutableStateOf(false) }
        val context = LocalContext.current

        // Animate glow pulsation for logo & buttons
        val infiniteTransition = rememberInfiniteTransition()
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(bottom = 100.dp), // leave space for floating nav
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Glowy Logo
                Box(contentAlignment = Alignment.Center) {
                    GlowEffect(color = Color(0xFFF5DC5C), alpha = glowAlpha, radius = 80f)
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(100.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = "Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Welcome to Snapenhance Manager!",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    "This lets you setup Snapenhance easily",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Made with ", fontSize = 14.sp, color = Color(0xFFFFE066))
                    Text("\u2764", fontSize = 14.sp, color = Color.Red)
                    Text(" by ΞTΞRNAL & rhunk", fontSize = 14.sp, color = Color(0xFFFFE066), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(25.dp))
                // Social Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    GlowyIconButtonPainter(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/YOUR_TELEGRAM_LINK"))) },
                        icon = painterResource(R.drawable.ic_telegram),
                        glowColor = Color(0xFF229ED9),
                        glowAlpha = glowAlpha
                    )
                    GlowyIconButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://your.donate.url"))) },
                        icon = Icons.Outlined.FavoriteBorder,
                        glowColor = Color(0xFFF95B5B),
                        glowAlpha = glowAlpha
                    )
                    GlowyIconButtonPainter(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/particle-box/SnapEnhance"))) },
                        icon = painterResource(R.drawable.ic_github),
                        glowColor = Color(0xFFB18FF5),
                        glowAlpha = glowAlpha
                    )
                }
                Spacer(Modifier.height(32.dp))
                // Toggle
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("Non-Root", fontWeight = if (!isRoot) FontWeight.Bold else null)
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = isRoot,
                        onCheckedChange = { isRoot = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFE066))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Root", fontWeight = if (isRoot) FontWeight.Bold else null)
                }
                Spacer(Modifier.height(30.dp))
                // Main Buttons
                Row(
                    Modifier
                        .fillMaxWidth(0.88f)
                        .align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GlowyButton(
                        text = "Manual Patch",
                        icon = Icons.Default.Handyman,
                        color = Color(0xFFF5DC5C),
                        onClick = { navigation.navigateTo(ManualPatchTab::class) },
                        glowAlpha = glowAlpha
                    )
                    GlowyButton(
                        text = "Auto Patch",
                        icon = Icons.Default.AutoFixHigh,
                        color = Color(0xFFD5D7B7),
                        onClick = { navigation.navigateTo(AutoPatchTab::class) },
                        glowAlpha = glowAlpha
                    )
                }
            }
            // Floating modern nav bar, single and centered
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    shadowElevation = 25.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.91f),
                    modifier = Modifier
                        .height(62.dp)
                        .widthIn(min = 220.dp, max = 300.dp)
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
                                tint = Color(0xFFF5DC5C).copy(alpha = 0.92f), modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            onClick = { navigation.navigateTo(SettingsTab::class) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Settings, "Settings",
                                tint = Color(0xFFF5DC5C).copy(alpha = 0.85f), modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Glowing logo/button composable
@Composable
fun GlowEffect(color: Color, alpha: Float, radius: Float) {
    Spacer(
        modifier = Modifier
            .size((radius * 2).dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha * 0.45f), Color.Transparent),
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
fun GlowyButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    glowAlpha: Float
) {
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(color, alpha = glowAlpha, radius = 82f)
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = color,
            shadowElevation = 19.dp,
            modifier = Modifier
                .width(148.dp)
                .height(110.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(11.dp))
                Text(text, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun GlowyIconButtonPainter(onClick: () -> Unit, icon: Painter, glowColor: Color, glowAlpha: Float) {
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glowColor, alpha = glowAlpha, radius = 38f)
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            shadowElevation = 0.dp,
            modifier = Modifier.size(52.dp)
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(58.dp)
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = glowColor,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

@Composable
fun GlowyIconButton(onClick: () -> Unit, icon: ImageVector, glowColor: Color, glowAlpha: Float) {
    Box(contentAlignment = Alignment.Center) {
        GlowEffect(glowColor, alpha = glowAlpha, radius = 38f)
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            shadowElevation = 0.dp,
            modifier = Modifier.size(52.dp)
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(58.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = glowColor,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
