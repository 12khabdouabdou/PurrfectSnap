package me.eternal.purrfectsnap.ui.manager.pages.features

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.config.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.eternal.purrfectsnap.common.ui.TopBarActionButton
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.rememberRouteLazyListState
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class FeaturesRootSection : Routes.Route() {
    override val title: @Composable (() -> Unit)? = @Composable {
        val navBackStackEntry by routes.navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val titleText = when (currentDestination?.route) {
            FEATURE_CONTAINER_ROUTE -> {
                navBackStackEntry?.arguments?.getString("name")?.let { containerName ->
                    allContainers[containerName]?.let {
                        context.translation[it.key.propertyName()]
                    }
                } ?: routeInfo.translatedKey?.value
            }
            SEARCH_FEATURE_ROUTE -> {
                translation["search_button"]
            }
            else -> {
                routeInfo.translatedKey?.value
            }
        }
        Text(titleText ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    private val alertDialogs by lazy { AlertDialogs(context.translation) }
    private val gson by lazy { Gson() }
    private val listTypeToken = object : TypeToken<List<String>>() {}.type

    companion object {
        const val FEATURE_CONTAINER_ROUTE = "feature_container/{name}"
        const val SEARCH_FEATURE_ROUTE = "search_feature/{keyword}"
    }

    private val allContainers by lazy {
        val containers = mutableMapOf<String, PropertyPair<*>>()
        fun queryContainerRecursive(container: ConfigContainer) {
            container.properties.forEach {
                if (
                    it.key.dataType.type == DataProcessors.Type.CONTAINER &&
                    !it.key.params.flags.contains(ConfigFlag.HIDDEN)
                ) {
                    containers[it.key.name] = PropertyPair(it.key, it.value)
                    queryContainerRecursive(it.value.get() as ConfigContainer)
                }
            }
        }
        queryContainerRecursive(context.config.root)
        containers
    }

    private val allProperties by lazy {
        val properties = mutableMapOf<PropertyKey<*>, PropertyValue<*>>()
        allContainers.values.forEach {
            val container = it.value.get() as ConfigContainer
            container.properties.forEach { property ->
                properties[property.key] = property.value
            }
        }
        properties
    }

    private fun isSearchVisibleProperty(propertyKey: PropertyKey<*>): Boolean {
        return !propertyKey.params.flags.contains(ConfigFlag.HIDDEN)
    }

    private data class SearchEntry(val keyword: String, val tokens: List<String>)

    private fun buildSearchEntries(): List<SearchEntry> {
        return allProperties.keys.mapNotNull { key ->
            if (!isSearchVisibleProperty(key)) return@mapNotNull null
            val name = context.translation[key.propertyName()]
            val description = context.translation[key.propertyDescription()]
            val tokens = listOfNotNull(name, description, key.name).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) null else SearchEntry(keyword = name ?: key.name, tokens = tokens)
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[b.length]
    }

    private fun similarityScore(query: String, target: String): Float {
        val q = query.lowercase()
        val t = target.lowercase()
        val maxLen = max(q.length, t.length)
        if (maxLen == 0) return 1f
        val dist = levenshtein(q, t)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun fuzzySuggest(query: String, entries: List<SearchEntry>): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return entries.map { entry ->
            val best = entry.tokens.maxOfOrNull { similarityScore(q, it) } ?: 0f
            best to entry.keyword
        }.filter { it.first >= 0.45f }
            .sortedWith(compareByDescending<Pair<Float, String>> { it.first }.thenBy { it.second.length })
            .map { it.second }
            .distinct()
            .take(6)
    }

    private fun loadSearchHistory(): List<String> {
        return context.sharedPreferences
            .getString("features_search_history", "") // stored as | separated
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun saveSearchHistory(history: List<String>) {
        context.sharedPreferences.edit()
            .putString("features_search_history", history.joinToString("|"))
            .apply()
    }

    private fun upsertHistory(term: String, history: SnapshotStateList<String>) {
        val cleaned = term.trim()
        if (cleaned.isEmpty()) return
        val existingIndex = history.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
        if (existingIndex >= 0) history.removeAt(existingIndex)
        history.add(0, cleaned)
        while (history.size > 12) history.removeLast()
        saveSearchHistory(history)
    }
    
    private fun navigateToMainRoot() {
        routes.navController.navigate(routeInfo.id, NavOptions.Builder()
            .setPopUpTo(routes.navController.graph.findStartDestination().id, false)
            .setLaunchSingleTop(true)
            .build()
        )
    }

    private fun activityLauncher(block: ActivityLauncherHelper.() -> Unit) {
        routes.activityLauncher.let(block)
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        Container(
            configContainer = context.config.root,
            stateKey = "${routeInfo.id}:root"
        )
    }

    override val customComposables: NavGraphBuilder.() -> Unit = {
        routeInfo.childIds.addAll(listOf(FEATURE_CONTAINER_ROUTE, SEARCH_FEATURE_ROUTE))

        composable(FEATURE_CONTAINER_ROUTE, enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(140)
            )
        }, exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(160)
            )
        }) { backStackEntry ->
            backStackEntry.arguments?.getString("name")?.let { containerName ->
                allContainers[containerName]?.let {
                    val containerTitle = translation[it.key.propertyName()]
                    val containerSubtitle = translation[it.key.propertyDescription()]
                    Container(
                        configContainer = it.value.get() as ConfigContainer,
                        stateKey = "${routeInfo.id}:container:$containerName",
                        sectionTitle = containerTitle,
                        sectionSubtitle = containerSubtitle,
                        onBack = { routes.navController.popBackStack() }
                    )
                }
            }
        }

        composable(SEARCH_FEATURE_ROUTE) { backStackEntry ->
            backStackEntry.arguments?.getString("keyword")?.let { keyword ->
                val properties = allProperties.filter {
                    isSearchVisibleProperty(it.key) && (
                        it.key.name.contains(keyword, ignoreCase = true) ||
                            context.translation[it.key.propertyName()].contains(keyword, ignoreCase = true) ||
                            context.translation[it.key.propertyDescription()].contains(keyword, ignoreCase = true)
                    )
                }.map { PropertyPair(it.key, it.value) }

                PropertiesView(
                    properties = properties,
                    stateKey = "${routeInfo.id}:search:$keyword",
                    isSearchResults = true,
                    searchKeyword = keyword,
                    enableGlobalSearch = true,
                    onBack = { navigateToMainRoot() }
                )
            }
        }
    }

    @Composable
    private fun FeatureAuroraBackdrop(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        )
    }

    @Composable
    private fun NoticeBadge(text: String, color: Color) {
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.16f),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
        ) {
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }

    @Composable
    private fun EmptyState(isSearchResults: Boolean) {
        val shape = RoundedCornerShape(24.dp)
        val border = Brush.linearGradient(
            listOf(
                PurrfectPalette.glowPrimary.copy(alpha = 0.45f),
                PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
            )
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            shape = shape,
            color = Color.White.copy(alpha = 0.04f),
            tonalElevation = 10.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, border)
        ) {
            Box(modifier = Modifier.background(PurrfectPalette.cardOverlay, shape)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = PurrfectPalette.glowPrimary.copy(alpha = 0.18f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = Color.White
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isSearchResults) "No matches found" else "Nothing to show",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (isSearchResults) "Try a different keyword or clear filters." else "Toggle visibility with the new controls above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PurrfectPalette.textSecondary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PropertyAction(property: PropertyPair<*>, registerClickCallback: RegisterClickCallback) {
        var showDialog by remember { mutableStateOf(false) }
        var dialogComposable by remember { mutableStateOf<@Composable () -> Unit>({}) }

        fun registerDialogOnClickCallback() = registerClickCallback { showDialog = true }

        if (showDialog) {
            Dialog(
                properties = DialogProperties(
                    usePlatformDefaultWidth = false
                ),
                onDismissRequest = { showDialog = false },
            ) {
                dialogComposable()
            }
        }

        val propertyValue = property.value
        fun persistConfig() = context.config.writeConfig()

        if (property.key.params.flags.contains(ConfigFlag.USER_IMPORT)) {
            registerDialogOnClickCallback()
            dialogComposable = {
                var isEmpty by remember { mutableStateOf(false) }
                val files = rememberAsyncMutableStateList(defaultValue = listOf()) {
                    context.fileHandleManager.getStoredFiles {
                        property.key.params.filenameFilter?.invoke(it.name) == true
                    }.also {
                        isEmpty = it.isEmpty()
                        if (isEmpty) {
                            propertyValue.setAny(null)
                            persistConfig()
                        }
                    }
                }
                var selectedFile by remember(files.size) { mutableStateOf(files.firstOrNull { it.name == propertyValue.getNullable() }.also {
                    if (files.isNotEmpty() && it == null) propertyValue.setAny(null)
                    if (files.isNotEmpty() && it == null) persistConfig()
                }?.name) }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
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
                    Box(
                        modifier = Modifier
                            .background(PurrfectPalette.cardOverlay, RoundedCornerShape(24.dp))
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = context.translation["manager.dialogs.file_imports.settings_select_file_hint"],
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    if (isEmpty) {
                                        Text(
                                            text = context.translation["manager.dialogs.file_imports.no_files_settings_hint"],
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = PurrfectPalette.textSecondary,
                                            modifier = Modifier.padding(top = 4.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    } else {
                                        Text(
                                            text = translation["manager.dialogs.file_imports.settings_select_file_subtitle"]
                                                ?: translation["manager.dialogs.file_imports.settings_select_file_hint"],
                                            fontSize = 13.sp,
                                            color = PurrfectPalette.textSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            items(files, key = { it.name }) { file ->
                                val isSelected = selectedFile == file.name
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedFile = if (isSelected) null else file.name
                                            propertyValue.setAny(selectedFile)
                                            persistConfig()
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color.White.copy(alpha = 0.05f),
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Brush.linearGradient(
                                            listOf(
                                                PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
                                                PurrfectPalette.glowSecondary.copy(alpha = 0.48f)
                                            )
                                        ) else SolidColor(Color.White.copy(alpha = 0.08f))
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = PurrfectPalette.glowPrimary.copy(alpha = 0.16f)
                                        ) {
                                            Icon(
                                                Icons.Filled.AttachFile,
                                                contentDescription = null,
                                                modifier = Modifier.padding(10.dp),
                                                tint = Color.White
                                            )
                                        }
                                        Text(
                                            text = file.name,
                                            modifier = Modifier.weight(1f),
                                            fontSize = 14.sp,
                                            lineHeight = 16.sp,
                                            color = Color.White
                                        )
                                        if (isSelected) {
                                            Surface(
                                                shape = CircleShape,
                                                color = PurrfectPalette.glowSecondary.copy(alpha = 0.18f)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(8.dp),
                                                    tint = PurrfectPalette.glowSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Icon(Icons.Filled.AttachFile, contentDescription = null)
            return
        }

        if (property.key.params.flags.contains(ConfigFlag.FOLDER)) {
            IconButton(onClick = registerClickCallback {
                activityLauncher {
                    chooseFolder { uri ->
                        propertyValue.setAny(uri)
                        persistConfig()
                    }
                }
            }.let { { it.invoke(true) } }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
            }
            return
        }

        when (val dataType = remember { property.key.dataType.type }) {
            DataProcessors.Type.BOOLEAN -> {
                var state by remember { mutableStateOf(propertyValue.get() as Boolean) }
                val hapticFeedback = LocalHapticFeedback.current
                Switch(
                    checked = state,
                    onCheckedChange = registerClickCallback {
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        state = state.not()
                        propertyValue.setAny(state)
                        persistConfig()
                    },
                    colors = purrfectSwitchColors()
                )
            }

            DataProcessors.Type.MAP_COORDINATES -> {
                registerDialogOnClickCallback()
                dialogComposable = {
                    alertDialogs.ChooseLocationDialog(property) {
                        showDialog = false
                    }
                }

                Text(
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.widthIn(0.dp, 120.dp),
                    text = (propertyValue.get() as Pair<*, *>).let {
                        "${it.first.toString().toFloatOrNull() ?: 0F}, ${it.second.toString().toFloatOrNull() ?: 0F}"
                    }
                )
            }

            DataProcessors.Type.STRING_UNIQUE_SELECTION -> {
                registerDialogOnClickCallback()

                dialogComposable = {
                    alertDialogs.UniqueSelectionDialog(property)
                }

                Text(
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.widthIn(0.dp, 120.dp),
                    text = (propertyValue.getNullable() as? String ?: "null").let {
                        property.key.propertyOption(context.translation, it)
                    }
                )
            }

            DataProcessors.Type.STRING_MULTIPLE_SELECTION, DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> {
                dialogComposable = {
                    when (dataType) {
                        DataProcessors.Type.STRING_MULTIPLE_SELECTION -> {
                            alertDialogs.MultipleSelectionDialog(property)
                        }
                        DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> {
                            // Check if this is a message list property
                            val isMessageListProperty = property.key.name.endsWith("_messages")
                            if (isMessageListProperty) {
                                alertDialogs.MessageListPropertyDialog(property) { showDialog = false }
                            } else {
                                alertDialogs.KeyboardInputDialog(property) { showDialog = false }
                            }
                        }
                        else -> {}
                    }
                }

                registerDialogOnClickCallback().let { { it.invoke(true) } }.also {
                    if (dataType == DataProcessors.Type.INTEGER ||
                        dataType == DataProcessors.Type.FLOAT) {
                        ValueGlowChip(
                            text = propertyValue.get().toString(),
                            onClick = it
                        )
                    } else {
                        // Check if this is a message list property
                        val isMessageListProperty = property.key.name.endsWith("_messages")
                        if (isMessageListProperty) {
                            // Show message count
                            val messageCount = try {
                                val messageList: List<String> = gson.fromJson(propertyValue.get().toString(), listTypeToken) ?: emptyList()
                                messageList.size
                            } catch (e: Exception) {
                                1
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White.copy(alpha = 0.06f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            ) {
                                Text(
                                    text = translation.format("search_results_count", "count" to messageCount.toString()),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        } else {
                            IconButton(onClick = it) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            }
                        }
                    }
                }
            }

            DataProcessors.Type.INT_COLOR -> {
                dialogComposable = {
                    alertDialogs.ColorPickerPropertyDialog(property) {
                        showDialog = false
                    }
                }

                registerDialogOnClickCallback().let { { it.invoke(true) } }.also {
                    CircularAlphaTile(selectedColor = (propertyValue.getNullable() as? Int)?.let { Color(it) })
                }
            }

            DataProcessors.Type.CONTAINER -> {
                val container = propertyValue.get() as ConfigContainer

                registerClickCallback {
                    routes.navController.navigate(FEATURE_CONTAINER_ROUTE.replace("{name}", property.name))
                }

                if (!container.hasGlobalState) return

                var state by remember { mutableStateOf(container.globalState ?: false) }

                Box(
                    modifier = Modifier
                        .padding(end = 15.dp),
                ) {

                    Box(modifier = Modifier
                        .height(50.dp)
                        .width(1.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(5.dp)
                        ))
                }

                val hapticFeedback = LocalHapticFeedback.current
                Switch(
                    checked = state,
                    onCheckedChange = {
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        state = state.not()
                        container.globalState = state
                        persistConfig()
                    },
                    colors = purrfectSwitchColors()
                )
            }
        }

    }

    @Composable
    private fun ValueGlowChip(
        text: String,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                        PurrfectPalette.glowSecondary.copy(alpha = 0.45f)
                    )
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                PurrfectPalette.glowPrimary.copy(alpha = 0.28f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun PropertyCard(property: PropertyPair<*>, onOpen: (() -> Unit)? = null) {
        var clickCallback by remember { mutableStateOf<ClickCallback?>(null) }
        val noticeColorMap = remember {
            mapOf(
                FeatureNotice.UNSTABLE.key to Color(0xFFFFFB87),
                FeatureNotice.BAN_RISK.key to Color(0xFFFF8585),
                FeatureNotice.INTERNAL_BEHAVIOR.key to Color(0xFFFFFB87),
            )
        }

        val versionCheck = remember { property.key.params.versionCheck }
        val versionCheckPair = remember(property) { versionCheck?.checkVersion(context.installationSummary.snapchatInfo?.versionCode ?: return@remember null)}
        val isComponentDisabled = remember { versionCheckPair != null && versionCheck?.isDisabled == true }

        val cardShape = RoundedCornerShape(22.dp)
        val interactionSource = remember { MutableInteractionSource() }
        val density = LocalDensity.current
        val cardBorder = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        val cardBackground = remember { PurrfectPalette.cardOverlay }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .graphicsLayer { if (isComponentDisabled) alpha = 0.5f }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    onOpen?.invoke()
                    clickCallback?.invoke(true)
                }
                .scaleOnPress(interactionSource),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(cardBackground, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    property.key.params.icon?.let { icon ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = PurrfectPalette.glowPrimary.copy(alpha = 0.16f),
                            tonalElevation = 0.dp,
                            modifier = Modifier.size(62.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                PurrfectPalette.glowPrimary.copy(alpha = 0.35f),
                                                PurrfectPalette.glowSecondary.copy(alpha = 0.28f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                            Text(
                                text = context.translation[property.key.propertyName()],
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PurrfectPalette.textPrimary,
                                lineHeight = 20.sp
                            )
                            Text(
                                text = context.translation[property.key.propertyDescription()],
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = PurrfectPalette.textSecondary
                            )

                        if (property.key.params.notices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                property.key.params.notices.forEach {
                                    NoticeBadge(
                                        text = context.translation["features.notices.${it.key}"],
                                        color = noticeColorMap[it.key] ?: Color(0xFFFFFB87)
                                    )
                                }
                            }
                        }

                        if (versionCheckPair != null) {
                            NoticeBadge(
                                text = context.translation.format(
                                    "manager.sections.features.${versionCheckPair.second.key}",
                                    "version" to versionCheckPair.first.first
                                ),
                                color = Color(0xFFFF8585)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        PropertyAction(property, registerClickCallback = { callback ->
                            if (property.key.propertyTranslationPath().startsWith("rules.properties")) {
                                clickCallback = {
                                    routes.manageRuleFeature.navigate {
                                        put("rule_type", property.key.name)
                                    }
                                }
                                return@PropertyAction clickCallback!!
                            }
                            clickCallback = callback
                            callback
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun FeatureSearchBar(rowScope: RowScope, focusRequester: FocusRequester) {
        var searchValue by remember { mutableStateOf("") }
        val isOverlay = remember { context.sharedPreferences.getBoolean("overlay_active", false) }
        val scope = rememberCoroutineScope()
        var currentSearchJob by remember { mutableStateOf<Job?>(null) }
        val searchHistory = remember { mutableStateListOf<String>().apply { addAll(loadSearchHistory()) } }
        fun launchSearch(term: String, record: Boolean, delayMs: Long = 0L) {
            val keyword = term.trim()
            if (keyword.isEmpty()) {
                navigateToMainRoot()
                return
            }
            currentSearchJob?.cancel()
            currentSearchJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                if (record) upsertHistory(keyword, searchHistory)
                routes.navController.navigate(
                    SEARCH_FEATURE_ROUTE.replace("{keyword}", keyword),
                    NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setPopUpTo(routeInfo.id, false)
                        .build()
                )
            }
        }

        rowScope.apply {
            val searchBorder = remember {
                Brush.linearGradient(
                    listOf(
                        Color(0xFF8C7BFF).copy(alpha = 0.48f),
                        Color(0xFF5FD8FF).copy(alpha = 0.4f)
                    )
                )
            }
            Surface(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .weight(1f, fill = true)
                    .padding(end = 10.dp)
                    .height(62.dp),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 8.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, searchBorder),
                color = Color.Transparent
            ) {
                TextField(
                    value = searchValue,
                    onValueChange = { keyword ->
                        searchValue = keyword
                        if (keyword.isEmpty()) {
                            if (isOverlay) {
                                routes.navController.popBackStack(routeInfo.id, false)
                            }
                        } else {
                            launchSearch(keyword, record = false, delayMs = 250L)
                        }
                    },
                    keyboardActions = KeyboardActions(onDone = {
                        launchSearch(searchValue, record = true, delayMs = 0L)
                        focusRequester.freeFocus()
                    }),
                    singleLine = true,
                    leadingIcon = {
                        Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                tonalElevation = 0.dp
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp)
                )
            }
        },
        placeholder = {
            Text(
                text = translation["search_button"],
                color = Color(0xFFE0DCFF)
            )
        },
                    trailingIcon = {
                        if (searchValue.isNotEmpty()) {
                            IconButton(onClick = {
                                searchValue = ""
                                if (isOverlay) {
                                    routes.navController.popBackStack(routeInfo.id, false)
                                }
                                focusRequester.requestFocus()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        disabledTextColor = Color.White.copy(alpha = 0.65f),
                        focusedPlaceholderColor = Color(0xFFE0DCFF),
                        unfocusedPlaceholderColor = Color(0xFFE0DCFF),
                        focusedLeadingIconColor = Color.White,
                        unfocusedLeadingIconColor = Color.White.copy(alpha = 0.9f),
                        focusedTrailingIconColor = Color.White,
                        unfocusedTrailingIconColor = Color.White.copy(alpha = 0.9f)
                    )
                )
            }
        }
    }

    @Composable
    private fun SensitiveDataDialog(
        onDismiss: () -> Unit,
        onConfirm: (exportSensitiveData: Boolean, includeSavedLocations: Boolean) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            val includeSavedLocations = remember { mutableStateOf(false) }
            
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.06f),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                            PurrfectPalette.glowSecondary.copy(alpha = 0.45f)
                        )
                    )
                )
            ) {
                Box(modifier = Modifier.background(PurrfectPalette.cardOverlay)) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = context.translation["manager.dialogs.export_config.title"],
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = context.translation["manager.dialogs.export_config.content"],
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = PurrfectPalette.textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = context.translation["include_saved_locations"],
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            val hapticFeedback = LocalHapticFeedback.current
                            Switch(
                                checked = includeSavedLocations.value,
                                onCheckedChange = { 
                                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    includeSavedLocations.value = it 
                                },
                                colors = purrfectSwitchColors()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            Button(
                                onClick = { onConfirm(false, includeSavedLocations.value) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(context.translation["button.negative"])
                            }
                            Button(
                                onClick = { onConfirm(true, includeSavedLocations.value) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PurrfectPalette.glowPrimary.copy(alpha = 0.32f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(context.translation["button.positive"])
                            }
                        }
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {}

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FloatingControls(
        isSearchResults: Boolean,
        modifier: Modifier = Modifier,
        activeSectionTitle: String? = null,
        activeSectionSubtitle: String? = null,
        searchKeyword: String? = null,
        searchHistory: SnapshotStateList<String>,
        onSearchQueryChange: (String) -> Unit,
        onBack: (() -> Unit)? = null
    ) {
        var showSearchBar by rememberSaveable { mutableStateOf(isSearchResults) }
        val focusRequester = remember { FocusRequester() }
        val isOverlay = remember { context.sharedPreferences.getBoolean("overlay_active", false) }
        var searchValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(
                    text = searchKeyword.orEmpty(),
                    selection = TextRange(searchKeyword?.length ?: 0)
                )
            )
        }
        val searchEntries = remember { buildSearchEntries() }
        val combinedSuggestions by remember(searchValue.text, searchHistory, searchEntries) {
            derivedStateOf {
                val query = searchValue.text
                val historyMatches = searchHistory.filter {
                    query.isBlank() || it.contains(query, ignoreCase = true)
                }
                val fuzzy = fuzzySuggest(query, searchEntries)
                (historyMatches + fuzzy).distinct().take(6)
            }
        }

        fun updateSearch(keyword: String, record: Boolean = true) {
            val term = keyword.trim()
            onSearchQueryChange(term)
            if (record && term.isNotEmpty()) upsertHistory(term, searchHistory)
        }

        var showExportDropdownMenu by remember { mutableStateOf(false) }
        var showResetConfirmationDialog by remember { mutableStateOf(false) }
        var showExportDialog by remember { mutableStateOf(false) }

        if (showResetConfirmationDialog) {
            Dialog(onDismissRequest = { showResetConfirmationDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                                PurrfectPalette.glowSecondary.copy(alpha = 0.45f)
                            )
                        )
                    )
                ) {
                    Box(modifier = Modifier.background(PurrfectPalette.cardOverlay)) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = context.translation["manager.dialogs.reset_config.title"],
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                            Text(
                                text = context.translation["manager.dialogs.reset_config.content"],
                                style = MaterialTheme.typography.bodyMedium,
                                color = PurrfectPalette.textSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                            ) {
                                Button(
                                    onClick = { showResetConfirmationDialog = false },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(text = context.translation["button.negative"])
                                }
                                Button(
                                    onClick = {
                                        context.config.reset()
                                        context.shortToast(context.translation["manager.dialogs.reset_config.success_toast"])
                                        showResetConfirmationDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PurrfectPalette.glowPrimary.copy(alpha = 0.32f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(text = context.translation["button.positive"])
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showExportDialog) {
            SensitiveDataDialog(
                onDismiss = { showExportDialog = false },
                onConfirm = { exportSensitiveData, includeSavedLocations ->
                    showExportDialog = false
                    routes.configExportSummary.navigate {
                        put("exportSensitiveData", exportSensitiveData.toString())
                        put("includeSavedLocations", includeSavedLocations.toString())
                    }
                }
            )
        }

        val actions = remember {
            listOf(
                Triple(translation["export_option"], Icons.Filled.SaveAlt) { showExportDialog = true },
                Triple(translation["import_option"], Icons.Filled.FileDownload) {
                    activityLauncher {
                        openFile("application/json") { uri ->
                            context.androidContext.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                routes.configJsonForImport = it.readBytes().toString(Charsets.UTF_8)
                                routes.navController.navigate(Routes.CONFIG_IMPORT_CONFIRMATION_ROUTE)
                            }
                        }
                    }
                },
                Triple(translation["reset_option"], Icons.Filled.Refresh) { showResetConfirmationDialog = true }
            )
        }

        val topBarShape = RoundedCornerShape(26.dp)
        val topBarBackground = remember { PurrfectPalette.cardOverlay }
        val topBarBorder = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.6f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.52f)
                )
            )
        }

        val headerTitle = activeSectionTitle ?: translation["manager.routes.features"]
        val subtitleText = when {
            isSearchResults -> translation["search_button"]
            !activeSectionSubtitle.isNullOrBlank() -> activeSectionSubtitle
            else -> translation["manager.sections.features.subtitle"] ?: ""
        }

        LaunchedEffect(isSearchResults, searchKeyword) {
            if (isSearchResults || !searchKeyword.isNullOrBlank()) {
                showSearchBar = true
                if (!searchKeyword.isNullOrBlank()) {
                    val text = searchKeyword
                    searchValue = TextFieldValue(
                        text = text,
                        selection = TextRange(text.length)
                    )
                }
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 0.dp)
                .zIndex(1f)
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                shape = topBarShape,
                color = PurrfectPalette.cardOverlayColor,
                border = BorderStroke(1.dp, topBarBorder),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(topBarShape)
                            .background(topBarBackground)
                    )
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (onBack != null) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = context.translation["common.back"],
                                        tint = Color.White
                                    )
                                }
                            }

                            if (showSearchBar) {
                                TextField(
                                    value = searchValue,
                                    onValueChange = { keywordValue ->
                                        searchValue = keywordValue
                                        if (keywordValue.text.isEmpty()) {
                                            updateSearch("", record = false)
                                        } else {
                                            updateSearch(keywordValue.text, record = false)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester),
                                    singleLine = true,
                                    placeholder = { Text(text = translation["search_button"], color = Color(0xFFE0DCFF)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchValue.text.isNotEmpty()) {
                                            IconButton(onClick = {
                                                searchValue = TextFieldValue("", TextRange(0))
                                                updateSearch("", record = false)
                                                if (isSearchResults) {
                                                    if (isOverlay) {
                                                        routes.navController.popBackStack(routeInfo.id, false)
                                                    }
                                                } else {
                                                    showSearchBar = false
                                                }
                                            }) {
                                                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                                            }
                                        }
                                    },
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            updateSearch(searchValue.text, record = true)
                                        }
                                    ),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = Color.White,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        disabledTextColor = Color.White.copy(alpha = 0.65f),
                                        focusedPlaceholderColor = Color(0xFFE0DCFF),
                                        unfocusedPlaceholderColor = Color(0xFFE0DCFF),
                                        focusedLeadingIconColor = Color.White,
                                        unfocusedLeadingIconColor = Color.White.copy(alpha = 0.9f),
                                        focusedTrailingIconColor = Color.White,
                                        unfocusedTrailingIconColor = Color.White.copy(alpha = 0.9f)
                                    )
                                )
                                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            } else {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = headerTitle ?: "",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        text = subtitleText,
                                        color = Color(0xFFCEC8FF),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (!showSearchBar || searchValue.text.isEmpty()) {
                                IconButton(onClick = {
                                    if (showSearchBar) {
                                        searchValue = TextFieldValue("", TextRange(0))
                                        if (isOverlay) {
                                            routes.navController.popBackStack(routeInfo.id, false)
                                        }
                                    }
                                    showSearchBar = !showSearchBar
                                }) {
                                    Icon(
                                        imageVector = if (showSearchBar) Icons.Filled.Close else Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }

                            if (context.activity != null) {
                                Box {
                                    IconButton(onClick = { showExportDropdownMenu = !showExportDropdownMenu }) {
                                        Icon(
                                            imageVector = Icons.Filled.MoreVert,
                                            contentDescription = null,
                                            tint = PurrfectPalette.glowSecondary
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showExportDropdownMenu,
                                        onDismissRequest = { showExportDropdownMenu = false },
                                        offset = DpOffset(0.dp, 8.dp),
                                        containerColor = Color(0xFF161821),
                                        shape = RoundedCornerShape(14.dp),
                                        tonalElevation = 8.dp,
                                        shadowElevation = 12.dp
                                    ) {
                                        actions.forEach { (name, icon, action) ->
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = PurrfectPalette.glowPrimary
                                                    )
                                                },
                                                text = { Text(text = name, color = Color.White) },
                                                onClick = {
                                                    action()
                                                    showExportDropdownMenu = false
                                                },
                                                colors = MenuDefaults.itemColors(
                                                    textColor = Color.White,
                                                    leadingIconColor = PurrfectPalette.glowPrimary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showSearchBar && combinedSuggestions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                combinedSuggestions.forEach { suggestion ->
                                    AssistChip(
                                        onClick = {
                                            searchValue = TextFieldValue(suggestion, TextRange(suggestion.length))
                                            updateSearch(suggestion, record = true)
                                        },
                                        label = { Text(text = suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            labelColor = Color.White,
                                            leadingIconContentColor = Color.White
                                        )
                                    )
                                }
                            }
                            if (searchHistory.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            searchHistory.clear()
                                            saveSearchHistory(emptyList())
                                        }
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
                                        Spacer(Modifier.width(6.dp))
                                        Text(text = translation["clear_history"], color = Color.White)
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
    private fun PropertiesView(
        properties: List<PropertyPair<*>>,
        stateKey: String,
        isSearchResults: Boolean = false,
        activeSectionTitle: String? = null,
        activeSectionSubtitle: String? = null,
        searchKeyword: String? = null,
        enableGlobalSearch: Boolean = false,
        onBack: (() -> Unit)? = null
    ) {
        val density = LocalDensity.current
        var controlsHeight by remember { mutableStateOf(96.dp) }
        val listState = rememberRouteLazyListState(stateKey)
        val sharedSearchHistory = remember { mutableStateListOf<String>().apply { addAll(loadSearchHistory()) } }
        var liveSearchQuery by rememberSaveable { mutableStateOf(searchKeyword.orEmpty()) }
        val isActiveSearch = isSearchResults || liveSearchQuery.isNotBlank()
        val globalSearchProperties = remember(enableGlobalSearch) {
            if (enableGlobalSearch) {
                allProperties.filter { isSearchVisibleProperty(it.key) }.map { PropertyPair(it.key, it.value) }
            } else {
                emptyList()
            }
        }
        val displayProperties = remember(properties, globalSearchProperties, liveSearchQuery, enableGlobalSearch) {
            val query = liveSearchQuery
            if (query.isBlank()) return@remember properties
            val candidates = if (enableGlobalSearch) (properties + globalSearchProperties) else properties
            candidates.filter {
                it.key.name.contains(query, ignoreCase = true) ||
                    context.translation[it.key.propertyName()].contains(query, ignoreCase = true) ||
                    context.translation[it.key.propertyDescription()].contains(query, ignoreCase = true)
            }
        }

        LaunchedEffect(searchKeyword) {
            if (searchKeyword != null && searchKeyword != liveSearchQuery) {
                liveSearchQuery = searchKeyword
            }
        }
        LaunchedEffect(liveSearchQuery) {
            if (!listState.isScrollInProgress) {
                listState.scrollToItem(0)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            FeatureAuroraBackdrop()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 6.dp,
                    top = controlsHeight + 12.dp,
                    bottom = routes.bottomPadding
                )
            ) {
                if (displayProperties.isEmpty()) {
                    item { EmptyState(isActiveSearch) }
                } else {
                    itemsIndexed(displayProperties, key = { _, item -> item.key.propertyName() }) { _, item ->
                        val onOpen = if (isActiveSearch && liveSearchQuery.isNotBlank()) {
                            {
                                upsertHistory(liveSearchQuery, sharedSearchHistory)
                            }
                        } else null
                        PropertyCard(item, onOpen = onOpen)
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
            FloatingControls(
                isSearchResults = isActiveSearch,
                activeSectionTitle = activeSectionTitle,
                activeSectionSubtitle = activeSectionSubtitle,
                searchKeyword = liveSearchQuery,
                searchHistory = sharedSearchHistory,
                onSearchQueryChange = { liveSearchQuery = it },
                onBack = onBack,
                modifier = Modifier.onGloballyPositioned {
                    val newHeight = with(density) { it.size.height.toDp() }
                    if (newHeight != controlsHeight) {
                        controlsHeight = newHeight
                    }
                }
            )
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {
        fun saveConfig() {
            context.coroutineScope.launch(Dispatchers.IO) {
                context.config.writeConfig()
                context.log.verbose("saved config!")
            }
        }

        OnLifecycleEvent { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveConfig()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                saveConfig()
            }
        }
    }



    @Composable
    private fun Container(
        configContainer: ConfigContainer,
        stateKey: String,
        sectionTitle: String? = null,
        sectionSubtitle: String? = null,
        searchKeyword: String? = null,
        onBack: (() -> Unit)? = null,
    ) {
        PropertiesView(
            properties = remember {
                configContainer.properties.map { PropertyPair(it.key, it.value) }.filter {
                    !it.key.params.flags.contains(ConfigFlag.HIDDEN)
                }
            },
            stateKey = stateKey,
            activeSectionTitle = sectionTitle,
            activeSectionSubtitle = sectionSubtitle,
            searchKeyword = searchKeyword,
            enableGlobalSearch = configContainer == context.config.root,
            onBack = onBack
        )
    }
}
