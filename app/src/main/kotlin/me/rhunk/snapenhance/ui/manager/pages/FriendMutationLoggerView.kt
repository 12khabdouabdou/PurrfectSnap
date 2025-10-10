package me.rhunk.snapenhance.ui.manager.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.bridge.wrapper.FriendMutationLog
import me.rhunk.snapenhance.common.bridge.wrapper.FriendMutationLoggerWrapper
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import java.text.DateFormat

class FriendMutationLoggerView : LoggerView<FriendMutationLog>() {
    private lateinit var loggerWrapper: FriendMutationLoggerWrapper
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    override val title: @Composable () -> Unit = { Text("Friend Mutation Logger") }

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    override val topBarActions: @Composable RowScope.() -> Unit = {
        var showDropDown by remember { mutableStateOf(false) }
        IconButton(onClick = {
            showDropDown = true
        }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = showDropDown,
            onDismissRequest = { showDropDown = false }
        ) {
            DropdownMenuItem(onClick = {
                activityLauncherHelper.saveFile("friend_mutation_logs.json", "application/json") { uri ->
                    context.coroutineScope.launch {
                        val logs = loggerWrapper.getAllLogs()
                        val json = context.gson.toJson(logs)
                        context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use {
                            it.write(json.toByteArray())
                        }
                        context.shortToast("Exported ${logs.size} logs")
                    }
                }
                showDropDown = false
            }, text = {
                Text("Export")
            })
            DropdownMenuItem(onClick = {
                activityLauncherHelper.openFile("application/json") { uri ->
                    context.coroutineScope.launch {
                        val json = context.androidContext.contentResolver.openInputStream(uri.toUri())?.reader()?.readText()
                        val logs = context.gson.fromJson<List<FriendMutationLog>>(json, object : com.google.gson.reflect.TypeToken<List<FriendMutationLog>>() {}.type)
                        logs.forEach {
                            loggerWrapper.logFriendMutation(it.eventType, it.friendName, it.details)
                        }
                        context.shortToast("Imported ${logs.size} logs")
                        navigateReload()
                    }
                }
                showDropDown = false
            }, text = {
                Text("Import")
            })
            DropdownMenuItem(onClick = {
                loggerWrapper.purgeAll()
                context.shortToast("Cleared all logs")
                navigateReload()
                showDropDown = false
            }, text = {
                Text("Clear")
            })
        }
    }

    @Composable
    override fun LogItemView(item: FriendMutationLog) {
        OutlinedCard(
            modifier = Modifier
                .padding(2.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "${item.eventType.replace('_', ' ').uppercase()} - ${item.friendName}",
                    fontWeight = FontWeight.Bold
                )
                Text(text = item.details)
                Text(
                    text = DateFormat.getDateTimeInstance().format(item.timestamp),
                    fontWeight = FontWeight.ExtraLight
                )
            }
        }
    }

    override suspend fun getScopes(): List<Pair<String, String>> {
        selectedScopeId = "friend_mutations"
        return emptyList()
    }

    override suspend fun fetchLogs(
        scopeId: String,
        lastItem: FriendMutationLog?,
        reverseOrder: Boolean,
        filter: String
    ): List<FriendMutationLog> {
        return loggerWrapper.getLogs(lastItem?.timestamp, 30, reverseOrder, filter)
    }

    @Composable
    override fun ScopeDropdownItem(id: String, name: String, isSelected: Boolean) {
        // No scopes for friend mutation logger
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        var initialized by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            loggerWrapper = FriendMutationLoggerWrapper(context.androidContext)
            initialized = true
        }
        if (initialized) {
            super.content.invoke(navBackStackEntry)
        }
    }
}