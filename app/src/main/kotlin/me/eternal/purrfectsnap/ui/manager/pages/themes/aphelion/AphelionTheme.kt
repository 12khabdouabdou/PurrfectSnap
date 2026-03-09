package me.eternal.purrfectsnap.ui.manager.pages.themes.aphelion

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfectsnap.ui.manager.ThemeContract
import me.eternal.purrfectsnap.ui.manager.pages.TasksRootSection
import me.eternal.purrfectsnap.ui.manager.pages.features.FeaturesRootSection
import me.eternal.purrfectsnap.ui.manager.pages.home.HomeAbout
import me.eternal.purrfectsnap.ui.manager.pages.home.HomeLogs
import me.eternal.purrfectsnap.ui.manager.pages.home.HomeRootSection
import me.eternal.purrfectsnap.ui.manager.pages.home.HomeSettings
import me.eternal.purrfectsnap.ui.manager.pages.scripting.ScriptingRootSection
import me.eternal.purrfectsnap.ui.manager.pages.social.SocialRootSection
import me.eternal.purrfectsnap.ui.manager.pages.tracker.FriendTrackerManagerRoot

object AphelionTheme : ThemeContract {
    @Composable
    override fun HomeRootSection.HomeScreen(nav: NavBackStackEntry) {
        AphelionHomeScreen(nav)
    }

    @Composable
    override fun HomeSettings.SettingsScreen(nav: NavBackStackEntry) {
        AphelionSettingsScreen(nav)
    }

    @Composable
    override fun HomeAbout.AboutScreen(nav: NavBackStackEntry) {
        AphelionAboutScreen(nav)
    }

    @Composable
    override fun HomeLogs.LogsScreen(nav: NavBackStackEntry) {
        AphelionLogsScreen(nav)
    }

    @Composable
    override fun SocialRootSection.SocialScreen(nav: NavBackStackEntry) {
        AphelionSocialScreen(nav)
    }

    @Composable
    override fun TasksRootSection.TasksScreen(nav: NavBackStackEntry) {
        AphelionTasksScreen(nav)
    }

    @Composable
    override fun FeaturesRootSection.FeaturesScreen(nav: NavBackStackEntry) {
        AphelionFeaturesScreen(nav)
    }

    @Composable
    override fun ScriptingRootSection.ScriptingScreen(nav: NavBackStackEntry) {
        AphelionScriptingScreen(nav)
    }

    @Composable
    override fun FriendTrackerManagerRoot.FriendTrackerScreen(nav: NavBackStackEntry) {
        TrackerScreenContent(nav)
    }
}
