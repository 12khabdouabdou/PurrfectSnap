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
import me.rhunk.snapenhance.manager.data.download.SEArtifact
import me.rhunk.snapenhance.manager.data.download.SEVersion
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
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
        // Your previous Release tab code goes here (omitted for brevity)
        Text("Release tab here (see Debug tab for debug prereleases)", modifier = Modifier.padding(24.dp))
    }

    @Composable
    private fun DebugTabDebugReleasesContent() {
        val coroutineScope = rememberCoroutineScope()
        var debugReleases by remember { mutableStateOf<List<SEVersion>>(emptyList()) }
        var expandedRelease by remember { mutableStateOf<String?>(null) }

        fun fetchDebugPrereleases(): List<SEVersion> {
            return runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/rhunk/SnapEnhance/releases").build()
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

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) { debugReleases = fetchDebugPrereleases() }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Debug prereleases", fontSize = 20.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(debugReleases) { rel ->
                    OutlinedCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { expandedRelease = if (expandedRelease == rel.versionName) null else rel.versionName }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(rel.versionName, fontSize = 18.sp)
                            Text("Published: ${rel.releaseDate}", fontSize = 13.sp)
                        }
                    }
                    if (expandedRelease == rel.versionName) {
                        rel.downloadAssets.values.forEach { asset ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 22.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                            ) {
                                Text(asset.fileName, fontSize = 15.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${asset.size / 1024 / 1024} MB", fontSize = 11.sp)
                                // Add a download/install button if desired
                            }
                        }
                    }
                }
            }
        }
    }
}
