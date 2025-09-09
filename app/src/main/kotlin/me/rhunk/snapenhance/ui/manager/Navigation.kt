package me.rhunk.snapenhance.ui.manager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navigation
import me.rhunk.snapenhance.RemoteSideContext

@OptIn(ExperimentalMaterial3Api::class)
class Navigation(
    private val context: RemoteSideContext,
    private val navController: NavHostController,
    val routes: Routes = Routes(context).also {
        it.navController = navController
    }
) {
    @Composable
    fun TopBar() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }
        val canGoBack = remember(navBackStackEntry) {
            currentRoute?.let {
                !it.routeInfo.primary || it.routeInfo.childIds.contains(routes.currentDestination)
            } == true
        }
        TopAppBar(title = {
            currentRoute?.apply {
                title?.invoke() ?: routeInfo.translatedKey?.value?.let {
                    Text(text = it)
                }
            }
        }, navigationIcon = {
            val backButtonAnimation by animateFloatAsState(if (canGoBack) 1f else 0f,
                label = "backButtonAnimation"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = backButtonAnimation }
                    .width(lerp(0.dp, 48.dp, backButtonAnimation))
                    .height(48.dp)
            ) {
                IconButton(
                    onClick = {
                        if (canGoBack) {
                            navController.popBackStack()
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        }, actions = {
            currentRoute?.topBarActions?.invoke(this)
        })
    }

    /**
     * Floating bottom navigation bar—dynamically adapts label width only if needed.
     */
    @Composable
    fun FloatingBottomBar() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }
        val primaryRoutes = remember { routes.getRoutes().filter { it.routeInfo.showInNavBar } }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp,
                tonalElevation = 5.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    primaryRoutes.forEach { route ->
                        NavigationBarItem(
                            alwaysShowLabel = true,
                            icon = {
                                Icon(imageVector = route.routeInfo.icon, contentDescription = null)
                            },
                            label = {
                                val label = remember(context.translation.loadedLocale) {
                                    context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"]
                                }
                                val isLong = label.length > 11 // threshold, adjust if needed
                                Text(
                                    text = label,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    maxLines = if (isLong) 2 else 1,
                                    overflow = if (isLong) TextOverflow.Ellipsis else TextOverflow.Clip,
                                    softWrap = isLong,
                                    modifier = if (isLong) {
                                        Modifier
                                            .widthIn(max = 80.dp)
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                    } else {
                                        Modifier.wrapContentWidth(Alignment.CenterHorizontally)
                                    }
                                )
                            },
                            selected = currentRoute == route,
                            onClick = { route.navigateReset() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun FloatingActionButton() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }?.floatingActionButton?.invoke()
    }

    @Composable
    fun Content(paddingValues: PaddingValues, startDestination: String) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            Modifier.padding(paddingValues),
            enterTransition = { fadeIn(tween(100)) },
            exitTransition = { fadeOut(tween(100)) }
        ) {
            routes.getRoutes().filter { it.parentRoute == null }.forEach { route ->
                val children = routes.getRoutes().filter { it.parentRoute == route }
                if (children.isEmpty()) {
                    val isSummaryScreen = route.routeInfo.id == Routes.CONFIG_IMPORT_CONFIRMATION_ROUTE || route.routeInfo.id == Routes.CONFIG_EXPORT_SUMMARY_ROUTE || route.routeInfo.id == Routes.FRIEND_TRACKER_CONFIG_EXPORT_ROUTE || route.routeInfo.id == Routes.FRIEND_TRACKER_CONFIG_IMPORT_ROUTE
                    val isCoolScreen = route.routeInfo.id.startsWith("edit_rule") || route.routeInfo.id == "friend_tracker_catalog" || route.routeInfo.id == "manage_script_repos"
                    composable(
                        route.routeInfo.id,
                        enterTransition = {
                            if (isSummaryScreen) slideInHorizontally { it }
                            else if (isCoolScreen) slideInHorizontally(animationSpec = tween(300)) { it / 2 } + fadeIn(animationSpec = tween(300))
                            else fadeIn(tween(100))
                        },
                        exitTransition = {
                            if (isSummaryScreen) slideOutHorizontally { -it }
                            else if (isCoolScreen) slideOutHorizontally(animationSpec = tween(300)) { -it / 2 } + fadeOut(animationSpec = tween(300))
                            else fadeOut(tween(100))
                        },
                        popEnterTransition = {
                            if (isSummaryScreen) slideInHorizontally { -it }
                            else if (isCoolScreen) slideInHorizontally(animationSpec = tween(300)) { -it / 2 } + fadeIn(animationSpec = tween(300))
                            else fadeIn(tween(100))
                        },
                        popExitTransition = {
                            if (isSummaryScreen) slideOutHorizontally { it }
                            else if (isCoolScreen) slideOutHorizontally(animationSpec = tween(300)) { it / 2 } + fadeOut(animationSpec = tween(300))
                            else fadeOut(tween(100))
                        }
                    ) {
                        route.content.invoke(it)
                    }
                    route.customComposables.invoke(this)
                } else {
                    navigation("main_" + route.routeInfo.id, route.routeInfo.id) {
                        composable("main_" + route.routeInfo.id) {
                            route.content.invoke(it)
                        }
                        children.forEach { child ->
                            composable(child.routeInfo.id) {
                                child.content.invoke(it)
                            }
                        }
                        route.customComposables.invoke(this)
                    }
                }
            }
        }
    }
}
