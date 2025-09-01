package me.rhunk.snapenhance.manager.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
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
                // List of all tab classes
                val allTabs = listOf(
                    HomeTab(),
                    ManualPatchTab(),
                    AutoPatchTab(),
                    SettingsTab()
                )
                // Construct navigation as in your project
                val navigation = Navigation(
                    tabs = allTabs,
                    defaultTab = HomeTab::class
                )
                Scaffold(
                    // Don't use bottomBar or topBar here! HomeTab and others draw what they want.
                    floatingActionButton = { navigation.FloatingActionButtons() },
                    floatingActionButtonPosition = FabPosition.End
                ) { paddingValues ->
                    navigation.NavigationHost(paddingValues)
                }
            }
        }
    }
}
