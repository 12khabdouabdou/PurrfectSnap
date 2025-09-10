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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.os.Build
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        if (currentRoute?.routeInfo?.hasOwnTopBar == true) return
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
            val surfaceColor = MaterialTheme.colorScheme.surface
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 24.dp, // Increased shadow
                tonalElevation = 8.dp, // Increased tonal elevation
                color = surfaceColor.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), // Subtle border
                modifier = Modifier.shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = MaterialTheme.colorScheme.primary,
                    ambientColor = MaterialTheme.colorScheme.primary
                ).then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.blur(radius = 20.dp)
                    } else {
                        Modifier
                    }
                )
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
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
                    val isAddRuleScreen = route.routeInfo.id.startsWith("edit_rule")

                    val animatedRoutes = setOf(
                        "friend_tracker_catalog",
                        "manage_friend_tracker_repos",
                        "manage_script_repos",
                        "manage_repos"
                    )
                    val isAnimatedRoute = animatedRoutes.contains(route.routeInfo.id)

                    val addRuleEnterAnimation = slideInHorizontally(animationSpec = tween(400)) { it }
                    val addRuleExitAnimation = slideOutHorizontally(animationSpec = tween(400)) { -it }
                    val addRulePopEnterAnimation = slideInHorizontally(animationSpec = tween(400)) { -it }
                    val addRulePopExitAnimation = slideOutHorizontally(animationSpec = tween(400)) { it }

                    val animatedRouteEnter = slideInHorizontally(animationSpec = tween(400)) { it }
                    val animatedRouteExit = slideOutHorizontally(animationSpec = tween(400)) { -it }
                    val animatedRoutePopEnter = slideInHorizontally(animationSpec = tween(400)) { -it }
                    val animatedRoutePopExit = slideOutHorizontally(animationSpec = tween(400)) { it }


                    composable(
                        route.routeInfo.id,
                        enterTransition = {
                            when {
                                isSummaryScreen -> slideInHorizontally { it }
                                isAddRuleScreen -> addRuleEnterAnimation
                                isAnimatedRoute -> animatedRouteEnter
                                else -> fadeIn(tween(100))
                            }
                        },
                        exitTransition = {
                            when {
                                isSummaryScreen -> slideOutHorizontally { -it }
                                isAddRuleScreen -> addRuleExitAnimation
                                isAnimatedRoute -> animatedRouteExit
                                else -> fadeOut(tween(100))
                            }
                        },
                        popEnterTransition = {
                            when {
                                isSummaryScreen -> slideInHorizontally { -it }
                                isAddRuleScreen -> addRulePopEnterAnimation
                                isAnimatedRoute -> animatedRoutePopEnter
                                else -> fadeIn(tween(100))
                            }
                        },
                        popExitTransition = {
                            when {
                                isSummaryScreen -> slideOutHorizontally { it }
                                isAddRuleScreen -> addRulePopExitAnimation
                                isAnimatedRoute -> animatedRoutePopExit
                                else -> fadeOut(tween(100))
                            }
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
