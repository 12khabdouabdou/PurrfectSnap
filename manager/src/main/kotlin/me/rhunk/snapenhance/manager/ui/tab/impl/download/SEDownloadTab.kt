package me.rhunk.snapenhance.manager.ui.tab.impl.download

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.manager.data.download.SEArtifact
import me.rhunk.snapenhance.manager.data.download.SEVersion
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class DebugBuild(
    val name: String,
    val createdAt: String,
    val artifactsUrl: String
)

data class DebugArtifactContents(val entries: List<String>)

class SEDownloadTab : Tab("se_download") {
    override fun init(activity: ComponentActivity) { super.init(activity) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Release", "Debug")
        Column {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, title ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx }, text = { Text(title) })
                }
            }
            when (selectedTab) {
                0 -> ReleaseTabContent()
                1 -> DebugTabContentMinimal()
            }
        }
    }

    @Composable
    private fun ReleaseTabContent() {
        // Omitted: Use your release tab logic
        Text("Release tab here (omitted for brevity)")
    }

    @Composable
    private fun DebugTabContentMinimal() {
        val coroutineScope = rememberCoroutineScope()
        var builds by remember { mutableStateOf<List<DebugBuild>>(emptyList()) }
        var expandedBuild by remember { mutableStateOf<DebugBuild?>(null) }
        var expandedArtifactContents by remember { mutableStateOf<Map<String, DebugArtifactContents>>(emptyMap()) }
        var artifactLoading by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        fun fetchDebugCIs(): List<DebugBuild> {
            return runCatching {
                val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/actions/workflows/debug.yml/runs?branch=dev&status=completed").build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) return emptyList()
                val runs = com.google.gson.JsonParser.parseString(response.body!!.string()).asJsonObject["workflow_runs"].asJsonArray
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                runs.mapNotNull { runObj ->
                    val runJson = runObj.asJsonObject
                    if (runJson["name"].asString != "Debug CI" || runJson["path"].asString != ".github/workflows/debug.yml") return@mapNotNull null
                    val artifactsUrl = runJson["artifacts_url"].asString
                    val createdAt = runJson["created_at"].asString.let { time ->
                        dateFormat.parse(time)?.let { date ->
                            SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(date)
                        } ?: time
                    }
                    DebugBuild(
                        name = runJson["display_title"].asString,
                        createdAt = createdAt,
                        artifactsUrl = artifactsUrl
                    )
                }
            }.getOrElse { emptyList() }
        }

        fun fetchArtifactsForBuild(artifactsUrl: String): List<SEArtifact> {
            return runCatching {
                val endpoint = Request.Builder().url(artifactsUrl).build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) return emptyList()
                val artifactsArr = com.google.gson.JsonParser.parseString(response.body!!.string())
                    .asJsonObject.getAsJsonArray("artifacts")
                artifactsArr.map { obj ->
                    val artifactObj = obj.asJsonObject
                    SEArtifact(
                        fileName = artifactObj.getAsJsonPrimitive("name").asString + ".zip",
                        size = artifactObj.getAsJsonPrimitive("size_in_bytes").asLong,
                        downloadUrl = artifactObj.getAsJsonPrimitive("archive_download_url").asString
                    )
                }
            }.getOrElse { emptyList() }
        }

        fun enumerateFilesInArtifact(artifact: SEArtifact, onDone: (DebugArtifactContents) -> Unit) {
            artifactLoading = artifact.downloadUrl
            coroutineScope.launch(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder().url(artifact.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    artifactLoading = null
                    return@launch
                }
                val tempFile = File.createTempFile("art", ".zip", context.externalCacheDir).also { it.deleteOnExit() }
                response.body!!.byteStream().use { input -> tempFile.outputStream().use { output -> input.copyTo(output) }}
                val entries = mutableListOf<String>()
                ZipInputStream(tempFile.inputStream()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory)
                            entries.add(entry.name)
                        entry = zip.nextEntry
                    }
                }
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    artifactLoading = null
                    onDone(DebugArtifactContents(entries))
                }
            }
        }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) { builds = fetchDebugCIs() }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Debug CI builds", fontSize = 20.sp)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(builds) { build ->
                    var isBuildExpanded by remember { mutableStateOf(false) }
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                isBuildExpanded = !isBuildExpanded
                                expandedBuild = if (isBuildExpanded) build else null
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(build.name, fontSize = 18.sp)
                            Text("Created: ${build.createdAt}", fontSize = 13.sp)
                        }
                    }
                    if (isBuildExpanded && expandedBuild == build) {
                        val artifacts = fetchArtifactsForBuild(build.artifactsUrl)
                        artifacts.forEach { artifact ->
                            var isArtifactExpanded by remember { mutableStateOf(false) }
                            val artifactKey = artifact.downloadUrl
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isArtifactExpanded = !isArtifactExpanded
                                            if (isArtifactExpanded && expandedArtifactContents[artifactKey] == null && artifactLoading != artifactKey) {
                                                enumerateFilesInArtifact(artifact) { contents ->
                                                    expandedArtifactContents = expandedArtifactContents.toMutableMap().apply {
                                                        put(artifactKey, contents)
                                                    }
                                                }
                                            }
                                        }
                                        .padding(8.dp)
                                ) {
                                    Text(artifact.fileName, fontSize = 16.sp)
                                    if (artifactLoading == artifactKey) {
                                        CircularProgressIndicator(
                                            Modifier
                                                .padding(start = 8.dp)
                                                .size(18.dp), strokeWidth = 2.dp)
                                    }
                                }
                                if (isArtifactExpanded) {
                                    val contents = expandedArtifactContents[artifactKey]
                                    if (artifactLoading == artifactKey) {
                                        Text("Reading zip contents...", fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom=6.dp))
                                    } else if (contents != null) {
                                        contents.entries.forEach { name ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp)
                                            ) {
                                                Text(name, fontSize = 14.sp)
                                            }
                                        }
                                        if (contents.entries.isEmpty()) {
                                            Text("No files in zip.", modifier = Modifier.padding(start = 14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
