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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.ui.AsyncUpdateDispatcher
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.storage.getRepositories
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

// Data classes for parsing script repo manifests
data class ScriptRepoManifest(
    val scripts: List<ScriptRepoEntry>
)
data class ScriptRepoEntry(
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val version: String? = null,
    val filepath: String
)

@Composable
fun ScriptCatalog(root: ScriptingRootSection) {
    val context = root.context
    val coroutineScope = rememberCoroutineScope()
    val okHttpClient = remember { OkHttpClient() }
    val gson = remember { context.gson }

    // Store indexes (repo url -> manifest)
    var repoIndexes by remember { mutableStateOf(mapOf<String, ScriptRepoManifest>()) }
    val updateDispatcher = remember { AsyncUpdateDispatcher() }

    // Fetch all repo indexes
    fun refreshIndexes() {
        coroutineScope.launch(Dispatchers.IO) {
            val newIndexes = mutableMapOf<String, ScriptRepoManifest>()
            context.database.getRepositories().forEach { repoRoot ->
                val indexUrl = if (repoRoot.endsWith("/")) "${repoRoot}index.json" else "$repoRoot/index.json"
                try {
                    val req = Request.Builder().url(indexUrl).build()
                    okHttpClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.charStream()?.let { reader ->
                                val parsed = gson.fromJson(reader, ScriptRepoManifest::class.java)
                                if (parsed?.scripts != null) {
                                    newIndexes[repoRoot] = parsed
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to fetch or parse script repo at $indexUrl", e)
                }
            }
            withContext(Dispatchers.Main) {
                repoIndexes = newIndexes
                updateDispatcher.dispatch()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshIndexes()
    }

    // All repo scripts, with repository URL
    val allScripts = repoIndexes.entries.flatMap { (repoUrl, manifest) ->
        manifest.scripts.map { Pair(repoUrl, it) }
    }

    fun downloadScript(repoUrl: String, entry: ScriptRepoEntry) {
        coroutineScope.launch(Dispatchers.IO) {
            val rawUrl =
                if (repoUrl.endsWith("/")) repoUrl + entry.filepath else repoUrl + "/" + entry.filepath
            try {
                val req = Request.Builder().url(rawUrl).build()
                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            context.shortToast("Failed to download script: ${response.code}")
                        }
                        return@use
                    }
                    val content = response.body?.bytes()
                    if (content != null) {
                        val folder = context.scriptManager.getScriptsFolder()
                        if (folder != null) {
                            val file = File(folder, "${entry.name}.js")
                            file.writeBytes(content)
                            withContext(Dispatchers.Main) {
                                context.shortToast("Script downloaded!")
                                root.reloadDispatcher.dispatch()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                context.shortToast("No scripts folder selected.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    context.shortToast("Error: ${e.localizedMessage}")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {
        item {
            if (allScripts.isEmpty()) {
                Text(
                    text = "No scripts available from any repo.",
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }
        items(allScripts) { (repoUrl, entry) ->
            var isDownloading by remember { mutableStateOf(false) }
            ElevatedCard(Modifier.padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Code, null, Modifier.padding(end = 12.dp)
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold
                            )
                            entry.author?.let {
                                Text(
                                    text = "by $it",
                                    maxLines = 1,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Light,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        entry.description?.let {
                            Text(
                                text = it,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Version: ${entry.version ?: "N/A"}",
                            fontWeight = FontWeight.Light,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        enabled = !isDownloading,
                        onClick = {
                            isDownloading = true
                            downloadScript(repoUrl, entry)
                            isDownloading = false
                        }
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}
