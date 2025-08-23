package me.rhunk.snapenhance.ui.manager.pages.scripting

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.*
import me.rhunk.snapenhance.common.ui.AsyncUpdateDispatcher
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableStateList
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.storage.getRepositories
import okhttp3.Request
import java.io.File

// Data class matching the index.json for scripts. Defined here, not globally.
data class ScriptRepoManifest(
    val scripts: List<ScriptRepoEntry>
)
data class ScriptRepoEntry(
    val name: String,
    val author: String?,
    val description: String?,
    val version: String?,
    val filepath: String
)

private val cachedRepoIndexes = mutableStateMapOf<String, ScriptRepoManifest>()
private val cacheReloadDispatcher = AsyncUpdateDispatcher()

@Composable
fun ScriptCatalog(root: ScriptingRootSection) {
    val context = remember { root.context }
    val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

    // Fetches all added repo indexes
    fun fetchRepoIndexes(): Map<String, ScriptRepoManifest>? {
        val indexes = mutableMapOf<String, ScriptRepoManifest>()
        context.database.getRepositories().forEach { repoUrl->
            val indexUri = repoUrl.toUri().buildUpon().appendPath("index.json").build()
            runCatching {
                root.okHttpClient.newCall(
                    Request.Builder().url(indexUri.toString()).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        context.log.error("Failed to fetch script index from $indexUri: ${response.code}")
                        context.shortToast("Failed to fetch index of $indexUri")
                        return@forEach
                    }
                    runCatching {
                        // Only use the scripts section if present
                        val repoManifest = context.gson.fromJson(response.body?.charStream(), ScriptRepoManifest::class.java)
                        if (repoManifest.scripts != null) {
                            indexes[repoUrl] = repoManifest
                        }
                    }.onFailure {
                        context.log.error("Failed to parse script index from $indexUri", it)
                        context.shortToast("Failed to parse index of $indexUri")
                    }
                }
            }.onFailure {
                context.log.error("Failed to fetch script index from $indexUri", it)
                context.shortToast("Failed to fetch index of $indexUri")
            }
        }
        return indexes
    }
    // Download a script and save it just like "import from url" button does
    suspend fun downloadScript(scriptUri: Uri, scriptName: String) {
        withContext(Dispatchers.IO) {
            root.okHttpClient.newCall(
                Request.Builder().url(scriptUri.toString()).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    context.log.error("Failed to fetch script from $scriptUri: ${response.code}")
                    context.shortToast("Failed to fetch script from $scriptUri")
                    return@withContext
                }
                val content = response.body?.bytes() ?: return@withContext
                val scriptsFolder = context.scriptManager.getScriptsFolder()
                val file = File(scriptsFolder, "$scriptName.js")
                file.writeBytes(content)
                context.shortToast("Script downloaded!")
            }
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    suspend fun refreshCachedIndexes() {
        isRefreshing = true
        coroutineScope {
            launch(Dispatchers.IO) {
                fetchRepoIndexes()?.let {
                    context.log.verbose("Fetched ${it.size} script indexes")
                    synchronized(cachedRepoIndexes) {
                        cachedRepoIndexes.clear()
                        cachedRepoIndexes += it
                    }
                    cacheReloadDispatcher.dispatch()
                    delay(600)
                    isRefreshing = false
                }
            }
        }
    }
    val installedScripts = rememberAsyncMutableStateList(defaultValue = listOf<Any>(), updateDispatcher = root.reloadDispatcher, keys = arrayOf(cachedRepoIndexes)) {
        context.scriptManager.getSyncedModules()
    }
    val remoteScripts by rememberAsyncMutableState(defaultValue = listOf<Pair<String, ScriptRepoEntry>>(), updateDispatcher = cacheReloadDispatcher) {
        cachedRepoIndexes.entries.flatMap { entry ->
            entry.value.scripts.map { entry.key to it }
        }
    }

    LaunchedEffect(Unit) {
        if (cachedRepoIndexes.isNotEmpty()) return@LaunchedEffect
        isRefreshing = true
        coroutineScope.launch { refreshCachedIndexes() }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            item {
                if (remoteScripts.isEmpty()) {
                    Text(
                        text = "No scripts available",
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }
            items(remoteScripts, key = { it.first + it.second.hashCode() }) { (repoUrl, script) ->
                val scriptUri = remember {
                    repoUrl.toUri().buildUpon().appendEncodedPath(script.filepath).build()
                }
                var isDownloading by remember { mutableStateOf(false) }
                ElevatedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.padding(16.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = script.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold
                                )
                                script.author?.let {
                                    Text(
                                        text = "by $it",
                                        maxLines = 1,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Light,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            script.description?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "Version: ${script.version ?: "N/A"}",
                                fontWeight = FontWeight.Light,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            enabled = !isDownloading,
                            onClick = {
                                isDownloading = true
                                context.coroutineScope.launch {
                                    downloadScript(scriptUri, script.name)
                                    isDownloading = false
                                }
                            }
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
        // Add a floating button if you want manual repo refresh etc.
    }
}
