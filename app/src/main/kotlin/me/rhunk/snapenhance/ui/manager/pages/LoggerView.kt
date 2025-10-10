package me.rhunk.snapenhance.ui.manager.pages

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.bridge.DownloadCallback
import me.rhunk.snapenhance.common.bridge.wrapper.ConversationInfo
import me.rhunk.snapenhance.common.bridge.wrapper.LoggedMessage
import me.rhunk.snapenhance.common.bridge.wrapper.LoggerWrapper
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.download.DownloadMetadata
import me.rhunk.snapenhance.common.data.download.DownloadRequest
import me.rhunk.snapenhance.common.data.download.MediaDownloadSource
import me.rhunk.snapenhance.common.data.download.createNewFilePath
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.ui.transparentTextFieldColors
import me.rhunk.snapenhance.common.util.ktx.copyToClipboard
import me.rhunk.snapenhance.common.util.ktx.longHashCode
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.features.impl.downloader.decoder.DecodedAttachment
import me.rhunk.snapenhance.core.features.impl.downloader.decoder.MessageDecoder
import me.rhunk.snapenhance.download.DownloadProcessor
import me.rhunk.snapenhance.storage.findFriend
import me.rhunk.snapenhance.ui.manager.Routes
import java.text.DateFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue


abstract class LoggerView<T> : Routes.Route() {
    protected var selectedScopeId by mutableStateOf<String?>(null)
    protected var stringFilter by mutableStateOf("")
    protected var reverseOrder by mutableStateOf(true)

    override val title: @Composable () -> Unit = { Text(text = "Logger") }
    abstract suspend fun getScopes(): List<Pair<String, String>>
    abstract suspend fun fetchLogs(scopeId: String, lastItem: T?, reverseOrder: Boolean, filter: String): List<T>

    @Composable
    abstract fun LogItemView(item: T)

    @Composable
    open fun ScopeDropdownItem(id: String, name: String, isSelected: Boolean) {
        Text(
            text = name,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        Column {
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                val scopes by rememberAsyncMutableState(defaultValue = emptyList()) {
                    getScopes()
                }

                OutlinedTextField(
                    value = scopes.firstOrNull { it.first == selectedScopeId }?.second ?: "Select a scope",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    scopes.forEach { (id, name) ->
                        DropdownMenuItem(onClick = {
                            selectedScopeId = id
                            expanded = false
                        }, text = {
                            ScopeDropdownItem(id = id, name = name, isSelected = id == selectedScopeId)
                        })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(translation["reverse_order_checkbox"])
                    Checkbox(checked = reverseOrder, onCheckedChange = {
                        reverseOrder = it
                    })
                }
            }

            var hasReachedEnd by remember(selectedScopeId, stringFilter, reverseOrder) { mutableStateOf(false) }
            val logs = remember(selectedScopeId, stringFilter, reverseOrder) { mutableStateListOf<T>() }

            LazyColumn(
                contentPadding = PaddingValues(bottom = routes.bottomPadding)
            ) {
                items(logs) { log ->
                    LogItemView(item = log)
                }
                item {
                    if (selectedScopeId != null) {
                        if (hasReachedEnd) {
                            Text(translation["no_more_messages"], modifier = Modifier
                                .padding(8.dp)
                                .fillMaxWidth(), textAlign = TextAlign.Center)
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .height(20.dp)
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                    LaunchedEffect(Unit, selectedScopeId, stringFilter, reverseOrder) {
                        withContext(Dispatchers.IO) {
                            val newLogs = fetchLogs(
                                selectedScopeId ?: return@withContext,
                                logs.lastOrNull(),
                                reverseOrder,
                                stringFilter
                            )
                            if (newLogs.isEmpty()) {
                                hasReachedEnd = true
                                return@withContext
                            }
                            withContext(Dispatchers.Main) {
                                logs.addAll(newLogs)
                            }
                        }
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        val focusRequester = remember { FocusRequester() }
        var showSearchTextField by remember { mutableStateOf(false) }

        if (showSearchTextField) {
            var searchValue by remember { mutableStateOf("") }

            TextField(
                value = searchValue,
                onValueChange = { keyword ->
                    searchValue = keyword
                    stringFilter = keyword
                },
                keyboardActions = KeyboardActions(onDone = { focusRequester.freeFocus() }),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .weight(1f, fill = true)
                    .padding(end = 10.dp)
                    .height(70.dp),
                singleLine = true,
                colors = transparentTextFieldColors()
            )

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }

        IconButton(onClick = {
            showSearchTextField = !showSearchTextField
            stringFilter = ""
        }) {
            Icon(
                imageVector = if (showSearchTextField) Icons.Filled.Close
                else Icons.Filled.Search,
                contentDescription = null
            )
        }
    }
}