package me.rhunk.snapenhance.ui.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import me.rhunk.snapenhance.RemoteSideContext
import me.rhunk.snapenhance.SharedContextHolder
import me.rhunk.snapenhance.common.ui.AppMaterialTheme
import me.rhunk.snapenhance.common.ui.ThemeMode
import me.rhunk.snapenhance.common.ui.ThemePreferences

class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController
    private lateinit var managerContext: RemoteSideContext

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::navController.isInitialized.not()) return
        intent.getStringExtra("route")?.let { route ->
            navController.popBackStack()
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        managerContext = SharedContextHolder.remote(this).apply {
            activity = this@MainActivity
            checkForRequirements()
        }
        val routes = Routes(managerContext)
        routes.getRoutes().forEach { it.init() }
        setContent {
            val context = LocalContext.current
            val themeMode by ThemePreferences.getThemeModeFlow(context).collectAsState(initial = ThemeMode.SYSTEM)
            val isDarkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            navController = rememberNavController()
            val navigation = remember {
                Navigation(managerContext, navController, routes.also {
                    it.navController = navController
                })
            }
            val startDestination = remember { intent.getStringExtra("route") ?: routes.home.routeInfo.id }

            AppMaterialTheme(isDarkTheme = isDarkTheme) {
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

                // Edge-to-edge Box, background base color always fills screen
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Scaffold(
                        containerColor = Color.Transparent, // To keep background color under system bars
                        topBar = { navigation.TopBar() },
                        floatingActionButton = { navigation.FloatingActionButton() }
                    ) { innerPadding ->
                        // Ensure content fits with system bars in all orientations
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            navigation.Content(PaddingValues(0.dp), startDestination)
                        }
                    }
                    // Floating bottom bar, always at proper bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    ) {
                        navigation.FloatingBottomBar()
                    }
                }
            }
        }
    }
}
