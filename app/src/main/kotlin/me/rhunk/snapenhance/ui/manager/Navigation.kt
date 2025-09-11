package me.rhunk.snapenhance.ui.manager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex

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
        val haptic = LocalHapticFeedback.current
        // Build available route set and customizable selection
        val availableRoutes = remember { listOf(
            routes.tasks,
            routes.features,
            routes.home,
            routes.social,
            routes.scripting,
            routes.friendTracker
        ) }
        val availableRouteMap = remember(availableRoutes) { availableRoutes.associateBy { it.routeInfo.id } }

        val prefs = remember { context.sharedPreferences }
        val defaultOrder = remember { listOf("tasks", "features", "home", "social", "scripts") }

        fun loadSelected(): List<String> {
            val raw = prefs.getString("manager_nav_tabs", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val cleaned = raw.filter { availableRouteMap.containsKey(it) }
            val list = (if (cleaned.isNotEmpty()) cleaned else defaultOrder).distinct()
            return list.take(5)
        }

        fun saveSelected(ids: List<String>) {
            prefs.edit().putString("manager_nav_tabs", ids.joinToString(",")).apply()
        }

        var selectedTabIds by remember { mutableStateOf(loadSelected()) }
        val selectedRoutes = remember(selectedTabIds) { selectedTabIds.mapNotNull { availableRouteMap[it] } }
        var showCustomize by remember { mutableStateOf(false) }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            showCustomize = true
                        })
                    }
                    .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = MaterialTheme.colorScheme.primary,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    selectedRoutes.forEach { route ->
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

            if (showCustomize) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showCustomize = false },
                    sheetState = sheetState,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "Customize Bottom Bar",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap to add, remove or reorder tabs (max 5)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))

                        Text(text = "Shown Tabs", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (selectedTabIds.isEmpty()) {
                            Text(
                                text = "No tabs selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val haptic = LocalHapticFeedback.current
                            var draggingId by remember { mutableStateOf<String?>(null) }
                            var dragDelta by remember { mutableStateOf(0f) }
                            val itemPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() } // id -> (topPx, heightPx)

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                itemsIndexed(selectedTabIds, key = { _, id -> id }) { index, id ->
                                    val route = availableRouteMap[id] ?: return@itemsIndexed
                                    val label = context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"]
                                    val isDragging = draggingId == id
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .animateItemPlacement()
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                if (isDragging) {
                                                    translationY = dragDelta
                                                    scaleX = 1.02f
                                                    scaleY = 1.02f
                                                }
                                            }
                                            .onGloballyPositioned { coords ->
                                                val top = coords.positionInRoot().y.toInt()
                                                val height = coords.size.height
                                                itemPositions[id] = top to height
                                            }
                                            .pointerInput(id) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingId = id
                                                        dragDelta = 0f
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    },
                                                    onDrag = { _, dragAmount ->
                                                        dragDelta += dragAmount.y
                                                        val currentIndex = selectedTabIds.indexOf(id)
                                                        val currentPos = itemPositions[id]
                                                        if (currentPos != null) {
                                                            val currentCenter = currentPos.first + currentPos.second / 2f + dragDelta
                                                            // Move down
                                                            val nextId = selectedTabIds.getOrNull(currentIndex + 1)
                                                            val nextCenter = nextId?.let { itemPositions[it]?.let { p -> p.first + p.second / 2f } }
                                                            if (nextId != null && nextCenter != null && currentCenter > nextCenter) {
                                                                val height = itemPositions[nextId]?.second?.toFloat() ?: 0f
                                                                val list = selectedTabIds.toMutableList()
                                                                list.removeAt(currentIndex)
                                                                list.add(currentIndex + 1, id)
                                                                selectedTabIds = list
                                                                saveSelected(selectedTabIds)
                                                                dragDelta -= height
                                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                return@detectDragGestures
                                                            }
                                                            // Move up
                                                            val prevId = selectedTabIds.getOrNull(currentIndex - 1)
                                                            val prevCenter = prevId?.let { itemPositions[it]?.let { p -> p.first + p.second / 2f } }
                                                            if (prevId != null && prevCenter != null && currentCenter < prevCenter) {
                                                                val height = itemPositions[prevId]?.second?.toFloat() ?: 0f
                                                                val list = selectedTabIds.toMutableList()
                                                                list.removeAt(currentIndex)
                                                                list.add(currentIndex - 1, id)
                                                                selectedTabIds = list
                                                                saveSelected(selectedTabIds)
                                                                dragDelta += height
                                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                return@detectDragGestures
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingId = null
                                                        dragDelta = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggingId = null
                                                        dragDelta = 0f
                                                    }
                                                )
                                            },
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(12.dp)
                                                    .fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(route.routeInfo.icon, contentDescription = null)
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    text = label,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                IconButton(onClick = {
                                                    if (selectedTabIds.size > 1) {
                                                        selectedTabIds = selectedTabIds.toMutableList().also { it.removeAt(index) }
                                                        saveSelected(selectedTabIds)
                                                    }
                                                }) {
                                                    Icon(Icons.Filled.Close, contentDescription = null)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(text = "Available Tabs", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableRoutes.forEach { route ->
                                val id = route.routeInfo.id
                                val already = selectedTabIds.contains(id)
                                val label = context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"]
                                AssistChip(
                                    onClick = {
                                        if (!already && selectedTabIds.size < 5) {
                                            selectedTabIds = selectedTabIds + id
                                            saveSelected(selectedTabIds)
                                        }
                                    },
                                    label = { Text(text = label) },
                                    leadingIcon = { Icon(route.routeInfo.icon, contentDescription = null) },
                                    enabled = !already && selectedTabIds.size < 5
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                selectedTabIds = defaultOrder
                                saveSelected(selectedTabIds)
                            }) { Text(text = "Reset") }
                            Button(onClick = { showCustomize = false }) { Text(text = "Done") }
                        }
                        Spacer(Modifier.height(8.dp))
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
