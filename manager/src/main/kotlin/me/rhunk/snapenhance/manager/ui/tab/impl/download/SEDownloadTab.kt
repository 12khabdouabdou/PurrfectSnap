package me.rhunk.snapenhance.manager.ui.tab.impl.download

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
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
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.util.zip.ZipInputStream

data class DebugBuild(
    val name: String,
    val createdAt: String,
    val artifactsUrl: String
)

data class ArtifactApkChoice(
    val artifact: SEArtifact,
    val apkList: List<String>
)

class SEDownloadTab : Tab("se_download") {

    private fun fetchSEReleases(): List<SEVersion>? {
        return runCatching {
            val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/releases").build()
            val response = OkHttpClient().newCall(endpoint).execute()
            if (!response.isSuccessful) return null
            val releases = JsonParser.parseString(response.body!!.string()).asJsonArray.also {
                if (it.size() == 0) return null
            }
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
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    override fun init(activity: ComponentActivity) {
        super.init(activity)
    }

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
        val snapEnhanceReleases = remember { mutableStateOf(null as List<SEVersion>?) }
        var selectedVersion by remember { mutableStateOf(null as SEVersion?) }
        var selectedArtifact by remember { mutableStateOf(null as SEArtifact?) }
        val snapEnhanceApp = remember {
            runCatching { activity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
        }
        var showDowngradeNotice by remember { mutableStateOf(false) }
        fun triggerPackageInstallation(shouldUninstall: Boolean) {
            navigation.navigateTo(
                InstallPackageTab::class, Bundle().apply {
                    putString("downloadPath", selectedArtifact?.downloadUrl)
                    putString("appPackage", sharedConfig.snapEnhancePackageName)
                    putBoolean("uninstall", shouldUninstall)
                },
                noHistory = true
            )
        }

        if (showDowngradeNotice) {
            Dialog(onDismissRequest = { showDowngradeNotice = false }) {
                DowngradeNoticeDialog(onDismiss = { showDowngradeNotice = false }, onSuccess = {
                    triggerPackageInstallation(false)
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
                    if (snapEnhanceReleases.value == null) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) { CircularProgressIndicator() }
                    }
                }
                items(snapEnhanceReleases.value ?: listOf()) { version ->
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (snapEnhanceApp != null) {
                    if (sharedConfig.enableRepackage && sharedConfig.snapEnhancePackageName != snapEnhanceApp.packageName) {
                        Button(
                            onClick = {
                                navigation.navigateTo(
                                    RepackageTab::class, Bundle().apply {
                                        putString("apkPath", snapEnhanceApp.applicationInfo.sourceDir)
                                        putString("oldPackage", snapEnhanceApp.packageName)
                                    },
                                    noHistory = true
                                )
                            },
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(text = "Repackage installed version (>=2.0.0)") }
                    }
                    Button(
                        onClick = { triggerPackageInstallation(true) },
                        enabled = selectedVersion != null && selectedArtifact != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(text = "Uninstall & Install") }
                }
                Button(
                    onClick = {
                        if (snapEnhanceApp != null) {
                            showDowngradeNotice = true
                        } else {
                            triggerPackageInstallation(false)
                        }
                    },
                    enabled = selectedVersion != null && selectedArtifact != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(text = if (snapEnhanceApp != null) "Update" else "Install") }
            }
        }
        LaunchedEffect(Unit) { coroutineScope.launch(Dispatchers.IO) { snapEnhanceReleases.value = fetchSEReleases() } }
    }

    @Composable
    private fun DebugTabContent() {
        val coroutineScope = rememberCoroutineScope()
        var builds by remember { mutableStateOf<List<DebugBuild>>(emptyList()) }
        var selectedBuild by remember { mutableStateOf<DebugBuild?>(null) }
        var artifactApkChoices by remember { mutableStateOf<Map<String, ArtifactApkChoice>>(emptyMap()) }
        var selectedApk by remember { mutableStateOf<Pair<SEArtifact, String>?>(null) }
        var isInstalling by remember { mutableStateOf(false) }
        val context = LocalContext.current

        fun fetchDebugCIs() : List<DebugBuild> {
            return runCatching {
                val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/actions/workflows/debug.yml/runs?branch=dev&status=completed").build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) return emptyList()
                val runs = JsonParser.parseString(response.body!!.string()).asJsonObject["workflow_runs"].asJsonArray
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
                val artifactsArr = JsonParser.parseString(response.body!!.string()).asJsonObject.getAsJsonArray("artifacts")
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

        fun fetchApkListFromArtifact(artifact: SEArtifact): List<String> {
            // Download the ZIP, index all .apk files inside, and return their entry names
            return runCatching {
                val client = OkHttpClient()
                val request = Request.Builder().url(artifact.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return emptyList()
                val tmpZipFile = File.createTempFile("debugartifact", ".zip", context.cacheDir).also { it.deleteOnExit() }
                response.body!!.byteStream().use { input ->
                    tmpZipFile.outputStream().use { output -> input.copyTo(output) }
                }
                val apkEntries = mutableListOf<String>()
                ZipInputStream(tmpZipFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                            apkEntries.add(entry.name)
                        }
                        entry = zip.nextEntry
                    }
                }
                tmpZipFile.delete()
                apkEntries
            }.getOrElse { emptyList() }
        }

        fun onInstallClicked(artifact: SEArtifact, apkNameInZip: String) {
            if (isInstalling) return
            isInstalling = true
            coroutineScope.launch(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder().url(artifact.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    isInstalling = false
                    return@launch
                }
                val tmpZipFile = File.createTempFile("debugartifact", ".zip", context.cacheDir).also { it.deleteOnExit() }
                response.body!!.byteStream().use { input ->
                    tmpZipFile.outputStream().use { output -> input.copyTo(output) }
                }
                var apkFile: File? = null
                ZipInputStream(tmpZipFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == apkNameInZip) {
                            val apkTmpFile = File.createTempFile("extracted_debug", ".apk", context.cacheDir).also { it.deleteOnExit() }
                            apkTmpFile.outputStream().use { output -> zip.copyTo(output) }
                            apkFile = apkTmpFile
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
                tmpZipFile.delete()
                if (apkFile != null) {
                    val bundle = Bundle().apply {
                        putString("downloadPath", apkFile!!.absolutePath)
                        putString("appPackage", BuildConfig.APPLICATION_ID)
                        putBoolean("uninstall", false)
                    }
                    launch(Dispatchers.Main) {
                        navigation.navigateTo(InstallPackageTab::class, bundle, noHistory = true)
                    }
                }
                isInstalling = false
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
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { selectedBuild = if (selectedBuild == build) null else build }
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(build.name, fontSize = 18.sp)
                            Text("Created: ${build.createdAt}", fontSize = 13.sp)
                        }
                    }
                    if (selectedBuild == build) {
                        val artifacts = fetchArtifactsForBuild(build.artifactsUrl)
                        artifacts.forEach { artifact ->
                            val artifactChoice = artifactApkChoices[artifact.downloadUrl]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                                    .border(
                                        shape = MaterialTheme.shapes.small,
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                            ) {
                                Text(
                                    artifact.fileName,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                                if (artifactChoice == null) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val apkList = fetchApkListFromArtifact(artifact)
                                                artifactApkChoices = buildMap {
                                                    putAll(artifactApkChoices)
                                                    put(artifact.downloadUrl, ArtifactApkChoice(artifact, apkList))
                                                }
                                            }
                                        },
                                        enabled = !artifactApkChoices.containsKey(artifact.downloadUrl),
                                        modifier = Modifier.padding(8.dp)
                                    ) { Text("Show APKs in .zip") }
                                } else {
                                    artifactChoice.apkList.forEach { apkName ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 14.dp, end = 10.dp, bottom = 4.dp)
                                                .background(
                                                    if (selectedApk == Pair(artifact, apkName)) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                                    shape = MaterialTheme.shapes.medium
                                                )
                                                .clickable { selectedApk = Pair(artifact, apkName) }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Android,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Text(apkName, fontSize = 14.sp)
                                            Spacer(Modifier.weight(1f))
                                            Button(
                                                enabled = selectedApk == Pair(artifact, apkName) && !isInstalling,
                                                onClick = { onInstallClicked(artifact, apkName) }
                                            ) { Text("Install") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (isInstalling) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
