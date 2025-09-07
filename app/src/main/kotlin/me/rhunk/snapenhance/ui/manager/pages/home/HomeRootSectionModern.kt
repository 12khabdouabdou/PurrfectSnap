@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import java.text.DateFormat

class HomeRootSectionModern : Routes.Route() {
    companion object {
        val cardMargin = 14.dp
        val glassColor = Brush.linearGradient(
            listOf(Color(0x80FFFFFF), Color(0x40B0D4FF), Color(0x30A44FF9))
        )
    }
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val cards by lazy {
        EnumQuickActions.entries.map {
            (context.translation["actions.${it.key}.name"] to it.icon) to it.action
        }.associate { it.first to it.second }
            .toMutableMap()
            .apply {
                EnumAction.entries.forEach { action ->
                    this[context.translation["actions.${action.key}.name"] to action.icon] = {
                        context.launchActionIntent(action)
                    }
                }
            }
    }
    override val title: @Composable (() -> Unit)? = {}
    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }
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
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember {
            FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium))
        }
        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf()) {
            context.database.getQuickTiles()
        }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null) { Updater.latestRelease }
        var showQuickActionsMenu by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF152032), Color(0xFF485B95), Color(0xFF51517C))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
            ) {
                // Logo
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
                    fontSize = 16.sp,
                    fontFamily = avenirNext,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp, bottom = 10.dp),
                    color = Color.White.copy(0.7f)
                )
                // Social and help icons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 9.dp)
                ) {
                    ExternalLinkIconModern(
                        modifier = Modifier.clickable {
                            context.androidContext.openLink("https://t.me/snapenhance")
                        },
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                        tint = Color(0xFFE8F0FE)
                    )
                    ExternalLinkIconModern(
                        modifier = Modifier.clickable {
                            context.androidContext.openLink("https://github.com/rhunk/SnapEnhance")
                        },
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_github)
                    )
                    ExternalLinkIconModern(
                        modifier = Modifier
                            .offset(x = (-3).dp)
                            .clickable {
                                context.androidContext.openLink("https://github.com/rhunk/SnapEnhance/wiki")
                            },
                        size = 40.dp,
                        imageVector = Icons.AutoMirrored.Filled.Help
                    )
                }
                // Glassy update card
                if (latestUpdate != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassInfoCardModern {
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
                                                    val downloadUrl =
                                                        "https://nightly.link/rhunk/SnapEnhance/actions/runs/${latest.workflowId}/$artifactName.zip"
                                                    UpdateDownloader.downloadAndInstall(
                                                        context.androidContext,
                                                        downloadUrl,
                                                        "$artifactName.zip",
                                                        coroutineScope
                                                    )
                                                } else {
                                                    android.widget.Toast.makeText(
                                                        context.androidContext,
                                                        "Your device architecture is not supported for automatic updates.",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
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
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Completed"
                                        )
                                    }
                                    UpdateDownloader.DownloadState.FAILED -> {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Failed"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Glassy debug card
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassInfoCardModern {
                        Text(
                            text = translation["debug_build_summary_title"],
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val buildSummary = buildAnnotatedString {
                            append(
                                translation.format(
                                    "debug_build_summary_content",
                                    "versionName" to BuildConfig.VERSION_NAME,
                                    "versionCode" to BuildConfig.VERSION_CODE.toString(),
                                )
                            )
                            append(" - ")
                            append(BuildConfig.GIT_HASH.substring(0, 7))
                        }
                        Text(text = buildSummary)
                        Text(
                            fontSize = 12.sp,
                            text = translation.format(
                                "debug_build_summary_date",
                                "date" to DateFormat.getDateTimeInstance().format(BuildConfig.BUILD_TIMESTAMP),
                                "days" to ((System.currentTimeMillis() - BuildConfig.BUILD_TIMESTAMP) / 86400000).toInt().toString()
                            ),
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
                AnimatedContent(targetState = selectedTiles.isNotEmpty(), label = "QuickActionsTitleModern") { hasQuickActions ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(38.dp),
                            color = Color(0x20FFFFFF),
                            shadowElevation = 3.dp,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(
                                translation["quick_actions_title"],
                                fontSize = if (hasQuickActions) 22.sp else 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE8F0FE),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 19.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                // Quick Actions grid
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
                                tint = Color(0xFF93E0F8)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No quick actions added yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFA8B6CC)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { showQuickActionsMenu = true },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Quick Action",
                                    modifier = Modifier.size(22.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Add")
                            }
                        }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier
                            .padding(all = cardMargin)
                            .fillMaxWidth(),
                        maxItemsInEachRow = 3,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        val tileHeight = LocalDensity.current.run {
                            remember { (context.androidContext.resources.displayMetrics.widthPixels / 3).toDp() - cardMargin / 2 }
                        }
                        remember(selectedTiles.size, context.translation.loadedLocale) {
                            selectedTiles.mapNotNull {
                                cards.entries.find { entry -> entry.key.first == it }
                            }
                        }.forEach { (card, action) ->
                            Surface(
                                modifier = Modifier
                                    .height(tileHeight)
                                    .weight(1f)
                                    .padding(all = 6.dp)
                                    .clickable { action(routes) },
                                shadowElevation = 8.dp,
                                shape = RoundedCornerShape(24.dp),
                                color = Color(0x1AFFFFFF)
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
                                        tint = Color(0xFF43F3D9),
                                        modifier = Modifier.size(50.dp)
                                    )
                                    Text(
                                        text = card.first,
                                        lineHeight = 16.sp,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = Color(0xFFF0F1FE),
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                // QuickActionsDialog
                if (showQuickActionsMenu) {
                    QuickActionsDialog(
                        quickActions = cards,
                        selectedQuickActions = selectedTiles,
                        onDismiss = { showQuickActionsMenu = false },
                        onSave = {
                            selectedTiles.clear()
                            selectedTiles.addAll(it)
                            context.coroutineScope.launch {
                                context.database.setQuickTiles(selectedTiles)
                            }
                            showQuickActionsMenu = false
                        }
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    @Composable
    fun GlassInfoCardModern(content: @Composable ColumnScope.() -> Unit) {
        Surface(
            shadowElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            color = Color(0x1AFFFFFF),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                content()
            }
        }
    }
    @Composable
    fun ExternalLinkIconModern(
        modifier: Modifier = Modifier,
        size: Dp = 34.dp,
        imageVector: ImageVector,
        tint: Color = Color(0xFFE7E7FB),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    Color(0x40FFFFFF),
                    RoundedCornerShape(50)
                )
                .clip(RoundedCornerShape(50))
                .then(modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.72f)
            )
        }
    }
}
