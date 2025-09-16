@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
package me.rhunk.snapenhance.ui.setup

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
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.composable
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import me.rhunk.snapenhance.ui.util.scaleOnPress
import me.rhunk.snapenhance.SharedContextHolder
import me.rhunk.snapenhance.common.ui.AppMaterialTheme
import me.rhunk.snapenhance.ui.setup.screens.SetupScreen
import me.rhunk.snapenhance.R
import android.net.Uri
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import me.rhunk.snapenhance.ui.setup.screens.impl.MappingsScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.PermissionsScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.PickLanguageScreen
import me.rhunk.snapenhance.ui.setup.screens.impl.SaveFolderScreen


@Composable
fun VideoPlayer(onCompletion: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.setup_video}")
                setVideoURI(uri)
                setOnCompletionListener {
                    onCompletion()
                }
                start()
            }
        }
    )
}

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
            if (isFirstRun || hasRequirement(Requirements.MAPPINGS)) {
                add(MappingsScreen().apply { route = "mappings" })
            }
        }

        // If there are no required screens, we can just finish the activity
        if (requiredScreens.isEmpty()) {
            endActivity()
            return
        }

        requiredScreens.forEach { screen ->
            screen.context = setupContext
            screen.init()
        }

        setContent {
            val navController = rememberAnimatedNavController()
            var canGoNext by remember { mutableStateOf(false) }
            var showVideo by remember { mutableStateOf(true) }


            fun nextScreen() {
                if (!canGoNext) return
                requiredScreens.firstOrNull()?.onLeave()
                if (requiredScreens.size > 1) {
                    canGoNext = false
                    requiredScreens.removeFirst()
                    navController.navigate(requiredScreens.first().route)
                } else {
                    endActivity()
                }
            }

            AppMaterialTheme {
                val background = MaterialTheme.colorScheme.background
                val isLight = background.luminance() > 0.5f
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = isLight
                    insetsController.isAppearanceLightNavigationBars = isLight
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(background)
                ) {
                    AnimatedVisibility(
                        visible = showVideo,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        VideoPlayer {
                            showVideo = false
                        }
                    }


                    AnimatedVisibility(
                        visible = !showVideo,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        val bottomPadding = 110.dp +
                                WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = bottomPadding)
                        ) {
                            AnimatedNavHost(
                                navController = navController,
                                startDestination = requiredScreens.first().route,
                                enterTransition = { fadeIn() },
                                exitTransition = { fadeOut() },
                                popEnterTransition = { fadeIn() },
                                popExitTransition = { fadeOut() }
                            ) {
                                requiredScreens.forEach { screen ->
                                    screen.allowNext = { canGoNext = it }
                                    screen.goNext = {
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

                        val alpha: Float by animateFloatAsState(
                            if (canGoNext) 1f else 0f,
                            label = "NextButton"
                        )

                        val nextSrc =
                            remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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
                                    Icons.AutoMirrored.Default.ArrowForwardIos
                                },
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }
    }
}
