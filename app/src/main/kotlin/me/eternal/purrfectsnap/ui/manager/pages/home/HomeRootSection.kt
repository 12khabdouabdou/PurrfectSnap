package me.eternal.purrfectsnap.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.vectorResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfectsnap.R
import me.eternal.purrfectsnap.action.EnumQuickActions
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.action.EnumAction
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfectsnap.common.util.ktx.openLink
import me.eternal.purrfectsnap.storage.getQuickTiles
import me.eternal.purrfectsnap.storage.setQuickTiles
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.manager.data.UpdateDownloader
import me.eternal.purrfectsnap.ui.manager.data.Updater
import me.eternal.purrfectsnap.ui.manager.data.Updater.Channel
import me.eternal.purrfectsnap.ui.manager.components.AestheticDialog
import me.eternal.purrfectsnap.ui.util.ActivityLauncherHelper
import me.eternal.purrfectsnap.ui.util.AlertDialogs
import me.eternal.purrfectsnap.ui.util.scaleOnPress
import okhttp3.OkHttpClient
import okhttp3.Request

class HomeRootSection : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.home") }

    companion object {
        val cardMargin = 10.dp
        val pageBackgroundGradient = Brush.verticalGradient(
            listOf(
                Color(0xFF261F58),
                Color(0xFF302A6D),
                Color(0xFF241F52)
            )
        )
    }

    private val changelogClient by lazy { OkHttpClient() }
    private val changelogStableUrl = "https://raw.githubusercontent.com/particle-box/PurrfectSnap/dev/changelogs-stable.txt"
    private val changelogPrereleaseUrl = "https://raw.githubusercontent.com/particle-box/PurrfectSnap/dev/changelogs-prerelease.txt"

    private val heroGradientColors = listOf(
        Color(0xFF5C4B99),
        Color(0xFF322B5E),
        Color(0xFF1B1836)
    )
    private val quickActionsGradientColors = listOf(
        Color(0xFF241C3E),
        Color(0xFF151127)
    )
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
    private fun rememberPreferenceBool(key: String, default: Boolean = false): State<Boolean> {
        val prefs = remember { context.sharedPreferences }
        val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
        DisposableEffect(prefs, key) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key) {
                    state.value = prefs.getBoolean(key, default)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }

    @Composable
    fun ExternalLinkIcon(
        modifier: Modifier = Modifier,
        size: Dp = 44.dp,
        imageVector: ImageVector,
        onClick: (() -> Unit)? = null,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val clickModifier = if (onClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() }
        } else {
            Modifier
        }
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .background(containerColor)
                .scaleOnPress(interactionSource)
                .then(clickModifier)
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.55f)
            )
        }
    }

    @Composable
    private fun HeroBadge(text: String) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }

    @Composable
    private fun TopBarActionChip(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(40),
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(40))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = label, tint = Color.White)
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
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
                    .padding(all = 10.dp),
                content = content
            )
        }
    }

    @Composable
    private fun RowScope.HomeActionChips() {
        TopBarActionChip(
            icon = Icons.Filled.BugReport,
            label = context.translation["manager.routes.home_logs"]
        ) { routes.homeLogs.navigate() }
        TopBarActionChip(
            icon = Icons.Filled.Settings,
            label = context.translation["manager.routes.home_settings"]
        ) { routes.settings.navigate() }
    }


    @Composable
    private fun AuroraBackground() {
        val infiniteTransition = rememberInfiniteTransition(label = "aurora")
        val driftX by infiniteTransition.animateFloat(
            initialValue = -120f,
            targetValue = 220f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "driftX"
        )
        val driftY by infiniteTransition.animateFloat(
            initialValue = 80f,
            targetValue = -140f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "driftY"
        )
        val shimmer by infiniteTransition.animateFloat(
            initialValue = -120f,
            targetValue = 160f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 11000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmer"
        )

        val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        val tertiaryGlow = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
        val trailGradient = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow, Color.Transparent),
                    center = Offset(
                        x = size.width * 0.25f + driftX,
                        y = size.height * 0.18f + driftY * 0.4f
                    ),
                    radius = size.minDimension * 0.9f
                ),
                alpha = 0.85f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiaryGlow, Color.Transparent),
                    center = Offset(
                        x = size.width * 0.78f - driftX * 0.45f,
                        y = size.height * 0.72f
                    ),
                    radius = size.minDimension * 0.95f
                ),
                alpha = 0.9f
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = trailGradient,
                    start = Offset(x = 0f, y = size.height * 0.15f + shimmer),
                    end = Offset(x = size.width, y = size.height * 0.9f + shimmer)
                ),
                size = this.size,
                alpha = 0.24f
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HeroSection(
        versionName: String,
        latestUpdate: Updater.LatestRelease?,
        downloadState: UpdateDownloader.DownloadState,
        downloadProgress: Float,
        onUpdateAction: () -> Unit,
        channelLabel: String,
        isPurrAuraActive: Boolean,
        onWikiClick: () -> Unit,
        onTelegramClick: () -> Unit,
        onGithubClick: () -> Unit,
        authorName: String,
        onManageClick: () -> Unit,
        avenirNext: FontFamily
    ) {
        val heroShape = RoundedCornerShape(36.dp)
        val gitHashShort = remember { (context.installationSummary.modInfo?.gitHash ?: BuildConfig.GIT_HASH).take(7) }
        Box(
            modifier = Modifier
                .padding(horizontal = cardMargin, vertical = 6.dp)
                .clip(heroShape)
                .background(
                    Brush.linearGradient(
                        heroGradientColors
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.1f), heroShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PurrfectSnap",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = avenirNext
                    )
                    Text(
                        text = "By ΞTΞRNAL",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        fontFamily = avenirNext
                    )
                    Text(
                        text = "An Xposed Module meant to enhance your Snapchat experience",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroBadge("Version: $versionName - $channelLabel")
            gitHashShort.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }?.let {
                HeroBadge("Build: $it")
            }
        }

                if (latestUpdate != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = translation["update_title"],
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = translation.format(
                                        "update_content",
                                        "version" to (latestUpdate.versionName)
                                    ),
                                    color = Color.White.copy(alpha = 0.82f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            AnimatedContent(
                                targetState = downloadState,
                                label = "UpdateDownloadHero"
                            ) { state ->
                                when (state) {
                                    UpdateDownloader.DownloadState.IDLE,
                                    UpdateDownloader.DownloadState.FAILED -> {
                                        Button(
                                            onClick = onUpdateAction,
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor = Color(0xFF1B152E)
                                            ),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                            contentPadding = PaddingValues(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = translation["download_icon_description"],
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    UpdateDownloader.DownloadState.DOWNLOADING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { downloadProgress },
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 3.dp,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${(downloadProgress * 100).toInt()}%",
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    UpdateDownloader.DownloadState.COMPLETED -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = translation["completed_icon_description"],
                                                tint = Color(0xFFA3F0C2)
                                            )
                                            Text(
                                                text = "Ready to install",
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isPurrAuraActive) PurrfectPalette.glowPrimary else Color(0xFF8C8CA3))
                                )
                                Text(
                                    text = if (isPurrAuraActive) "PurrAura Active!" else "PurrAura Inactive",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onManageClick,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Settings")
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = onWikiClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1B152E)
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Wiki", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onGithubClick,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "GitHub", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ExternalLinkIcon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                            onClick = onTelegramClick,
                            tint = Color.White,
                            containerColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
            }
        }
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

    private fun clearTileOffset(name: String) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().remove("quick_tile_offset_$key").apply()
    }

    override val title: @Composable (() -> Unit)? = {}
    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }
    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            HomeActionChips()
        }
    }


    @OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember {
            FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium))
        }
        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf()) {
            context.database.getQuickTiles().filter { it.isNotBlank() }
        }
        val updateChannel = context.config.root.global.updateSettings.updateChannel.getNullable() ?: "stable"
        val channelLabel = if (updateChannel == "prerelease") "Pre-release" else "Stable"
        val latestUpdate by rememberAsyncMutableState(defaultValue = null, keys = arrayOf(updateChannel)) {
            val channel = if (updateChannel == "prerelease") Channel.PRERELEASE else Channel.STABLE
            Updater.getLatestRelease(channel)
        }
        val changelogUrl = if (updateChannel == "prerelease") changelogPrereleaseUrl else changelogStableUrl
        val downloadState by UpdateDownloader.downloadState.collectAsState()
        val downloadProgress by UpdateDownloader.downloadProgress.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        val isPurrAuraActive by rememberPreferenceBool("debug_test_mode", true)
        var showChangelogDialog by remember { mutableStateOf(false) }
        var changelogLoading by remember { mutableStateOf(false) }
        var changelogError by remember { mutableStateOf<String?>(null) }
        var changelogText by remember { mutableStateOf<String?>(null) }
        var changelogVersion by remember { mutableStateOf<String?>(null) }

        val handleUpdateAction: () -> Unit = {
            latestUpdate?.let { latest ->
                val supportedAbis = android.os.Build.SUPPORTED_ABIS
                var abiName: String? = null
                for (abi in supportedAbis) {
                    when (abi) {
                        "arm64-v8a" -> {
                            abiName = "arm64"
                            break
                        }
                        "armeabi-v7a" -> {
                            abiName = "armv7"
                            break
                        }
                    }
                }
                context.log.info(
                    "Update request: device ABIs=${supportedAbis.joinToString()} resolvedArch=${abiName ?: "unknown"}",
                    "HomeRoot"
                )

                if (latest.workflowId != null) {
                    if (abiName == null) {
                        android.widget.Toast.makeText(
                            context.androidContext,
                            "Your device architecture is not supported for automatic updates.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val artifactName = "purrfectsnap-${abiName}-debug"
                        val downloadUrl = "https://nightly.link/particle-box/PurrfectSnap/actions/runs/${latest.workflowId}/$artifactName.zip"
                        context.log.info("Debug update -> downloading $artifactName from $downloadUrl", "HomeRoot")
                        UpdateDownloader.downloadAndInstall(context, downloadUrl, "$artifactName.zip", coroutineScope)
                    }
                    return@let
                }

                val releaseDownload = abiName?.let { arch -> latest.assetDownloads[arch] }
                if (releaseDownload != null) {
                    val fileName = releaseDownload.substringAfterLast('/')
                    context.log.info("Release update -> arch=$abiName url=$releaseDownload file=$fileName", "HomeRoot")
                    UpdateDownloader.downloadAndInstall(context, releaseDownload, fileName, coroutineScope)
                } else {
                    context.log.warn(
                        "No matching update asset for arch=$abiName (available: ${latest.assetDownloads.keys})",
                        "HomeRoot"
                    )
                    context.androidContext.openLink(latest.releaseUrl)
                }
            }
        }

        fun loadChangelog(targetVersion: String, url: String) {
            if (changelogVersion == targetVersion && changelogText != null) return
            changelogLoading = true
            changelogError = null
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    changelogClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("Failed to fetch changelog (${response.code})")
                        val body = response.body?.string() ?: throw IllegalStateException("Empty changelog body")
                        extractChangelogForVersion(body, targetVersion).ifBlank { body.trim() }
                    }
                }.onSuccess { text ->
                    withContext(Dispatchers.Main) {
                        changelogText = text
                        changelogVersion = targetVersion
                        changelogLoading = false
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        changelogError = error.message ?: "Failed to load changelog"
                        changelogLoading = false
                    }
                }
            }
        }

        val onUpdateButtonClick: () -> Unit = {
            latestUpdate?.let {
                showChangelogDialog = true
                loadChangelog(it.versionName, changelogUrl)
            }
        }

        var showQuickActionsMenu by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentBottomPadding = routes.bottomPadding + navigationBarPadding + 96.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackgroundGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = contentBottomPadding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WindowInsets.statusBars.asPaddingValues())
                        .padding(horizontal = cardMargin, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    HomeActionChips()
                }
                Spacer(modifier = Modifier.height(12.dp))
                HeroSection(
                    versionName = BuildConfig.VERSION_NAME,
                    latestUpdate = latestUpdate,
                    downloadState = downloadState,
                    downloadProgress = downloadProgress,
                    onUpdateAction = onUpdateButtonClick,
                    channelLabel = channelLabel,
                    isPurrAuraActive = isPurrAuraActive,
                    onWikiClick = { context.androidContext.openLink("https://github.com/particle-box/PurrfectSnap/wiki") },
                    onTelegramClick = { context.androidContext.openLink("https://t.me/purrfectsnap_official") },
                    onGithubClick = { context.androidContext.openLink("https://github.com/particle-box/PurrfectSnap") },
                    authorName = "ETERNAL",
                    onManageClick = { routes.settings.navigate() },
                    avenirNext = avenirNext,
                )
                Spacer(modifier = Modifier.height(12.dp))
                AnimatedContent(targetState = selectedTiles.isNotEmpty(), label = "QuickActionsAnim") { hasQuickActions ->
                    val quickCardShape = RoundedCornerShape(34.dp)
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = cardMargin, vertical = 10.dp),
                        shape = quickCardShape,
                        tonalElevation = 0.dp,
                        shadowElevation = 24.dp,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(quickActionsGradientColors))
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                        .padding(bottom = navigationBarPadding + 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                            if (!hasQuickActions) {
                                Text(
                                    translation["quick_actions_title"],
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Widgets,
                                        contentDescription = translation["quick_actions_icon_description"],
                                        modifier = Modifier.size(72.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "No quick tiles yet",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Design your dream grid with the actions you use the most.",
                                        fontSize = 14.sp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                    Button(
                                        onClick = { showQuickActionsMenu = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = Color(0xFF1B152E)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = translation["add_quick_action_description"],
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = "Add tile")
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        translation["quick_actions_title"],
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = Color.White,
                                        maxLines = 3,
                                        overflow = TextOverflow.Clip
                                    )
                                    Text(
                                        text = "${selectedTiles.size} curated shortcuts",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        textAlign = TextAlign.Center
                                    )
                                    Row(
                                        modifier = Modifier.wrapContentWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { showQuickActionsMenu = true },
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                        ) {
                                            Icon(
                                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_manage),
                                                contentDescription = translation["manage_quick_actions_description"],
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = "Manage")
                                        }
                                    }
                                }
                                val spacing = 12.dp
                                val gridPadding = 8.dp
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    val preferredTileWidth = 100.dp
                                    val columns = ((maxWidth + spacing) / (preferredTileWidth + spacing))
                                        .toInt()
                                        .coerceAtLeast(2)
                                        .coerceAtMost(4)
                                    val computedWidth = (maxWidth - gridPadding * 2 - spacing * (columns - 1)) / columns
                                    val tileWidth = if (computedWidth < preferredTileWidth) computedWidth else preferredTileWidth
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(all = gridPadding),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalArrangement = Arrangement.spacedBy(spacing),
                                        maxItemsInEachRow = columns
                                    ) {
                                        selectedTiles.forEach { tileName ->
                                            val cardEntry = cards.entries.find { entry -> entry.key.first == tileName } ?: return@forEach
                                            val (card, action) = cardEntry
                                            val interactionSource = remember { MutableInteractionSource() }
                                            Surface(
                                                modifier = Modifier
                                                    .width(tileWidth)
                                                    .aspectRatio(1.05f)
                                                    .scaleOnPress(interactionSource)
                                                    .clickable { action(routes) },
                                                shape = RoundedCornerShape(18.dp),
                                                color = Color.White.copy(alpha = 0.06f),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            Brush.linearGradient(
                                                                listOf(
                                                                    PurrfectPalette.glowPrimary.copy(alpha = 0.3f),
                                                                    PurrfectPalette.glowSecondary.copy(alpha = 0.22f)
                                                                )
                                                            )
                                                        )
                                                        .clipToBounds()
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(all = 10.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center,
                                                    ) {
                                                        Icon(
                                                            imageVector = card.second, contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(44.dp)
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            text = card.first,
                                                            lineHeight = 16.sp,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            textAlign = TextAlign.Center,
                                                            color = Color.White,
                                                            overflow = TextOverflow.Ellipsis,
                                                            maxLines = 2,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showChangelogDialog && latestUpdate != null) {
            AestheticDialog(
                onDismissRequest = { showChangelogDialog = false },
                title = "Changelog",
                text = "",
                icon = Icons.Filled.Info,
                confirmButtonText = "Update",
                onConfirm = {
                    showChangelogDialog = false
                    handleUpdateAction()
                },
                dismissButtonText = "Cancel",
                onDismiss = { showChangelogDialog = false },
                confirmEnabled = !changelogLoading,
                customContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                    when {
                        changelogLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Loading changelog…",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        changelogError != null -> {
                            Text(
                                text = changelogError ?: "Failed to load changelog",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        else -> {
                            Text(
                                text = changelogText ?: "Changelog not available",
                                color = PurrfectPalette.textPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                }
            )
        }

        if (showQuickActionsMenu) {
            QuickActionsDialog(
                quickActions = cards,
                selectedQuickActions = selectedTiles,
                onDismiss = { showQuickActionsMenu = false },
                onSave = { newList ->
                    val previous = selectedTiles.toList()
                    val removed = previous.filter { it !in newList }
                    removed.forEach { clearTileSpan(it); clearTileOffset(it) }
                    newList.forEach { clearTileOffset(it) }
                    selectedTiles.clear()
                    selectedTiles.addAll(newList)
                    context.coroutineScope.launch {
                        context.database.setQuickTiles(selectedTiles)
                    }
                    showQuickActionsMenu = false
                },
                translation = translation
            )
        }
    }
}

private fun extractChangelogForVersion(raw: String, version: String): String {
    val lines = raw.lines()
    val headerRegex = Regex("^\\s*#+\\s*v?${Regex.escape(version)}\\b", RegexOption.IGNORE_CASE)
    val collected = mutableListOf<String>()
    var collecting = false
    for (line in lines) {
        if (!collecting) {
            if (headerRegex.containsMatchIn(line)) {
                collecting = true
            }
            continue
        }
        if (line.trimStart().startsWith("#")) break
        collected.add(line)
    }
    return collected.joinToString("\n").trim()
}
}

