package me.rhunk.snapenhance.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    private fun SectionHeader(title: String) {
        Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp, start = 10.dp)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Divider(
                Modifier
                    .fillMaxWidth(0.13f)
                    .padding(top = 3.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }

    @Composable
    private fun RowTitle(title: String) {
        Text(
            text = title,
            modifier = Modifier.padding(vertical = 13.dp, horizontal = 18.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        )
    }

    @Composable
    private fun PreferenceToggle(
        sharedPreferences: SharedPreferences,
        key: String,
        text: String,
        highlight: Boolean = false
    ) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, false)) }
        val toggleBg =
            if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = toggleBg,
                    shape = RoundedCornerShape(if (highlight) 18.dp else 12.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .heightIn(min = 58.dp)
                .clickable {
                    value = !value
                    sharedPreferences.edit { putBoolean(realKey, value) }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp, end = 16.dp)
            )
            Switch(
                checked = value,
                onCheckedChange = {
                    value = it
                    sharedPreferences.edit().putBoolean(realKey, it).apply()
                },
                modifier = Modifier.padding(end = 18.dp)
            )
        }
    }

    @Composable
    private fun RowAction(
        key: String,
        action: () -> Unit,
        requireConfirmation: Boolean = false,
        accent: Boolean = false
    ) {
        var confirmationDialog by remember { mutableStateOf(false) }
        fun takeAction() {
            if (requireConfirmation) confirmationDialog = true else action()
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
        val rowColor = if (accent) MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f) else Color.Transparent
        ShiftedRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor, RoundedCornerShape(14.dp))
                .heightIn(min = 56.dp)
                .clickable { takeAction() }
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = context.translation["actions.$key.name"],
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.92f)
                )
                context.translation.getOrNull("actions.$key.description")?.let {
                    Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp)
                }
            }
            IconButton(
                onClick = { takeAction() },
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(23.dp)
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
            modifier = modifier.padding(start = 24.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment
        ) { content(this) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val contextC = LocalContext.current
        val scope = rememberCoroutineScope()
        val themeMode by ThemePreferences.getThemeModeFlow(contextC)
            .collectAsState(initial = ThemeMode.SYSTEM)
        var showThemeDialog by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
        ) {
            // THEME SELECTION CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, start = 12.dp, end = 12.dp)
                    .clickable { showThemeDialog = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 7.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Brightness4,
                            contentDescription = "Theme",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(19.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App Theme", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                        Text(
                            themeMode.displayName,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
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

            // --- BEAUTIFIED UPDATES SECTION ---
            SectionHeader("Updates")
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp)),
                elevation = CardDefaults.cardElevation(6.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(vertical = 18.dp, horizontal = 14.dp)) {
                    val updateManager = context.config.root.global.updateManager
                    val property = updateManager.getPropertyPair("update_check_interval")
                    val intervalOptions = property.value.defaultValues?.map { it.toString() }
                        ?: listOf("24")
                    val safeDefaultInterval = intervalOptions.firstOrNull() ?: "24"

                    val automaticUpdateCheck = remember {
                        mutableStateOf(
                            try {
                                updateManager.automaticUpdateCheck.get()
                            } catch (e: IllegalStateException) {
                                false
                            }
                        )
                    }
                    val updateCheckInterval = remember {
                        mutableStateOf(
                            try {
                                updateManager.updateCheckInterval.get()
                            } catch (e: IllegalStateException) {
                                safeDefaultInterval
                            }
                        )
                    }

                    fun enableAutoUpdate(newValue: Boolean) {
                        automaticUpdateCheck.value = newValue
                        updateManager.automaticUpdateCheck.set(newValue)
                        if (newValue) {
                            val wasNotSet = try {
                                updateManager.updateCheckInterval.get(); false
                            } catch (_: IllegalStateException) {
                                true
                            }
                            if (wasNotSet) {
                                updateManager.updateCheckInterval.set(updateCheckInterval.value)
                            }
                        }
                        me.rhunk.snapenhance.task.UpdateScheduler.schedule(context.androidContext, updateManager)
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.19f))
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .fillMaxWidth()
                            .heightIn(min = 55.dp)
                            .clickable {
                                val newValue = !automaticUpdateCheck.value; enableAutoUpdate(newValue)
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Automatic update check",
                                modifier = Modifier.padding(end = 16.dp),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            if (automaticUpdateCheck.value) {
                                Text(
                                    "ENABLED",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Switch(
                            checked = automaticUpdateCheck.value,
                            onCheckedChange = { newValue -> enableAutoUpdate(newValue) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Update interval",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 5.dp, start = 3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded && automaticUpdateCheck.value },
                        modifier = Modifier
                            .background(
                                if (automaticUpdateCheck.value) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        TextField(
                            value = updateCheckInterval.value,
                            onValueChange = {},
                            readOnly = true,
                            enabled = automaticUpdateCheck.value,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .menuAnchor()
                                .background(Color.Transparent),
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
                            textStyle = LocalTextStyle.current.copy(
                                color = if (automaticUpdateCheck.value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.55f
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            intervalOptions.forEach { interval ->
                                DropdownMenuItem(
                                    text = { Text(text = interval, fontSize = 16.sp) },
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

            SectionHeader(translation["actions_title"])
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                elevation = CardDefaults.cardElevation(5.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(vertical = 18.dp)) {
                    EnumAction.entries.forEach { enumAction ->
                        RowAction(
                            key = enumAction.key,
                            action = { context.launchActionIntent(enumAction) },
                            accent = true
                        )
                    }
                    RowAction(
                        key = "regen_mappings",
                        action = { context.checkForRequirements(Requirements.MAPPINGS) }
                    )
                    RowAction(
                        key = "change_language",
                        action = { context.checkForRequirements(Requirements.LANGUAGE) }
                    )
                }
            }

            SectionHeader(translation["message_logger_title"])
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                elevation = CardDefaults.cardElevation(5.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                ShiftedRow {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 13.dp, horizontal = 7.dp)
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
                            modifier = Modifier.fillMaxWidth().padding(5.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    translation.format(
                                        "message_logger_summary",
                                        "messageCount" to storedMessagesCount.toString(),
                                        "storyCount" to storedStoriesCount.toString()
                                    ),
                                    maxLines = 2
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
                            }) { Text(text = translation["export_button"]) }
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
                            }) { Text(text = translation["clear_button"]) }
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().padding(5.dp),
                            onClick = { routes.loggerHistory.navigate() }
                        ) { Text(translation["view_logger_history_button"]) }
                    }
                }
            }

            SectionHeader(translation["debug_title"])
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(vertical = 13.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                modifier = Modifier.fillMaxWidth(0.9f)
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
                        Button(
                            onClick = {
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
                            },
                            modifier = Modifier.padding(start = 10.dp)
                        ) { Text(translation["clear_button"]) }
                    }
                    PreferenceToggle(context.sharedPreferences, key = "test_mode", text = "Test Mode (FOR DEBUGGING ONLY)", highlight = true)
                    Spacer(Modifier.height(6.dp))
                    PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = "Disable Feature Loading")
                    Spacer(Modifier.height(6.dp))
                    PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = "Disable Auto Mapper")
                }
            }
            Spacer(modifier = Modifier.height(58.dp))
        }
    }
}
