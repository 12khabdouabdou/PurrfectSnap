package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab
import kotlin.math.*

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var isRoot by remember { mutableStateOf(false) }
        var selectedAction by remember { mutableStateOf<Int?>(null) }
        val scrollState = rememberScrollState()
        
        // Navigation handling
        LaunchedEffect(selectedAction) {
            when (selectedAction) {
                0 -> {
                    delay(400)
                    navigation?.navigateTo(ManualPatchTab::class)
                    selectedAction = null
                }
                1 -> {
                    delay(400)
                    navigation?.navigateTo(AutoPatchTab::class)
                    selectedAction = null
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            // Premium gradient background
            PremiumBackground()
            
            // Subtle animated orbs in background
            AnimatedBackgroundOrbs()
            
            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(48.dp))
                
                // Logo section with elegant animation
                ElegantLogoSection()
                
                Spacer(Modifier.height(40.dp))
                
                // Title and description
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "SNAPENHANCE",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 4.sp,
                        fontFamily = FontFamily.Default
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        text = "This app allows you to setup Snapenhance easily",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Light
                    )
                }
                
                Spacer(Modifier.height(48.dp))
                
                // Mode selector with smooth animation
                ModernModeSelector(
                    isRoot = isRoot,
                    onModeChange = { isRoot = it }
                )
                
                Spacer(Modifier.height(48.dp))
                
                // Action cards
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PremiumActionCard(
                        title = "Manual Patch",
                        description = "Full control over the patching process",
                        icon = Icons.Default.Engineering,
                        gradientColors = listOf(
                            Color(0xFF667EEA),
                            Color(0xFF764BA2)
                        ),
                        isPressed = selectedAction == 0,
                        onClick = { selectedAction = 0 }
                    )
                    
                    PremiumActionCard(
                        title = "Auto Patch",
                        description = "Automated patching with smart detection",
                        icon = Icons.Default.AutoAwesome,
                        gradientColors = listOf(
                            Color(0xFFF093FB),
                            Color(0xFFF5576C)
                        ),
                        isPressed = selectedAction == 1,
                        onClick = { selectedAction = 1 }
                    )
                }
                
                Spacer(Modifier.height(48.dp))
                
                // Stats section
                StatsSection()
                
                Spacer(Modifier.height(48.dp))
                
                // Social links
                SocialLinksSection(context)
                
                Spacer(Modifier.height(32.dp))
                
                // Credits
                CreditsSection()
                
                Spacer(Modifier.height(120.dp))
            }
            
            // Modern bottom bar
            ModernBottomBar(
                currentTab = this@HomeTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun PremiumBackground() {
    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        // Create a sophisticated gradient mesh
        val width = size.width
        val height = size.height
        
        // Dark gradient base
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0F0F1E),
                    Color(0xFF1A1A2E),
                    Color(0xFF16213E)
                ),
                startY = 0f,
                endY = height
            )
        )
        
        // Subtle purple accent gradient
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF7B2CBF).copy(alpha = 0.1f),
                    Color.Transparent
                ),
                center = Offset(width * 0.8f, height * 0.2f),
                radius = width * 0.6f
            )
        )
        
        // Blue accent gradient
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2196F3).copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(width * 0.2f, height * 0.7f),
                radius = width * 0.5f
            )
        )
    }
}

@Composable
fun AnimatedBackgroundOrbs() {
    val infiniteTransition = rememberInfiniteTransition()
    
    val orb1Y by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val orb2Y by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(25000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.3f)
    ) {
        // Floating orb 1
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF667EEA).copy(alpha = 0.4f),
                    Color.Transparent
                )
            ),
            radius = 150f,
            center = Offset(
                x = size.width * 0.2f,
                y = size.height * orb1Y
            )
        )
        
        // Floating orb 2
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFF093FB).copy(alpha = 0.4f),
                    Color.Transparent
                )
            ),
            radius = 120f,
            center = Offset(
                x = size.width * 0.8f,
                y = size.height * orb2Y
            )
        )
    }
}

@Composable
fun ElegantLogoSection() {
    val infiniteTransition = rememberInfiniteTransition()
    
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing)
        )
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
    ) {
        // Rotating gradient border
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .scale(breathingScale)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val strokeWidth = 3.dp.toPx()
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF667EEA),
                        Color(0xFFF093FB),
                        Color(0xFF667EEA)
                    )
                ),
                radius = size.minDimension / 2 - strokeWidth / 2,
                style = Stroke(width = strokeWidth)
            )
        }
        
        // Logo container
        Surface(
            modifier = Modifier
                .size(100.dp)
                .scale(breathingScale),
            shape = CircleShape,
            color = Color(0xFF1A1A2E),
            shadowElevation = 12.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF667EEA).copy(alpha = 0.1f),
                                Color(0xFFF093FB).copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer {
                            shadowElevation = 8.dp.toPx()
                        },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
fun ModernModeSelector(
    isRoot: Boolean,
    onModeChange: (Boolean) -> Unit
) {
    val selectedOffset by animateDpAsState(
        targetValue = if (isRoot) 122.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    
    Surface(
        modifier = Modifier
            .width(260.dp)
            .height(60.dp),
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF1A1A2E),
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Sliding selector background
            Surface(
                modifier = Modifier
                    .offset(x = selectedOffset + 4.dp)
                    .size(124.dp, 52.dp)
                    .align(Alignment.CenterStart),
                shape = RoundedCornerShape(26.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = if (isRoot) listOf(
                                    Color(0xFFF093FB),
                                    Color(0xFFF5576C)
                                ) else listOf(
                                    Color(0xFF667EEA),
                                    Color(0xFF764BA2)
                                )
                            )
                        )
                )
            }
            
            // Options
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeButton(
                    text = "NON-ROOT",
                    isSelected = !isRoot,
                    onClick = { onModeChange(false) }
                )
                
                ModeButton(
                    text = "ROOT",
                    isSelected = isRoot,
                    onClick = { onModeChange(true) }
                )
            }
        }
    }
}

@Composable
fun ModeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(300)
    )
    
    Box(
        modifier = Modifier
            .width(124.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun PremiumActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    isPressed: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    val infiniteTransition = rememberInfiniteTransition()
    val shimmer by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing)
        )
    )
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1A1A2E),
        shadowElevation = if (isPressed) 4.dp else 8.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = gradientColors.map { it.copy(alpha = 0.1f) }
                        )
                    )
            )
            
            // Shimmer effect
            if (isPressed) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.1f),
                                Color.Transparent
                            ),
                            startX = size.width * shimmer,
                            endX = size.width * (shimmer + 0.5f)
                        )
                    )
                }
            }
            
            // Content
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon with gradient
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(
                            Brush.linearGradient(colors = gradientColors),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
                
                Spacer(Modifier.width(20.dp))
                
                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        lineHeight = 18.sp
                    )
                }
                
                Spacer(Modifier.weight(1f))
                
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun StatsSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatCard("1.5M+", "Active Users", Color(0xFF667EEA))
        StatCard("99.9%", "Positive Reviews", Color(0xFFF093FB))
    }
}

@Composable
fun StatCard(
    value: String,
    label: String,
    color: Color
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { 20 })
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun SocialLinksSection(context: android.content.Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SocialButton(
            icon = painterResource(R.drawable.ic_telegram),
            color = Color(0xFF26A5E4),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/snapenhance_chat")))
            }
        )
        
        SocialButton(
            icon = painterResource(R.drawable.ic_github),
            color = Color(0xFF6E5494),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance")))
            }
        )
        
        SocialButton(
            iconVector = Icons.Default.Favorite,
            color = Color(0xFFE91E63),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance?tab=readme-ov-file#donate")))
            }
        )
    }
}

@Composable
fun SocialButton(
    icon: Painter? = null,
    iconVector: ImageVector? = null,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = CircleShape,
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                icon != null -> Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = color
                )
                iconVector != null -> Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = color
                )
            }
        }
    }
}

@Composable
fun CreditsSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Created with",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color(0xFFE91E63)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "by ΞTΞRNAL & rhunk",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.7f),
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun ModernBottomBar(
    currentTab: Tab,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
        color = Color(0xFF0F0F1E).copy(alpha = 0.98f)
    ) {
        Box {
            // Top gradient line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF667EEA),
                                Color(0xFFF093FB),
                                Color.Transparent
                            )
                        )
                    )
            )
            
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 60.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    icon = Icons.Default.Home,
                    label = "Home",
                    isSelected = true,
                    onClick = { currentTab.navigation?.navigateTo(HomeTab::class) }
                )
                
                BottomNavItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    isSelected = false,
                    onClick = { currentTab.navigation?.navigateTo(SettingsTab::class) }
                )
            }
        }
    }
}

@Composable
fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF667EEA) else Color.White.copy(alpha = 0.5f),
        animationSpec = tween(300)
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(12.dp)
    ) {
        Box {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color.copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                        .align(Alignment.Center)
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier
                    .size(26.dp)
                    .align(Alignment.Center),
                tint = color
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
