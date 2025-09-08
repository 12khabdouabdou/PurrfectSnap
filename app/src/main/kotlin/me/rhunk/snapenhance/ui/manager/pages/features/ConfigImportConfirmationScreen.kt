package me.rhunk.snapenhance.ui.manager.pages.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.ui.manager.Routes
import org.json.JSONArray
import org.json.JSONObject

class ConfigImportConfirmationScreen : Routes.Route() {
    private data class ImportedFeature(
        val category: String,
        val categoryKey: String,
        val name: String,
        val key: String,
        val value: Any,
        val indentation: Int
    )

    private inner class ConfigParser {
        fun parse(configJson: String): Map<String, List<ImportedFeature>> {
            val featureList = mutableListOf<ImportedFeature>()
            val json = JSONObject(configJson)

            fun parseProperties(categoryKey: String, niceCategoryName: String, properties: JSONObject, prefix: String, indent: Int) {
                for (key in properties.keys()) {
                    val value = properties.get(key)
                    val currentPrefix = if (prefix.isEmpty()) key else "$prefix.$key"

                    if (value is JSONObject && value.has("state") && value.has("properties")) {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        val featureName = context.translation[featureNameKey] ?: key
                        featureList.add(ImportedFeature(niceCategoryName, categoryKey, featureName, key, value.getBoolean("state"), indent))
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent + 1)
                    } else if (value is JSONObject && value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent)
                    }
                    else {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        val featureName = context.translation[featureNameKey] ?: key
                        featureList.add(ImportedFeature(niceCategoryName, categoryKey, featureName, key, value, indent))
                    }
                }
            }

            for (categoryKey in json.keys()) {
                val value = json.get(categoryKey)
                if (value is JSONObject) {
                    val niceCategoryName = context.translation["features.properties.$categoryKey.name"] ?: categoryKey.replaceFirstChar { it.uppercase() }
                    if (value.has("state") && !value.has("properties")) {
                        featureList.add(ImportedFeature(niceCategoryName, categoryKey, "Enable Feature", categoryKey, value.getBoolean("state"), 0))
                    } else if (value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), "", 0)
                    }
                }
            }
            return featureList.groupBy { it.category }
        }

        fun parseValue(categoryKey: String, featureKey: String, value: Any): String {
            fun innerParse(v: Any): String {
                if (v is String) {
                    val translationKey = "features.options.$categoryKey.$featureKey.$v"
                    val translated = context.translation[translationKey]
                    if (translated != null && translated != translationKey) {
                        return translated
                    }
                }
                return when (v) {
                    is Boolean -> if (v) "Enabled" else "Disabled"
                    is JSONArray -> (0 until v.length()).joinToString(", ") {
                        innerParse(v.get(it))
                    }
                    else -> v.toString()
                }
            }
            return innerParse(value)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val parser = remember { ConfigParser() }
        val featuresByCategory = remember {
            routes.configJsonForImport?.let { parser.parse(it) } ?: emptyMap()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Confirm Import") },
                    navigationIcon = {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            routes.configJsonForImport?.let {
                                runCatching {
                                    context.config.loadFromString(it)
                                }.onFailure {
                                    context.longToast(context.translation.format("config_import_failure_toast", "error" to it.message.toString()))
                                    return@TextButton
                                }
                                context.shortToast(context.translation["config_import_success_toast"])
                                context.coroutineScope.launch(Dispatchers.Main) {
                                    routes.features.navigateReload()
                                }
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
                items(featuresByCategory.toList()) { (category, features) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = category,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            features.forEachIndexed { index, feature ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).padding(start = (feature.indentation * 16).dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = feature.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = parser.parseValue(feature.categoryKey, feature.key, feature.value),
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.End
                                    )
                                }
                                if (index < features.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
