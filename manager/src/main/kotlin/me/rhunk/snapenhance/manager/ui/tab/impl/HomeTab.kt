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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab
import kotlin.math.*
import kotlin.random.Random

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        var isRoot by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        var selectedButtonIndex by remember { mutableStateOf<Int?>(null) }
        
        // Parallax scroll state
        val scrollState = rememberScrollState()
        val density = LocalDensity.current
        
        // Animated particles
        val particles = remember { List(15) { AnimatedParticle() } }
        
        // Navigation with animation
        LaunchedEffect(selectedButtonIndex) {
            when (selectedButtonIndex) {
                0 -> {
                    delay(300)
                    navigation?.navigateTo(ManualPatchTab::class)
                    selectedButtonIndex = null
                }
                1 -> {
                    delay(300)
                    navigation?.navigateTo(AutoPatchTab::class)
                    selectedButtonIndex = null
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D0E1C),
                            Color(0xFF1A1B3A),
                            Color(0xFF252654)
                        )
                    )
                )
        ) {
            // Animated background mesh
            AnimatedMeshBackground()
            
            // Floating particles
            particles.forEach { particle ->
                FloatingParticle(particle)
            }
            
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))
                
                // Futuristic logo with hologram effect
                HolographicLogo()
                
                Spacer(Modifier.height(32.dp))
                
                // Animated title with glitch effect
                GlitchText(
                    text = "SNAPENHANCE",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black
                )
                
                Spacer(Modifier.height(8.dp))
                
                // Subtitle with typing animation
                TypewriterText(
                    text = "Welcome to the future of Snapchat",
                    fontSize = 16.sp,
                    color = Color(0xFF8B92FF)
                )
                
                Spacer(Modifier.height(32.dp))
                
                // Interactive mode selector with liquid animation
                LiquidModeSelector(
                    isRoot = isRoot,
                    onModeChange = { isRoot = it }
                )
                
                Spacer(Modifier.height(40.dp))
                
                // Main action cards with 3D effect
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FuturisticActionCard(
                        modifier = Modifier.weight(1f),
                        title = "MANUAL",
                        subtitle = "PATCH",
                        icon = Icons.Default.Build,
                        gradient = listOf(
                            Color(0xFF6B5FFF),
                            Color(0xFF9C4FFF)
                        ),
                        isSelected = selectedButtonIndex == 0,
                        onClick = { selectedButtonIndex = 0 }
                    )
                    
                    FuturisticActionCard(
                        modifier = Modifier.weight(1f),
                        title = "AUTO",
                        subtitle = "PATCH",
                        icon = Icons.Default.AutoAwesome,
                        gradient = listOf(
                            Color(0xFFFF5F9E),
                            Color(0xFFFF8F5F)
                        ),
                        isSelected = selectedButtonIndex == 1,
                        onClick = { selectedButtonIndex = 1 }
                    )
                }
                
                Spacer(Modifier.height(32.dp))
                
                // Stats dashboard with animated counters
                GlassmorphicStatsCard()
                
                Spacer(Modifier.height(32.dp))
                
                // Social links with orbital animation
                OrbitalSocialLinks(context)
                
                Spacer(Modifier.height(24.dp))
                
                // Credits with pulse animation
                PulsingCredits()
                
                Spacer(Modifier.height(100.dp))
            }
            
            // Futuristic bottom navigation
            FuturisticBottomNav(
                currentTab = this@HomeTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

// Holographic logo with 3D rotation
@Composable
fun HolographicLogo() {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing)
        )
    )
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(120.dp)
    ) {
        // Holographic rings
        repeat(3) { index ->
            val delay = index * 400
            val ringScale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
            val ringAlpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
            
            Box(
                Modifier
                    .size(100.dp)
                    .scale(ringScale)
                    .border(
                        width = 2.dp,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color(0xFF00D4FF),
                                Color(0xFFFF00FF),
                                Color(0xFF00FF88),
                                Color(0xFF00D4FF)
                            )
                        ),
                        shape = CircleShape
                    )
                    .alpha(ringAlpha)
            )
        }
        
        // Main logo with glow
        Surface(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                },
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF00D4FF),
                        Color(0xFFFF00FF)
                    )
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF2A2B5A).copy(alpha = 0.9f),
                                Color(0xFF1A1B3A).copy(alpha = 0.7f)
                            )
                        )
                    )
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

// Glitch text effect
@Composable
fun GlitchText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight
) {
    val infiniteTransition = rememberInfiniteTransition()
    var glitchActive by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2000, 5000))
            glitchActive = true
            delay(200)
            glitchActive = false
        }
    }
    
    Box {
        // Shadow layers for glitch effect
        if (glitchActive) {
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = Color(0xFFFF00FF).copy(alpha = 0.8f),
                modifier = Modifier.offset(x = 2.dp, y = 0.dp)
            )
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = Color(0xFF00FFFF).copy(alpha = 0.8f),
                modifier = Modifier.offset(x = (-2).dp, y = 0.dp)
            )
        }
        
        // Main text with gradient
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = Color.White,
            modifier = Modifier.drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF),
                            Color(0xFF00D4FF),
                            Color(0xFFFF00FF)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    blendMode = BlendMode.SrcAtop
                )
            }
        )
    }
}

// Typewriter animation text
@Composable
fun TypewriterText(
    text: String,
    fontSize: TextUnit,
    color: Color
) {
    var displayedText by remember { mutableStateOf("") }
    
    LaunchedEffect(text) {
        displayedText = ""
        text.forEachIndexed { index, _ ->
            delay(50)
            displayedText = text.substring(0, index + 1)
        }
    }
    
    Row {
        Text(
            text = displayedText,
            fontSize = fontSize,
            color = color,
            fontWeight = FontWeight.Medium
        )
        
        // Blinking cursor
        val cursorAlpha by rememberInfiniteTransition().animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(500),
                repeatMode = RepeatMode.Reverse
            )
        )
        
        Text(
            text = "|",
            fontSize = fontSize,
            color = color.copy(alpha = cursorAlpha),
            fontWeight = FontWeight.Medium
        )
    }
}

// Liquid mode selector with morphing animation
@Composable
fun LiquidModeSelector(
    isRoot: Boolean,
    onModeChange: (Boolean) -> Unit
) {
    val animatedOffset by animateDpAsState(
        targetValue = if (isRoot) 110.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    Surface(
        modifier = Modifier
            .width(240.dp)
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF1E1F3A).copy(alpha = 0.8f),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8B92FF).copy(alpha = 0.5f),
                    Color(0xFFFF5F9E).copy(alpha = 0.5f)
                )
            )
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            // Liquid background
            Box(
                modifier = Modifier
                    .offset(x = animatedOffset)
                    .size(120.dp, 56.dp)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = if (isRoot) listOf(
                                Color(0xFFFF5F9E),
                                Color(0xFFFF8F5F)
                            ) else listOf(
                                Color(0xFF6B5FFF),
                                Color(0xFF9C4FFF)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            )
            
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeOption(
                    text = "NON-ROOT",
                    isSelected = !isRoot,
                    onClick = { onModeChange(false) }
                )
                ModeOption(
                    text = "ROOT",
                    isSelected = isRoot,
                    onClick = { onModeChange(true) }
                )
            }
        }
    }
}

@Composable
fun ModeOption(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (isSelected) Color.White else Color(0xFF8B92FF),
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

// Futuristic action card with 3D tilt effect
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun FuturisticActionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    val rotation by animateFloatAsState(
        targetValue = if (isSelected) 5f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    Box(
        modifier = modifier
            .height(180.dp)
            .scale(scale)
            .graphicsLayer {
                rotationZ = rotation
            }
    ) {
        // Glow effect
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(20.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = gradient.map { it.copy(alpha = 0.6f) }
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            )
        }
        
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1E1F3A).copy(alpha = 0.9f),
            border = BorderStroke(
                width = 2.dp,
                brush = Brush.linearGradient(colors = gradient)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Animated icon
                val iconRotation by rememberInfiniteTransition().animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(10000, easing = LinearEasing)
                    )
                )
                
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer { rotationY = iconRotation }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.linearGradient(colors = gradient),
                                    blendMode = BlendMode.SrcAtop
                                )
                            },
                        tint = Color.White
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = gradient.first()
                )
            }
        }
    }
}

// Glassmorphic stats card
@Composable
fun GlassmorphicStatsCard() {
    val stats = listOf(
        Triple("Active Users", "10.2K", Color(0xFF00D4FF)),
        Triple("Patches", "523", Color(0xFFFF00FF)),
        Triple("Success Rate", "99.8%", Color(0xFF00FF88))
    )
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1E1F3A).copy(alpha = 0.4f),
        border = BorderStroke(
            width = 1.dp,
            color = Color(0xFF8B92FF).copy(alpha = 0.3f)
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2A2B5A).copy(alpha = 0.2f),
                            Color(0xFF1A1B3A).copy(alpha = 0.1f)
                        )
                    )
                )
                .blur(10.dp, BlurredEdgeTreatment.Unbounded)
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            stats.forEach { (label, value, color) ->
                AnimatedStatItem(label, value, color)
            }
        }
    }
}

@Composable
fun AnimatedStatItem(label: String, value: String, color: Color) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color(0xFF8B92FF)
            )
        }
    }
}

// Orbital social links
@Composable
fun OrbitalSocialLinks(context: android.content.Context) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing)
        )
    )
    
    Box(
        modifier = Modifier
            .size(200.dp)
            .graphicsLayer { rotationZ = rotation },
        contentAlignment = Alignment.Center
    ) {
        // Center text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CONNECT",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8B92FF)
            )
        }
        
        // Orbiting social buttons
        val socialLinks = listOf(
            Triple(painterResource(R.drawable.ic_telegram), Color(0xFF299EFF)) { 
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/snapenhance_chat")))
            },
            Triple(painterResource(R.drawable.ic_github), Color(0xFFA088FA)) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance")))
            },
            Triple(null as Painter?, Color(0xFFFF5F9E)) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance?tab=readme-ov-file#donate")))
            }
        )
        
        socialLinks.forEachIndexed { index, (icon, color, action) ->
            val angle = (index * 120f) - rotation
            val radius = 80.dp
            
            Box(
                modifier = Modifier
                    .offset(
                        x = (radius.value * cos(Math.toRadians(angle.toDouble()))).dp,
                        y = (radius.value * sin(Math.toRadians(angle.toDouble()))).dp
                    )
            ) {
                NeonSocialButton(
                    icon = icon,
                    iconVector = if (icon == null) Icons.Outlined.Favorite else null,
                    color = color,
                    onClick = action
                )
            }
        }
    }
}

@Composable
fun NeonSocialButton(
    icon: Painter?,
    iconVector: ImageVector?,
    color: Color,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp)
    ) {
        // Neon glow
        Box(
            modifier = Modifier
                .size((40 * pulse).dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
                .blur(10.dp)
        )
        
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            shape = CircleShape,
            color = Color(0xFF1E1F3A),
            border = BorderStroke(2.dp, color)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    icon != null -> Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = color
                    )
                    iconVector != null -> Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = color
                    )
                }
            }
        }
    }
}

// Pulsing credits
@Composable
fun PulsingCredits() {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Row(
        modifier = Modifier.scale(scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Made with ",
            fontSize = 12.sp,
            color = Color(0xFF8B92FF)
        )
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color(0xFFFF5F9E)
        )
        Text(
            text = " by ΞTΞRNAL & rhunk",
            fontSize = 12.sp,
            color = Color(0xFF8B92FF)
        )
    }
}

// Futuristic bottom navigation
@Composable
fun FuturisticBottomNav(
    currentTab: Tab,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF1E1F3A).copy(alpha = 0.95f),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8B92FF).copy(alpha = 0.5f),
                    Color(0xFFFF5F9E).copy(alpha = 0.5f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                icon = Icons.Default.Home,
                label = "Home",
                isSelected = true,
                onClick = { currentTab.navigation?.navigateTo(HomeTab::class) }
            )
            NavItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                isSelected = false,
                onClick = { currentTab.navigation?.navigateTo(SettingsTab::class) }
            )
        }
    }
}

@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF00D4FF) else Color(0xFF8B92FF),
        animationSpec = tween(300)
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = color
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// Animated mesh background
@Composable
fun AnimatedMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition()
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing)
        )
    )
    
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.1f)
    ) {
        val step = 50.dp.toPx()
        val amplitude = 20f
        
        for (x in 0..size.width.toInt() step step.toInt()) {
            for (y in 0..size.height.toInt() step step.toInt()) {
                val offsetX = amplitude * sin(animatedProgress * 2 * PI + x / 100)
                val offsetY = amplitude * cos(animatedProgress * 2 * PI + y / 100)
                
                drawCircle(
                    color = Color(0xFF8B92FF),
                    radius = 2f,
                    center = Offset(x.toFloat() + offsetX.toFloat(), y.toFloat() + offsetY.toFloat())
                )
            }
        }
    }
}

// Floating particle animation
data class AnimatedParticle(
    val x: Float = Random.nextFloat(),
    val y: Float = Random.nextFloat(),
    val size: Float = Random.nextFloat() * 4 + 2,
    val speedX: Float = Random.nextFloat() * 0.002f - 0.001f,
    val speedY: Float = Random.nextFloat() * 0.002f - 0.001f,
    val color: Color = listOf(
        Color(0xFF00D4FF),
        Color(0xFFFF00FF),
        Color(0xFF00FF88),
        Color(0xFF8B92FF)
    ).random()
)

@Composable
fun FloatingParticle(particle: AnimatedParticle) {
    var position by remember { mutableStateOf(Offset(particle.x, particle.y)) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            position = Offset(
                (position.x + particle.speedX).coerceIn(0f, 1f),
                (position.y + particle.speedY).coerceIn(0f, 1f)
            )
        }
    }
    
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.6f)
        ) {
            drawCircle(
                color = particle.color,
                radius = particle.size,
                center = Offset(
                    position.x * size.width,
                    position.y * size.height
                ),
                blendMode = BlendMode.Plus
            )
        }
    }
}
