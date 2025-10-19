@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package me.rhunk.snapenhance.ui.setup

import me.rhunk.snapenhance.common.ui.Requirements

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import me.rhunk.snapenhance.ui.util.scaleOnPress
import me.rhunk.snapenhance.SharedContextHolder
import me.rhunk.snapenhance.common.ui.AppMaterialTheme
import me.rhunk.snapenhance.ui.setup.screens.SetupScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.MappingsScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.PermissionsScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.PickLanguageScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.SaveFolderScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.RemoteBypassConsentScreen

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
        fun hasRequirement(requirement: Int) = requirements and requirement == requirement
        val requiredScreens = mutableListOf<SetupScreen>()
        with(requiredScreens) {
            val isFirstRun = hasRequirement(Requirements.FIRST_RUN)
            if (isFirstRun || hasRequirement(Requirements.LANGUAGE)) {
                add(PickLanguageScreen().apply { route = "language" })
            }
            if (isFirstRun || hasRequirement(Requirements.GRANT_PERMISSIONS)) {
                add(PermissionsScreen().apply { route = "permissions" })
            }
            if (isFirstRun || hasRequirement(Requirements.SAVE_FOLDER)) {
                add(SaveFolderScreen().apply { route = "saveFolder" })
            }
            if (hasRequirement(Requirements.REMOTE_BYPASS_CONSENT)) {
                add(RemoteBypassConsentScreen().apply { route = "remoteBypassConsent" })
            }
            if (isFirstRun || hasRequirement(Requirements.MAPPINGS)) {
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
        setContent {
            val navController = rememberNavController()
            var canGoNext by remember { mutableStateOf(false) }
            fun nextScreen() {
                setupContext.log.error("SetupActivity: nextScreen called, canGoNext is $canGoNext")
                if (!canGoNext) return
                requiredScreens.firstOrNull()?.onLeave()
                if (requiredScreens.size > 1) {
                    canGoNext = false
                    requiredScreens.removeAt(0)
                    navController.navigate(requiredScreens.first().route)
                } else {
                    endActivity()
                }
            }
            AppMaterialTheme {
                val background = MaterialTheme.colorScheme.background
                val isLight = background.luminance() > 0.5f
                val view = LocalView.current
                @Suppress("DEPRECATION")
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    // Set transparent system bars and light/dark icons
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = isLight
                    insetsController.isAppearanceLightNavigationBars = isLight
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(background)
                ) {
                    val bottomPadding = 110.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomPadding)
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
                                screen.allowNext = {
                                    setupContext.log.error("SetupActivity: allowNext called with $it for screen ${screen.route}")
                                    canGoNext = it
                                }
                                screen.goNext = {
                                    if (screen.route == requiredScreens.first().route) {
                                        setupContext.log.error("SetupActivity: goNext called for screen ${screen.route}")
                                        canGoNext = true
                                        nextScreen()
                                    } else {
                                        setupContext.log.error("SetupActivity: goNext called for non-current screen ${screen.route}, ignoring")
                                    }
                                }
                                composable(
                                    screen.route,
                                    enterTransition = { slideInHorizontally { it } },
                                    exitTransition = { slideOutHorizontally { -it } },
                                    popEnterTransition = { slideInHorizontally { -it } },
                                    popExitTransition = { slideOutHorizontally { it } }
                                ) {
                                    BackHandler(true) {}
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        screen.Content()
                                    }
                                }
                            }
                        }
                    }
                    val alpha: Float by animateFloatAsState(if (canGoNext) 1f else 0f,
                        label = "NextButton"
                    )
                    val nextSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    FilledIconButton(
                        onClick = { nextScreen() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 50.dp)
                            .size(60.dp)
                            .alpha(alpha)
                            .scaleOnPress(nextSrc),
                        interactionSource = nextSrc
                    ) {
                        Icon(
                            imageVector = if (requiredScreens.size <= 1 && canGoNext) {
                                Icons.Default.Check
                            } else {
                                Icons.AutoMirrored.Filled.ArrowForwardIos
                            },
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}
