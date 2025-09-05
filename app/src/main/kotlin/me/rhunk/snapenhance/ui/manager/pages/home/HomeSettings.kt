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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

class HomeSettings : Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    value = !value
                    sharedPreferences
                        .edit() {
                            putBoolean(realKey, value)
                        }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, modifier = Modifier.padding(end = 16.dp), fontSize = 14.sp)
            Switch(checked = value, onCheckedChange = {
                value = it
                sharedPreferences.edit().putBoolean(realKey, it).apply()
            }, modifier = Modifier.padding(end = 26.dp))
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
                    contentDescription = null,
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

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val contextC = LocalContext.current
        val scope = rememberCoroutineScope()
        val themeMode by ThemePreferences.getThemeModeFlow(contextC).collectAsState(initial = ThemeMode.SYSTEM)
        var showThemeDialog by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // APP THEME (Popup)
            Spacer(Modifier.height(20.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { showThemeDialog = true },
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
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
                        scope.launch {
                            ThemePreferences.setThemeMode(contextC, mode)
                        }
                    },
                    onDismiss = { showThemeDialog = false }
                )
            }
            Spacer(Modifier.height(20.dp))

            // ------ BEAUTIFIED UPDATES CARD ------
            RowTitle(title = "Updates")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    Modifier
                        .padding(vertical = 16.dp, horizontal = 16.dp)
                ) {
                    val updateManager = context.config.root.global.updateManager
                    val property = updateManager.getPropertyPair("update_check_interval")
                    val intervalOptions = property.value.defaultValues?.map { it.toString() }
                        ?: listOf("24")
                    val safeDefaultInterval = intervalOptions.firstOrNull() ?: "24"

                    val automaticUpdateCheck = remember {
                        mutableStateOf(
                            try { updateManager.automaticUpdateCheck.get() } catch (e: IllegalStateException) { false }
                        )
                    }
                    val updateCheckInterval = remember {
                        mutableStateOf(
                            try { updateManager.updateCheckInterval.get() } catch (e: IllegalStateException) { safeDefaultInterval }
                        )
                    }

                    fun enableAutoUpdate(newValue: Boolean) {
                        automaticUpdateCheck.value = newValue
                        updateManager.automaticUpdateCheck.set(newValue)
                        if (newValue) {
                            val wasNotSet = try { updateManager.updateCheckInterval.get(); false } catch (_: IllegalStateException) { true }
                            if (wasNotSet) {
                                updateManager.updateCheckInterval.set(updateCheckInterval.value)
                            }
                        }
                        me.rhunk.snapenhance.task.UpdateScheduler.schedule(context.androidContext, updateManager)
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.33f))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { val newValue = !automaticUpdateCheck.value; enableAutoUpdate(newValue) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Automatic update check",
                            modifier = Modifier.padding(end = 12.dp),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Switch(
                            checked = automaticUpdateCheck.value,
                            onCheckedChange = { newValue ->
                                enableAutoUpdate(newValue)
                            },
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(9.dp))
                    Text(
                        "Update interval",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp, start = 3.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded && automaticUpdateCheck.value },
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                    ) {
                        TextField(
                            value = updateCheckInterval.value,
                            onValueChange = {},
                            readOnly = true,
                            enabled = automaticUpdateCheck.value,
                            modifier = Modifier.menuAnchor(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            intervalOptions.forEach { interval ->
                                DropdownMenuItem(
                                    text = { Text(text = interval, fontSize = 15.sp) },
                                    onClick = {
                                        updateCheckInterval.value = interval
                                        updateManager.updateCheckInterval.set(interval)
                                        if (automaticUpdateCheck.value) {
                                            me.rhunk.snapenhance.task.UpdateScheduler.schedule(context.androidContext, updateManager)
                                        }
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            // ------------------------------------------------------------

            RowTitle(title = translation["actions_title"])
            EnumAction.entries.forEach { enumAction ->
                RowAction(key = enumAction.key) {
                    context.launchActionIntent(enumAction)
                }
            }
            RowAction(key = "regen_mappings") {
                context.checkForRequirements(Requirements.MAPPINGS)
            }
            RowAction(key = "change_language") {
                context.checkForRequirements(Requirements.LANGUAGE)
            }
            RowTitle(title = translation["message_logger_title"])
            ShiftedRow {
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
                                translation.format("message_logger_summary",
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
                            Text(text = translation["export_button"])
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
                                context.shortToast(translation["success_toast"])
                            }
                        }) {
                            Text(text = translation["clear_button"])
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
                        Text(translation["view_logger_history_button"])
                    }
                }
            }
            RowTitle(title = translation["debug_title"])
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
                        context.shortToast(translation["success_toast"])
                    }
                }) {
                    Text(translation["clear_button"])
                }
            }
            ShiftedRow {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PreferenceToggle(context.sharedPreferences, key = "test_mode", text = "Test Mode (FOR DEBUGGING ONLY)")
                    PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = "Disable Feature Loading")
                    PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = "Disable Auto Mapper")
                }
            }
            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}
