package me.rhunk.snapenhance.manager.ui.tab.impl.download

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.BuildConfig
import me.rhunk.snapenhance.manager.data.download.SEArtifact
import me.rhunk.snapenhance.manager.data.download.SEVersion
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
                1 -> DebugTabDebugReleasesContent()
            }
        }
    }

    @Composable
    private fun ReleaseTabContent() {
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        var releases by remember { mutableStateOf<List<SEVersion>>(emptyList()) }
        var expandedRelease by remember { mutableStateOf<String?>(null) }
        var downloadingApk by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }

        fun fetchReleases(): List<SEVersion> {
            return runCatching {
                val endpoint = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/releases").build()
                val response = OkHttpClient().newCall(endpoint).execute()
                if (!response.isSuccessful) return emptyList()
                val arr = com.google.gson.JsonParser.parseString(response.body!!.string()).asJsonArray
                arr.mapNotNull { objElem ->
                    val obj = objElem.asJsonObject
                    if (obj["prerelease"].asBoolean) return@mapNotNull null
                    val versionName = obj["name"]?.asString ?: obj["tag_name"].asString
                    val publishedAt = obj["published_at"].asString.let { time ->
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                            .parse(time)?.let { date ->
                                SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(date)
                            } ?: time
                    }
                    val assetsArr = obj["assets"].asJsonArray
                    val assets = assetsArr.associate { assetEl ->
                        val asset = assetEl.asJsonObject
                        val fname = asset["name"].asString
                        SEArtifact(
                            fileName = fname,
                            size = asset["size"].asLong,
                            downloadUrl = asset["browser_download_url"].asString
                        ).let { it.fileName to it }
                    }
                    SEVersion(versionName, publishedAt, assets)
                }
            }.getOrElse { emptyList() }
        }

        fun installApk(asset: SEArtifact) {
            downloadingApk = asset.fileName
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(asset.downloadUrl).build()
                    val resp = OkHttpClient().newCall(req).execute()
                    if (!resp.isSuccessful) throw Exception("Failed to download: ${resp.code}")
                    val outFile = File.createTempFile("release", ".apk", context.externalCacheDir).also { it.deleteOnExit() }
                    resp.body!!.byteStream().use { input ->
                        val output = outFile.outputStream()
                        val buf = ByteArray(4096)
                        var read: Int
                        var written = 0L
                        val size = resp.body!!.contentLength()
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            written += read
                            if (size > 0) downloadProgress = written / size.toFloat()
                        }
                        output.flush()
                        output.close()
                    }
                    downloadingApk = null
                    downloadProgress = 0f
                    (context as ComponentActivity).runOnUiThread {
                        navigation.navigateTo(
                            InstallPackageTab::class,
                            Bundle().apply {
                                putString("downloadPath", outFile.absolutePath)
                                putString("appPackage", BuildConfig.APPLICATION_ID)
                                putBoolean("uninstall", false)
                            },
                            noHistory = true
                        )
                    }
                } catch (e: Exception) {
                    downloadingApk = null
                    downloadProgress = 0f
                    coroutineScope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) { releases = fetchReleases() }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Text(
                "Stable releases",
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 10.dp, start = 18.dp),
                color = MaterialTheme.colorScheme.primary
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(releases) { rel ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .shadow(
                                elevation = if (expandedRelease == rel.versionName) 6.dp else 2.dp,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { expandedRelease = if (expandedRelease == rel.versionName) null else rel.versionName },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (expandedRelease == rel.versionName)
                                lerp(MaterialTheme.colorScheme.primaryContainer, Color.White, 0.8f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(if (expandedRelease == rel.versionName) 8.dp else 2.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(rel.versionName, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                            Text("Published: ${rel.releaseDate}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expandedRelease == rel.versionName) {
                            Divider(Modifier.padding(horizontal = 9.dp, vertical = 1.dp))
                            rel.downloadAssets.values.forEach { asset ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 13.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                                        .background(
                                            if (downloadingApk == asset.fileName) MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f)
                                            else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Android,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(asset.fileName, fontSize = 16.sp)
                                        Text(
                                            "${asset.size / 1024 / 1024} MB",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (downloadingApk == asset.fileName) {
                                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(progress = downloadProgress, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                                        }
                                    } else {
                                        Button(
                                            onClick = { installApk(asset) },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(35.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, Modifier.size(15.dp))
                                            Spacer(Modifier.width(7.dp))
                                            Text("Install", fontSize = 15.sp)
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

    @Composable
    private fun DebugTabDebugReleasesContent() {
        val coroutineScope = rememberCoroutineScope()
        var debugReleases by remember { mutableStateOf<List<SEVersion>>(emptyList()) }
        var expandedRelease by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        var downloadingApk by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }

        fun fetchDebugPrereleases(): List<SEVersion> {
            return runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/particle-box/SnapEnhance/releases").build()
                val resp = OkHttpClient().newCall(req).execute()
                if (!resp.isSuccessful) return emptyList()
                val arr = com.google.gson.JsonParser.parseString(resp.body!!.string()).asJsonArray
                arr.mapNotNull { objElem ->
                    val obj = objElem.asJsonObject
                    if (!obj["prerelease"].asBoolean) return@mapNotNull null
                    val tag = obj["tag_name"].asString
                    if (!tag.startsWith("debug-")) return@mapNotNull null
                    val versionName = obj["name"]?.asString ?: tag
                    val publishedAt = obj["published_at"].asString.let { time ->
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                            .parse(time)?.let { date ->
                                SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(date)
                            } ?: time
                    }
                    val assetsArr = obj["assets"].asJsonArray
                    val assets = assetsArr.associate { assetEl ->
                        val asset = assetEl.asJsonObject
                        val fname = asset["name"].asString
                        SEArtifact(
                            fileName = fname,
                            size = asset["size"].asLong,
                            downloadUrl = asset["browser_download_url"].asString
                        ).let { it.fileName to it }
                    }
                    SEVersion(versionName, publishedAt, assets)
                }
            }.getOrElse { emptyList() }
        }

        fun installApk(apk: SEArtifact) {
            downloadingApk = apk.fileName
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(apk.downloadUrl).build()
                    val resp = OkHttpClient().newCall(req).execute()
                    if (!resp.isSuccessful) throw Exception("Failed to download: ${resp.code}")
                    val outFile = File.createTempFile("prerelease", ".apk", context.externalCacheDir).also { it.deleteOnExit() }
                    resp.body!!.byteStream().use { input ->
                        val output = outFile.outputStream()
                        val buf = ByteArray(4096)
                        var read: Int
                        var written = 0L
                        val size = resp.body!!.contentLength()
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            written += read
                            if (size > 0) downloadProgress = written / size.toFloat()
                        }
                        output.flush()
                        output.close()
                    }
                    downloadingApk = null
                    downloadProgress = 0f
                    (context as ComponentActivity).runOnUiThread {
                        navigation.navigateTo(
                            InstallPackageTab::class,
                            Bundle().apply {
                                putString("downloadPath", outFile.absolutePath)
                                putString("appPackage", context.packageName)
                                putBoolean("uninstall", false)
                            },
                            noHistory = true
                        )
                    }
                } catch (e: Exception) {
                    downloadingApk = null
                    downloadProgress = 0f
                    coroutineScope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) { debugReleases = fetchDebugPrereleases() }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Text(
                "Debug prereleases",
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 10.dp, start = 18.dp),
                color = MaterialTheme.colorScheme.primary
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(debugReleases) { rel ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .shadow(
                                elevation = if (expandedRelease == rel.versionName) 6.dp else 2.dp,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { expandedRelease = if (expandedRelease == rel.versionName) null else rel.versionName },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (expandedRelease == rel.versionName)
                                lerp(MaterialTheme.colorScheme.primaryContainer, Color.White, 0.8f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(if (expandedRelease == rel.versionName) 8.dp else 2.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(rel.versionName, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                            Text("Published: ${rel.releaseDate}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expandedRelease == rel.versionName) {
                            Divider(Modifier.padding(horizontal = 9.dp, vertical = 1.dp))
                            rel.downloadAssets.values.forEach { asset ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 13.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                                        .background(
                                            if (downloadingApk == asset.fileName) MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f)
                                            else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Android,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(asset.fileName, fontSize = 16.sp)
                                        Text(
                                            "${asset.size / 1024 / 1024} MB",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (downloadingApk == asset.fileName) {
                                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(progress = downloadProgress, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                                        }
                                    } else {
                                        Button(
                                            onClick = { installApk(asset) },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(35.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, Modifier.size(15.dp))
                                            Spacer(Modifier.width(7.dp))
                                            Text("Install", fontSize = 15.sp)
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
