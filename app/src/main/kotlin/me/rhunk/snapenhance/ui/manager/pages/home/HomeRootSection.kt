package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.zIndex
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
import me.rhunk.snapenhance.ui.manager.data.Updater
import me.rhunk.snapenhance.ui.manager.data.UpdaterDownloader
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import java.text.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

sealed class UpdateState {
    object Idle : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
    object Success : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

class HomeRootSection : Routes.Route() {

    companion object {
        val cardMargin = 10.dp

        private fun getCards(context: RemoteSideContext): MutableMap<Pair<String, ImageVector>, (Routes) -> Unit> {
            return EnumQuickActions.entries.map {
                (context.translation["actions.${it.key}.name"] to it.icon) to it.action
            }.associate { it.first to it.second }
                .toMutableMap().apply {
                    EnumAction.entries.forEach { action ->
                        this[context.translation["actions.${action.key}.name"] to action.icon] = {
                            context.launchActionIntent(action)
                        }
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
    fun ExternalLinkIcon(modifier: Modifier = Modifier, size: Dp = 32.dp, imageVector: ImageVector) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .then(modifier)
        )
    }

    override val title: @Composable (() -> Unit)? = {}

    override val init: () -> Unit = {}

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        TopBarActionButton(
            onClick = { routes.homeLogs.navigate() },
            icon = Icons.Filled.BugReport,
            text = context.translation["manager.routes.home_logs"]
        )
        Spacer(modifier = Modifier.width(8.dp))
        TopBarActionButton(
            onClick = { routes.settings.navigate() },
            icon = Icons.Filled.Settings,
            text = context.translation["manager.routes.home_settings"]
        )
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val context = this@HomeRootSection.context
        val androidContext = LocalContext.current

        val activityLauncherHelper = remember { ActivityLauncherHelper(context.activity!!) }
        val scope = rememberCoroutineScope()

        val installerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            UpdaterDownloader.completeInstall(result.resultCode)
        }

        LaunchedEffect(Unit) {
            UpdaterDownloader.register(installerLauncher)
        }

        val avenirNext = remember { FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium)) }

        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf<String>()) {
            context.database.getQuickTiles()
        }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null) { Updater.latestRelease }
        var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
        var showQuickActionsMenu by remember { mutableStateOf(false) }
        val cards = remember { getCards(context) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Icon(
                imageVector = Snapenhance,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 8.dp)
                    .align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = context.translation.format("version_title", "versionName" to BuildConfig.VERSION_NAME),
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
                    modifier = Modifier.clickable {
                        androidContext.openLink("https://t.me/snapenhance")
                    },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                )
                ExternalLinkIcon(
                    modifier = Modifier.clickable {
                        androidContext.openLink("https://github.com/rhunk/SnapEnhance")
                    },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_github),
                )
                ExternalLinkIcon(
                    modifier = Modifier.offset(x = (-3).dp).clickable {
                        androidContext.openLink("https://github.com/rhunk/SnapEnhance/wiki")
                    },
                    size = 40.dp,
                    imageVector = Icons.AutoMirrored.Filled.Help,
                )
            }
            if (latestUpdate != null) {
                Spacer(modifier = Modifier.height(10.dp))
                InfoCard {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = context.translation["update_title"],
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    fontSize = 12.sp,
                                    text = context.translation.format(
                                        "update_content",
                                        "version" to (latestUpdate?.versionName ?: "unknown")
                                    ),
                                    lineHeight = 20.sp,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                modifier = Modifier.height(40.dp),
                                enabled = updateState is UpdateState.Idle,
                                onClick = {
                                    if (BuildConfig.DEBUG) {
                                        UpdaterDownloader.downloadAndInstall(
                                            scope = scope,
                                            context = androidContext,
                                            onDownloadStart = { updateState = UpdateState.Downloading(0f) },
                                            onProgress = { progress -> updateState = UpdateState.Downloading(progress) },
                                            onSuccess = { updateState = UpdateState.Success },
                                            onFailure = { message -> updateState = UpdateState.Failed(message) }
                                        )
                                    } else {
                                        latestUpdate?.releaseUrl?.let { androidContext.openLink(it) }
                                    }
                                }
                            ) {
                                Text(text = context.translation["update_button"])
                            }
                        }
                        when (val state = updateState) {
                            is UpdateState.Downloading -> {
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is UpdateState.Success -> {
                                Text(text = "Update installed successfully!", color = MaterialTheme.colorScheme.primary)
                                Button(onClick = { updateState = UpdateState.Idle }) {
                                    Text(text = "OK")
                                }
                            }
                            is UpdateState.Failed -> {
                                Text(text = "Update failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                                Button(onClick = { updateState = UpdateState.Idle }) {
                                    Text(text = "Retry")
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(10.dp))
                InfoCard {
                    Text(
                        text = context.translation["debug_build_summary_title"],
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val buildSummary: AnnotatedString = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Light
                            )
                        ) {
                            append(
                                context.translation.format(
                                    "debug_build_summary_content",
                                    "versionName" to BuildConfig.VERSION_NAME,
                                    "versionCode" to BuildConfig.VERSION_CODE.toString(),
                                )
                            )
                            append(" - ")
                            withLink(
                                LinkAnnotation.Clickable(
                                    "git_hash",
                                    linkInteractionListener = {
                                        androidContext.openLink("https://github.com/rhunk/SnapEnhance/commit/${BuildConfig.GIT_HASH}")
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
                    }
                    Text(text = buildSummary)
                    Text(
                        fontSize = 12.sp,
                        text = context.translation.format(
                            "debug_build_summary_date",
                            "date" to DateFormat.getDateTimeInstance().format(BuildConfig.BUILD_TIMESTAMP),
                            "days" to ((System.currentTimeMillis() - BuildConfig.BUILD_TIMESTAMP) / 86400000).toInt().toString()
                        ),
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    context.translation["quick_actions_title"],
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(
                        onClick = { showQuickActionsMenu = !showQuickActionsMenu },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showQuickActionsMenu,
                        onDismissRequest = { showQuickActionsMenu = false }
                    ) {
                        cards.forEach { (card, _) ->
                            fun toggle(state: Boolean? = null) {
                                if (state?.let { !it } ?: selectedTiles.contains(card.first)) {
                                    selectedTiles.remove(card.first)
                                } else {
                                    selectedTiles.add(0, card.first)
                                }
                                context.coroutineScope.launch {
                                    context.database.setQuickTiles(selectedTiles)
                                }
                            }
                            DropdownMenuItem(text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(all = 5.dp)
                                ) {
                                    Checkbox(
                                        checked = selectedTiles.contains(card.first),
                                        onCheckedChange = { toggle(it) }
                                    )
                                    Text(text = card.first)
                                }
                            }, onClick = { toggle() })
                        }
                    }
                }
            }

            // Quick Actions Tiles
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
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .padding(all = cardMargin)
                        .fillMaxWidth(),
                    maxItemsInEachRow = 3,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val tileHeight = LocalDensity.current.run {
                        (androidContext.resources.displayMetrics.widthPixels / 3).dp - cardMargin / 2
                    }
                    selectedTiles.mapNotNull { tileName ->
                        cards.entries.find { entry -> entry.key.first == tileName }
                    }.forEach { (card, action) ->
                        ElevatedCard(
                            modifier = Modifier
                                .height(tileHeight)
                                .weight(1f)
                                .padding(all = 6.dp),
                            onClick = { action(routes) }
                        ) {
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
                        }
                    }
                }
            }
        }
    }
}
