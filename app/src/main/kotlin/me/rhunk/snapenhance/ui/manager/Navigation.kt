package me.rhunk.snapenhance.ui.manager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
// overscroll APIs not available in current compose; skip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
 

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
class Navigation(
    private val context: RemoteSideContext,
    private val navController: NavHostController,
    val routes: Routes = Routes(context).also {
        it.navController = navController
    }
) {
    var openBottomBarCustomization by mutableStateOf(false)
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
            IconButton(onClick = { openBottomBarCustomization = true }) {
                Icon(Icons.Filled.Tune, contentDescription = null)
            }
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
        var highlightId by remember { mutableStateOf<String?>(null) }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val baseItemWidth = 92.dp
            val containerPadding = 24.dp
            val targetBarWidth = if (selectedRoutes.size < 5) {
                (baseItemWidth * selectedRoutes.size.toFloat() + containerPadding)
            } else null
            val animatedBarWidth by animateDpAsState(targetValue = targetBarWidth ?: 0.dp, label = "barWidth")
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                modifier = Modifier
                    .then(
                        if (targetBarWidth != null) Modifier.width(animatedBarWidth) else Modifier.fillMaxWidth()
                    )
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
                                val label = if (route.routeInfo.id == "friend_tracker") {
                                    "Tracker"
                                } else {
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

            if (openBottomBarCustomization) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { openBottomBarCustomization = false },
                    sheetState = sheetState,
                ) {
                    // Revamped, animated, polished customization UI
                    Column(Modifier.fillMaxWidth()) {
                        val listState = rememberLazyListState()
                        var headerWidth by remember { mutableStateOf(0) }
                        val headerPull = remember { Animatable(0f) }
                        val density = LocalDensity.current
                        val shimmer = rememberInfiniteTransition(label = "headerShimmer")
                        val shift by shimmer.animateFloat(
                            initialValue = -headerWidth.toFloat(),
                            targetValue = headerWidth.toFloat(),
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 5000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        // Gradient header
                        val extraHeader = with(density) { (headerPull.value / 2f).toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp + extraHeader)
                                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                                .background(
                                    brush = run {
                                        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
                                        val primaryA = if (isLight) 0.18f else 0.12f
                                        val midA = if (isLight) 0.06f else 0.04f
                                        val tertiaryA = if (isLight) 0.22f else 0.14f
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = primaryA),
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = midA),
                                                MaterialTheme.colorScheme.tertiary.copy(alpha = tertiaryA)
                                            ),
                                            start = Offset(shift, 0f),
                                            end = Offset(shift + headerWidth.toFloat(), 0f)
                                        )
                                    }
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                                .graphicsLayer { translationY = -listState.firstVisibleItemScrollOffset * 0.15f }
                                .onGloballyPositioned { headerWidth = it.size.width }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { },
                                        onDrag = { _, dragAmount ->
                                            val dy = dragAmount.y
                                            if (dy > 0) {
                                                val newVal = (headerPull.value + dy).coerceIn(0f, 200f)
                                                headerPull.snapTo(newVal)
                                            }
                                        },
                                        onDragEnd = {
                                            // bounce back
                                            launch { headerPull.animateTo(0f, animationSpec = tween(300)) }
                                        },
                                        onDragCancel = {
                                            launch { headerPull.animateTo(0f, animationSpec = tween(300)) }
                                        }
                                    )
                                }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Customize Bottom Bar",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Reorder, add or remove tabs. Max of five.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "Shown Tabs",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
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
                            var dragStartIndex by remember { mutableStateOf(-1) }
                            var rowHeight by remember { mutableStateOf(0) }
                            val appeared = remember { mutableStateMapOf<String, Boolean>() }

                            LaunchedEffect(highlightId, selectedTabIds) {
                                val hid = highlightId
                                if (hid != null) {
                                    val index = selectedTabIds.indexOf(hid)
                                    if (index >= 0) listState.animateScrollToItem(index)
                                }
                            }

                            // overscroll behavior not available in current compose version
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                contentPadding = PaddingValues(bottom = 8.dp),
                                state = listState
                            ) {
                                itemsIndexed(selectedTabIds, key = { _, id -> id }) { index, id ->
                                    val route = availableRouteMap[id] ?: return@itemsIndexed
                                    val label = if (route.routeInfo.id == "friend_tracker") "Tracker" else context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"]
                                    val isDragging = draggingId == id
                                    val isHighlighted = highlightId == id
                                    LaunchedEffect(id) {
                                        // Staggered appearance for a lively feel
                                        if (appeared[id] != true) {
                                            kotlin.runCatching { kotlinx.coroutines.delay((index * 30).toLong()) }
                                            appeared[id] = true
                                        }
                                    }
                                    AnimatedVisibility(
                                        visible = appeared[id] == true,
                                        enter = (
                                            if (index == 0)
                                                scaleIn(animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f), initialScale = 0.9f)
                                            else scaleIn(tween(180), initialScale = 0.98f)
                                        ) + slideInVertically(animationSpec = tween(200), initialOffsetY = { it / 2 }) + fadeIn(tween(200)),
                                        exit = slideOutVertically(animationSpec = tween(160)) + fadeOut(tween(160))
                                    ) {
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .then(if (isHighlighted) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp)) else Modifier)
                                            .animateItemPlacement()
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                if (isDragging) {
                                                    scaleX = 1.02f
                                                    scaleY = 1.02f
                                                }
                                            }
                                            .onGloballyPositioned { coords ->
                                                if (rowHeight == 0) rowHeight = coords.size.height
                                            }
                                            .pointerInput(id) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingId = id
                                                        dragStartIndex = selectedTabIds.indexOf(id)
                                                        dragDelta = 0f
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    },
                                                    onDrag = { _: PointerInputChange, dragAmount: Offset ->
                                                        dragDelta += dragAmount.y
                                                        if (rowHeight > 0 && dragStartIndex >= 0) {
                                                            val currentIndex = selectedTabIds.indexOf(id)
                                                            val deltaRows = kotlin.math.round(dragDelta / rowHeight.toFloat()).toInt()
                                                            val targetIndex = (dragStartIndex + deltaRows).coerceIn(0, selectedTabIds.lastIndex)
                                                            if (targetIndex != currentIndex) {
                                                                val list = selectedTabIds.toMutableList()
                                                                list.removeAt(currentIndex)
                                                                list.add(targetIndex, id)
                                                                selectedTabIds = list
                                                                saveSelected(selectedTabIds)
                                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingId = null
                                                        dragDelta = 0f
                                                        dragStartIndex = -1
                                                    },
                                                    onDragCancel = {
                                                        draggingId = null
                                                        dragDelta = 0f
                                                        dragStartIndex = -1
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
                                                Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(8.dp))
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
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Available Tabs",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableRoutes.forEach { route ->
                                val id = route.routeInfo.id
                                val already = selectedTabIds.contains(id)
                                val label = if (id == "friend_tracker") "Tracker" else context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"]
                                AnimatedVisibility(
                                    visible = !already && selectedTabIds.size < 5,
                                    enter = scaleIn(tween(160), initialScale = 0.95f) + fadeIn(tween(180)) + slideInVertically(tween(180), initialOffsetY = { it / 3 }),
                                    exit = scaleOut(tween(120)) + fadeOut(tween(120)) + slideOutVertically(tween(120))
                                ) {
                                    AssistChip(
                                        onClick = {
                                            if (!already && selectedTabIds.size < 5) {
                                                selectedTabIds = selectedTabIds + id
                                                saveSelected(selectedTabIds)
                                            }
                                        },
                                        label = { Text(text = label) },
                                        leadingIcon = { Icon(route.routeInfo.icon, contentDescription = null) },
                                        enabled = true
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val primaryColor = MaterialTheme.colorScheme.primary
                            var pulse by remember { mutableStateOf(false) }
                            val pulseScale by animateFloatAsState(targetValue = if (pulse) 1.05f else 1f, label = "donePulse")
                            var rippleKey by remember { mutableStateOf(0) }
                            var rippleProgress by remember { mutableStateOf(0f) }
                            var resetRippleKey by remember { mutableStateOf(0) }
                            var resetRippleProgress by remember { mutableStateOf(0f) }
                            val scope = rememberCoroutineScope()
                            LaunchedEffect(Unit) {
                                pulse = true
                                kotlin.runCatching { kotlinx.coroutines.delay(350) }
                                pulse = false
                            }
                            Box {
                                LaunchedEffect(resetRippleKey) {
                                    if (resetRippleKey > 0) {
                                        resetRippleProgress = 0f
                                        val start = System.currentTimeMillis()
                                        val dur = 160L
                                        while (true) {
                                            val t = (System.currentTimeMillis() - start).coerceAtMost(dur)
                                            resetRippleProgress = t / dur.toFloat()
                                            if (t >= dur) break
                                            kotlinx.coroutines.delay(10)
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        resetRippleKey++
                                        selectedTabIds = defaultOrder
                                        saveSelected(selectedTabIds)
                                    },
                                    modifier = Modifier.drawBehind {
                                        if (resetRippleKey > 0 && resetRippleProgress in 0f..1f) {
                                            val radius = size.minDimension * (0.1f + 0.7f * resetRippleProgress)
                                            drawCircle(
                                                color = primaryColor.copy(alpha = 0.07f * (1f - resetRippleProgress)),
                                                radius = radius,
                                                center = this.center
                                            )
                                        }
                                    }
                                ) { Text(text = "Reset") }
                            }
                            Box {
                                // Custom light ripple behind the Done button
                                LaunchedEffect(rippleKey) {
                                    if (rippleKey > 0) {
                                        rippleProgress = 0f
                                        val start = System.currentTimeMillis()
                                        val dur = 180L
                                        while (true) {
                                            val t = (System.currentTimeMillis() - start).coerceAtMost(dur)
                                            rippleProgress = t / dur.toFloat()
                                            if (t >= dur) break
                                            kotlinx.coroutines.delay(10)
                                        }
                                    }
                                }
                                Button(
                                    onClick = {
                                        // Trigger ripple then close with a tiny delay so it’s visible
                                        rippleKey++
                                        scope.launch {
                                            kotlinx.coroutines.delay(120)
                                            openBottomBarCustomization = false
                                        }
                                    },
                                    modifier = Modifier
                                        .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                                        .drawBehind {
                                            if (rippleKey > 0 && rippleProgress in 0f..1f) {
                                                val radius = size.minDimension * (0.2f + 0.8f * rippleProgress)
                                                drawCircle(
                                                    color = primaryColor.copy(alpha = 0.08f * (1f - rippleProgress)),
                                                    radius = radius,
                                                    center = this.center
                                                )
                                            }
                                        }
                                ) { Text(text = "Done") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    

    @Composable
    fun Fab() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }?.floatingActionButton?.invoke()
    }

    @Composable
    fun NavContent(paddingValues: PaddingValues, startDestination: String) {
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

    // Backwards-compat wrappers for existing call sites
    @Composable
    fun FloatingActionButton() = Fab()

    @Composable
    fun Content(paddingValues: PaddingValues, startDestination: String) =
        NavContent(paddingValues, startDestination)
}
