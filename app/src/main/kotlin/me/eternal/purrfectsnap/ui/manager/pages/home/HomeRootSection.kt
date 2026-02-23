package me.eternal.purrfectsnap.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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
import me.eternal.purrfectsnap.ui.manager.components.AestheticDialog
import me.eternal.purrfectsnap.ui.manager.data.UpdateDownloader
import me.eternal.purrfectsnap.ui.manager.data.Updater
import me.eternal.purrfectsnap.ui.manager.data.Updater.Channel
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.*
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

    private val announcementsUrl = "https://raw.githubusercontent.com/particle-box/PurrfectSnap/dev/announcements.txt"    

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
    @Composable
    private fun rememberCards(): Map<Pair<String, ImageVector>, (Routes) -> Unit> {
        return remember {
            val map = EnumQuickActions.entries.associate {
                (context.translation["actions.${it.key}.name"] to it.icon) to it.action
            }.toMutableMap()
            EnumAction.entries.forEach { action ->
                map[context.translation["actions.${action.key}.name"] to action.icon] = {
                    context.launchActionIntent(action)
                }
            }
            map
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
        label: String? = null,
        contentDescription: String? = label,
        shrinkFactor: Float = 1f,
        onClick: () -> Unit,
    ) {
        val chipShape = RoundedCornerShape(40)
        Surface(
            modifier = Modifier.widthIn(min = 36.dp),
            shape = chipShape,
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(
                1.dp, 
                Brush.linearGradient(
                    listOf(
                        PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                        PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .clip(chipShape)
                    .clickable(onClick = onClick)
                    .padding(
                        vertical = 6.dp,
                        horizontal = lerp(10.dp, 12.dp, shrinkFactor)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon, 
                    contentDescription = contentDescription, 
                    tint = Color.White, 
                    modifier = Modifier.size(20.dp).graphicsLayer {
                        val iconScale = 0.82f + (0.18f * shrinkFactor)
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                )
                if (label != null) {
                    val labelAlpha = (shrinkFactor - 0.1f).coerceIn(0f, 1f)
                    if (labelAlpha > 0f) {
                        Spacer(modifier = Modifier.width((8 * shrinkFactor).dp))
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = labelAlpha),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = labelAlpha
                                    translationX = (-4 * (1f - shrinkFactor)).dp.toPx()
                                }
                                .widthIn(max = (75 * shrinkFactor).dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.HomeActionChips(scrollState: ScrollState) {
        val shrinkFactor by remember {
            derivedStateOf { (1f - (scrollState.value.toFloat() / Motion.HEADER_MORPH_THRESHOLD)).coerceIn(0f, 1f) }
        }

        TopBarActionChip(
            icon = Icons.Filled.BugReport,
            label = context.translation["manager.routes.home_logs"],
            shrinkFactor = shrinkFactor
        ) { routes.homeLogs.navigate() }
        TopBarActionChip(
            icon = Icons.Filled.Settings,
            label = context.translation["manager.routes.home_settings"],
            shrinkFactor = shrinkFactor
        ) { routes.settings.navigate() }
    }

    @Composable
    private fun LivingPurrAura(isActive: Boolean) {
        val haptic = LocalHapticFeedback.current
        val infiniteTransition = rememberInfiniteTransition(label = "aura")
        
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        val glowAlpha1 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
            label = "g1"
        )
        val glowAlpha2 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, delayMillis = 1100, easing = LinearEasing), RepeatMode.Restart),
            label = "g2"
        )

        val coreColor by animateColorAsState(
            targetValue = if (isActive) PurrfectPalette.glowPrimary else Color(0xFF8C8CA3),
            animationSpec = tween(800), label = "coreColor"
        )
        val secondaryColor by animateColorAsState(
            targetValue = if (isActive) PurrfectPalette.glowSecondary else Color(0xFF6B6B7A),
            animationSpec = tween(800), label = "secColor"
        )

        LaunchedEffect(glowAlpha1) {
            if (isActive && glowAlpha1 < 0.05f) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        Canvas(modifier = Modifier.size(44.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = 6.dp.toPx()

            fun drawGlow(progress: Float, alpha: Float) {
                if (!isActive || progress <= 0f) return
                val radius = baseRadius * (1.2f + 4.5f * progress)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to coreColor.copy(alpha = 0.25f * (1f - progress) * alpha),
                        0.6f to secondaryColor.copy(alpha = 0.12f * (1f - progress) * alpha),
                        1.0f to Color.Transparent,
                        center = center, 
                        radius = radius
                    ),
                    radius = radius, center = center
                )
            }

            drawGlow(glowAlpha1, 0.8f)
            drawGlow(glowAlpha2, 0.5f)

            drawCircle(
                brush = Brush.radialGradient(listOf(coreColor, secondaryColor), center = center, radius = baseRadius * pulseScale),
                radius = baseRadius * pulseScale, center = center
            )
            
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = (baseRadius * pulseScale) * 0.25f,
                center = Offset(center.x - (baseRadius * pulseScale) * 0.3f, center.y - (baseRadius * pulseScale) * 0.3f)
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
        onAboutClick: () -> Unit,
        avenirNext: FontFamily,
        scrollOffset: () -> Int
    ) {
        val heroShape = RoundedCornerShape(36.dp)
        val gitHashShort = remember { (context.installationSummary.modInfo?.gitHash ?: BuildConfig.GIT_HASH).take(7) }
        Box(
            modifier = Modifier
                .padding(horizontal = cardMargin, vertical = 6.dp)
                .clip(heroShape)
                .background(Brush.linearGradient(heroGradientColors))
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
                        fontFamily = avenirNext,
                        modifier = Modifier.graphicsLayer {
                            // Starts at 250px scroll
                            alpha = (1f - ((scrollOffset() - 250f) / 120f)).coerceIn(0f, 1f)
                            translationY = (-scrollOffset() * 0.06f)
                        }
                    )
                    Text(
                        text = "By ΞTΞRNAL", 
                        color = Color.White.copy(alpha = 0.75f), 
                        fontSize = 14.sp, 
                        fontFamily = avenirNext,
                        modifier = Modifier.graphicsLayer {
                            // Starts at 300px scroll
                            alpha = (1f - ((scrollOffset() - 300f) / 120f)).coerceIn(0f, 1f)
                            translationY = (-scrollOffset() * 0.04f)
                        }
                    )
                    Text(
                        text = translation["hero_tagline"], 
                        color = Color.White.copy(alpha = 0.9f), 
                        fontSize = 15.sp, 
                        lineHeight = 20.sp, 
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            // Starts at 350px scroll
                            alpha = (1f - ((scrollOffset() - 350f) / 120f)).coerceIn(0f, 1f)
                            translationY = (-scrollOffset() * 0.02f)
                        }
                    )
                }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // Starts at 400px scroll
                            alpha = (1f - ((scrollOffset() - 400f) / 120f)).coerceIn(0f, 1f)
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroBadge(translation.format("hero_version_label", "version" to versionName, "channel" to channelLabel))
                    gitHashShort.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }?.let {
                        HeroBadge(translation.format("hero_build_label", "build" to it))
                    }
                }

                if (latestUpdate != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(translation["update_title"], color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(translation.format("update_content", "version" to (latestUpdate.versionName)), color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
                            }
                            AnimatedContent(targetState = downloadState, label = "UpdateDownloadHero") { state ->
                                when (state) {
                                    UpdateDownloader.DownloadState.IDLE,
                                    UpdateDownloader.DownloadState.FAILED -> {
                                        Button(onClick = onUpdateAction, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B152E))) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    UpdateDownloader.DownloadState.DOWNLOADING -> {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            CircularProgressIndicator(progress = { downloadProgress }, modifier = Modifier.size(28.dp), color = Color.White)
                                            Text("${(downloadProgress * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    UpdateDownloader.DownloadState.COMPLETED -> {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFA3F0C2))
                                            Text(translation["update_ready_label"], color = Color.White, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    val unifiedButtonWidth = 180.dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.width(unifiedButtonWidth).height(46.dp),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    LivingPurrAura(isActive = isPurrAuraActive)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPurrAuraActive) translation["purr_aura_active_label"] else translation["purr_aura_inactive_label"],
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onAboutClick,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.06f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.width(unifiedButtonWidth).height(46.dp)
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = translation.getOrNull("about_meet_team_button") ?: "About Us",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val androidContext = context.androidContext
                        Button(
                            modifier = Modifier.weight(1f).height(44.dp),
                            onClick = { androidContext.openLink("https://purrfectsnap.me", context.translation["toast_open_link_failed"]) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B152E)),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                PurrfectMarqueeText(
                                    text = "Site",
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                    color = Color(0xFF1B152E)
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(44.dp),
                            onClick = { androidContext.openLink("https://github.com/particle-box/PurrfectSnap", context.translation["toast_open_link_failed"]) },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                PurrfectMarqueeText(
                                    text = translation["github_button"],
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                        ExternalLinkIcon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram), onClick = { androidContext.openLink("https://t.me/purrfectsnap_official", context.translation["toast_open_link_failed"]) }, tint = Color.White, containerColor = Color.White.copy(alpha = 0.14f))
                    }
                }
            }
        }
    }

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember { FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium)) }
        val cards = rememberCards()
        val selectedTiles = rememberAsyncMutableStateList(defaultValue = listOf()) { context.database.getQuickTiles().filter { it.isNotBlank() } }
        val updateChannel = context.config.root.global.updateSettings.updateChannel.getNullable() ?: "stable"
        val channelLabel = if (updateChannel == "prerelease") translation["channel_label_prerelease"] else translation["channel_label_stable"]
        val latestUpdate by rememberAsyncMutableState(defaultValue = null, keys = arrayOf(updateChannel)) { Updater.getLatestRelease(if (updateChannel == "prerelease") Channel.PRERELEASE else Channel.STABLE) }
        val downloadState by UpdateDownloader.downloadState.collectAsState()
        val downloadProgress by UpdateDownloader.downloadProgress.collectAsState()
        val isPurrAuraActive by rememberPreferenceBool("debug_test_mode", true)
        val scrollState = rememberScrollState()
        var showQuickActionsMenu by rememberSaveable { mutableStateOf(false) }
        var showChangelogDialog by rememberSaveable { mutableStateOf(false) }
        var showAnnouncementsDialog by rememberSaveable { mutableStateOf(false) }
        var announcementsText by rememberSaveable { mutableStateOf<String?>(null) }
        var announcementsLoading by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        var controlsHeight by remember { mutableStateOf(100.dp) }

        LaunchedEffect(scrollState.value) { routes.navigation?.globalScrollOffset = scrollState.value }

        val handleUpdateAction: () -> Unit = {
            latestUpdate?.let { latest ->
                val abiName = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { when(it) { "arm64-v8a" -> "arm64"; "armeabi-v7a" -> "armv7"; else -> null } }
                if (latest.workflowId != null) {
                    if (abiName != null) {
                        // Use workflowId to ensure the specific build is downloaded via nightly.link
                        val artifactName = "purrfectsnap-${if (abiName == "arm64") "armv8" else "armv7"}-debug"
                        UpdateDownloader.downloadAndInstall(context, "https://nightly.link/particle-box/PurrfectSnap/actions/runs/${latest.workflowId}/$artifactName.zip", "$artifactName.zip", coroutineScope)
                    }
                } else {
                    abiName?.let { arch -> latest.assetDownloads[arch] }?.let { url -> UpdateDownloader.downloadAndInstall(context, url, url.substringAfterLast('/'), coroutineScope) }
                }
            }
        }

        fun loadAnnouncements() {
            if (announcementsText != null) return
            announcementsLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                runCatching { OkHttpClient().newCall(Request.Builder().url(announcementsUrl).build()).execute().use { it.body?.string() ?: "" } }
                    .onSuccess { withContext(Dispatchers.Main) { announcementsText = it; announcementsLoading = false } }
                    .onFailure { withContext(Dispatchers.Main) { announcementsLoading = false } }
            }
        }

        val borderPath = remember { Path() }
        val uPath = remember { Path() }

        Box(modifier = Modifier.fillMaxSize().background(pageBackgroundGradient)) {
            // FLOATING HEADER OVERLAY
            val focusFactor = (scrollState.value.toFloat() / Motion.HEADER_MORPH_THRESHOLD).coerceIn(0f, 1f)
            val stickyBrandingAlpha = ((scrollState.value.toFloat() - 50f) / 100f).coerceIn(0f, 1f)
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            
            // a438a7e & Particle-Box Symmetry Standards
            val headerHeight = lerp(54.dp, 56.dp, focusFactor)
            val sidePadding = 0.dp 
            val containerTopPadding = lerp(statusBarHeight + 2.dp, 0.dp, focusFactor)
            val internalTopPadding = lerp(0.dp, statusBarHeight, focusFactor)
            val internalVerticalPadding = 0.dp
            val topCorners = lerp(26.dp, 0.dp, focusFactor)
            val bottomCorners = lerp(26.dp, 28.dp, focusFactor)

            // Header Container
            Box(modifier = Modifier.fillMaxWidth().zIndex(10f)) {
                // "Under-Glass" Refractive Dissolve Layer
                val refractiveColor = Color(0xFF241F52)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sidePadding)
                        .padding(top = containerTopPadding)
                        .height(internalTopPadding + headerHeight + 32.dp)
                        .background(
                            Brush.verticalGradient(
                                0.0f to refractiveColor.copy(alpha = 0.95f * focusFactor),
                                0.6f to refractiveColor.copy(alpha = 0.85f * focusFactor),
                                1.0f to Color.Transparent
                            )
                        )
                )

                // 1. Background Sticky Bar (Fades in)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sidePadding)
                        .padding(top = containerTopPadding)
                        .headerHeightTracker { controlsHeight = it }
                        .drawBehind {
                            val strokeWidth = 1.dp.toPx()
                            val brush = Brush.linearGradient(
                                listOf(
                                    PurrfectPalette.glowPrimary.copy(alpha = focusFactor * 0.6f),
                                    PurrfectPalette.glowSecondary.copy(alpha = focusFactor * 0.4f)
                                )
                            )
                            val tr = topCorners.toPx()
                            val br = bottomCorners.toPx()
                            
                            if (focusFactor > 0.9f) {
                                uPath.reset()
                                uPath.apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, size.height - br)
                                    arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2*br, 2*br, size.height), 180f, -90f, false)
                                    lineTo(size.width - br, size.height)
                                    arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*br, size.height - 2*br, size.width, size.height), 90f, -90f, false)
                                    lineTo(size.width, 0f)
                                }
                                drawPath(uPath, brush, style = Stroke(strokeWidth))
                            } else if (focusFactor > 0.01f) {
                                borderPath.reset()
                                borderPath.apply {
                                    moveTo(tr, 0f)
                                    lineTo(size.width - tr, 0f)
                                    arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*tr, 0f, size.width, 2*tr), 270f, 90f, false)
                                    lineTo(size.width, size.height - br)
                                    arcTo(androidx.compose.ui.geometry.Rect(size.width - 2*br, size.height - 2*br, size.width, size.height), 0f, 90f, false)
                                    lineTo(br, size.height)
                                    arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2*br, 2*br, size.height), 90f, 90f, false)
                                    lineTo(0f, tr)
                                    arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2*tr, 2*tr), 180f, 90f, false)
                                }
                                drawPath(borderPath, brush, style = Stroke(strokeWidth))
                            }
                        },
                    shape = RoundedCornerShape(
                        topStart = topCorners, topEnd = topCorners, 
                        bottomStart = bottomCorners, bottomEnd = bottomCorners
                    ),
                    color = Color(0xFF1B152E).copy(alpha = focusFactor * 0.95f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = internalTopPadding)
                            .padding(horizontal = 16.dp, vertical = internalVerticalPadding)
                            .height(headerHeight)
                    ) {
                        // Original Logo Sticky Branding
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "PurrfectSnap",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(42.dp)
                                .graphicsLayer { 
                                    alpha = stickyBrandingAlpha
                                }
                        )

                        // LEFT: Announcement
                        val announcementShift = with(LocalDensity.current) { (-6 * focusFactor).dp.toPx() }
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .graphicsLayer { 
                                    translationX = announcementShift
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TopBarActionChip(
                                icon = Icons.Filled.Notifications,
                                label = null,
                                shrinkFactor = (1f - focusFactor).coerceIn(0f, 1f),
                                contentDescription = translation["announcements_button_description"]
                            ) {
                                showAnnouncementsDialog = true
                                loadAnnouncements()
                            }
                        }

                        // RIGHT: Logs & Settings (Group)
                        val settingsShift = with(LocalDensity.current) { (6 * focusFactor).dp.toPx() }
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .graphicsLayer { 
                                    translationX = settingsShift
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeActionChips(scrollState = scrollState)
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = routes.bottomPadding + 12.dp)) {
                Spacer(Modifier.height(controlsHeight + containerTopPadding))
                
                HeroSection(
                    versionName = BuildConfig.VERSION_NAME,
                    latestUpdate = latestUpdate,
                    downloadState = downloadState,
                    downloadProgress = downloadProgress,
                    onUpdateAction = { latestUpdate?.let { showChangelogDialog = true } },
                    channelLabel = channelLabel,
                    isPurrAuraActive = isPurrAuraActive,
                    onAboutClick = { routes.about.navigate() },
                    avenirNext = avenirNext,
                    scrollOffset = { scrollState.value }
                )
                
                Spacer(Modifier.height(12.dp))
                AnimatedContent(targetState = selectedTiles.isNotEmpty(), label = "QuickActions") { hasQuickActions ->
                    Surface(modifier = Modifier.padding(horizontal = cardMargin, vertical = 10.dp), shape = RoundedCornerShape(34.dp), color = Color.Transparent, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))) {
                        Column(modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(quickActionsGradientColors)).padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!hasQuickActions) {
                                Text(translation["quick_actions_title"], fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
                                Spacer(Modifier.height(24.dp))
                                Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text(translation["quick_actions_empty_title"], fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.height(20.dp))
                                Button(onClick = { showQuickActionsMenu = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B152E))) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(translation["quick_actions_add_tile_button"])
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(translation["quick_actions_title"], fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(translation.format("quick_actions_count_label", "count" to selectedTiles.size.toString()), fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(onClick = { showQuickActionsMenu = true }, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                        Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_manage), contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(translation["quick_actions_manage_button"])
                                    }
                                }

                                var gridIsVisible by remember { mutableStateOf(false) }
                                var animationPhase by remember { mutableIntStateOf(1) }
                                
                                LaunchedEffect(gridIsVisible) {
                                    if (gridIsVisible) {
                                        delay(600) // Initial wait
                                        animationPhase = 2 // Icons shrink & Text expands to 2 lines
                                        delay(1200) // Distinct wait for expansion to finish
                                        animationPhase = 3 // Enable Marquee scrolling
                                    }
                                }

                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coordinates ->
                                            val windowHeight = context.androidContext.resources.displayMetrics.heightPixels
                                            val positionInWindow = coordinates.localToWindow(Offset.Zero).y
                                            // Trigger when the grid reaches the bottom area of the screen
                                            if (positionInWindow > 0 && positionInWindow < windowHeight * 0.95f) {
                                                gridIsVisible = true
                                            }
                                        }
                                ) {
                                    val columns = (maxWidth / 110.dp).toInt().coerceIn(2, 4)
                                    FlowRow(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = columns) {
                                        selectedTiles.forEachIndexed { index, name ->
                                            val cardEntry = cards.entries.find { it.key.first == name } ?: return@forEachIndexed
                                            val interactionSource = remember { MutableInteractionSource() }
                                            
                                            val animatedIconSize by animateDpAsState(
                                                targetValue = if (animationPhase >= 2) 28.dp else 44.dp,
                                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                                                label = "iconShrink"
                                            )

                                            Surface(
                                                modifier = Modifier
                                                    .width(100.dp)
                                                    .aspectRatio(1.05f) // Restored to 1.05f for square look
                                                    .scaleOnPress(interactionSource)
                                                    .clickable { cardEntry.value(routes) }, 
                                                shape = RoundedCornerShape(18.dp), 
                                                color = Color.White.copy(alpha = 0.06f), 
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                                            ) {
                                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(PurrfectPalette.glowPrimary.copy(alpha = 0.3f), PurrfectPalette.glowSecondary.copy(alpha = 0.22f)))).clipToBounds()) {
                                                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                                        Icon(cardEntry.key.second, contentDescription = null, tint = Color.White, modifier = Modifier.size(animatedIconSize))
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(
                                                            text = cardEntry.key.first,
                                                            style = TextStyle(lineHeight = 15.sp, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                                            color = Color.White,
                                                            maxLines = if (animationPhase >= 2) 2 else 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            softWrap = true,
                                                            modifier = Modifier.fillMaxWidth()
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
                }
            }
        }

        if (showAnnouncementsDialog) {
            AestheticDialog(onDismissRequest = { showAnnouncementsDialog = false }, title = "Announcements", text = "", icon = Icons.Filled.Info, confirmButtonText = "Close", onConfirm = { showAnnouncementsDialog = false }, customContent = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (announcementsLoading) CircularProgressIndicator(color = Color.White)
                    else Text(announcementsText ?: "No announcements", color = PurrfectPalette.textPrimary, fontSize = 14.sp)
                }
            })
        }
        
        if (showChangelogDialog) {
            AestheticDialog(onDismissRequest = { showChangelogDialog = false }, title = "Changelog", text = "Latest refinements and stability fixes.", icon = Icons.Filled.Info, confirmButtonText = "Update", onConfirm = { showChangelogDialog = false; handleUpdateAction() }, dismissButtonText = "Cancel", onDismiss = { showChangelogDialog = false })
        }

        if (showQuickActionsMenu) {
            QuickActionsDialog(quickActions = cards, selectedQuickActions = selectedTiles, onDismiss = { showQuickActionsMenu = false }, onSave = { selectedTiles.clear(); selectedTiles.addAll(it); context.coroutineScope.launch { context.database.setQuickTiles(selectedTiles) }; showQuickActionsMenu = false }, translation = translation)
        }
    }
}
