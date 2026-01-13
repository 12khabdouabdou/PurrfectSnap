package me.eternal.purrfectsnap.ui.manager.pages.home

import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.action.EnumAction
import me.eternal.purrfectsnap.common.bridge.InternalFileHandleType
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.storage.getAllScopeNotes
import me.eternal.purrfectsnap.storage.setAllScopeNotes
import me.eternal.purrfectsnap.task.UpdateCheckWorker
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.setup.Requirements
import me.eternal.purrfectsnap.ui.util.ActivityLauncherHelper
import me.eternal.purrfectsnap.ui.util.AlertDialogs
import me.eternal.purrfectsnap.ui.util.openFile
import me.eternal.purrfectsnap.ui.util.saveFile
import me.eternal.purrfectsnap.ui.util.purrfectSwitchColors
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HomeSettings : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.home_settings") }
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }

    private fun scheduleUpdateCheck() {
        val workManager = WorkManager.getInstance(context.androidContext)
        val updateSettings = context.config.root.global.updateSettings
        var configDirty = false
        val autoUpdateCheck = updateSettings.autoUpdateCheck.getNullable() ?: run {
            configDirty = true
            updateSettings.autoUpdateCheck.set(true)
            true
        }
        val frequency = updateSettings.updateCheckFrequency.getNullable() ?: run {
            configDirty = true
            updateSettings.updateCheckFrequency.set("daily")
            "daily"
        }
        val updateChannel = updateSettings.updateChannel.getNullable() ?: run {
            configDirty = true
            updateSettings.updateChannel.set("stable")
            "stable"
        }
        if (configDirty) {
            context.config.writeConfig()
        }

        if (autoUpdateCheck) {
            val repeatInterval = when (frequency) {
                "daily" -> 1L
                "weekly" -> 7L
                "monthly" -> 30L
                else -> 1L
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString("channel_name", translation["update_notification_channel_name"])
                .putString("channel_description", translation["update_notification_channel_description"])
                .putString("notification_title", translation["update_notification_title"])
                .putString("notification_text", translation["update_notification_text"])
                .putString("update_channel", updateChannel)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(repeatInterval, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            workManager.enqueueUniquePeriodicWork(
                "purrfectsnap_update_check",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        } else {
            workManager.cancelUniqueWork("purrfectsnap_update_check")
        }
    }
    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }
    @Composable
    private fun RowTitle(title: String) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
    @Composable
    private fun PremiumPreferenceToggle(
        sharedPreferences: SharedPreferences,
        key: String,
        text: String,
        defaultValue: Boolean = false
    ) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, defaultValue)) }
        val hapticFeedback = LocalHapticFeedback.current

        LaunchedEffect(realKey) {
            if (!sharedPreferences.contains(realKey)) {
                sharedPreferences.edit().putBoolean(realKey, defaultValue).apply()
                value = defaultValue
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    value = !value
                    sharedPreferences
                        .edit() {
                            putBoolean(realKey, value)
                        }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, modifier = Modifier.padding(start = 26.dp, end = 16.dp), fontSize = 14.sp)
            Switch(
                checked = value,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 26.dp),
                colors = purrfectSwitchColors()
            )
        }
    }

    @Composable
    private fun PreferenceToggle(sharedPreferences: SharedPreferences, key: String, text: String) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, false)) }
        val hapticFeedback = LocalHapticFeedback.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    value = !value
                    sharedPreferences
                        .edit() {
                            putBoolean(realKey, value)
                        }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, modifier = Modifier.padding(start = 26.dp, end = 16.dp), fontSize = 14.sp)
            Switch(
                checked = value,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 26.dp),
                colors = purrfectSwitchColors()
            )
        }
    }

    @Composable
    private fun RowAction(key: String, requireConfirmation: Boolean = false, action: () -> Unit) {
        var confirmationDialog by remember {
            mutableStateOf(false)
        }
        fun takeAction() {
            if (requireConfirmation) {
                confirmationDialog = true
            } else {
                action()
            }
        }
        if (requireConfirmation && confirmationDialog) {
            Dialog(onDismissRequest = { confirmationDialog = false }) {
                dialogs.ConfirmDialog(title = context.translation["manager.dialogs.action_confirm.title"], onConfirm = {
                    action()
                    confirmationDialog = false
                }, onDismiss = {
                    confirmationDialog = false
                })
            }
        }
        ShiftedRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    takeAction()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(text = context.translation["actions.$key.name"], fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                context.translation.getOrNull("actions.$key.description")?.let { Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp) }
            }
            IconButton(onClick = { takeAction() },
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = context.translation.getOrNull("actions.$key.name"),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
    @Composable
    private fun ShiftedRow(
        modifier: Modifier = Modifier,
        horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
        verticalAlignment: Alignment.Vertical = Alignment.Top,
        content: @Composable RowScope.() -> Unit
    ) {
        Row(
            modifier = modifier.padding(start = 26.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment
        ) { content(this) }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val contextC = LocalContext.current
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val sharedButtonColors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White
        )
        val sharedOutlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)

        @Composable
        fun GlassCard(
            modifier: Modifier = Modifier,
            content: @Composable ColumnScope.() -> Unit
        ) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.04f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }

        @Composable
        fun AestheticDropdownField(
            value: String,
            expanded: Boolean,
            modifier: Modifier = Modifier,
            onClick: () -> Unit
        ) {
            val shape = RoundedCornerShape(16.dp)
            Row(
                modifier = modifier
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                    .clickable { onClick() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = value, color = Color.White)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }

        val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(topPadding))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.07f),
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
                    ),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        Text(
                            text = translation["manager.routes.home_settings"],
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                        IconButton(onClick = { routes.navigation?.openBottomBarCustomization = true }) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    GlassCard {
                        RowTitle(title = translation["actions_title"])
                        EnumAction.entries.forEach { enumAction ->
                            RowAction(key = enumAction.key) { context.launchActionIntent(enumAction) }
                        }
                        RowAction(key = "regen_mappings") { context.checkForRequirements(Requirements.MAPPINGS) }
                        RowAction(key = "change_language") { context.checkForRequirements(Requirements.LANGUAGE) }
                    }

                    GlassCard {
                        RowTitle(title = translation["ui_settings_title"])
                        ShiftedRow {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 55.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = translation["haptic_feedback_label"])
                                    var hapticFeedbackEnabled by remember { mutableStateOf(context.config.root.global.uiSettings.hapticFeedback.getNullable() ?: true) }
                                    val hapticFeedback = LocalHapticFeedback.current
                                    Switch(
                                        checked = hapticFeedbackEnabled,
                                        onCheckedChange = {
                                            if (it) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            hapticFeedbackEnabled = it
                                            context.config.root.global.uiSettings.hapticFeedback.set(it)
                                            context.config.writeConfig()
                                        },
                                        modifier = Modifier.padding(end = 26.dp),
                                        colors = purrfectSwitchColors()
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 55.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = translation["use_system_toasts_label"])
                                    var useSystemToasts by remember { mutableStateOf(context.config.root.global.uiSettings.useSystemToasts.getNullable() ?: false) }
                                    val hapticFeedback = LocalHapticFeedback.current
                                    Switch(
                                        checked = useSystemToasts,
                                        onCheckedChange = {
                                            if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            useSystemToasts = it
                                            context.config.root.global.uiSettings.useSystemToasts.set(it)
                                            context.config.writeConfig()
                                        },
                                        modifier = Modifier.padding(end = 26.dp),
                                        colors = purrfectSwitchColors()
                                    )
                                }
                            }
                        }
                    }

                    GlassCard {
                        RowTitle(title = translation["updates_title"])
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var autoUpdateCheck by remember { mutableStateOf(context.config.root.global.updateSettings.autoUpdateCheck.getNullable() ?: true) }
                            var selectedChannel by remember { mutableStateOf(context.config.root.global.updateSettings.updateChannel.getNullable() ?: "stable") }
                            var channelMenuExpanded by remember { mutableStateOf(false) }
                            ShiftedRow {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 55.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = translation["auto_update_check"])
                                    val hapticFeedback = LocalHapticFeedback.current
                                    Switch(
                                        checked = autoUpdateCheck,
                                        onCheckedChange = {
                                            if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        autoUpdateCheck = it
                                        context.config.root.global.updateSettings.autoUpdateCheck.set(it)
                                        context.config.writeConfig()
                                        scheduleUpdateCheck()
                                    },
                                    modifier = Modifier.padding(end = 26.dp),
                                        colors = purrfectSwitchColors()
                                    )
                                }
                            }
                            AnimatedVisibility(visible = autoUpdateCheck) {
                                val spacingModifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 26.dp)

                                ExposedDropdownMenuBox(
                                    expanded = channelMenuExpanded,
                                    onExpandedChange = { channelMenuExpanded = it },
                                    modifier = spacingModifier
                                ) {
                                AestheticDropdownField(
                                    value = translation.getOrNull("update_channel_${selectedChannel}") ?: selectedChannel,
                                    expanded = channelMenuExpanded,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                    onClick = { channelMenuExpanded = true }
                                )
                                ExposedDropdownMenu(
                                    expanded = channelMenuExpanded,
                                    onDismissRequest = { channelMenuExpanded = false }
                                ) {
                                    listOf("stable", "prerelease").forEach { channel ->
                                        DropdownMenuItem(
                                            text = { Text(text = translation.getOrNull("update_channel_${channel}") ?: channel) },
                                            onClick = {
                                                selectedChannel = channel
                                                channelMenuExpanded = false
                                                context.config.root.global.updateSettings.updateChannel.set(channel)
                                                context.config.writeConfig()
                                                scheduleUpdateCheck()
                                            }
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }

                    GlassCard {
                        RowTitle(title = "Reset PurrfectSnap")
                        ShiftedRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 55.dp)
                                .clickable {
                                    // Clear setup progress and route back to SetupActivity
                                    context.sharedPreferences.edit()
                                        .remove("setup_in_progress")
                                        .remove("setup_current_route")
                                        .remove("setup_skip_patch")
                                        .remove("setup_install_mode")
                                        .apply()

                                    // Clear config to defaults
                                    context.config.reset()
                                    context.config.writeConfig()

                                    // Launch setup activity fresh
                                    val intent = android.content.Intent(context.androidContext, me.eternal.purrfectsnap.ui.setup.SetupActivity::class.java)
                                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    context.androidContext.startActivity(intent)

                                    // Close current manager activity
                                    routes.navController.popBackStack()
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Reset and restart setup",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Reset",
                                modifier = Modifier.padding(end = 14.dp)
                            )
                        }
                    }

                    GlassCard {
                        RowTitle(title = translation["message_logger_title"])
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var storedMessagesCount by rememberAsyncMutableState(defaultValue = 0) {
                                context.messageLogger.getStoredMessageCount()
                            }
                            var storedStoriesCount by rememberAsyncMutableState(defaultValue = 0) {
                                context.messageLogger.getStoredStoriesCount()
                            }
                            var showImportDialog by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(5.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val summary = translation.format(
                                    "message_logger_summary",
                                    "messageCount" to storedMessagesCount.toString(),
                                    "storyCount" to storedStoriesCount.toString()
                                ).replace("\n", " | ")
                                Text(
                                    summary,
                                    maxLines = 2,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.CenterHorizontally),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            runCatching {
                                                activityLauncherHelper.saveFile("message_logger.db", "application/octet-stream") { uri ->
                                                    context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use { outputStream ->
                                                        context.messageLogger.databaseFile.inputStream().use { inputStream ->
                                                            inputStream.copyTo(outputStream)
                                                        }
                                                    }
                                                }
                                            }.onFailure {
                                                context.log.error("Failed to export database", it)
                                                context.longToast(translation.format("export_database_failed_toast", "message" to (it.localizedMessage ?: "")))
                                            }
                                        },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = translation["export_button"])
                                    }
                                    Button(
                                        onClick = {
                                            runCatching {
                                                activityLauncherHelper.openFile("application/octet-stream") { uri ->
                                                    val tempFile = File(context.androidContext.cacheDir, "view_message_logger.db")
                                                    context.androidContext.contentResolver.openInputStream(uri.toUri())?.use { inputStream ->
                                                        FileOutputStream(tempFile).use { outputStream ->
                                                            inputStream.copyTo(outputStream)
                                                        }
                                                    }
                                                    routes.viewLoggerHistory.navigate {
                                                        put("uri", URLEncoder.encode(tempFile.toUri().toString(), "UTF-8"))
                                                    }
                                                }
                                            }.onFailure {
                                                context.log.error("Failed to open file", it)
                                                context.longToast("Failed to open file! ${it.localizedMessage}")
                                            }
                                        },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = translation["view_button"])
                                    }
                                    Button(
                                        onClick = {
                                            runCatching {
                                                context.messageLogger.purgeAll()
                                                storedMessagesCount = 0
                                                storedStoriesCount = 0
                                            }.onFailure {
                                                context.log.error("Failed to clear messages", it)
                                                context.longToast(translation.format("clear_messages_failed_toast", "message" to (it.localizedMessage ?: "")))
                                            }.onSuccess {
                                                context.shortToast(translation["success_toast"])
                                            }
                                        },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = translation["clear_button"])
                                    }
                                    Button(
                                        onClick = { showImportDialog = true },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = "Import")
                                    }
                                }
                            }
                            OutlinedButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(5.dp),
                                onClick = { routes.loggerHistory.navigate() },
                                colors = sharedOutlinedColors,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                            ) {
                                Text(translation["view_logger_history_button"])
                            }
                            if (showImportDialog) {
                                AlertDialog(
                                    onDismissRequest = { showImportDialog = false },
                                    title = { Text("Import message logger") },
                                    text = { Text("Importing will override your current message logger database. Continue?") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showImportDialog = false
                                            runCatching {
                                                activityLauncherHelper.openFile("application/octet-stream") { uri ->
                                                    runCatching {
                                                        context.androidContext.contentResolver.openInputStream(uri.toUri())?.use { inputStream ->
                                                            context.messageLogger.databaseFile.outputStream().use { outputStream ->
                                                                inputStream.copyTo(outputStream)
                                                            }
                                                        } ?: throw IllegalStateException("Unable to open selected file")
                                                        storedMessagesCount = context.messageLogger.getStoredMessageCount()
                                                        storedStoriesCount = context.messageLogger.getStoredStoriesCount()
                                                        context.shortToast(translation["success_toast"])
                                                        context.log.info("Imported message logger from $uri", "MessageLogger")
                                                    }.onFailure {
                                                        context.log.error("Failed to import message logger", it)
                                                        context.longToast("Import failed: ${it.localizedMessage ?: it.message}")
                                                    }
                                                }
                                            }.onFailure {
                                                context.log.error("Failed to launch import picker", it)
                                                context.longToast("Import failed: ${it.localizedMessage ?: it.message}")
                                            }
                                        }) {
                                            Text(translation["button.import"] ?: "Import")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showImportDialog = false }) {
                                            Text(translation["button.cancel"])
                                        }
                                    }
                                )
                            }
                        }
                    }

                    GlassCard {
                        RowTitle(title = translation["friend_notes_title"])
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = translation["friend_notes_description"],
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 5.dp),
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 5.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = {
                                        runCatching {
                                            val notes = context.database.getAllScopeNotes()
                                            if (notes.isEmpty()) {
                                                context.shortToast(translation["friend_notes_no_notes_to_backup"])
                                                return@runCatching
                                            }
                                            val json = context.gson.toJson(notes)
                                            activityLauncherHelper.saveFile("friend_notes_backup.json", "application/json") { uri ->
                                                context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use {
                                                    it.write(json.toByteArray())
                                                }
                                                context.shortToast(translation["friend_notes_backup_success"])
                                            }
                                        }.onFailure {
                                                context.log.error("Failed to backup notes", it)
                                                context.longToast(translation.format("friend_notes_backup_failure", "error" to (it.localizedMessage ?: "")))
                                            }
                                    },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = translation["backup_button"])
                                    }
                                    Button(onClick = {
                                        runCatching {
                                            activityLauncherHelper.openFile("application/json") { uri ->
                                                context.androidContext.contentResolver.openInputStream(uri.toUri())?.use {
                                                    val json = it.reader().readText()
                                                    val notes = context.gson.fromJson<Map<String, String>>(json, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type)
                                                    context.database.setAllScopeNotes(notes)
                                                    context.shortToast(translation["friend_notes_restore_success"])
                                                }
                                            }
                                        }.onFailure {
                                            context.log.error("Failed to restore notes", it)
                                            context.longToast(translation.format("friend_notes_restore_failure", "error" to (it.localizedMessage ?: "")))
                                        }
                                    },
                                        colors = sharedButtonColors,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ) {
                                        Text(text = translation["restore_button"])
                                    }
                                }
                            }
                        }
                    }

                    GlassCard {
                        RowTitle(title = translation["debug_title"])
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)
                            ) {
                                var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                                Box(modifier = Modifier.weight(1f)) {
                                    var expanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        AestheticDropdownField(
                                            value = translation.getOrNull("debug_file_${selectedFileType.name.lowercase()}") ?: selectedFileType.fileName,
                                            expanded = expanded,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                            onClick = { expanded = true }
                                        )
                                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            InternalFileHandleType.entries.forEach { fileType ->
                                                DropdownMenuItem(onClick = {
                                                    expanded = false
                                                    selectedFileType = fileType
                                                }, text = {
                                                    Text(text = translation.getOrNull("debug_file_${fileType.name.lowercase()}") ?: fileType.fileName)
                                                })
                                            }
                                        }
                                    }
                                }
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.coroutineScope.launch {
                                                selectedFileType.resolve(context.androidContext).delete()
                                            }
                                        }.onFailure {
                                            context.log.error("Failed to clear file", it)
                                            context.longToast(translation.format("clear_file_failed_toast", "message" to (it.localizedMessage ?: "")))
                                        }.onSuccess {
                                            context.shortToast(translation["success_toast"])
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(translation["clear_button"])
                                }
                            }
                            ShiftedRow {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    PremiumPreferenceToggle(
                                        context.sharedPreferences,
                                        key = "test_mode",
                                        text = translation["test_mode_label"],
                                        defaultValue = true
                                    )
                                    PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = translation["disable_feature_loading_label"])
                                    PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = translation["disable_auto_mapper_label"])
                                    PreferenceToggle(context.sharedPreferences, key = "disable_bypass_indicator", text = translation["disable_bypass_indicator_label"])
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(routes.bottomPadding + 12.dp))
                }
            }
        }
    }
}
}
