package me.rhunk.snapenhance.manager.ui.tab.impl.download

import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.text.SimpleDateFormat
import java.util.*
import com.google.gson.JsonParser
import me.rhunk.snapenhance.manager.data.download.SEArtifact
import java.util.zip.ZipInputStream
import java.io.File
import me.rhunk.snapenhance.manager.BuildConfig

@Composable
fun DebugSEBuildTab() {
    val coroutineScope = rememberCoroutineScope()
    var builds by remember { mutableStateOf(listOf<DebugBuild>()) }
    var selectedBuild by remember { mutableStateOf<DebugBuild?>(null) }
    var selectedArtifact by remember { mutableStateOf<SEArtifact?>(null) }
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
                // only those with name == "Debug CI", path contains debug.yml, etc.
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

    fun onInstallClicked(artifact: SEArtifact) {
        if (isInstalling) return
        isInstalling = true
        coroutineScope.launch(Dispatchers.IO) {
            // Download ZIP and extract APK
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
            // extract first APK
            var apkFile: File? = null
            ZipInputStream(tmpZipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                        val apkTmpFile = File.createTempFile("extracted_debug", ".apk", context.cacheDir).also { it.deleteOnExit() }
                        apkTmpFile.outputStream().use { output -> zip.copyTo(output) }
                        apkFile = apkTmpFile
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            tmpZipFile.delete()
            // Now trigger installer
            if (apkFile != null) {
                val bundle = Bundle().apply {
                    putString("downloadPath", apkFile!!.absolutePath)
                    putString("appPackage", BuildConfig.APPLICATION_ID)
                    putBoolean("uninstall", false)
                }
                // switch to main thread for navigation
                launch(Dispatchers.Main) {
                    (context as? androidx.activity.ComponentActivity)?.let { activity ->
                        // navigation handled by the Activity via your Tab API
                        navigation.navigateTo(InstallPackageTab::class, bundle, noHistory = true)
                    }
                }
            }
            isInstalling = false
        }
    }

    // UI
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    shape = MaterialTheme.shapes.medium,
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                .clickable {
                                    selectedArtifact = if (selectedArtifact == artifact) null else artifact
                                }
                                .background(
                                    if (selectedArtifact == artifact) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .padding(16.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Android, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Column {
                                Text(artifact.fileName, fontSize = 15.sp)
                                Text("${artifact.size / 1024 / 1024} MB", fontSize = 10.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { onInstallClicked(artifact) },
                                enabled = !isInstalling && artifact == selectedArtifact
                            ) {
                                Text("Install")
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

data class DebugBuild(
    val name: String,
    val createdAt: String,
    val artifactsUrl: String
)
