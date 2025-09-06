package me.rhunk.snapenhance.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

const val PREFS_KEY_MODERN_UI = "snapenhance_pref_modern_ui"

class HomeSettings : Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }
    private fun scheduleUpdateCheck() {
        val workManager = WorkManager.getInstance(context.androidContext)
        if (context.config.root.global.updateSettings.autoUpdateCheck.get()) {
            val frequency = context.config.root.global.updateSettings.updateCheckFrequency.get()
            val repeatInterval = when (frequency) {
                "daily" -> 1L; "weekly" -> 7L; "monthly" -> 30L
                else -> 1L
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(repeatInterval, TimeUnit.DAYS)
                .setConstraints(constraints).build()
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
    private fun RowTitle(title: String) {
        Text(text = title, modifier = Modifier.padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    value = !value
                    sharedPreferences.edit { putBoolean(realKey, value) }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, modifier = Modifier.padding(end = 16.dp), fontSize = 14.sp)
            Switch(checked = value, onCheckedChange = {
                if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                value = it
                sharedPreferences.edit { putBoolean(realKey, it) }
            }, modifier = Modifier.padding(end = 26.dp))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val contextC = LocalContext.current
        val scope = rememberCoroutineScope()
        val themeMode by ThemePreferences.getThemeModeFlow(contextC).collectAsState(initial = ThemeMode.SYSTEM)
        var showThemeDialog by remember { mutableStateOf(false) }
        val prefs = context.sharedPreferences
        var modernUiEnabled by remember {
            mutableStateOf(prefs.getBoolean(PREFS_KEY_MODERN_UI, true))
        }

        fun setModernUi(enabled: Boolean) {
            modernUiEnabled = enabled
            prefs.edit { putBoolean(PREFS_KEY_MODERN_UI, enabled) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 16.dp)
                    .clickable { setModernUi(!modernUiEnabled) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Modern UI", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = modernUiEnabled,
                        onCheckedChange = { setModernUi(it) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { showThemeDialog = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Brightness4, contentDescription = "Theme", modifier = Modifier.size(26.dp))
                    Spacer(modifier = Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App Theme", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(themeMode.displayName, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    }
                }
            }
            if (showThemeDialog) {
                ThemeChooserDialog(
                    selected = themeMode,
                    onSelect = { mode ->
                        scope.launch { ThemePreferences.setThemeMode(contextC, mode) }
                    },
                    onDismiss = { showThemeDialog = false }
                )
            }
            Spacer(Modifier.height(20.dp))
            RowTitle(title = context.translation["actions_title"])
            EnumAction.entries.forEach { enumAction ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 55.dp)
                        .clickable { context.launchActionIntent(enumAction) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = context.translation["actions.${enumAction.key}.name"], fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                        context.translation.getOrNull("actions.${enumAction.key}.description")?.let {
                            Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp)
                        }
                    }
                    IconButton(onClick = { context.launchActionIntent(enumAction) }, modifier = Modifier.padding(end = 2.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                }
            }
            RowTitle(title = "UI Settings")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 55.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Haptic Feedback")
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
            RowTitle(title = context.translation["updates_title"])
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
                    Text(text = context.translation["auto_update_check"])
                    if (autoUpdateCheck) {
                        Text(
                            text = context.translation["update_check_frequency_" + selectedFrequency],
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light
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
            RowTitle(title = context.translation["message_logger_title"])
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var storedMessagesCount by rememberAsyncMutableState(defaultValue = 0) {
                    context.messageLogger.getStoredMessageCount()
                }
                var storedStoriesCount by rememberAsyncMutableState(defaultValue = 0) {
                    context.messageLogger.getStoredStoriesCount()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            context.translation.format("message_logger_summary",
                                "messageCount" to storedMessagesCount.toString(),
                                "storyCount" to storedStoriesCount.toString()
                            ), maxLines = 2)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    onClick = {
                        routes.loggerHistory.navigate()
                    }
                ) {
                    Text(context.translation["view_logger_history_button"])
                }
            }
            RowTitle(title = context.translation["debug_title"])
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 26.dp)
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
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreferenceToggle(context.sharedPreferences, key = "test_mode", text = "Test Mode (FOR DEBUGGING ONLY)")
                PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = "Disable Feature Loading")
                PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = "Disable Auto Mapper")
            }
            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}
