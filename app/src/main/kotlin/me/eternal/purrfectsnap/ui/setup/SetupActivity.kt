@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package me.eternal.purrfectsnap.ui.setup

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.eternal.purrfectsnap.RemoteSideContext
import me.eternal.purrfectsnap.SharedContextHolder
import me.eternal.purrfectsnap.common.ui.AppMaterialTheme
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.setup.screens.SetupScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.InstallModeScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.InstallMode
import me.eternal.purrfectsnap.ui.setup.screens.impl.MappingsScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.PermissionsScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.PickLanguageScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.PatchSnapchatScreen
import me.eternal.purrfectsnap.ui.setup.screens.impl.SaveFolderScreen
import me.eternal.purrfectsnap.ui.util.scaleOnPress

private data class SetupStepMeta(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val setupContext = SharedContextHolder.remote(this).apply {
            activity = this@SetupActivity
        }
        fun endActivity() {
            setupContext.reload()
            finish()
        }
        val requirements = intent.getIntExtra("requirements", Requirements.FIRST_RUN)
        val setupPrefs = setupContext.sharedPreferences
        fun hasRequirement(requirement: Int) = requirements and requirement == requirement
        val wasInProgress = setupPrefs.getBoolean("setup_in_progress", false)
        val isFirstRunFlow = hasRequirement(Requirements.FIRST_RUN) || wasInProgress
        val persistedRoute = setupPrefs.getString("setup_current_route", null)
        val persistedSkipPatch = setupPrefs.getBoolean("setup_skip_patch", false)
        val skipPatchChoice = mutableStateOf(persistedSkipPatch)

        fun persistProgress(route: String, skipPatch: Boolean, inProgress: Boolean = true) {
            if (!isFirstRunFlow) return
            setupPrefs.edit()
                .putBoolean("setup_in_progress", inProgress)
                .putString("setup_current_route", route)
                .putBoolean("setup_skip_patch", skipPatch)
                .apply()
        }

        fun clearProgress() {
            setupPrefs.edit()
                .remove("setup_in_progress")
                .remove("setup_current_route")
                .remove("setup_skip_patch")
                .apply()
        }

        val requiredScreens = mutableListOf<SetupScreen>().apply {
            if (isFirstRunFlow || hasRequirement(Requirements.LANGUAGE)) {
                add(PickLanguageScreen().apply { route = "language" })
                if (isFirstRunFlow) {
                    add(InstallModeScreen { mode ->
                        skipPatchChoice.value = mode == InstallMode.ROOT
                    }.apply { route = "installMode" })
                }
                if (isFirstRunFlow) {
                    add(PatchSnapchatScreen().apply { route = "patchSnapchat" })
                }
            }
            if (isFirstRunFlow || hasRequirement(Requirements.GRANT_PERMISSIONS)) {
                add(PermissionsScreen().apply { route = "permissions" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.SAVE_FOLDER)) {
                add(SaveFolderScreen().apply { route = "saveFolder" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.MAPPINGS)) {
                add(MappingsScreen().apply { route = "mappings" })
            }
        }

        if (requiredScreens.isEmpty()) {
            endActivity()
            return
        }
        requiredScreens.forEach { screen ->
            screen.context = setupContext
            screen.init()
        }

        if (!isFirstRunFlow) {
            clearProgress()
            skipPatchChoice.value = false
        }

        setContent {
            val navController = rememberNavController()
            var canGoNext by remember { mutableStateOf(false) }
            var currentRoute by rememberSaveable {
                mutableStateOf(
                    when {
                        requiredScreens.first().route == "language" -> requiredScreens.first().route
                        persistedRoute?.takeIf { route -> requiredScreens.any { it.route == route } } != null -> persistedRoute
                        else -> requiredScreens.first().route
                    }
                )
            }
            val skipPatch by rememberSaveable { skipPatchChoice }
            val visibleScreens = remember(skipPatch) {
                requiredScreens.filterNot { skipPatch && it is PatchSnapchatScreen }
            }
            val stepMeta = remember(skipPatch) { visibleScreens.map { it.meta(setupContext) } }
            val currentStepIndex = visibleScreens.indexOfFirst { it.route == currentRoute }.let {
                if (it == -1) 0 else it
            }
            val animatedProgress by animateFloatAsState(
                targetValue = (currentStepIndex + 1f) / stepMeta.size.toFloat(),
                label = "SetupProgress"
            )
            LaunchedEffect(skipPatch) {
                val adjustedRoute = visibleScreens.firstOrNull { it.route == currentRoute }?.route
                    ?: run {
                        val currentIndex = requiredScreens.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
                        val nextVisible = requiredScreens.drop(currentIndex + 1)
                            .firstOrNull { screen -> visibleScreens.any { it.route == screen.route } }
                        nextVisible?.route ?: visibleScreens.firstOrNull()?.route
                    }
                adjustedRoute?.let {
                    if (it != currentRoute) currentRoute = it
                }
            }

            LaunchedEffect(currentRoute, skipPatch) {
                persistProgress(currentRoute, skipPatch, true)
                if (navController.currentDestination?.route != currentRoute) {
                    navController.navigate(currentRoute) {
                        popUpTo(requiredScreens.first().route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                canGoNext = false
            }

            fun nextScreen() {
                if (!canGoNext) return
                canGoNext = false
                val currentScreen = visibleScreens.getOrNull(currentStepIndex)
                val proceed = {
                    currentScreen?.onLeave()
                    if (currentStepIndex < visibleScreens.lastIndex) {
                        val nextRoute = visibleScreens[currentStepIndex + 1].route
                        currentRoute = nextRoute
                    } else {
                        clearProgress()
                        endActivity()
                    }
                }
                currentScreen?.onNext { proceed() } ?: proceed()
            }

            AppMaterialTheme {
                val view = LocalView.current
                val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = false
                    insetsController.isAppearanceLightNavigationBars = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    SetupAuroraBackground()
                    SetupTopBar()
                    val bottomPadding = 118.dp + navBarPadding
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 110.dp,
                                bottom = bottomPadding
                            )
                            .statusBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SetupHeader(
                            currentStep = stepMeta[currentStepIndex],
                            currentIndex = currentStepIndex,
                            total = stepMeta.size
                        )
                        SetupProgressBar(animatedProgress)
                        val scrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = requiredScreens.first().route,
                                enterTransition = { fadeIn() },
                                exitTransition = { fadeOut() },
                                popEnterTransition = { fadeIn() },
                                popExitTransition = { fadeOut() }
                            ) {
                                requiredScreens.forEach { screen ->
                                    val screenRoute = screen.route
                                    screen.allowNext = allowNext@{ canGoNextFlag ->
                                        if (screenRoute != currentRoute) return@allowNext
                                        canGoNext = canGoNextFlag
                                    }
                                    screen.goNext = goNext@{
                                        if (screenRoute != currentRoute) return@goNext
                                        canGoNext = true
                                        nextScreen()
                                    }
                                    composable(
                                        screen.route,
                                        enterTransition = { slideInHorizontally { it } },
                                        exitTransition = { slideOutHorizontally { -it } },
                                        popEnterTransition = { slideInHorizontally { -it } },
                                        popExitTransition = { slideOutHorizontally { it } }
                                    ) {
                                        BackHandler(true) {}
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 10.dp, vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .widthIn(max = 560.dp)
                                                    .verticalScroll(scrollState),
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                screen.Content()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    NextButton(
                        enabled = canGoNext,
                        isFinalStep = currentStepIndex >= stepMeta.lastIndex,
                        onClick = { nextScreen() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 32.dp)
                    )
                }
            }
        }
    }
}

private fun SetupScreen.meta(context: RemoteSideContext): SetupStepMeta {
    val translation = context.translation
    return when (this) {
        is PickLanguageScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.dialogs.select_language"] ?: "Choose your language",
            subtitle = "Tune PurrfectSnap to speak your voice before anything else.",
            icon = Icons.Filled.Language
        )

        is InstallModeScreen -> SetupStepMeta(
            route = route,
            title = "Choose your device",
            subtitle = "Pick the path that matches how you'll install PurrfectSnap.",
            icon = Icons.Filled.VerifiedUser
        )

        is PermissionsScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.permissions.dialog"] ?: "Essential permissions",
            subtitle = "Grant the essentials so overlays, downloads, and alerts stay reliable.",
            icon = Icons.Filled.VerifiedUser
        )

        is PatchSnapchatScreen -> SetupStepMeta(
            route = route,
            title = "Auto Patcher",
            subtitle = "Streamlined download, patch, and install with a single flow.",
            icon = Icons.Filled.Download
        )

        is SaveFolderScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.dialogs.save_folder"] ?: "Where should we save?",
            subtitle = "Pick your personal vault so snaps land exactly where you expect.",
            icon = Icons.Filled.Folder
        )

        is MappingsScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.mappings.dialog"] ?: "Mapping your Snapchat",
            subtitle = "We calibrate everything to your install so the magic works flawlessly.",
            icon = Icons.Filled.AutoAwesome
        )

        else -> SetupStepMeta(route, route, route, Icons.Filled.Check)
    }
}

@Composable
private fun SetupAuroraBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "setupAurora")
    val driftX by infiniteTransition.animateFloat(
        initialValue = -90f,
        targetValue = 140f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftX"
    )
    val driftY by infiniteTransition.animateFloat(
        initialValue = 60f,
        targetValue = -120f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftY"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PurrfectPalette.backgroundGradient)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val primaryGlow = PurrfectPalette.glowPrimary.copy(alpha = 0.36f)
            val secondaryGlow = PurrfectPalette.glowSecondary.copy(alpha = 0.28f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    start = Offset(x = size.width * 0.15f, y = 0f),
                    end = Offset(x = size.width * 0.75f, y = size.height * 0.6f)
                ),
                size = this.size
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        PurrfectPalette.glowPrimary.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    start = Offset(x = 0f, y = size.height * 0.72f),
                    end = Offset(x = size.width, y = size.height)
                ),
                size = this.size
            )
        }
    }
}

@Composable
private fun SetupTopBar() {
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .padding(top = topPadding),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.07f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.45f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PurrfectSnap",
                color = PurrfectPalette.textPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun SetupHeader(
    currentStep: SetupStepMeta,
    currentIndex: Int,
    total: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(30),
                color = Color.White.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = "Step ${currentIndex + 1} of $total",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(30),
                color = PurrfectPalette.glowPrimary.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PurrfectPalette.glowPrimary.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = currentStep.icon,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = currentStep.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private enum class StepState { COMPLETE, ACTIVE, UPCOMING }

@Composable
private fun StepBadgesRow(steps: List<SetupStepMeta>, currentStep: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        steps.forEachIndexed { index, step ->
            val state = when {
                index < currentStep -> StepState.COMPLETE
                index == currentStep -> StepState.ACTIVE
                else -> StepState.UPCOMING
            }
            StepBadge(step, state)
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(28.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.05f),
                                    PurrfectPalette.glowPrimary.copy(alpha = 0.4f),
                                    Color.White.copy(alpha = 0.05f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun StepBadge(step: SetupStepMeta, state: StepState) {
    val baseColor = when (state) {
        StepState.COMPLETE -> PurrfectPalette.glowSecondary
        StepState.ACTIVE -> PurrfectPalette.glowPrimary
        StepState.UPCOMING -> Color.White.copy(alpha = 0.35f)
    }
    val background = when (state) {
        StepState.UPCOMING -> Color.White.copy(alpha = 0.05f)
        StepState.COMPLETE -> Color.White.copy(alpha = 0.08f)
        StepState.ACTIVE -> Color.White.copy(alpha = 0.12f)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            baseColor.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                color = baseColor.copy(alpha = 0.22f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(min = 0.dp, max = 160.dp)
            ) {
                Text(
                    text = step.title,
                    color = Color.White,
                    fontWeight = if (state == StepState.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val hint = when (state) {
                    StepState.COMPLETE -> "Checked off"
                    StepState.ACTIVE -> "In progress"
                    StepState.UPCOMING -> "Ready next"
                }
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SetupProgressBar(progress: Float) {
    val gradient = Brush.horizontalGradient(
        listOf(
            PurrfectPalette.glowSecondary,
            PurrfectPalette.glowPrimary
        )
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.07f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = 0.14f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(gradient)
            )
        }
    }
}

@Composable
private fun SetupContentCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(34.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.45f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                )
            )
        ),
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .background(PurrfectPalette.cardOverlay)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = RoundedCornerShape(34.dp)
                )
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun NextButton(
    enabled: Boolean,
    isFinalStep: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.6f, label = "NextButtonAlpha")
    val gradient = Brush.horizontalGradient(
        listOf(
            PurrfectPalette.glowSecondary,
            PurrfectPalette.glowPrimary
        )
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .alpha(alpha)
            .scaleOnPress(interactionSource)
            .clip(RoundedCornerShape(40.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.24f),
                shape = RoundedCornerShape(40.dp)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        color = Color.White.copy(alpha = if (enabled) 0.07f else 0.03f)
    ) {
        Box(
            modifier = Modifier
                .background(if (enabled) gradient else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f))))
                .padding(horizontal = 28.dp, vertical = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isFinalStep) "Finish setup" else "Continue",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Icon(
                    imageVector = if (isFinalStep) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}
