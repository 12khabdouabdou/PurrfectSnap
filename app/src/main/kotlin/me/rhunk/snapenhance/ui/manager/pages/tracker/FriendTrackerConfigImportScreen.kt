package me.rhunk.snapenhance.ui.manager.pages.tracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.data.ExportedTrackerData
import me.rhunk.snapenhance.ui.manager.Routes
import org.json.JSONArray

class FriendTrackerConfigImportScreen : Routes.Route() {
    private data class ImportedFeature(
        val category: String,
        val name: String,
        val key: String,
        val value: Any,
        val indentation: Int
    )

    private inner class ConfigParser {
        fun parse(configJson: String): Map<String, List<ImportedFeature>> {
            val featureList = mutableListOf<ImportedFeature>()
            val exportedData = context.gson.fromJson(configJson, ExportedTrackerData::class.java)
            exportedData.rules.forEach { rule ->
                featureList.add(ImportedFeature("Friend Tracker Rules", rule.name, rule.id.toString(), rule.enabled, 0))
            }
            return featureList.groupBy { it.category }
        }

        fun parseValue(featureKey: String, value: Any): Any {
            return when (value) {
                is Boolean -> if (value) "Enabled" else "Disabled"
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        list.add(value.get(i).toString())
                    }
                    list
                }
                else -> value.toString()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val configJson = routes.friendTrackerConfigJsonForImport ?: ""
        val parser = remember { ConfigParser() }
        val featuresByCategory = remember {
            parser.parse(configJson)
        }
        val expandedState = remember { mutableStateMapOf<String, Boolean>() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Import Friend Tracker Rules") },
                    navigationIcon = {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            runCatching {
                                val trackerData = context.gson.fromJson(configJson, ExportedTrackerData::class.java)
                                context.trackerDataManager.importTrackerData(trackerData)
                            }.onSuccess {
                                context.shortToast("Friend Tracker Rules Imported!")
                                context.coroutineScope.launch(Dispatchers.Main) {
                                    routes.friendTracker.navigate()
                                }
                            }.onFailure {
                                context.longToast("Failed to import rules: ${it.message}")
                            }
                        }) {
                            Text("Confirm")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                featuresByCategory.forEach { (category, features) ->
                    item {
                        Text(
                            text = category,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(features) { feature ->
                        when (val parsedValue = parser.parseValue(feature.key, feature.value)) {
                            is String -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = feature.name,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = parsedValue,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}
