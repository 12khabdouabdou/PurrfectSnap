package me.rhunk.snapenhance.manager.ui

import android.os.Bundle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import me.rhunk.snapenhance.manager.ui.tab.Tab
import kotlin.reflect.KClass


class Navigation(
    val navHostController: NavHostController,
    private val tabs: List<Tab>,
    private val defaultTab: KClass<out Tab>
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TopBar() {
        val navBackStackEntry by navHostController.currentBackStackEntryAsState()
        val currentTab = tabs.firstOrNull { it.route == navBackStackEntry?.destination?.route }
        TopAppBar(title = {
            Text(text = currentTab?.route ?: "")
        }, navigationIcon =  {
            currentTab?.icon?.let {
                Icon(imageVector = it, contentDescription = null)
            }
        }, actions = {
            currentTab?.TopBar()
        })
    }

    @Composable
    fun FloatingActionButtons() {
        val navBackStackEntry by navHostController.currentBackStackEntryAsState()
        tabs.firstOrNull { it.route == navBackStackEntry?.destination?.route }?.FloatingActionButtons()
    }

    fun navigateTo(tab: KClass<out Tab>, noHistory: Boolean = false) {
        navHostController.navigate(tabs.first { it::class == tab }.route) {
            if (noHistory) {
                restoreState = false
                launchSingleTop = true
                popUpTo(navHostController.graph.findStartDestination().id) {
                    saveState = true
                }
            }
        }
    }


    fun navigateTo(tab: KClass<out Tab>, args: Bundle, noHistory: Boolean = false) {
        navigateTo(tab, noHistory)
        navHostController.currentBackStackEntry?.savedStateHandle?.set("args", args)
    }

    @Composable
    fun NavigationHost(
        innerPadding: PaddingValues
    ) {
        NavHost(
            navHostController,
            startDestination = tabs.first { it::class == defaultTab }.route,
            Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) }
        ) {
            tabs.forEach { tab ->
                tab.build(this)
            }
        }
    }


    @Composable
    fun BottomBar() {
        val navBackStackEntry by navHostController.currentBackStackEntryAsState()
        val primaryTabs = remember { tabs.filter { it.isPrimary } }

        // Container to overlay a sliding pill indicator behind NavigationBar items
        Box(Modifier.fillMaxWidth()) {
            var barWidthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
            val itemCount = primaryTabs.size.coerceAtLeast(1)
            val density = androidx.compose.ui.platform.LocalDensity.current

            // Determine selected index by matching current destination hierarchy
            val selectedIndex = remember(navBackStackEntry) {
                var idx = 0
                primaryTabs.forEachIndexed { i, tab ->
                    val tabSubRoutes = tab.nestedTabs.map { it.route }
                    val isSelected = navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route || tabSubRoutes.contains(it.route) } == true
                    if (isSelected) idx = i
                }
                idx
            }

            val itemWidthPx = remember(barWidthPx, itemCount) { if (itemCount > 0) barWidthPx / itemCount else 0f }
            val offsetAnim = androidx.compose.runtime.remember { Animatable(0f) }
            var lastSelectedIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(selectedIndex) }
            // Initialize when width is known
            LaunchedEffect(itemWidthPx) {
                if (itemWidthPx > 0f) offsetAnim.snapTo(selectedIndex * itemWidthPx)
            }
            // Spring animation with subtle overshoot
            LaunchedEffect(selectedIndex, itemWidthPx) {
                if (itemWidthPx <= 0f) return@LaunchedEffect
                val dist = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                val damping = when {
                    dist >= 3 -> 0.65f
                    dist == 2 -> 0.75f
                    else -> 0.90f
                }
                val stiffness = Spring.StiffnessMediumLow
                offsetAnim.animateTo(
                    targetValue = selectedIndex * itemWidthPx,
                    animationSpec = spring(dampingRatio = damping, stiffness = stiffness)
                )
                lastSelectedIndex = selectedIndex
            }

            val horizontalInset = 8.dp
            val indicatorWidth = with(density) { itemWidthPx.toDp() } - horizontalInset * 2

            // Indicator background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { barWidthPx = it.size.width.toFloat() }
            ) {
                // Squash-and-stretch animation during tab change
                val motionProgress = androidx.compose.runtime.remember { Animatable(1f) }
                LaunchedEffect(selectedIndex) {
                    motionProgress.snapTo(0f)
                    val dist = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                    val dur = when {
                        dist >= 3 -> 440
                        dist == 2 -> 380
                        else -> 320
                    }
                    motionProgress.animateTo(1f, animationSpec = tween(durationMillis = dur, easing = FastOutSlowInEasing))
                }
                val pulse = sin(PI * motionProgress.value).toFloat()
                val distForScale = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                val scaleXBase = 0.18f
                val scaleXExtra = 0.06f
                val scaleYBase = 0.06f
                val scaleYExtra = 0.02f
                val mult = (distForScale - 1).coerceAtLeast(0)
                val scaleXAnim = 1f + (scaleXBase + scaleXExtra * mult) * pulse
                val scaleYAnim = 1f - (scaleYBase + scaleYExtra * mult) * pulse

                if (barWidthPx > 0f && itemCount > 0) {
                    val offsetX = with(density) { offsetAnim.value.toDp() } + horizontalInset
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .width(indicatorWidth.coerceAtLeast(0.dp))
                            .offset(x = offsetX)
                            .padding(vertical = 8.dp)
                            .graphicsLayer { scaleX = scaleXAnim; scaleY = scaleYAnim }
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .then(
                                Modifier
                            )
                    )
                }
            }

            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
                primaryTabs.forEach { tab ->
                    val tabSubRoutes = remember { tab.nestedTabs.map { it.route } }
                    NavigationBarItem(
                        selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route || tabSubRoutes.contains(it.route) } == true,
                        alwaysShowLabel = false,
                        icon = {
                            Icon(imageVector = tab.icon!!, contentDescription = null)
                        },
                        label = {
                            Text(
                                textAlign = TextAlign.Center,
                                softWrap = false,
                                fontSize = 12.sp,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                text = tab.route
                            )
                        },
                        onClick = {
                            navHostController.navigate(tab.route) {
                                popUpTo(navHostController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    }
}
