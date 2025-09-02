package me.rhunk.snapenhance.manager.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.topjohnwu.superuser.Shell
import me.rhunk.snapenhance.manager.BuildConfig
import me.rhunk.snapenhance.manager.data.SharedConfig
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.ui.Navigation
import me.rhunk.snapenhance.manager.ui.tab.impl.HomeTab
import me.rhunk.snapenhance.manager.ui.tab.impl.SettingsTab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.InstallPackageTab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.RepackageTab
import me.rhunk.snapenhance.manager.ui.tab.impl.ManualPatchTab
import me.rhunk.snapenhance.manager.ui.tab.impl.AutoPatchTab
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    companion object {
        private val primaryTabs = listOf(
            HomeTab::class,
            ManualPatchTab::class,
            AutoPatchTab::class,
            SettingsTab::class,
            InstallPackageTab::class,
            RepackageTab::class
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Make status bar and navigation bar transparent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        // Permission check for Install Unknown Apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = packageManager
            if (!pm.canRequestPackageInstalls()) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
        
        val tabs = primaryTabs.mapNotNull {
            runCatching { it.java.constructors.first().newInstance() as Tab }.getOrNull()
        }.toMutableList().apply {
            forEach { it.init(this@MainActivity) }
            fun addNestedTabsRecursively(tabs: List<Tab>) {
                tabs.forEach { tab ->
                    add(tab)
                    addNestedTabsRecursively(tab.nestedTabs)
                }
            }
            toList().forEach { addNestedTabsRecursively(it.nestedTabs) }
        }
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                val navHostController = rememberNavController()
                val sharedConfig = remember { SharedConfig(this) }
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
                
                // Set system bar colors
                val view = LocalView.current
                val darkTheme = isSystemInDarkTheme()
                
                DisposableEffect(darkTheme) {
                    val window = (view.context as ComponentActivity).window
                    val insetsController = WindowInsetsControllerCompat(window, view)
                    
                    // Set light status bar icons for dark backgrounds
                    insetsController.isAppearanceLightStatusBars = false
                    insetsController.isAppearanceLightNavigationBars = false
                    
                    onDispose {}
                }
                
                // Root Box with no padding to extend edge-to-edge
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Scaffold(
                        floatingActionButton = { navigation.FloatingActionButtons() },
                        floatingActionButtonPosition = FabPosition.End,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ) { _ ->
                        // Don't use innerPadding here to allow content to draw behind system bars
                        navigation.NavigationHost(androidx.compose.foundation.layout.PaddingValues())
                    }
                }
            }
        }
    }
}
