package me.rhunk.snapenhance.ui.manager.pages.tracker

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.ui.AsyncUpdateDispatcher
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableStateList
import me.rhunk.snapenhance.common.util.ktx.getUrlFromClipboard
import me.rhunk.snapenhance.storage.addRepo
import me.rhunk.snapenhance.storage.getRepositories
import me.rhunk.snapenhance.storage.removeRepo
import me.rhunk.snapenhance.ui.manager.Routes
import okhttp3.OkHttpClient

@Composable
fun ManageFriendTrackerReposSection(context: me.rhunk.snapenhance.RemoteSideContext) {
    val updateDispatcher = remember { AsyncUpdateDispatcher() }
    val okHttpClient by lazy { OkHttpClient() }
    val coroutineScope = rememberCoroutineScope()
    val repositories = rememberAsyncMutableStateList(defaultValue = listOf<String>(), updateDispatcher = updateDispatcher) {
        context.database.getRepositories("friend_tracker")
    }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        val addRepoScope = rememberCoroutineScope { Dispatchers.IO }

        suspend fun addRepo(url: String) {
            var modifiedUrl = url
            if (url.startsWith("https://github.com/")) {
                val splitUrl = modifiedUrl.removePrefix("https://github.com/").split("/")
                val repoName = splitUrl[0] + "/" + splitUrl[1]
                okHttpClient.newCall(
                    okhttp3.Request.Builder().url("https://api.github.com/repos/$repoName").build()
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to fetch default branch: ${response.code}")
                    }
                    val json = response.body?.string() ?: throw Exception("Empty response")
                    val defaultBranch = Regex("\"default_branch\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                        ?: throw Exception("No default_branch field")
                    modifiedUrl = "https://raw.githubusercontent.com/$repoName/$defaultBranch/"
                }
            }
            context.database.addRepo("friend_tracker", modifiedUrl)
            context.shortToast("Repository added successfully!")
            showAddDialog = false
            updateDispatcher.dispatch()
        }

        var url by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Repository URL") },
            text = {
                val focusRequester = remember { FocusRequester() }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { focusRequester.requestFocus() },
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository URL") }
                )
                LaunchedEffect(Unit) {
                    context.androidContext.getUrlFromClipboard()?.let { url = it }
                }
            },
            confirmButton = {
                Button(
                    enabled = !loading && url.isNotBlank(),
                    onClick = {
                        loading = true
                        addRepoScope.launch {
                            runCatching {
                                addRepo(url)
                            }.onFailure {
                                context.log.error("Failed to add repository", it)
                                context.shortToast("Failed to add repository: ${it.message}")
                            }
                            loading = false
                        }
                    }
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text("Add")
                    }
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAddDialog = true }) {
                Text("Add Repository")
            }
        }
    ) { padding ->
        if (repositories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No repositories added",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(repositories) { url ->
                     val (repoName, author) = remember(url) {
                        url.removePrefix("https://raw.githubusercontent.com/").split("/").let { it[1] to it[0] }
                    }

                    ElevatedCard(
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = repoName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = author,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    context.database.removeRepo("friend_tracker", url)
                                    coroutineScope.launch { updateDispatcher.dispatch() }
                                }
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
