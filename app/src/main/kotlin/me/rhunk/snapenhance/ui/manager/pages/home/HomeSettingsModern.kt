package me.rhunk.snapenhance.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.action.EnumAction
import me.rhunk.snapenhance.common.bridge.InternalFileHandleType
import me.rhunk.snapenhance.common.ui.ThemeChooserDialog
import me.rhunk.snapenhance.common.ui.ThemeMode
import me.rhunk.snapenhance.common.ui.ThemePreferences
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.setup.Requirements
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.AlertDialogs
import me.rhunk.snapenhance.ui.util.saveFile
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import me.rhunk.snapenhance.task.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class HomeSettingsModern : Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }

    private fun scheduleUpdateCheck() {
        val workManager = WorkManager.getInstance(context.androidContext)
        if (context.config.root.global.updateSettings.autoUpdateCheck.get()) {
            val frequency = context.config.root.global.updateSettings.updateCheckFrequency.get()
            val repeatInterval = when (frequency) {
                "daily" -> 1L
                "weekly" -> 7L
                "monthly" -> 30L
                else -> 1L
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(repeatInterval, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                "snapenhance_update_check",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        } else {
            workManager.cancelUniqueWork("snapenhance_update_check")
        }
    }

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    private fun RowTitleModern(title: String) {
        Text(
            text = title,
            modifier = Modifier.padding(16.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFFE8F1FF)
        )
    }

    @Composable
    private fun PreferenceToggleModern(sharedPreferences: SharedPreferences, key: String, text: String) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, false)) }
        val hapticFeedback = LocalHapticFeedback.current

        Surface(
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 3.dp,
            color = Color(0x22E0F7FA),
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .blur(6.dp)
                .fillMaxWidth()
                .background(Color(0x16FFFFFF))
                .clickable {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    value = !value
                    sharedPreferences.edit { putBoolean(realKey, value) }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = text, fontSize = 16.sp, color = Color(0xFFF0F8FF))
                Switch(
                    checked = value,
                    onCheckedChange = {
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                        value = it
                        sharedPreferences.edit { putBoolean(realKey, it) }
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun RowActionModern(key: String, requireConfirmation: Boolean = false, action: () -> Unit) {
        var confirmationDialog by remember { mutableStateOf(false) }
        fun takeAction() {
            if (requireConfirmation) {
                confirmationDialog = true
            } else {
                action()
            }
        }
        if (requireConfirmation && confirmationDialog) {
            Dialog(onDismissRequest = { confirmationDialog = false }) {
                dialogs.ConfirmDialog(
                    title = context.translation["manager.dialogs.action_confirm.title"],
                    onConfirm = {
                        action()
                        confirmationDialog = false
                    },
                    onDismiss = { confirmationDialog = false }
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(19.dp),
            color = Color(0x13FFFFFF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clickable { takeAction() }
                .blur(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 55.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = context.translation["actions.$key.name"],
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp,
                        color = Color(0xFFE0F7FA)
                    )
                    context.translation.getOrNull("actions.$key.description")
                        ?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Light, color = Color(0xFFB1E2FF), lineHeight = 16.sp) }
                }
                IconButton(onClick = { takeAction() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFF8EDAF7)
                    )
                }
            }
        }
    }

    @Composable
    private fun ShiftedRowModern(
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

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val contextC = LocalContext.current
        val scope = rememberCoroutineScope()
        val themeMode by ThemePreferences.getThemeModeFlow(contextC).collectAsState(initial = ThemeMode.SYSTEM)
        var showThemeDialog by remember { mutableStateOf(false) }
        var modernUiEnabled by remember { mutableStateOf(true) } // UI Toggle

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(Brush.verticalGradient(colors = listOf(Color(0xFF172442), Color(0xFF5B5B85))))
        ) {
            // Modern UI toggle switcher
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(22.dp, 16.dp, 22.dp, 10.dp)
                    .background(Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                    .blur(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Modern UI", fontSize = 18.sp, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = modernUiEnabled,
                    onCheckedChange = { modernUiEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF51F3E4))
                )
            }

            // THEME
            Spacer(Modifier.height(18.dp))
            RowTitleModern(title = "App Theme")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clickable { showThemeDialog = true },
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Brightness4,
                        contentDescription = "Theme",
                        tint = Color(0xFF51F3E4),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App Theme", fontWeight = FontWeight.Normal, fontSize = 16.sp, color = Color(0xFFE8F1FF))
                        Text(themeMode.displayName, color = Color(0xFF51F3E4), fontSize = 14.sp)
                    }
                }
            }
            if (showThemeDialog) {
                ThemeChooserDialog(
                    selected = themeMode,
                    onSelect = { mode -> scope.launch { ThemePreferences.setThemeMode(contextC, mode) } },
                    onDismiss = { showThemeDialog = false }
                )
            }

            Spacer(Modifier.height(14.dp))
            RowTitleModern(title = context.translation["actions_title"])
            EnumAction.entries.forEach { enumAction ->
                RowActionModern(key = enumAction.key) { context.launchActionIntent(enumAction) }
            }
            RowActionModern(key = "regen_mappings") { context.checkForRequirements(Requirements.MAPPINGS) }
            RowActionModern(key = "change_language") { context.checkForRequirements(Requirements.LANGUAGE) }

            RowTitleModern(title = "UI Settings")
            ShiftedRowModern {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Haptic Feedback", color = Color(0xFFEDEDED))
                    var hapticFeedbackEnabled by remember { mutableStateOf(context.config.root.global.uiSettings.hapticFeedback.getNullable() ?: true) }
                    val hapticFeedback = LocalHapticFeedback.current
                    Switch(
                        checked = hapticFeedbackEnabled,
                        onCheckedChange = {
                            if (it) {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            hapticFeedbackEnabled = it
                            context.config.root.global.uiSettings.hapticFeedback.set(it)
                            context.config.writeConfig()
                        },
                        modifier = Modifier.padding(end = 26.dp)
                    )
                }
            }

            RowTitleModern(title = context.translation["updates_title"])
            ShiftedRowModern {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var autoUpdateCheck by remember { mutableStateOf(context.config.root.global.updateSettings.autoUpdateCheck.getNullable() ?: true) }
                    var selectedFrequency by remember { mutableStateOf(context.config.root.global.updateSettings.updateCheckFrequency.getNullable() ?: "weekly") }
                    var frequencyMenuExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 55.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = context.translation["auto_update_check"], color = Color.White)
                            if (autoUpdateCheck) {
                                Text(
                                    text = context.translation["update_check_frequency_" + selectedFrequency],
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Light,
                                    color = Color(0xFFB1E2FF)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(
                                    onClick = { frequencyMenuExpanded = true },
                                    enabled = autoUpdateCheck,
                                    modifier = Modifier.alpha(if (autoUpdateCheck) 1f else 0f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = context.translation["update_check_frequency"]
                                    )
                                }
                                if (autoUpdateCheck) {
                                    DropdownMenu(
                                        expanded = frequencyMenuExpanded,
                                        onDismissRequest = { frequencyMenuExpanded = false }
                                    ) {
                                        val frequencies = remember { listOf("daily", "weekly", "monthly") }
                                        frequencies.forEach { frequency ->
                                            DropdownMenuItem(
                                                text = { Text(text = context.translation["update_check_frequency_" + frequency]) },
                                                onClick = {
                                                    selectedFrequency = frequency
                                                    context.config.root.global.updateSettings.updateCheckFrequency.set(frequency)
                                                    context.config.writeConfig()
                                                    scheduleUpdateCheck()
                                                    frequencyMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            val hapticFeedback = LocalHapticFeedback.current
                            Switch(
                                checked = autoUpdateCheck,
                                onCheckedChange = {
                                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    autoUpdateCheck = it
                                    context.config.root.global.updateSettings.autoUpdateCheck.set(it)
                                    if (it && context.config.root.global.updateSettings.updateCheckFrequency.getNullable() == null) {
                                        context.config.root.global.updateSettings.updateCheckFrequency.set("weekly")
                                    }
                                    context.config.writeConfig()
                                    scheduleUpdateCheck()
                                },
                                modifier = Modifier.padding(end = 26.dp)
                            )
                        }
                    }
                }
            }

            RowTitleModern(title = context.translation["message_logger_title"])
            ShiftedRowModern {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var storedMessagesCount by rememberAsyncMutableState(defaultValue = 0) {
                        context.messageLogger.getStoredMessageCount()
                    }
                    var storedStoriesCount by rememberAsyncMutableState(defaultValue = 0) {
                        context.messageLogger.getStoredStoriesCount()
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(5.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                context.translation.format("message_logger_summary",
                                    "messageCount" to storedMessagesCount.toString(),
                                    "storyCount" to storedStoriesCount.toString()
                                ), maxLines = 2, color = Color(0xFFF2F6FC)
                            )
                        }
                        Button(onClick = {
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
                                context.longToast("Failed to export database! ${it.localizedMessage}")
                            }
                        }) {
                            Text(text = context.translation["export_button"])
                        }
                        Button(onClick = {
                            runCatching {
                                context.messageLogger.purgeAll()
                                storedMessagesCount = 0
                                storedStoriesCount = 0
                            }.onFailure {
                                context.log.error("Failed to clear messages", it)
                                context.longToast("Failed to clear messages! ${it.localizedMessage}")
                            }.onSuccess {
                                context.shortToast(context.translation["success_toast"])
                            }
                        }) {
                            Text(text = context.translation["clear_button"])
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().padding(5.dp),
                        onClick = { routes.loggerHistory.navigate() }
                    ) {
                        Text(context.translation["view_logger_history_button"])
                    }
                }
            }

            RowTitleModern(title = context.translation["debug_title"])
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                Box(
                    modifier = Modifier.weight(1f).padding(start = 26.dp)
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        TextField(
                            value = selectedFileType.fileName,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            InternalFileHandleType.entries.forEach { fileType ->
                                DropdownMenuItem(onClick = {
                                    expanded = false
                                    selectedFileType = fileType
                                }, text = {
                                    Text(text = fileType.fileName)
                                })
                            }
                        }
                    }
                }
                Button(onClick = {
                    runCatching {
                        context.coroutineScope.launch {
                            selectedFileType.resolve(context.androidContext).delete()
                        }
                    }.onFailure {
                        context.log.error("Failed to clear file", it)
                        context.longToast("Failed to clear file! ${it.localizedMessage}")
                    }.onSuccess {
                        context.shortToast(context.translation["success_toast"])
                    }
                }) {
                    Text(context.translation["clear_button"])
                }
            }
            ShiftedRowModern {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PreferenceToggleModern(context.sharedPreferences, key = "test_mode", text = "Test Mode (FOR DEBUGGING ONLY)")
                    PreferenceToggleModern(context.sharedPreferences, key = "disable_feature_loading", text = "Disable Feature Loading")
                    PreferenceToggleModern(context.sharedPreferences, key = "disable_mapper", text = "Disable Auto Mapper")
                }
            }
            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
