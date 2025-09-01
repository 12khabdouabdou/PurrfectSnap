package me.rhunk.snapenhance.manager.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import me.rhunk.snapenhance.manager.ui.tab.Navigation
import me.rhunk.snapenhance.manager.ui.tab.SharedConfig
import me.rhunk.snapenhance.manager.ui.tab.impl.HomeTab
import me.rhunk.snapenhance.manager.ui.tab.impl.ManualPatchTab
import me.rhunk.snapenhance.manager.ui.tab.impl.AutoPatchTab
import me.rhunk.snapenhance.manager.ui.tab.impl.SettingsTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme(
                colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (isSystemInDarkTheme()) dynamicDarkColorScheme(LocalContext.current)
                    else dynamicLightColorScheme(LocalContext.current)
                } else darkColorScheme()
            ) {
                // Build your navigation controller and shared config
                val navHostController = rememberNavController()
                val sharedConfig = remember { SharedConfig(this) }
                val tabs = listOf(
                    HomeTab(),
                    ManualPatchTab(),
                    AutoPatchTab(),
                    SettingsTab()
                )
                // Instantiate your repo's Navigation system
                val navigation = remember {
                    Navigation(
                        navHostController = navHostController,
                        tabs = tabs,
                        defaultTab = HomeTab::class
                    ).also {
                        tabs.forEach { tab ->
                            tab.navigation = it
                            tab.sharedConfig = sharedConfig
                        }
                    }
                }
                Scaffold(
                    floatingActionButton = { navigation.FloatingActionButtons() },
                    floatingActionButtonPosition = FabPosition.End,
                ) { paddingValues ->
                    navigation.NavigationHost(paddingValues)
                }
            }
        }
    }
}
