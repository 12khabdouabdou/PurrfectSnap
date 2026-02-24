package me.eternal.purrfectsnap.ui.manager.pages.scripting

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import me.eternal.purrfectsnap.common.scripting.type.ModuleInfo
import me.eternal.purrfectsnap.common.scripting.ui.EnumScriptInterface
import me.eternal.purrfectsnap.common.scripting.ui.InterfaceManager
import me.eternal.purrfectsnap.common.scripting.ui.ScriptInterface
import me.eternal.purrfectsnap.common.ui.AsyncUpdateDispatcher
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfectsnap.common.ui.rememberAsyncUpdateDispatcher
import me.eternal.purrfectsnap.common.util.ktx.getUrlFromClipboard
import me.eternal.purrfectsnap.common.util.ktx.openLink
import me.eternal.purrfectsnap.storage.isScriptEnabled
import me.eternal.purrfectsnap.storage.setScriptEnabled
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.components.AestheticDialog
import me.eternal.purrfectsnap.ui.manager.components.AestheticEmptyState
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.headerHeightTracker
import me.eternal.purrfectsnap.ui.util.Motion
import me.eternal.purrfectsnap.ui.util.ActivityLauncherHelper
import me.eternal.purrfectsnap.ui.util.Dialog
import me.eternal.purrfectsnap.ui.util.chooseFolder
import me.eternal.purrfectsnap.ui.util.purrfectSwitchColors
import me.eternal.purrfectsnap.ui.util.pullrefresh.PullRefreshIndicator
import me.eternal.purrfectsnap.ui.util.pullrefresh.pullRefresh
import me.eternal.purrfectsnap.ui.util.pullrefresh.rememberPullRefreshState

class ScriptingRootSection : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.scripting") }
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    val reloadDispatcher = AsyncUpdateDispatcher(updateOnFirstComposition = false)

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    suspend fun isScriptInstalledByUrl(scriptUrl: String): Boolean {
        return try {
            val installedScripts = context.scriptManager.getSyncedModules()
            installedScripts.any { module ->
                module.updateUrl?.equals(scriptUrl, ignoreCase = true) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun downloadScript(scriptUrl: String, onComplete: () -> Unit) {
        context.coroutineScope.launch {
            if (isScriptInstalledByUrl(scriptUrl)) {
                context.shortToast(translation["script_already_installed"])
                return@launch
            }

            runCatching {
                context.shortToast(translation["downloading_script"])
                val moduleInfo = context.scriptManager.importFromUrl(scriptUrl)
                context.shortToast(translation.format("script_downloaded", "name" to moduleInfo.name))
                reloadDispatcher.dispatch()
                onComplete()
            }.onFailure {
                context.log.error("Failed to download script", it)
                context.shortToast(translation["download_script_failed"])
            }
        }
    }

    @Composable
    private fun ImportRemoteScript(
        dismiss: () -> Unit
    ) {
        var url by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        var isLoading by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            context.androidContext.getUrlFromClipboard()?.let { url = it }
        }

        AestheticDialog(
            onDismissRequest = dismiss,
            title = translation["import_script_from_url_title"],
            text = translation["import_script_warning"],
            icon = Icons.Default.Link,
            confirmButtonText = translation["import_button"],
            dismissButtonText = translation["button.cancel"],
            onDismiss = dismiss,
            loading = isLoading,
            confirmEnabled = url.isNotBlank(),
            showCloseButton = false,
            onConfirm = {
                isLoading = true
                context.coroutineScope.launch {
                    runCatching {
                        if (isScriptInstalledByUrl(url)) {
                            context.shortToast(translation["script_already_installed"])
                            withContext(Dispatchers.Main) {
                                dismiss()
                            }
                            return@launch
                        }

                        val moduleInfo = context.scriptManager.importFromUrl(url)
                        context.shortToast(translation.format("script_imported", "name" to moduleInfo.name))
                        reloadDispatcher.dispatch()
                        withContext(Dispatchers.Main) {
                            dismiss()
                        }
                        return@launch
                    }.onFailure {
                        context.log.error("Failed to import script", it)
                        context.shortToast(translation.format("import_failed", "message" to (it.message ?: "Unknown")))
                    }
                    isLoading = false
                }
            },
            customContent = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(text = translation["enter_url_label"]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { focusRequester.requestFocus() },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        focusedIndicatorColor = PurrfectPalette.glowSecondary.copy(alpha = 0.5f),
                        unfocusedIndicatorColor = Color.White.copy(alpha = 0.12f),
                        cursorColor = PurrfectPalette.glowSecondary,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = PurrfectPalette.textSecondary
                    )
                )
            }
        )
    }

    @Composable
    private fun ModuleActions(
        script: ModuleInfo,
        canUpdate: Boolean,
        dismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = dismiss) {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(2.dp)) {
                val actions = remember {
                    mutableMapOf<Pair<String, ImageVector>, suspend () -> Unit>().apply {
                        if (canUpdate) {
                            put(translation["update_module_button"] to Icons.Default.Download) {
                                dismiss()
                                context.shortToast(translation.format("updating_script", "name" to script.name))
                                runCatching {
                                    val modulePath = context.scriptManager.getModulePath(script.name) ?: throw Exception(translation["module_not_found"])
                                    context.scriptManager.unloadScript(modulePath)
                                    val moduleInfo = context.scriptManager.importFromUrl(script.updateUrl!!, filepath = modulePath)
                                    context.shortToast(translation.format("updated_script", "name" to script.name, "version" to moduleInfo.version))
                                    context.database.setScriptEnabled(script.name, false)
                                    withContext(context.database.executor.asCoroutineDispatcher()) {
                                        reloadDispatcher.dispatch()
                                    }
                                }.onFailure {
                                    context.log.error("Failed to update module", it)
                                    context.shortToast(translation["update_module_failed"])
                                }
                            }
                        }
                        put(translation["edit_module_button"] to Icons.Default.Edit) {
                            runCatching {
                                val modulePath = context.scriptManager.getModulePath(script.name)!!
                                context.androidContext.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        data = context.scriptManager.getScriptsFolder()!!.findFile(modulePath)!!.uri
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    }
                                )
                                dismiss()
                            }.onFailure {
                                context.log.error("Failed to open module file", it)
                                context.shortToast(translation["open_module_failed"])
                            }
                        }
                        put(translation["clear_module_data_button"] to Icons.Default.Save) {
                            runCatching {
                                context.scriptManager.getModuleDataFolder(script.name).deleteRecursively()
                                context.shortToast(translation["module_data_cleared"])
                                dismiss()
                            }.onFailure {
                                context.log.error("Failed to clear module data", it)
                                context.shortToast(translation["clear_module_data_failed"])
                            }
                        }
                        put(translation["delete_module_button"] to Icons.Default.DeleteOutline) {
                            context.scriptManager.apply {
                                runCatching {
                                    val modulePath = getModulePath(script.name)!!
                                    unloadScript(modulePath)
                                    getScriptsFolder()?.findFile(modulePath)?.delete()
                                    reloadDispatcher.dispatch()
                                    context.shortToast(translation.format("deleted_script", "name" to script.name))
                                    dismiss()
                                }.onFailure {
                                    context.log.error("Failed to delete module", it)
                                    context.shortToast(translation["delete_module_failed"])
                                }
                            }
                        }
                    }.toMap()
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Text(
                            text = translation["actions_title"],
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    items(actions.size) { index ->
                        val action = actions.entries.elementAt(index)
                        ListItem(
                            modifier = Modifier
                                .clickable { context.coroutineScope.launch { action.value(); dismiss() } }
                                .fillMaxWidth(),
                            leadingContent = {
                                Icon(action.key.second, action.key.first)
                            },
                            headlineContent = { Text(action.key.first) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ModuleItem(script: ModuleInfo) {
        var enabled by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(script)) {
            context.database.isScriptEnabled(script.name)
        }
        var openSettings by remember(script) { mutableStateOf(false) }
        var openActions by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        val dispatcher = rememberAsyncUpdateDispatcher()
        val reloadCallback = remember { suspend { dispatcher.dispatch() } }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null, updateDispatcher = dispatcher, keys = arrayOf(script)) {
            context.scriptManager.checkForUpdate(script)
        }

        LaunchedEffect(Unit) {
            reloadDispatcher.addCallback(reloadCallback)
        }
        DisposableEffect(Unit) {
            onDispose { reloadDispatcher.removeCallback(reloadCallback) }
        }

        val cardShape = RoundedCornerShape(20.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(
                1.dp,
                if (enabled) Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary))
                else SolidColor(Color.White.copy(alpha = 0.08f))
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { 
                        if (enabled) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            openSettings = !openSettings 
                        }
                    }
                    .background(PurrfectPalette.cardOverlay, cardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        PurrfectPalette.glowPrimary.copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = script.displayName ?: script.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = script.description ?: translation["no_description"],
                            fontSize = 13.sp,
                            color = PurrfectPalette.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            latestUpdate?.let {
                                AssistChip(
                                    onClick = { },
                                    enabled = false,
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = PurrfectPalette.glowSecondary) },
                                    label = { Text(translation.format("update_available", "version" to it.version)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        labelColor = Color.White
                                    )
                                )
                            }
                            if (openSettings && enabled) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(translation["actions_button"]) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.06f),
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                    IconButton(onClick = { 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        openActions = !openActions 
                    }) {
                        Icon(Icons.Default.Build, translation["actions_button"], tint = Color.White)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { isChecked ->
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            openSettings = false
                            context.coroutineScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val modulePath = context.scriptManager.getModulePath(script.name)!!
                                    context.scriptManager.unloadScript(modulePath)
                                    if (isChecked) {
                                        context.scriptManager.loadScript(modulePath)
                                        context.scriptManager.runtime.getModuleByName(script.name)
                                            ?.callFunction("module.onPurrfectSnapLoad")
                                        context.shortToast(translation.format("loaded_script", "name" to script.name))
                                    } else {
                                        context.shortToast(translation.format("unloaded_script", "name" to script.name))
                                    }
                                    context.database.setScriptEnabled(script.name, isChecked)
                                    withContext(Dispatchers.Main) { enabled = isChecked }
                                }.onFailure { throwable ->
                                    withContext(Dispatchers.Main) { enabled = !isChecked }
                                    context.log.error("Failed to ${if (isChecked) "enable" else "disable"} script", throwable)
                                    context.shortToast(translation.format(if (isChecked) "enable_script_failed" else "disable_script_failed"))
                                }
                            }
                        },
                        colors = purrfectSwitchColors()
                    )
                }
                if (openSettings) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    ScriptSettings(script)
                }
            }
        }
        if (openActions) {
            ModuleActions(script = script, canUpdate = latestUpdate != null) { openActions = false }
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {}

    @Composable
    private fun SelectFolderButton(onClick: () -> Unit) {
        val label = translation.getOrNull("select_folder_button") ?: "Select folder"
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.1f),
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            PurrfectPalette.glowPrimary.copy(alpha = 0.7f),
                            PurrfectPalette.glowSecondary.copy(alpha = 0.65f)
                        )
                    )
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    PurrfectPalette.glowPrimary.copy(alpha = 0.335f),
                                    PurrfectPalette.glowSecondary.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        )
                        .clickable(onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onClick()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = label,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun ScriptSettings(script: ModuleInfo) {
        val settingsInterface = remember {
            val module =
                context.scriptManager.runtime.getModuleByName(script.name) ?: return@remember null
            (module.getBinding(InterfaceManager::class))?.buildInterface(EnumScriptInterface.SETTINGS)
        }
        if (settingsInterface == null) {
            Text(
                text = translation["no_settings_for_module"],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            ScriptInterface(interfaceBuilder = settingsInterface)
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val scriptingFolder by rememberAsyncMutableState(
            defaultValue = null,
            updateDispatcher = reloadDispatcher
        ) { context.scriptManager.getScriptsFolder() }
        val tabTitles = listOf(translation["installed_scripts_tab"], translation["catalog_tab"])
        var showImportDialog by remember { mutableStateOf(false) }
        var showToast by remember { mutableStateOf(false) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        var controlsHeight by remember { mutableStateOf(100.dp) }
        val coroutineScope = rememberCoroutineScope()
        val pagerState = androidx.compose.foundation.pager.rememberPagerState { tabTitles.size }

        LaunchedEffect(scriptingFolder) {
            if (scriptingFolder == null && pagerState.currentPage != 0) {
                pagerState.scrollToPage(0)
            }
        }

        if (showImportDialog) {
            ImportRemoteScript { showImportDialog = false }
        }
        if (showToast) {
            LaunchedEffect(showToast) {
                context.shortToast(translation["select_scripts_folder_toast"])
                showToast = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            ScriptingHeader(
                titles = tabTitles,
                selectedTab = pagerState.currentPage,
                onTabSelected = { index ->
                    if (index == 1 && scriptingFolder == null) {
                        showToast = true
                    } else {
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    }
                },
                onImport = {
                    if (scriptingFolder == null) showToast = true else showImportDialog = true
                },
                onOpenFolder = {
                    if (scriptingFolder == null) {
                        showToast = true
                    } else {
                        scriptingFolder?.let {
                            context.androidContext.openLink(
                                it.uri.toString(),
                                context.translation["toast_open_link_failed"]
                            )
                        }
                    }
                },
                onManageRepos = { routes.manageScriptRepos.navigate() },
                onDocs = {
                    context.androidContext.openLink(
                        "https://github.com/SnapEnhance/scripting-docs",
                        context.translation["toast_open_link_failed"]
                    )
                },
                folderSelected = scriptingFolder != null,
                onPositioned = { controlsHeight = it }
            )
            
            androidx.compose.foundation.pager.HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                userScrollEnabled = scriptingFolder != null
            ) { page ->
                when (page) {
                    0 -> InstalledTabContent(
                        scriptingFolder = scriptingFolder,
                        controlsHeight = controlsHeight
                    )
                    1 -> CatalogTabContent(
                        scriptingFolder = scriptingFolder,
                        controlsHeight = controlsHeight
                    )
                }
            }

            var scriptingWarning by remember {
                mutableStateOf<Boolean>(context.sharedPreferences.run {
                    getBoolean("scripting_warning", true).also {
                        if (it) edit().putBoolean("scripting_warning", false).apply()
                    }
                })
            }

            if (scriptingWarning) {
                var timeout by remember { mutableIntStateOf(10) }
                LaunchedEffect(Unit) {
                    while (timeout > 0) {
                        delay(1000)
                        timeout--
                    }
                }
                AestheticDialog(
                    onDismissRequest = { if (timeout == 0) scriptingWarning = false },
                    title = context.translation["manager.dialogs.scripting_warning.title"] ?: "Scripting Warning",
                    text = context.translation["manager.dialogs.scripting_warning.content"] ?: "Scripts can execute arbitrary code on your device. Only install scripts from trusted sources.",
                    icon = Icons.Default.Warning,
                    confirmButtonText = translation["button.ok"] ?: "OK",
                    onConfirm = { if (timeout == 0) scriptingWarning = false },
                    loading = timeout > 0,
                    showCloseButton = false,
                    customContent = {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = PurrfectPalette.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    PurrfectPalette.glowPrimary.copy(alpha = 0.25f),
                                                    PurrfectPalette.glowSecondary.copy(alpha = 0.18f)
                                                )
                                            ),
                                            shape = RoundedCornerShape(18.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = timeout.toString(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun InstalledTabContent(
        scriptingFolder: DocumentFile?,
        controlsHeight: androidx.compose.ui.unit.Dp
    ) {
        val scriptModules by rememberAsyncMutableState(
            defaultValue = emptyList(),
            updateDispatcher = reloadDispatcher
        ) { context.scriptManager.sync(); context.scriptManager.getSyncedModules() }
        val coroutineScope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            refreshing = true
            withContext(Dispatchers.IO) {
                reloadDispatcher.dispatch()
                refreshing = false
            }
        }
        val pullRefreshState = rememberPullRefreshState(refreshing, onRefresh = {
            refreshing = true
            coroutineScope.launch(Dispatchers.IO) {
                reloadDispatcher.dispatch()
                refreshing = false
            }
        })

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val listState = rememberLazyListState()

            LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) {
                val offset = if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else listState.firstVisibleItemScrollOffset
                routes.navigation?.globalScrollOffset = offset
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState),
                state = listState,
                contentPadding = PaddingValues(bottom = routes.bottomPadding, start = 8.dp, end = 8.dp, top = 12.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (scriptingFolder == null && !refreshing) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(
                                    1.dp,
                                    Brush.linearGradient(
                                        listOf(
                                            PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
                                            PurrfectPalette.glowSecondary.copy(alpha = 0.45f)
                                        )
                                    )
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color.White.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = PurrfectPalette.glowSecondary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Text(
                                        text = translation["no_scripts_folder_selected_title"] ?: "No scripts folder selected",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center,
                                        color = Color.White
                                    )
                                    Text(
                                        text = translation["select_scripts_folder_toast"] ?: "Please select a folder to store scripts.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        color = PurrfectPalette.textSecondary,
                                        lineHeight = 18.sp
                                    )
                                    SelectFolderButton(
                                        onClick = {
                                            activityLauncherHelper.chooseFolder {
                                                context.config.root.scripting.moduleFolder.set(it)
                                                context.config.writeConfig()
                                                coroutineScope.launch { reloadDispatcher.dispatch() }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else if (scriptModules.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AestheticEmptyState(
                                icon = Icons.Default.DataObject,
                                title = translation["no_scripts_found_title"] ?: "No scripts found",
                                subtitle = translation["use_catalog_to_add_scripts"] ?: "Check the catalog to find and install scripts.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp)
                            )
                        }
                    }
                }
                items(scriptModules.size, key = { scriptModules[it].hashCode() }) { index ->
                    ModuleItem(scriptModules[index])
                }
            }
            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }
    }

    @Composable
    private fun CatalogTabContent(
        scriptingFolder: DocumentFile?,
        controlsHeight: androidx.compose.ui.unit.Dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (scriptingFolder == null) {
                AestheticEmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = translation["no_scripts_folder_selected_title"] ?: "No scripts folder selected",
                    subtitle = translation["select_scripts_folder_toast"] ?: "Please select a folder to store scripts.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                )
            } else {
                ScriptCatalog(this@ScriptingRootSection)
            }
        }
    }

    @Composable
    private fun ScriptingHeader(
        titles: List<String>,
        selectedTab: Int,
        onTabSelected: (Int) -> Unit,
        onImport: () -> Unit,
        onOpenFolder: () -> Unit,
        onManageRepos: () -> Unit,
        onDocs: () -> Unit,
        folderSelected: Boolean,
        onPositioned: (androidx.compose.ui.unit.Dp) -> Unit = {}
    ) {
        val scrollOffset = routes.navigation?.globalScrollOffset ?: 0
        val shrinkThreshold = 300f
        val focusFactor = (scrollOffset / shrinkThreshold).coerceIn(0f, 1f)
        val tabSwitcherAlpha = (1f - (focusFactor * 2.5f)).coerceIn(0f, 1f)
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        Column(modifier = Modifier.headerHeightTracker(onPositioned), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar(
                title = translation["manager.routes.scripts"] ?: "Scripts",
                subtitle = if (selectedTab == 0) translation["installed_scripts_tab"] else translation["catalog_tab"],
                scrollOffset = scrollOffset,
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onDocs()
                    }) {
                        Icon(Icons.Default.CollectionsBookmark, contentDescription = translation["documentation_button"], tint = Color.White)
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onManageRepos()
                    }) {
                        Icon(Icons.Default.Public, contentDescription = translation["manage_repos_button"], tint = Color.White)
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onOpenFolder()
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = translation["open_scripts_folder_button"], tint = Color.White)
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onImport()
                    }, enabled = folderSelected) {
                        Icon(Icons.Default.Link, contentDescription = translation["import_from_url_button"], tint = if (folderSelected) Color.White else Color.White.copy(alpha = 0.4f))
                    }
                }
            )

            if (tabSwitcherAlpha > 0.05f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .graphicsLayer {
                            alpha = tabSwitcherAlpha
                            translationY = (-10 * focusFactor).dp.toPx()
                        }
                ) {
                    ScriptingTabSwitcher(
                        titles = titles,
                        selectedTab = selectedTab,
                        onTabSelected = { index ->
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onTabSelected(index)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ScriptingTabSwitcher(
        titles: List<String>,
        selectedTab: Int,
        onTabSelected: (Int) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            titles.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f),
                    border = if (isSelected) BorderStroke(
                        1.dp,
                        Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary))
                    ) else BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onTabSelected(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (index == 0) Icons.Default.Extension else Icons.Default.Public,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable RowScope.() -> Unit = {}
}
