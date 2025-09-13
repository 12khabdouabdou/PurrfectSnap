package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.R
import me.rhunk.snapenhance.action.EnumQuickActions
import me.rhunk.snapenhance.common.BuildConfig
import me.rhunk.snapenhance.common.action.EnumAction
import me.rhunk.snapenhance.common.ui.TopBarActionButton
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableStateList
import me.rhunk.snapenhance.common.util.ktx.openLink
import me.rhunk.snapenhance.core.ui.Snapenhance
import me.rhunk.snapenhance.storage.getQuickTiles
import me.rhunk.snapenhance.storage.setQuickTiles
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.manager.data.UpdateDownloader
import me.rhunk.snapenhance.ui.manager.data.Updater
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.AlertDialogs
import me.rhunk.snapenhance.ui.util.Motion
import me.rhunk.snapenhance.ui.util.scaleOnPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
 
import java.text.DateFormat

class HomeRootSection : Routes.Route() {
    companion object {
        val cardMargin = 10.dp
    }
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    data class QaCard(val id: String, val name: String, val icon: ImageVector, val action: (Routes) -> Unit)
    private val cardEntries by lazy {
        val list = mutableListOf<QaCard>()
        EnumQuickActions.entries.forEach { q ->
            val name = context.translation["actions.${q.key}.name"]
            list.add(QaCard(id = "quick.${q.key}", name = name, icon = q.icon, action = q.action))
        }
        EnumAction.entries.forEach { a ->
            val name = context.translation["actions.${a.key}.name"]
            list.add(QaCard(id = "action.${a.key}", name = name, icon = a.icon, action = { context.launchActionIntent(a) }))
        }
        list
    }
    private val cards by lazy {
        EnumQuickActions.entries.map {
            (context.translation["actions.${it.key}.name"] to it.icon) to it.action
        }.associate {
            it.first to it.second
        }.toMutableMap().apply {
            EnumAction.entries.forEach { action ->
                this[context.translation["actions.${action.key}.name"] to action.icon] = {
                    context.launchActionIntent(action)
                }
            }
        }
    }
    @Composable
    private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
        OutlinedCard(
            modifier = Modifier
                .padding(start = cardMargin, end = cardMargin)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 10.dp)
            ) {
                content()
            }
        }
    }
    @Composable
    fun ExternalLinkIcon(
        modifier: Modifier = Modifier,
        size: Dp = 32.dp,
        imageVector: ImageVector,
        onClick: (() -> Unit)? = null,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .scaleOnPress(interactionSource)
                .then(
                    if (onClick != null)
                        Modifier.clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
                    else Modifier
                )
                .then(modifier)
        )
    }
    private fun resolveTileKey(name: String): String {
        val entry = cardEntries.firstOrNull { it.name == name }
        return entry?.id ?: name
    }
    private fun getTileSpan(name: String): Pair<Int, Int> {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        val raw = prefs.getString("quick_tile_size_$key", null) ?: "1x1"
        val parts = raw.split('x')
        val w = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        val h = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        return w to h
    }
    private fun setTileSpan(name: String, w: Int, h: Int) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().putString("quick_tile_size_$key", "${w.coerceIn(1,3)}x${h.coerceIn(1,3)}").apply()
    }
    private fun clearTileSpan(name: String) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().remove("quick_tile_size_$key").apply()
    }
    override val title: @Composable (() -> Unit)? = {}
    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }
    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        TopBarActionButton(
            onClick = {
                routes.homeLogs.navigate()
            },
            icon = Icons.Filled.BugReport,
            text = context.translation["manager.routes.home_logs"]
        )
        Spacer(modifier = Modifier.width(8.dp))
        TopBarActionButton(
            onClick = {
                routes.settings.navigate()
            },
            icon = Icons.Filled.Settings,
            text = context.translation["manager.routes.home_settings"]
        )
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember {
            FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium))
        }
        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf()) {
            context.database.getQuickTiles()
        }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null) { Updater.latestRelease }
        var showQuickActionsMenu by remember { mutableStateOf(false) }
        var editMode by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Icon(
                imageVector = Snapenhance, contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 8.dp)
                    .align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = translation.format(
                    "version_title",
                    "versionName" to BuildConfig.VERSION_NAME
                ),
                fontSize = 14.sp,
                fontFamily = avenirNext,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(15.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 5.dp)
            ) {
                ExternalLinkIcon(
                    onClick = { context.androidContext.openLink("https://t.me/snapenhance") },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                )
                ExternalLinkIcon(
                    onClick = { context.androidContext.openLink("https://github.com/rhunk/SnapEnhance") },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_github),
                )
                ExternalLinkIcon(
                    onClick = { context.androidContext.openLink("https://github.com/rhunk/SnapEnhance/wiki") },
                    modifier = Modifier.offset(x = (-3).dp),
                    size = 40.dp,
                    imageVector = Icons.AutoMirrored.Filled.Help,
                )
            }
            if (latestUpdate != null) {
                Spacer(modifier = Modifier.height(10.dp))
                InfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = translation["update_title"],
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                fontSize = 12.sp,
                                text = translation.format(
                                    "update_content",
                                    "version" to (latestUpdate?.versionName ?: "unknown")
                                ),
                                lineHeight = 20.sp,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val downloadState by UpdateDownloader.downloadState.collectAsState()
                        val downloadProgress by UpdateDownloader.downloadProgress.collectAsState()
                        val coroutineScope = rememberCoroutineScope()

                        AnimatedContent(
                            targetState = downloadState,
                            modifier = Modifier.height(40.dp)
                        ) { state ->
                            when (state) {
                                UpdateDownloader.DownloadState.IDLE -> {
                                    Button(
                                        onClick = {
                                            val latest = latestUpdate ?: return@Button
                                            if (latest.workflowId == null) {
                                                context.androidContext.openLink(latest.releaseUrl)
                                                return@Button
                                            }
                                            val supportedAbis = android.os.Build.SUPPORTED_ABIS
                                            var abiName: String? = null
                                            for (abi in supportedAbis) {
                                                when (abi) {
                                                    "arm64-v8a" -> {
                                                        abiName = "armv8"
                                                        break
                                                    }
                                                    "armeabi-v7a" -> {
                                                        abiName = "armv7"
                                                        break
                                                    }
                                                }
                                            }

                                            if (abiName != null) {
                                                val artifactName = "snapenhance-${abiName}-debug"
                                                val downloadUrl = "https://nightly.link/rhunk/SnapEnhance/actions/runs/${latest.workflowId}/$artifactName.zip"
                                                UpdateDownloader.downloadAndInstall(context.androidContext, downloadUrl, "$artifactName.zip", coroutineScope)
                                            } else {
                                                android.widget.Toast.makeText(context.androidContext, "Your device architecture is not supported for automatic updates.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    ) {
                                        Text(text = translation["update_button"])
                                    }
                                }
                                UpdateDownloader.DownloadState.DOWNLOADING -> {
                                    CircularProgressIndicator(progress = downloadProgress)
                                }
                                UpdateDownloader.DownloadState.COMPLETED -> {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Completed")
                                }
                                UpdateDownloader.DownloadState.FAILED -> {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Failed")
                                }
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(10.dp))
                InfoCard {
                    Text(
                        text = translation["debug_build_summary_title"],
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val buildSummary = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Light
                            )
                        ) {
                            append(
                                remember {
                                    translation.format(
                                        "debug_build_summary_content",
                                        "versionName" to BuildConfig.VERSION_NAME,
                                        "versionCode" to BuildConfig.VERSION_CODE.toString(),
                                    )
                                }
                            )
                            append(" - ")
                        }
                        withLink(
                            LinkAnnotation.Clickable(
                                "git_hash",
                                linkInteractionListener = {
                                    context.androidContext.openLink("https://github.com/rhunk/SnapEnhance/commit/${BuildConfig.GIT_HASH}")
                                }
                            )
                        ) {
                            withStyle(
                                style = SpanStyle(
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                append(BuildConfig.GIT_HASH.substring(0, 7))
                            }
                        }
                    }
                    Text(text = buildSummary)
                    Text(
                        fontSize = 12.sp,
                        text = remember {
                            translation.format(
                                "debug_build_summary_date",
                                "date" to DateFormat.getDateTimeInstance().format(BuildConfig.BUILD_TIMESTAMP),
                                "days" to ((System.currentTimeMillis() - BuildConfig.BUILD_TIMESTAMP) / 86400000).toInt().toString()
                            )
                        },
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedContent(targetState = selectedTiles.isNotEmpty(), label = "QuickActionsTitleAnim") { hasQuickActions ->
                if (!hasQuickActions) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 2.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(
                                translation["quick_actions_title"],
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            translation["quick_actions_title"],
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Start,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showQuickActionsMenu = true },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_manage),
                                contentDescription = "Manage Quick Actions"
                            )
                        }
                        FilterChip(
                            selected = editMode,
                            onClick = { editMode = !editMode },
                            label = { Text(if (editMode) "Done" else "Edit") },
                            leadingIcon = { Icon(Icons.Filled.DragHandle, contentDescription = null) }
                        )
                    }
                }
            }
            if (selectedTiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Widgets,
                            contentDescription = "Quick Actions",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No quick actions added yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showQuickActionsMenu = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Quick Action",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Add")
                        }
                    }
                }
            } else {
                val spacing = 6.dp
                var spanTick by remember { mutableIntStateOf(0) }
                FlowRow(
                    modifier = Modifier
                        .padding(all = cardMargin)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    val density = LocalDensity.current
                    val baseCell = density.run { remember { (context.androidContext.resources.displayMetrics.widthPixels / 3).toDp() - cardMargin / 2 } }
                    val baseCellPx = with(density) { baseCell.toPx() }
                    remember(selectedTiles.size, context.translation.loadedLocale) {
                        selectedTiles.mapNotNull {
                            cards.entries.find { entry -> entry.key.first == it }
                        }
                    }.forEach { (card, action) ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val _tick = spanTick
                        val (wSpan, hSpan) = getTileSpan(card.first)
                        val tileWidth = baseCell * wSpan + spacing * (wSpan - 1)
                        val tileHeight = baseCell * hSpan + spacing * (hSpan - 1)
                        // Appear animation per tile
                        var appeared by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { appeared = true }
                        val alpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (appeared) 1f else 0f,
                            animationSpec = Motion.tweenFloatSpec(200),
                            label = "tileAlpha"
                        )
                        val scale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (appeared) 1f else 0.9f,
                            animationSpec = Motion.tweenFloatSpec(220),
                            label = "tileScale"
                        )
                        var dxAcc by remember(card.first, spanTick) { mutableStateOf(0f) }
                        var dyAcc by remember(card.first, spanTick) { mutableStateOf(0f) }
                        ElevatedCard(
                            modifier = Modifier
                                .width(tileWidth)
                                .height(tileHeight)
                                .padding(all = 6.dp)
                                .graphicsLayer { this.alpha = alpha; this.scaleX = scale; this.scaleY = scale }
                                .then(if (!editMode) Modifier.scaleOnPress(interactionSource) else Modifier)
                                .then(
                                    if (editMode) Modifier.pointerInput(card.first, selectedTiles.size, spanTick) {
                                        detectDragGestures(
                                            onDragStart = { dxAcc = 0f; dyAcc = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dxAcc += dragAmount.x
                                                dyAcc += dragAmount.y
                                                val stepX = baseCellPx / 2f
                                                val stepY = baseCellPx / 2f
                                                var targetIndex = selectedTiles.indexOf(card.first)
                                                while (dxAcc > stepX) { targetIndex += 1; dxAcc -= stepX }
                                                while (dxAcc < -stepX) { targetIndex -= 1; dxAcc += stepX }
                                                while (dyAcc > stepY) { targetIndex += 3; dyAcc -= stepY }
                                                while (dyAcc < -stepY) { targetIndex -= 3; dyAcc += stepY }
                                                val currentIndex = selectedTiles.indexOf(card.first)
                                                targetIndex = targetIndex.coerceIn(0, selectedTiles.lastIndex)
                                                if (currentIndex != -1 && targetIndex != currentIndex) {
                                                    val item = selectedTiles.removeAt(currentIndex)
                                                    selectedTiles.add(targetIndex, item)
                                                }
                                            }
                                        )
                                    } else Modifier
                                ),
                            onClick = { if (!editMode) action(routes) },
                            interactionSource = interactionSource
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(all = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Icon(
                                        imageVector = card.second, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(50.dp)
                                    )
                                    Text(
                                        text = card.first,
                                        lineHeight = 16.sp,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // Drag handle for resizing
                                if (editMode) Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(28.dp)
                                        .pointerInput(card.first, spanTick) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                var newW = wSpan
                                                var newH = hSpan
                                                val step = baseCellPx / 2f
                                                if (dragAmount.x > step) newW = (wSpan + 1).coerceIn(1, 3)
                                                if (dragAmount.x < -step) newW = (wSpan - 1).coerceIn(1, 3)
                                                if (dragAmount.y > step) newH = (hSpan + 1).coerceIn(1, 3)
                                                if (dragAmount.y < -step) newH = (hSpan - 1).coerceIn(1, 3)
                                                if (newW != wSpan || newH != hSpan) {
                                                    setTileSpan(card.first, newW, newH)
                                                    spanTick++
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                // No dialog-based resizing
            }
            if (showQuickActionsMenu) {
                QuickActionsDialog(
                    quickActions = cards,
                    selectedQuickActions = selectedTiles,
                    onDismiss = { showQuickActionsMenu = false },
                    onSave = { newList ->
                        val previous = selectedTiles.toList()
                        val removed = previous.filter { it !in newList }
                        removed.forEach { clearTileSpan(it) }
                        selectedTiles.clear()
                        selectedTiles.addAll(newList)
                        context.coroutineScope.launch {
                            context.database.setQuickTiles(selectedTiles)
                        }
                        showQuickActionsMenu = false
                    }
                )
            }
            Spacer(modifier = Modifier.height(routes.bottomPadding))
        }
    }
}
