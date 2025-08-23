package me.rhunk.snapenhance.ui.manager.pages.scripting

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

class ManageScriptReposSection : Routes.Route() {
    private val updateDispatcher = AsyncUpdateDispatcher()
    private val okHttpClient by lazy { OkHttpClient() }

    override val floatingActionButton: @Composable () -> Unit = {
        var showAddDialog by remember { mutableStateOf(false) }
        ExtendedFloatingActionButton(onClick = { showAddDialog = true }) {
            Text("Add Repository")
        }

        if (showAddDialog) {
            val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

            suspend fun addRepo(url: String) {
                var modifiedUrl = url
                if (url.startsWith("https://github.com/")) {
                    val splitUrl = modifiedUrl.removePrefix("https://github.com/").split("/")
                    val repoName = splitUrl[0] + "/" + splitUrl[1]
                    // Fetch the default branch from GitHub API
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
                context.database.addRepo(modifiedUrl)
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
                            coroutineScope.launch {
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
    }

    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val coroutineScope = rememberCoroutineScope()
        val repositories = rememberAsyncMutableStateList(defaultValue = listOf<String>(), updateDispatcher = updateDispatcher) {
            context.database.getRepositories()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
        ) {
            item {
                if (repositories.isEmpty()) {
                    Text(
                        "No repositories added",
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center
                    )
                }
            }
            items(repositories) { url ->
                ElevatedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null)
                        Text(text = url, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                context.database.removeRepo(url)
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
