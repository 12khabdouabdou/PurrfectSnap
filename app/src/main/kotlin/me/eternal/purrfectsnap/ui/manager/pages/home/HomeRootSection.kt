package me.eternal.purrfectsnap.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import me.eternal.purrfectsnap.ui.util.ActivityLauncherHelper
import me.eternal.purrfectsnap.ui.util.AlertDialogs
import me.eternal.purrfectsnap.ui.util.scaleOnPress

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
        isPurrAuraActive: Boolean,
        onWikiClick: () -> Unit,
        onTelegramClick: () -> Unit,
        onGithubClick: () -> Unit,
        authorName: String,
        onManageClick: () -> Unit,
        avenirNext: FontFamily
    ) {
        val heroShape = RoundedCornerShape(36.dp)
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
                        text = "by $authorName",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp
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
            HeroBadge("Codename: Rass Malayi")
            HeroBadge("Debug Build")
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

    private fun getTileOffset(name: String): Pair<Float, Float> {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        val raw = prefs.getString("quick_tile_offset_$key", null)
        if (raw == null) return 0f to 0f
        val parts = raw.split(',')
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        return x to y
    }

    private fun setTileOffset(name: String, x: Float, y: Float) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().putString("quick_tile_offset_$key", "$x,$y").apply()
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
        val latestUpdate by rememberAsyncMutableState(defaultValue = null) { Updater.latestRelease }
        val downloadState by UpdateDownloader.downloadState.collectAsState()
        val downloadProgress by UpdateDownloader.downloadProgress.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        val isPurrAuraActive by rememberPreferenceBool("debug_test_mode", true)

        val handleUpdateAction: () -> Unit = {
            latestUpdate?.let { latest ->
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

                if (latest.workflowId == null) {
                    context.androidContext.openLink(latest.releaseUrl)
                } else if (abiName == null) {
                    android.widget.Toast.makeText(
                        context.androidContext,
                        "Your device architecture is not supported for automatic updates.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    val artifactName = "purrfectsnap-${abiName}-debug"
                    val downloadUrl = "https://nightly.link/particle-box/PurrfectSnap/actions/runs/${latest.workflowId}/$artifactName.zip"
                    UpdateDownloader.downloadAndInstall(context.androidContext, downloadUrl, "$artifactName.zip", coroutineScope)
                }
            }
        }

        var showQuickActionsMenu by remember { mutableStateOf(false) }
        var editMode by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val density = LocalDensity.current
        val contentBottomPadding = routes.bottomPadding + navigationBarPadding + 96.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackgroundGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState, enabled = !editMode)
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
                Spacer(modifier = Modifier.height(4.dp))
                HeroSection(
                    versionName = BuildConfig.VERSION_NAME,
                    latestUpdate = latestUpdate,
                    downloadState = downloadState,
                    downloadProgress = downloadProgress,
                    onUpdateAction = handleUpdateAction,
                    isPurrAuraActive = isPurrAuraActive,
                    onWikiClick = { context.androidContext.openLink("https://github.com/particle-box/PurrfectSnap/wiki") },
                    onTelegramClick = { context.androidContext.openLink("https://t.me/purrfectsnap_official") },
                    onGithubClick = { context.androidContext.openLink("https://github.com/particle-box/PurrfectSnap") },
                    authorName = "ΞTΞRNAL",
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
                                    modifier = Modifier.fillMaxWidth()
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
                                        Button(
                                            onClick = { editMode = !editMode },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (editMode) Color.White else Color.White.copy(alpha = 0.12f),
                                                contentColor = if (editMode) Color(0xFF1B152E) else Color.White
                                            )
                                        ) {
                                            Icon(
                                                imageVector = if (editMode) Icons.Default.Check else Icons.Filled.DragHandle,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(if (editMode) "Done" else "Reorder")
                                        }
                                    }
                                }
                            val spacing = 12.dp
                            var spanTick by remember { mutableIntStateOf(0) }

                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                            val density = LocalDensity.current
                            val baseCell = remember { (maxWidth - (spacing * 2)) / 3f }
                            val baseCellPx = with(density) { baseCell.toPx() }

                            val (tilePositions, totalHeight) = remember(selectedTiles.size, spanTick) {
                                val positions = mutableMapOf<String, Offset>()
                                var currentX = 0f
                                var currentY = 0f
                                var rowMaxHeight = 0f
                                val screenWidthPx = with(density) { maxWidth.toPx() }

                                selectedTiles.forEach { tileName ->
                                    cards.entries.find { entry -> entry.key.first == tileName }?.let {
                                        val card = it.key
                                        val (wSpan, hSpan) = getTileSpan(card.first)
                                        val tileWidthPx = with(density) { (baseCell * wSpan + spacing * (wSpan - 1)).toPx() }
                                        val tileHeightPx = with(density) { (baseCell * hSpan + spacing * (hSpan - 1)).toPx() }

                                        if (currentX + tileWidthPx > screenWidthPx) {
                                            currentX = 0f
                                            currentY += rowMaxHeight
                                            rowMaxHeight = 0f
                                        }

                                        positions[tileName] = Offset(currentX, currentY)
                                        currentX += tileWidthPx + with(density) { spacing.toPx() }
                                        if (tileHeightPx > rowMaxHeight) {
                                            rowMaxHeight = tileHeightPx
                                        }
                                    }
                                }
                                positions to (currentY + rowMaxHeight)
                            }

                            Box(modifier = Modifier.height(with(density) { totalHeight.toDp() })) {
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
                                    val tileWidthPx = with(density) { tileWidth.toPx() }
                                    val tileHeightPx = with(density) { tileHeight.toPx() }

                                    var offsetX by remember(card.first) { mutableStateOf(0f) }
                                    var offsetY by remember(card.first) { mutableStateOf(0f) }
                                    var isDragging by remember { mutableStateOf(false) }

                                    LaunchedEffect(card.first, spanTick) {
                                        val (x, y) = getTileOffset(card.first)
                                        if (x != 0f || y != 0f) {
                                            offsetX = x
                                            offsetY = y
                                        } else {
                                            val pos = tilePositions[card.first]
                                            if (pos != null) {
                                                offsetX = pos.x
                                                offsetY = pos.y
                                                setTileOffset(card.first, offsetX, offsetY)
                                            }
                                        }
                                    }

                                    val animatedOffsetX by animateFloatAsState(
                                        targetValue = offsetX,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ),
                                        label = "offsetX"
                                    )
                                    val animatedOffsetY by animateFloatAsState(
                                        targetValue = offsetY,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ),
                                        label = "offsetY"
                                    )

                                    val currentOffsetX = if (isDragging) offsetX else animatedOffsetX
                                    val currentOffsetY = if (isDragging) offsetY else animatedOffsetY

                                    val baseModifier = Modifier
                                        .offset { IntOffset(currentOffsetX.roundToInt(), currentOffsetY.roundToInt()) }
                                        .width(tileWidth)
                                        .height(tileHeight)
                                        .padding(all = 6.dp)

                                    val editModifier = baseModifier.then(
                                        Modifier.pointerInput(card.first, tileWidthPx, tileHeightPx) {
                                            var originalOffsetX = 0f
                                            var originalOffsetY = 0f
                                            detectDragGestures(
                                                onDragStart = {
                                                    isDragging = true
                                                    originalOffsetX = offsetX
                                                    originalOffsetY = offsetY
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    offsetX += dragAmount.x
                                                    offsetY += dragAmount.y
                                                },
                                                onDragEnd = {
                                                    isDragging = false
                                                    var targetTile: String? = null
                                                    var maxOverlap = 0f
                                                    val tileRect = Rect(Offset(offsetX, offsetY), Size(tileWidthPx, tileHeightPx))

                                                    for (otherTileName in selectedTiles) {
                                                        if (otherTileName == card.first) continue
                                                        val (otherOffsetX, otherOffsetY) = getTileOffset(otherTileName)
                                                        val (otherWSpan, otherHSpan) = getTileSpan(otherTileName)
                                                        val otherTileWidth = baseCell * otherWSpan + spacing * (otherWSpan - 1)
                                                        val otherTileHeight = baseCell * otherHSpan + spacing * (otherHSpan - 1)
                                                        val otherRect = Rect(Offset(otherOffsetX, otherOffsetY), Size(with(density) { otherTileWidth.toPx() }, with(density) { otherTileHeight.toPx() }))
                                                        val intersectRect = tileRect.intersect(otherRect)
                                                        val overlapArea = intersectRect.width * intersectRect.height
                                                        if (overlapArea > maxOverlap) {
                                                            maxOverlap = overlapArea
                                                            targetTile = otherTileName
                                                        }
                                                    }

                                                    if (targetTile != null) {
                                                        val (wSpan, hSpan) = getTileSpan(card.first)
                                                        val (targetWSpan, targetHSpan) = getTileSpan(targetTile!!)
                                                        if (wSpan == targetWSpan && hSpan == targetHSpan) {
                                                            // swap
                                                            val (targetOffsetX, targetOffsetY) = getTileOffset(targetTile!!)
                                                            setTileOffset(card.first, targetOffsetX, targetOffsetY)
                                                            setTileOffset(targetTile!!, originalOffsetX, originalOffsetY)
                                                            spanTick++ // this will trigger recomposition for all tiles
                                                        } else {
                                                            // revert
                                                            offsetX = originalOffsetX
                                                            offsetY = originalOffsetY
                                                        }
                                                    } else {
                                                        setTileOffset(card.first, offsetX, offsetY)
                                                    }
                                                }
                                            )
                                        }
                                    )
                                    val viewModifier = baseModifier.then(Modifier.scaleOnPress(interactionSource))

                                    val tileContent: @Composable (Modifier, Boolean) -> Unit = { tileModifier, isEdit ->
                                        Surface(
                                            modifier = tileModifier,
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
                                                    verticalArrangement = Arrangement.SpaceEvenly,
                                                ) {
                                                    Icon(
                                                        imageVector = card.second, contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(50.dp)
                                                    )
                                                    Text(
                                                        text = card.first,
                                                        lineHeight = 16.sp,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = TextAlign.Center,
                                                        color = Color.White,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (isEdit) {
                                                    var dxAccResize by remember(card.first, spanTick) { mutableStateOf(0f) }
                                                    var dyAccResize by remember(card.first, spanTick) { mutableStateOf(0f) }
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .size(28.dp)
                                                            .pointerInput(card.first, spanTick) {
                                                                detectDragGestures(
                                                                    onDragStart = {
                                                                        dxAccResize = 0f
                                                                        dyAccResize = 0f
                                                                    },
                                                                    onDrag = { change, dragAmount ->
                                                                        change.consume()
                                                                        dxAccResize += dragAmount.x
                                                                        dyAccResize += dragAmount.y

                                                                        var newW = wSpan
                                                                        var newH = hSpan
                                                                        val step = baseCellPx / 2f

                                                                        while (dxAccResize > step) {
                                                                            newW = (wSpan + 1).coerceIn(1, 3)
                                                                            dxAccResize -= step
                                                                        }
                                                                        while (dxAccResize < -step) {
                                                                            newW = (wSpan - 1).coerceIn(1, 3)
                                                                            dxAccResize += step
                                                                        }
                                                                        while (dyAccResize > step) {
                                                                            newH = (hSpan + 1).coerceIn(1, 3)
                                                                            dyAccResize -= step
                                                                        }
                                                                        while (dyAccResize < -step) {
                                                                            newH = (hSpan - 1).coerceIn(1, 3)
                                                                            dyAccResize += step
                                                                        }

                                                                        if (newW != wSpan || newH != hSpan) {
                                                                            setTileSpan(card.first, newW, newH)
                                                                            spanTick++
                                                                            selectedTiles.forEach { clearTileOffset(it) }
                                                                        }
                                                                    }
                                                                )
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (editMode) {
                                        tileContent(editModifier, true)
                                    } else {
                                        tileContent(viewModifier.then(Modifier.clickable { action(routes) }), false)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
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
                    if (newList.isEmpty()) {
                        editMode = false
                    }
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
}
}
