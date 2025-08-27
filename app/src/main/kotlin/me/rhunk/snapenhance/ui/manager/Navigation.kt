package me.rhunk.snapenhance.ui.manager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
     * Floating bottom navigation bar—absolutely aligned, minimal system gesture bar gap.
     */
    @Composable
    fun FloatingBottomBar() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }
        val primaryRoutes = remember { routes.getRoutes().filter { it.routeInfo.showInNavBar } }

        // Calculate the real gesture bar inset (if present) and add just a bit of padding above
        val view = LocalView.current
        val density = LocalDensity.current
        // default fallback (minimal for most phones), will be replaced if system inset found
        var bottomPadding: Dp = 8.dp
        ViewCompat.getRootWindowInsets(view)?.let { insets ->
            val gestureInset = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gestureInset > 0) {
                with(density) { bottomPadding = gestureInset.toDp() + 6.dp }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding, start = 16.dp, end = 16.dp),
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
                                Text(
                                    textAlign = TextAlign.Center,
                                    softWrap = false,
                                    fontSize = 12.sp,
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                    text = remember(context.translation.loadedLocale) { context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"] },
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
                    composable(route.routeInfo.id) {
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
