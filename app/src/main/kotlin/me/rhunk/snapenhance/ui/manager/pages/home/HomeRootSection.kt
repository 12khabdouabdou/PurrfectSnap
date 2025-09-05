package me.rhunk.snapenhance.ui.manager.pages.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import me.rhunk.snapenhance.ui.manager.data.Updater
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.AlertDialogs
import java.text.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

class HomeRootSection : Routes.Route() {
    companion object {
        val cardMargin = 10.dp
    }
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
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
    ) {
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
    @OptIn(
        ExperimentalLayoutApi::class,
        ExperimentalAnimationApi::class,
        ExperimentalFoundationApi::class
    )
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember {
            FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium))
        }
        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf()) {
            context.database.getQuickTiles()
        }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null) { Updater.latestRelease }
        var showQuickActionsMenu by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0f) }

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
                    modifier = Modifier.clickable {
                        context.androidContext.openLink("https://t.me/snapenhance")
                    },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                )
                ExternalLinkIcon(
                    modifier = Modifier.clickable {
                        context.androidContext.openLink("https://github.com/rhunk/SnapEnhance")
                    },
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_github),
                )
                ExternalLinkIcon(
                    modifier = Modifier.offset(x = (-3).dp).clickable {
                        context.androidContext.openLink("https://github.com/rhunk/SnapEnhance/wiki")
                    },
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
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
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
                        Spacer(Modifier.width(8.dp))
                        AnimatedContent(
                            targetState = isDownloading,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "DownloadProgressButton"
                        ) { downloading ->
                            if (!downloading) {
                                Button(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .defaultMinSize(minWidth = 80.dp)
                                        .padding(end = 2.dp),
                                    onClick = {
                                        context.coroutineScope.launch(Dispatchers.Main) {
                                            isDownloading = true
                                            downloadProgress = 0f
                                            try {
                                                val abisList = Build.SUPPORTED_ABIS?.map { it.lowercase() } ?: emptyList()
                                                val abiMatchList = buildList {
                                                    if (abisList.any { it.contains("arm64") || it.contains("v8a") || it.contains("aarch64") || it.contains("armv8") })
                                                        add("-armv8-")
                                                    else if (abisList.any { it.contains("armeabi-v7a") || it.contains("armv7") || it.contains("armeabi") || it.contains("v7a") })
                                                        add("-armv7-")
                                                }
                                                val client = OkHttpClient()
                                                val releasesReq = Request.Builder()
                                                    .url("https://api.github.com/repos/particle-box/SnapEnhance/releases")
                                                    .build()
                                                val releasesResp = withContext(Dispatchers.IO) { client.newCall(releasesReq).execute() }
                                                val releasesJson = JSONArray(releasesResp.body?.string() ?: "[]")
                                                var downloadUrl: String? = null
                                                var assetName: String? = null
                                                loop@for (abiHint in abiMatchList) {
                                                    for (i in 0 until releasesJson.length()) {
                                                        val rel = releasesJson.getJSONObject(i)
                                                        if (!rel.optBoolean("prerelease", false)) continue
                                                        val assetsArr = rel.optJSONArray("assets") ?: continue
                                                        for (j in 0 until assetsArr.length()) {
                                                            val asset = assetsArr.getJSONObject(j)
                                                            val name = asset.optString("name")
                                                            val url = asset.optString("browser_download_url")
                                                            if (name.contains(abiHint)) {
                                                                downloadUrl = url
                                                                assetName = name
                                                                break@loop
                                                            }
                                                        }
                                                    }
                                                }
                                                if (downloadUrl == null) {
                                                    Toast.makeText(context.androidContext, "No debug APK found for your device.", Toast.LENGTH_LONG).show()
                                                    isDownloading = false
                                                    return@launch
                                                }
                                                val notifMgr = context.androidContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                                val channelId = "update_download"
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    val channel = NotificationChannel(channelId, "Update Download", NotificationManager.IMPORTANCE_LOW)
                                                    notifMgr.createNotificationChannel(channel)
                                                }
                                                val notifBuilder = NotificationCompat.Builder(context.androidContext, channelId)
                                                val filename = assetName ?: "snapenhance-update.apk"
                                                val destFile = File(context.androidContext.getExternalFilesDir(null), filename)
                                                var out: OutputStream? = null
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        val req = Request.Builder().url(downloadUrl!!).build()
                                                        client.newCall(req).execute().use { resp ->
                                                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                                            val body = resp.body ?: throw Exception("Null body")
                                                            val len = body.contentLength()
                                                            out = FileOutputStream(destFile)
                                                            val buf = ByteArray(8 * 1024)
                                                            var downloaded = 0L
                                                            var lastNotified = 0
                                                            body.byteStream().use { input ->
                                                                while (true) {
                                                                    val read = input.read(buf)
                                                                    if (read == -1) break
                                                                    out!!.write(buf, 0, read)
                                                                    downloaded += read
                                                                    if (len > 0) {
                                                                        val percent = downloaded.toFloat() / len.toFloat()
                                                                        downloadProgress = percent
                                                                    }
                                                                    val percentInt = if (len > 0) ((downloaded * 100) / len).toInt() else -1
                                                                    if (percentInt >= lastNotified + 2 || percentInt == 100) {
                                                                        notifMgr.notify(
                                                                            991,
                                                                            notifBuilder
                                                                                .setContentTitle("Updating SnapEnhance")
                                                                                .setContentText("Downloading update… $percentInt%")
                                                                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                                                                .setProgress(100, percentInt, len <= 0)
                                                                                .setOngoing(true)
                                                                                .build()
                                                                        )
                                                                        lastNotified = percentInt
                                                                    }
                                                                }
                                                            }
                                                            out!!.flush()
                                                            out!!.close()
                                                        }
                                                    }
                                                    notifMgr.notify(
                                                        991,
                                                        notifBuilder
                                                            .setContentTitle("Download complete")
                                                            .setContentText("Tap to install")
                                                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                                            .setProgress(0, 0, false)
                                                            .setOngoing(false)
                                                            .build()
                                                    )
                                                    Toast.makeText(context.androidContext, "Update ready, tap to install.", Toast.LENGTH_SHORT).show()
                                                    val uri = FileProvider.getUriForFile(
                                                        context.androidContext,
                                                        "${context.androidContext.packageName}.provider",
                                                        destFile
                                                    )
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.androidContext.startActivity(intent)
                                                } catch (e: Exception) {
                                                    (context.androidContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(991)
                                                    Toast.makeText(context.androidContext, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                } finally {
                                                    out?.close()
                                                    isDownloading = false
                                                    downloadProgress = 0f
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context.androidContext, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                isDownloading = false
                                                downloadProgress = 0f
                                            }
                                        }
                                    }
                                ) {
                                    Text(text = translation["update_button"])
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            RoundedCornerShape(50)
                                        )
                                        .clip(RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = downloadProgress.coerceIn(0f, 1f),
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.secondary,
                                        strokeWidth = 3.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
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
        }
    }
}
