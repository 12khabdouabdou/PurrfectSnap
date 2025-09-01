package me.rhunk.snapenhance.manager.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
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
                colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (isSystemInDarkTheme()) dynamicDarkColorScheme(LocalContext.current)
                    else dynamicLightColorScheme(LocalContext.current)
                } else darkColorScheme()
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
                Scaffold(
                    bottomBar = { navigation.BottomBar() }, // << Unified bar for all tabs.
                    floatingActionButton = { navigation.FloatingActionButtons() },
                    floatingActionButtonPosition = FabPosition.End,
                ) { innerPadding ->
                    navigation.NavigationHost(innerPadding)
                }
            }
        }
    }
}
