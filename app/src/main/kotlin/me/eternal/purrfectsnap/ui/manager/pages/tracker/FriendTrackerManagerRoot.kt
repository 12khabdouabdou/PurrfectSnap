package me.eternal.purrfectsnap.ui.manager.pages.tracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Store
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfectsnap.common.ui.rememberAsyncUpdateDispatcher
import me.eternal.purrfectsnap.common.util.snap.BitmojiSelfie
import me.eternal.purrfectsnap.storage.*
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.components.AestheticDialog
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.headerHeightTracker
import me.eternal.purrfectsnap.ui.util.Motion
import me.eternal.purrfectsnap.ui.util.ActivityLauncherHelper
import me.eternal.purrfectsnap.ui.util.coil.BitmojiImage
import me.eternal.purrfectsnap.ui.util.openFile
import me.eternal.purrfectsnap.ui.util.purrfectSwitchColors

@OptIn(ExperimentalFoundationApi::class)
class FriendTrackerManagerRoot : Routes.Route() {
    enum class FilterType {
        CONVERSATION, USERNAME, EVENT
    }

    override val translation by lazy { context.translation.getCategory("manager.friend_tracker") }
    private val titles by lazy {
        listOf(
            translation["rules_tab"],
            translation["logs_tab"]
        )
    }
    private var currentPage by mutableIntStateOf(0)
    private lateinit var logDeleteAction : () -> Unit
    private lateinit var exportAction : () -> Unit

    @Composable
    private fun TrackerIconButton(
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        val backgroundBrush = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.28f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.24f)
                )
            )
        }
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.White.copy(alpha = 0.06f),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = modifier.size(46.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(backgroundBrush, shape)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = contentDescription, tint = Color.White)
            }
        }
    }

    @Composable
    private fun TrackerActionButton(
        label: String,
        icon: ImageVector,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val shape = RoundedCornerShape(22.dp)
        val backgroundBrush = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.34f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.3f)
                )
            )
        }
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier = modifier
        ) {
            Box(
                modifier = Modifier
                    .background(backgroundBrush, shape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = label, tint = Color.White)
                    Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    private fun TrackerPillButton(
        label: String,
        icon: ImageVector,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        scrollOffset: Int = 0
    ) {
        val shrinkThreshold = 300f
        val focusFactor = (scrollOffset / shrinkThreshold).coerceIn(0f, 1f)
        val labelAlpha = (1f - (focusFactor * 2.5f)).coerceIn(0f, 1f)
        
        val shape = RoundedCornerShape(18.dp)
        val backgroundBrush = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.32f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.26f)
                )
            )
        }
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = modifier
        ) {
            Row(
                modifier = Modifier
                    .background(backgroundBrush, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (labelAlpha > 0.05f) 8.dp else 0.dp)
            ) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
                if (labelAlpha > 0.05f) {
                    Text(
                        label, 
                        color = Color.White.copy(alpha = labelAlpha), 
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }

    override val topBarActions: @Composable RowScope.() -> Unit = {
        // Handled via FloatingTopBar
    }

    private lateinit var activityLauncherHelper: ActivityLauncherHelper

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    override val floatingActionButton: @Composable () -> Unit = {
        when (currentPage) {
            1 -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    TrackerActionButton(
                        label = translation["export_button"],
                        icon = Icons.Default.SaveAlt,
                        onClick = { context.coroutineScope.launch { exportAction() } }
                    )
                    TrackerActionButton(
                        label = translation["delete_button"],
                        icon = Icons.Default.DeleteOutline,
                        onClick = { context.coroutineScope.launch { logDeleteAction() } }
                    )
                }
            }
            0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                    TrackerActionButton(
                        label = translation["catalog_button"],
                        icon = Icons.Default.Store,
                        onClick = { routes.friendTrackerCatalog.navigate() }
                    )
                    TrackerActionButton(
                        label = translation["add_rule_button"],
                        icon = Icons.Default.Add,
                        onClick = { routes.editRule.navigate() }
                    )
                }
            }
        }
    }

    @Composable
    private fun ConfigRulesTab(scrollOffset: (Int) -> Unit) {
        val updateRules = rememberAsyncUpdateDispatcher()
        val rules = rememberAsyncMutableStateList(defaultValue = listOf(), updateDispatcher = updateRules) {
            context.database.getTrackerRulesDesc()
        }
        val listState = rememberLazyListState()
        
        LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) {
            val offset = if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else listState.firstVisibleItemScrollOffset
            scrollOffset(offset)
        }

        @Composable
        fun EmptyState(text: String) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        PurrfectPalette.glowPrimary.copy(alpha = 0.32f),
                                        PurrfectPalette.glowSecondary.copy(alpha = 0.28f)
                                    )
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.AutoGraph, contentDescription = text, tint = Color.White)
                    }
                }
                Text(text, color = Color.White, fontWeight = FontWeight.ExtraBold)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = routes.bottomPadding)
        ) {
            item {
                if (rules.isEmpty()) {
                    EmptyState(translation["no_rules_found"])
                }
            }
            items(rules, key = { it.id }) { rule ->
                val ruleName by rememberAsyncMutableState(defaultValue = rule.name) {
                    context.database.getTrackerRule(rule.id)?.name ?: translation["empty_rule_name"]
                }
                val eventCount by rememberAsyncMutableState(defaultValue = 0) {
                    context.database.getTrackerEvents(rule.id).size
                }
                val scopeCount by rememberAsyncMutableState(defaultValue = 0) {
                    context.database.getRuleTrackerScopes(rule.id).size
                }
                var enabled by rememberAsyncMutableState(defaultValue = rule.enabled) {
                    context.database.getTrackerRule(rule.id)?.enabled ?: false
                }

                val ruleShape = RoundedCornerShape(20.dp)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            routes.editRule.navigate {
                                this["rule_id"] = rule.id.toString()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    shape = ruleShape,
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                PurrfectPalette.glowPrimary.copy(alpha = 0.5f),
                                PurrfectPalette.glowSecondary.copy(alpha = 0.4f)
                            )
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PurrfectPalette.cardOverlay, ruleShape)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                PurrfectPalette.glowPrimary.copy(alpha = 0.35f),
                                                PurrfectPalette.glowSecondary.copy(alpha = 0.3f)
                                            )
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = null, tint = Color.White)
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(ruleName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(
                                buildString {
                                    append(eventCount)
                                    append(" ")
                                    append(translation["events_suffix"])
                                    if (scopeCount > 0) {
                                        append(" • ")
                                        append(scopeCount)
                                        append(" ")
                                        append(translation["scopes_suffix"])
                                    }
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PurrfectPalette.textSecondary
                            )
                            if (scopeCount > 0) {
                                val scopesBitmoji = rememberAsyncMutableStateList(defaultValue = emptyList()) {
                                    context.database.getRuleTrackerScopes(rule.id, limit = 8).mapNotNull {
                                        context.database.getFriendInfo(it.key)?.let { friend ->
                                            friend.selfieId to friend.bitmojiId
                                        }
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy((-10).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    scopesBitmoji.take(4).forEach { friend ->
                                        BitmojiImage(
                                            size = 34,
                                            modifier = Modifier
                                                .border(BorderStroke(1.dp, Color.White), CircleShape)
                                                .background(Color.White, CircleShape)
                                                .clip(CircleShape),
                                            context = context,
                                            url = BitmojiSelfie.getBitmojiSelfie(friend.first, friend.second, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D),
                                        )
                                    }
                                    if (scopeCount > scopesBitmoji.size) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.08f),
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                        ) {
                                            Text(
                                                text = "+${scopeCount - scopesBitmoji.size}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                Text(
                                    text = translation[if (enabled) "enabled_label" else "disabled_label"],
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    context.database.setTrackerRuleState(rule.id, it)
                                },
                                colors = purrfectSwitchColors()
                            )
                        }
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalFoundationApi::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val coroutineScope = rememberCoroutineScope()
        val pagerState = rememberPagerState(initialPage = 0) { titles.size }
        currentPage = pagerState.currentPage
        
        var scrollOffset by remember { mutableIntStateOf(0) }
        var showExportDialog by remember { mutableStateOf(false) }
        var showSingleExportDialog by remember { mutableStateOf(false) }
        var showImportDialog by remember { mutableStateOf(false) }
        var showInvalidImportTypeDialog by remember { mutableStateOf(false) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        var controlsHeight by remember { mutableStateOf(100.dp) }

        fun handleImport(type: me.eternal.purrfectsnap.common.data.ExportType) {
            routes.activityLauncher.openFile("application/json") { uri ->
                runCatching {
                    val content = context.androidContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@runCatching
                    val exportedData = context.gson.fromJson(content, me.eternal.purrfectsnap.common.data.ExportedTrackerData::class.java)
                    if (exportedData.type != type) {
                        showInvalidImportTypeDialog = true
                        return@runCatching
                    }
                    routes.friendTrackerConfigJsonForImport = content
                    routes.friendTrackerConfigImport.navigate()
                }.onFailure {
                    context.longToast(
                        translation.format("read_file_failed_toast", "message" to (it.message ?: ""))
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar(
                    title = context.translation["manager.routes.friend_tracker"],
                    subtitle = titles.getOrNull(pagerState.currentPage) ?: "",
                    onBack = { routes.navController.popBackStack() },
                    scrollOffset = scrollOffset,
                    modifier = Modifier.headerHeightTracker { controlsHeight = it },
                    actions = {
                        if (pagerState.currentPage == 0) {
                            TrackerPillButton(
                                label = translation["import_button"],
                                icon = Icons.Default.FolderOpen,
                                scrollOffset = scrollOffset,
                                onClick = { showImportDialog = true }
                            )
                            TrackerPillButton(
                                label = translation["export_button"],
                                icon = Icons.Default.SaveAlt,
                                scrollOffset = scrollOffset,
                                onClick = { showExportDialog = true }
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    titles.forEachIndexed { i, text ->
                        val selected = pagerState.currentPage == i
                        Surface(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(i) }
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
                            border = if (selected) BorderStroke(1.dp, Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary))) else BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                HorizontalPager(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    state = pagerState
                ) { page ->
                    when (page) {
                        1 -> LogsTab(
                            context = context,
                            activityLauncherHelper = activityLauncherHelper,
                            deleteAction = { logDeleteAction = it },
                            exportAction = { exportAction = it },
                            bottomPadding = routes.bottomPadding,
                            scrollOffset = { scrollOffset = it }
                        )
                        0 -> ConfigRulesTab(scrollOffset = { scrollOffset = it })
                    }
                }
            }
        }

        if (showExportDialog) {
            ChoiceDialog(
                onDismissRequest = { showExportDialog = false },
                title = translation["export_dialog_title"],
                choices = listOf(
                    translation["bulk_export_button"] to { Icon(Icons.Default.UploadFile, translation["bulk_export_button"], tint = Color.White) },
                    translation["individual_export_button"] to { Icon(Icons.Default.FileOpen, translation["individual_export_button"], tint = Color.White) }
                ),
                onChoiceSelected = { index ->
                    showExportDialog = false
                    when (index) {
                        0 -> routes.friendTrackerConfigExport.navigate()
                        1 -> showSingleExportDialog = true
                    }
                }
            )
        }

        if (showSingleExportDialog) {
            val rules = rememberAsyncMutableStateList(defaultValue = emptyList()) {
                context.database.getTrackerRulesDesc()
            }
            SelectRuleDialog(
                onDismissRequest = { showSingleExportDialog = false },
                rules = rules,
                onRuleSelected = { rule ->
                    showSingleExportDialog = false
                    routes.friendTrackerConfigExport.navigate {
                        this["rule_id"] = rule.id.toString()
                    }
                },
                translation = translation
            )
        }

        if (showInvalidImportTypeDialog) {
            AlertDialog(
                onDismissRequest = { showInvalidImportTypeDialog = false },
                title = { Text(translation["invalid_import_type_dialog_title"]) },
                text = { Text(translation["invalid_import_type_dialog_text"]) },
                confirmButton = {
                    Button(onClick = { showInvalidImportTypeDialog = false }) {
                        Text(translation["button.ok"])
                    }
                }
            )
        }

        if (showImportDialog) {
            ChoiceDialog(
                onDismissRequest = { showImportDialog = false },
                title = translation["import_dialog_title"],
                choices = listOf(
                    translation["bulk_import_button"] to { Icon(Icons.Default.UploadFile, translation["bulk_import_button"], tint = Color.White) },
                    translation["individual_import_button"] to { Icon(Icons.Default.FileOpen, translation["individual_import_button"], tint = Color.White) }
                ),
                onChoiceSelected = { index ->
                    showImportDialog = false
                    when (index) {
                        0 -> handleImport(me.eternal.purrfectsnap.common.data.ExportType.BULK)
                        1 -> handleImport(me.eternal.purrfectsnap.common.data.ExportType.SINGLE)
                    }
                }
            )
        }
    }
}

@Composable
private fun SelectRuleDialog(
    onDismissRequest: () -> Unit,
    rules: List<me.eternal.purrfectsnap.common.data.TrackerRule>,
    onRuleSelected: (me.eternal.purrfectsnap.common.data.TrackerRule) -> Unit,
    translation: me.eternal.purrfectsnap.common.bridge.wrapper.LocaleWrapper
) {
    Dialog(onDismissRequest = onDismissRequest) {
        val shape = RoundedCornerShape(24.dp)
        Surface(
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay, shape)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    translation["manager.friend_tracker.select_rule_to_export_title"], 
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(rules) { rule ->
                        Surface(
                            onClick = { onRuleSelected(rule) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                        ) {
                            Text(
                                text = rule.name,
                                modifier = Modifier.padding(16.dp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(translation["button.cancel"], color = PurrfectPalette.glowSecondary)
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(
    onDismissRequest: () -> Unit,
    title: String,
    choices: List<Pair<String, @Composable () -> Unit>>,
    onChoiceSelected: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        val shape = RoundedCornerShape(24.dp)
        Surface(
            shape = shape,
            color = Color.Transparent,
            shadowElevation = 20.dp,
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
                        PurrfectPalette.glowSecondary.copy(alpha = 0.5f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay, shape)
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                choices.forEachIndexed { index, (text, icon) ->
                    Surface(
                        onClick = { onChoiceSelected(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            icon()
                            Text(text = text, modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
