@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package me.eternal.purrfectsnap.ui.manager.pages.tracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfectsnap.storage.getRepositories
import me.eternal.purrfectsnap.storage.getTrackerRuleByName
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.components.AestheticEmptyState
import me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.headerHeightTracker
import me.eternal.purrfectsnap.ui.util.Motion
import okhttp3.OkHttpClient
import okhttp3.Request

data class FriendTrackerRepoManifest(
    val rules: List<FriendTrackerRepoEntry>
)

data class FriendTrackerRepoEntry(
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val path: String
)

@OptIn(ExperimentalMaterial3Api::class)
class FriendTrackerCatalog : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.friend_tracker_catalog") }

    @Composable
    private fun AvailableRulesTab(
        controlsHeight: androidx.compose.ui.unit.Dp,
        onScrollOffsetChanged: (Int) -> Unit
    ) {
        val coroutineScope = rememberCoroutineScope()
        val okHttpClient = remember { OkHttpClient() }
        val gson = remember { context.gson }

        var repositories by remember { mutableStateOf<List<String>>(emptyList()) }
        var repoIndexes by remember { mutableStateOf<Map<String, FriendTrackerRepoManifest>>(emptyMap()) }
        var isLoading by remember { mutableStateOf(false) }

        var importTick by remember { mutableStateOf(0) }

        fun refreshIndexes() {
            coroutineScope.launch(Dispatchers.IO) {
                isLoading = true
                val repos = context.database.getRepositories("friend_tracker")
                withContext(Dispatchers.Main) {
                    repositories = repos
                }
                if (repos.isNotEmpty()) {
                    val newIndexes = mutableMapOf<String, FriendTrackerRepoManifest>()
                    repos.forEach { repoRoot ->
                        val indexUrl = if (repoRoot.endsWith("/")) "${repoRoot}index.json" else "$repoRoot/index.json"
                        try {
                            val req = Request.Builder().url(indexUrl).build() 
                            okHttpClient.newCall(req).execute().use { response ->
                                if (response.isSuccessful) {
                                    response.body?.charStream()?.let { reader ->
                                        val parsed = gson.fromJson(reader, FriendTrackerRepoManifest::class.java)
                                        if (parsed.rules.isNotEmpty()) {
                                            newIndexes[repoRoot] = parsed
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        repoIndexes = newIndexes
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            refreshIndexes()
            routes.onRuleImported = {
                importTick++
            }
        }

        val allRules = repoIndexes.entries.flatMap { (repoUrl, manifest) ->
            manifest.rules.map { repoUrl to it }
        }

        if (repositories.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AestheticEmptyState(
                    icon = Icons.Default.Public,
                    title = translation["no_repos_added"],
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    actions = {
                        Button(
                            onClick = { routes.manageFriendTrackerRepos.navigate() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PurrfectPalette.glowPrimary.copy(alpha = 0.34f),
                                contentColor = Color.White
                            )
                        ) {
                            Text(translation["manage_repos_description"] ?: context.translation["manager.routes.manage_friend_tracker_repos"])
                        }
                    }
                )
            }
            return
        }

        fun importRule(repoUrl: String, entry: FriendTrackerRepoEntry) {
            coroutineScope.launch(Dispatchers.IO) {
                val rawUrl = if (repoUrl.endsWith("/")) repoUrl + entry.path else repoUrl + "/" + entry.path
                try {
                    val req = Request.Builder().url(rawUrl).build() 
                    okHttpClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) { context.shortToast(translation.format("download_failed", "code" to response.code.toString())) }
                            return@use
                        }
                        val content = response.body?.string()
                        if (content != null) { 
                            withContext(Dispatchers.Main) {
                                routes.friendTrackerConfigJsonForImport = content
                                routes.friendTrackerConfigImport.navigate()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        context.shortToast(translation.format("error", "message" to (e.localizedMessage ?: context.translation["common.unknown"])))
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        
        LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) {
            val offset = if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else listState.firstVisibleItemScrollOffset
            onScrollOffsetChanged(offset)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 8.dp,
                top = controlsHeight,
                end = 8.dp,
                bottom = routes.bottomPadding
            )
        ) {
            item {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (allRules.isEmpty() && repositories.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AestheticEmptyState(
                                icon = Icons.AutoMirrored.Filled.Rule,
                                title = translation["no_rules_available"],
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp)
                            )
                        }
                    }
                }
                items(allRules) { (repoUrl, entry) ->
                    val isImported by produceState(initialValue = false, key1 = entry.name, key2 = importTick) {
                        val exists = withContext(Dispatchers.IO) {
                            context.database.getTrackerRuleByName(entry.name) != null
                        }
                        value = exists
                    }

                    val shape = RoundedCornerShape(20.dp)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = shape,
                        color = PurrfectPalette.cardOverlayColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    PurrfectPalette.glowPrimary.copy(alpha = 0.45f),
                                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PurrfectPalette.cardOverlay, shape)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = PurrfectPalette.glowPrimary.copy(alpha = 0.18f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Rule,
                                    contentDescription = null,
                                    modifier = Modifier.padding(10.dp),
                                    tint = Color.White
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = entry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                entry.author?.let {
                                    Text(
                                        text = translation.format("by_author", "author" to it),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        color = PurrfectPalette.textSecondary
                                    )
                                }
                                entry.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        color = PurrfectPalette.textSecondary
                                    )
                                }
                            }
                            Button(
                                onClick = { importRule(repoUrl, entry) },
                                enabled = !isImported,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PurrfectPalette.glowPrimary.copy(alpha = 0.34f),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                                    disabledContentColor = PurrfectPalette.textSecondary
                                )
                            ) {
                                Text(if (isImported) translation["imported_button"] else translation["import_button"])
                            }
                        }
                    }
                }
            }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        var scrollOffset by remember { mutableIntStateOf(0) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        var controlsHeight by remember { mutableStateOf(100.dp) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            AvailableRulesTab(
                controlsHeight = controlsHeight,
                onScrollOffsetChanged = { scrollOffset = it }
            )

            FloatingTopBar(
                title = translation["title"],
                onBack = { routes.navController.popBackStack() },
                scrollOffset = scrollOffset,
                modifier = Modifier.headerHeightTracker { controlsHeight = it },
                actions = {
                    IconButton(onClick = { routes.manageFriendTrackerRepos.navigate() }) {
                        Icon(Icons.Default.Public, contentDescription = translation["manage_repos_description"], tint = Color.White)
                    }
                }
            )
        }
    }
}
