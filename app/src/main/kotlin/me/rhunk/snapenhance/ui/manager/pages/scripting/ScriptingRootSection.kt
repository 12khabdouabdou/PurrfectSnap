package me.rhunk.snapenhance.ui.manager.pages.scripting

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.*
import me.rhunk.snapenhance.common.scripting.type.ModuleInfo
import me.rhunk.snapenhance.common.scripting.ui.EnumScriptInterface
import me.rhunk.snapenhance.common.scripting.ui.InterfaceManager
import me.rhunk.snapenhance.common.scripting.ui.ScriptInterface
import me.rhunk.snapenhance.common.ui.AsyncUpdateDispatcher
import me.rhunk.snapenhance.common.ui.TopBarActionButton
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.ui.rememberAsyncUpdateDispatcher
import me.rhunk.snapenhance.common.util.ktx.getUrlFromClipboard
import me.rhunk.snapenhance.common.util.ktx.openLink
import me.rhunk.snapenhance.storage.isScriptEnabled
import me.rhunk.snapenhance.storage.setScriptEnabled
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.Dialog
import me.rhunk.snapenhance.ui.util.chooseFolder
import me.rhunk.snapenhance.ui.util.pullrefresh.PullRefreshIndicator
import me.rhunk.snapenhance.ui.util.pullrefresh.pullRefresh
import me.rhunk.snapenhance.ui.util.pullrefresh.rememberPullRefreshState

class ScriptingRootSection : Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    val reloadDispatcher = AsyncUpdateDispatcher(updateOnFirstComposition = false)

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    private fun ImportRemoteScript(
        dismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = dismiss) {
            var url by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            var isLoading by remember { mutableStateOf(false) }
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Import Script from URL",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp),
                    )
                    Text(
                        text = "Warning: Imported scripts can be harmful to your device. Only import scripts from trusted sources.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center,
                    )
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(text = "Enter URL here:") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onGloballyPositioned { focusRequester.requestFocus() }
                    )
                    LaunchedEffect(Unit) {
                        context.androidContext.getUrlFromClipboard()?.let { url = it }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        enabled = url.isNotBlank(),
                        onClick = {
                            isLoading = true
                            context.coroutineScope.launch {
                                runCatching {
                                    val moduleInfo = context.scriptManager.importFromUrl(url)
                                    context.shortToast("Script ${moduleInfo.name} imported!")
                                    reloadDispatcher.dispatch()
                                    withContext(Dispatchers.Main) {
                                        dismiss()
                                    }
                                    return@launch
                                }.onFailure {
                                    context.log.error("Failed to import script", it)
                                    context.shortToast("Failed to import script. ${it.message}. Check logs for more details")
                                }
                                isLoading = false
                            }
                        },
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(text = "Import")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FabArea(
        selectedTab: Int,
        showManageRepos: Boolean,
        setShowManageRepos: (Boolean) -> Unit,
        showImportDialog: Boolean,
        setShowImportDialog: (Boolean) -> Unit,
        showToast: Boolean,
        setShowToast: (Boolean) -> Unit,
        scriptingFolder: Any?
    ) {
        if (showImportDialog) {
            ImportRemoteScript { setShowImportDialog(false) }
        }
        if (showToast) {
            LaunchedEffect(Unit) {
                context.shortToast("Please select your scripts folder!")
                setShowToast(false)
            }
        }
        if (selectedTab == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = { setShowManageRepos(true) },
                    icon = { Icon(Icons.Default.Public, contentDescription = null) },
                    text = { Text("Manage Repos") }
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (scriptingFolder == null) setShowToast(true) else setShowImportDialog(true)
                    },
                    icon = { Icon(imageVector = Icons.Default.Link, contentDescription = "Link") },
                    text = { Text(text = "Import from URL") }
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        if (scriptingFolder == null) setShowToast(true)
                        else scriptingFolder.let { context.androidContext.openLink(it.uri.toString()) }
                    },
                    icon = { Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Folder") },
                    text = { Text(text = "Open Scripts Folder") }
                )
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

        val dispatcher = rememberAsyncUpdateDispatcher()
        val reloadCallback = remember { suspend { dispatcher.dispatch() } }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null, updateDispatcher = dispatcher, keys = arrayOf(script)) {
            context.scriptManager.checkForUpdate(script)
        }

        LaunchedEffect(Unit) {
            reloadDispatcher.addCallback(reloadCallback)
        }

        DisposableEffect(Unit) {
            onDispose {
                reloadDispatcher.removeCallback(reloadCallback)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!enabled) return@clickable
                        openSettings = !openSettings
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enabled) {
                    Icon(
                        imageVector = if (openSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(32.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(text = script.displayName ?: script.name, fontSize = 20.sp)
                    Text(text = script.description ?: "No description", fontSize = 14.sp)
                    latestUpdate?.let {
                        Text(text = "Update available: ${it.version}", fontSize = 14.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { openActions = !openActions }) {
                    Icon(imageVector = Icons.Default.Build, contentDescription = "Actions")
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { isChecked ->
                        openSettings = false
                        context.coroutineScope.launch(Dispatchers.IO) {
                            runCatching {
                                val modulePath = context.scriptManager.getModulePath(script.name)!!
                                context.scriptManager.unloadScript(modulePath)
                                if (isChecked) {
                                    context.scriptManager.loadScript(modulePath)
                                    context.scriptManager.runtime.getModuleByName(script.name)
                                        ?.callFunction("module.onSnapEnhanceLoad")
                                    context.shortToast("Loaded script ${script.name}")
                                } else {
                                    context.shortToast("Unloaded script ${script.name}")
                                }
                                context.database.setScriptEnabled(script.name, isChecked)
                                withContext(Dispatchers.Main) { enabled = isChecked }
                            }.onFailure { throwable ->
                                withContext(Dispatchers.Main) { enabled = !isChecked }
                                ("Failed to ${if (isChecked) "enable" else "disable"} script. Check logs for more details").also {
                                    context.log.error(it, throwable)
                                    context.shortToast(it)
                                }
                            }
                        }
                    }
                )
            }
            if (openSettings) {
                ScriptSettings(script)
            }
        }
        if (openActions) {
            @Suppress("UNCHECKED_CAST")
            ModuleActions(
                script = script,
                canUpdate = latestUpdate != null,
            ) { openActions = false }
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val reloadDispatcher = remember { this@ScriptingRootSection.reloadDispatcher }
        val scriptingFolder by rememberAsyncMutableState(
            defaultValue = null,
            updateDispatcher = reloadDispatcher
        ) { context.scriptManager.getScriptsFolder() }
        val tabTitles = listOf("Installed Scripts", "Catalog")

        // --- SHARED STATE HOISTED FOR UI ---
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var showManageRepos by rememberSaveable { mutableStateOf(false) }
        var showImportDialog by rememberSaveable { mutableStateOf(false) }
        var showToast by rememberSaveable { mutableStateOf(false) }
        // ------------------------------------

        // --- Full page overlay for Manage Repos ---
        if (showManageRepos) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.99f)),
                color = Color.Transparent
            ) {
                Box(Modifier.fillMaxSize()) {
                    ManageReposSection()
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        onClick = { showManageRepos = false }
                    ) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            }
        }
        // ----------------------------------------

        Box(Modifier.fillMaxSize()) {
            FabArea(
                selectedTab = selectedTab,
                showManageRepos = showManageRepos,
                setShowManageRepos = { showManageRepos = it },
                showImportDialog = showImportDialog,
                setShowImportDialog = { showImportDialog = it },
                showToast = showToast,
                setShowToast = { showToast = it },
                scriptingFolder = scriptingFolder
            )

            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { i, text ->
                        val enabled = !(i == 1 && scriptingFolder == null)
                        Tab(
                            selected = selectedTab == i,
                            onClick = {
                                if (!enabled) {
                                    showToast = true
                                } else {
                                    selectedTab = i
                                    showManageRepos = false
                                }
                            },
                            enabled = enabled,
                            text = { Text(text) }
                        )
                    }
                }
                when (selectedTab) {
                    0 -> {
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
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pullRefresh(pullRefreshState),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                item {
                                    if (scriptingFolder == null && !refreshing) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(320.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "No scripts folder selected",
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(bottom = 16.dp)
                                                )
                                                Button(
                                                    onClick = {
                                                        activityLauncherHelper.chooseFolder {
                                                            context.config.root.scripting.moduleFolder.set(it)
                                                            context.config.writeConfig()
                                                            coroutineScope.launch {
                                                                reloadDispatcher.dispatch()
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                                                ) {
                                                    Text(
                                                        text = "Select folder",
                                                        fontSize = 18.sp
                                                    )
                                                }
                                            }
                                        }
                                    } else if (scriptModules.isEmpty()) {
                                        Text(
                                            text = "No scripts found",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                                items(scriptModules.size, key = { scriptModules[it].hashCode() }) { index ->
                                    ModuleItem(scriptModules[index])
                                }
                                item { Spacer(modifier = Modifier.height(200.dp)) }
                            }

                            PullRefreshIndicator(
                                refreshing = refreshing,
                                state = pullRefreshState,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }

                        var scriptingWarning by remember {
                            mutableStateOf(context.sharedPreferences.run {
                                getBoolean("scripting_warning", true).also {
                                    edit().putBoolean("scripting_warning", false).apply()
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
                            AlertDialog(onDismissRequest = {
                                if (timeout == 0) {
                                    scriptingWarning = false
                                }
                            }, title = {
                                Text(text = context.translation["manager.dialogs.scripting_warning.title"])
                            }, text = {
                                Text(text = context.translation["manager.dialogs.scripting_warning.content"])
                            }, confirmButton = {
                                TextButton(
                                    onClick = { scriptingWarning = false },
                                    enabled = timeout == 0
                                ) {
                                    Text(text = "OK " + if (timeout > 0) "($timeout)" else "")
                                }
                            })
                        }
                    }
                    1 -> { ScriptCatalog(this@ScriptingRootSection) }
                }
            }
        }
    }

    override val topBarActions: @Composable() (RowScope.() -> Unit) = {
        TopBarActionButton(
            onClick = {
                context.androidContext.openLink("https://github.com/SnapEnhance/scripting-docs")
            },
            icon = Icons.Default.CollectionsBookmark,
            text = "Documentation",
        )
    }
}
