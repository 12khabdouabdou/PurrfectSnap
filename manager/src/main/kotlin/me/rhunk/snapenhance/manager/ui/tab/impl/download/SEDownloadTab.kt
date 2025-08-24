package me.rhunk.snapenhance.manager.ui.tab.impl.download

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.manager.BuildConfig
import me.rhunk.snapenhance.manager.data.download.SEArtifact
import me.rhunk.snapenhance.manager.data.download.SEVersion
import me.rhunk.snapenhance.manager.ui.components.DowngradeNoticeDialog
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class DebugBuild(
    val name: String,
    val createdAt: String,
    val artifactsUrl: String
)

data class DebugArtifactContents(val entries: List<String>)

class SEDownloadTab : Tab("se_download") {

    private fun fetchSEReleases(): List<SEVersion>? {
        return runCatching {
            val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/releases").build()
            val response = OkHttpClient().newCall(endpoint).execute()
            if (!response.isSuccessful) return null
            val releases = com.google.gson.JsonParser.parseString(response.body!!.string()).asJsonArray
            val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            releases.map { releaseObject ->
                val release = releaseObject.asJsonObject
                val versionName = release.getAsJsonPrimitive("tag_name").asString
                val releaseDate = release.getAsJsonPrimitive("published_at").asString.let { time ->
                    isoDateFormat.parse(time)?.let { date ->
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
                    } ?: time
                }
                val downloadAssets = release.getAsJsonArray("assets").associate { asset ->
                    val assetObject = asset.asJsonObject
                    SEArtifact(
                        fileName = assetObject.getAsJsonPrimitive("name").asString,
                        size = assetObject.getAsJsonPrimitive("size").asLong,
                        downloadUrl = assetObject.getAsJsonPrimitive("browser_download_url").asString
                    ).let { it.fileName to it }
                }
                SEVersion(versionName, releaseDate, downloadAssets)
            }
        }.getOrNull()
    }

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
                1 -> DebugTabContent()
            }
        }
    }

    @Composable
    private fun ReleaseTabContent() {
        val coroutineScope = rememberCoroutineScope()
        val releasesState = remember { mutableStateOf(null as List<SEVersion>?) }
        var selectedVersion by remember { mutableStateOf(null as SEVersion?) }
        var selectedArtifact by remember { mutableStateOf(null as SEArtifact?) }
        val snapEnhanceApp = remember {
            runCatching { activity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
        }
        var showDowngradeNotice by remember { mutableStateOf(false) }
        var installing by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0f) }
        val context = LocalContext.current

        fun startInstallFlow(asset: SEArtifact) {
            installing = true
            coroutineScope.launch(Dispatchers.IO) {
                val url = asset.downloadUrl
                val endpoint = Request.Builder().url(url).build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) {
                    installing = false
                    return@launch
                }
                val apkFile = File.createTempFile("release", ".apk", context.externalCacheDir).also { it.deleteOnExit() }
                response.body!!.byteStream().use { input ->
                    val output = apkFile.outputStream()
                    val buffer = ByteArray(4096)
                    var read: Int
                    var written: Long = 0
                    val size = response.body!!.contentLength()
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        written += read
                        if(size > 0) downloadProgress = written / size.toFloat()
                    }
                    output.flush()
                    output.close()
                }
                withContext(Dispatchers.Main) {
                    installing = false
                    navigation.navigateTo(InstallPackageTab::class, Bundle().apply {
                        putString("downloadPath", apkFile.absolutePath)
                        putString("appPackage", BuildConfig.APPLICATION_ID)
                        putBoolean("uninstall", false)
                    }, noHistory = true)
                }
            }
        }

        if (showDowngradeNotice) {
            Dialog(onDismissRequest = { showDowngradeNotice = false }) {
                DowngradeNoticeDialog(onDismiss = { showDowngradeNotice = false }, onSuccess = {
                    selectedArtifact?.let { startInstallFlow(it) }
                })
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Choose SnapEnhance version")
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    if (releasesState.value == null) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) { CircularProgressIndicator() }
                    }
                }
                items(releasesState.value ?: listOf()) { version ->
                    OutlinedCard(
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable {
                            selectedArtifact = if (selectedVersion != version) null else selectedArtifact
                            selectedVersion = if (selectedVersion == version) null else version
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = version.versionName, fontSize = 24.sp)
                                Text(text = "Release ${version.releaseDate}", fontSize = 12.sp)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) { Text(text = "${version.downloadAssets.size} assets", fontSize = 12.sp) }
                        }
                    }
                    selectedVersion?.takeIf { it == version }?.let { selVersion ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            selVersion.downloadAssets.values.forEach { artifact ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .border(
                                            shape = MaterialTheme.shapes.medium,
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        .clickable {
                                            selectedArtifact =
                                                if (selectedArtifact == artifact) null else artifact
                                        }
                                        .background(
                                            if (selectedArtifact == artifact) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Android, contentDescription = null, modifier = Modifier.padding(start = 2.dp, end = 2.dp))
                                    Column(modifier = Modifier.padding(start = 13.dp)) {
                                        Text(text = artifact.fileName, fontSize = 15.sp)
                                        Text(text = "${artifact.size / 1024 / 1024} MB", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(installing) {
                LinearProgressIndicator(progress = downloadProgress, Modifier.fillMaxWidth())
                Text("Downloading...", modifier = Modifier.padding(top=8.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        selectedArtifact?.let { startInstallFlow(it) }
                    },
                    enabled = selectedVersion != null && selectedArtifact != null && !installing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Install") }
            }
        }
        LaunchedEffect(Unit) { coroutineScope.launch(Dispatchers.IO) { releasesState.value = fetchSEReleases() } }
    }

    @Composable
    private fun DebugTabContent() {
        val coroutineScope = rememberCoroutineScope()
        var builds by remember { mutableStateOf<List<DebugBuild>>(emptyList()) }
        var expandedBuild by remember { mutableStateOf<DebugBuild?>(null) }
        var expandedArtifactContents by remember { mutableStateOf<Map<String, DebugArtifactContents>>(emptyMap()) }
        var artifactLoading by remember { mutableStateOf<String?>(null) }
        var showInstallDialog by remember { mutableStateOf(false) }
        var selectedArtifactFile: Pair<SEArtifact, String>? by remember { mutableStateOf(null) }
        var downloadStage by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableStateOf(0f) }
        val context = LocalContext.current

        fun fetchDebugCIs() : List<DebugBuild> {
            return runCatching {
                val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/actions/workflows/debug.yml/runs?branch=dev&status=completed").build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) return emptyList()
                val runs = com.google.gson.JsonParser.parseString(response.body!!.string()).asJsonObject["workflow_runs"].asJsonArray
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                runs.mapNotNull { runObj ->
                    val runJson = runObj.asJsonObject
                    if (runJson["name"].asString != "Debug CI" ||
                        runJson["path"].asString != ".github/workflows/debug.yml")
                        return@mapNotNull null
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

        fun installFromArtifact(artifact: SEArtifact, fileNameInZip: String) {
            showInstallDialog = true
            downloadStage = "Downloading ZIP…"
            progress = 0f
            coroutineScope.launch(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder().url(artifact.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        showInstallDialog = false
                    }
                    return@launch
                }
                val total = response.body!!.contentLength()
                var written = 0L
                val tempZip = File.createTempFile("se_dbg_zip", ".zip", context.externalCacheDir).also { it.deleteOnExit() }
                response.body!!.byteStream().use { input ->
                    tempZip.outputStream().use { output ->
                        val buffer = ByteArray(4096)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) progress = written / total.toFloat()
                        }
                    }
                }
                downloadStage = "Extracting file…"
                progress = 0.0f
                var outFile: File? = null
                ZipInputStream(tempZip.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == fileNameInZip) {
                            val destFile = File.createTempFile("se_dbg_ex", ".apk", context.externalCacheDir).also { it.deleteOnExit() }
                            destFile.outputStream().use { output -> 
                                val buffer = ByteArray(4096)
                                var count: Int
                                while (zip.read(buffer).also { count = it } != -1) {
                                    output.write(buffer, 0, count)
                                }
                            }
                            outFile = destFile
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
                tempZip.delete()
                withContext(Dispatchers.Main) {
                    showInstallDialog = false
                    if (outFile != null) {
                        navigation.navigateTo(InstallPackageTab::class, Bundle().apply {
                            putString("downloadPath", outFile!!.absolutePath)
                            putString("appPackage", BuildConfig.APPLICATION_ID)
                            putBoolean("uninstall", false)
                        }, noHistory = true)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) { builds = fetchDebugCIs() }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Text("Debug CI builds", fontSize = 20.sp)
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
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
                                        CircularProgressIndicator(Modifier
                                            .padding(start=8.dp)
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
                                                    .clickable { installFromArtifact(artifact, name) }
                                                    .padding(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Android,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(end = 8.dp)
                                                )
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

            if (showInstallDialog) {
                Dialog(onDismissRequest = {}) {
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(downloadStage ?: "...")
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(vertical=14.dp))
                        }
                    }
                }
                BackHandler { /* prevent dismiss */ }
            }
        }
    }
}
