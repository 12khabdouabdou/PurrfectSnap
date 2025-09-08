package me.rhunk.snapenhance.ui.manager.pages.features

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.util.saveFile
import org.json.JSONArray
import org.json.JSONObject

class ConfigExportSummaryScreen : Routes.Route() {
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
            val json = JSONObject(configJson)

            fun parseProperties(categoryKey: String, niceCategoryName: String, properties: JSONObject, prefix: String, indent: Int) {
                for (key in properties.keys()) {
                    val value = properties.get(key)
                    val currentPrefix = if (prefix.isEmpty()) key else "$prefix.$key"

                    if (value is JSONObject && value.has("state") && value.has("properties")) {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        val featureName = context.translation[featureNameKey] ?: key
                        featureList.add(ImportedFeature(niceCategoryName, featureName, key, value.getBoolean("state"), indent))
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent + 1)
                    } else if (value is JSONObject && value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent)
                    }
                    else {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        val featureName = context.translation[featureNameKey] ?: key
                        featureList.add(ImportedFeature(niceCategoryName, featureName, key, value, indent))
                    }
                }
            }

            for (categoryKey in json.keys()) {
                val value = json.get(categoryKey)
                if (value is JSONObject) {
                    val niceCategoryName = context.translation["features.properties.$categoryKey.name"] ?: categoryKey.replaceFirstChar { it.uppercase() }
                    if (value.has("state") && !value.has("properties")) {
                        featureList.add(ImportedFeature(niceCategoryName, "Enable Feature", categoryKey, value.getBoolean("state"), 0))
                    } else if (value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), "", 0)
                    }
                }
            }
            return featureList.groupBy { it.category }
        }

        fun parseValue(featureKey: String, value: Any): Any {
            fun innerParse(v: Any): String {
                if (v is String) {
                    val translationKey = "features.options.$featureKey.$v"
                    val translated = context.translation[translationKey]
                    if (translated != null && translated != translationKey) {
                        return translated
                    }
                }
                return v.toString()
            }

            return when (value) {
                is Boolean -> if (value) "Enabled" else "Disabled"
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        list.add(innerParse(value.get(i)))
                    }
                    list
                }
                else -> innerParse(value)
            }
        }
    }

    @Composable
    private fun SensitiveDataDialog(
        onDismiss: () -> Unit,
        onConfirm: (exportSensitiveData: Boolean) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Export Sensitive Data?",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = "Do you want to export the config with sensitive data? (Such as location coordinates, etc.)",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { onConfirm(false) }) {
                            Text("No")
                        }
                        TextButton(onClick = { onConfirm(true) }) {
                            Text("Yes")
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val parser = remember { ConfigParser() }
        val featuresByCategory = remember {
            parser.parse(context.config.exportToString(true))
        }
        val expandedState = remember { mutableStateMapOf<String, Boolean>() }
        var showSensitiveDataDialog by remember { mutableStateOf(false) }

        if (showSensitiveDataDialog) {
            SensitiveDataDialog(
                onDismiss = { showSensitiveDataDialog = false },
                onConfirm = { exportSensitiveData ->
                    showSensitiveDataDialog = false
                    routes.activityLauncher.saveFile("config.json", "application/json") { uri ->
                        runCatching {
                            context.androidContext.contentResolver.openOutputStream(android.net.Uri.parse(uri))?.use {
                                context.config.writeConfig()
                                context.config.exportToString(exportSensitiveData).byteInputStream().copyTo(it)
                                context.shortToast(context.translation["manager.sections.features.config_export_success_toast"])
                            }
                        }.onFailure {
                            context.longToast(context.translation.format("manager.sections.features.config_export_failure_toast", "error" to it.message.toString()))
                        }
                    }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Export Summary") },
                    navigationIcon = {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSensitiveDataDialog = true }) {
                            Text("Save")
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
                    val isExpanded = expandedState[category] ?: false
                    val rotationState by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f)

                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { expandedState[category] = !isExpanded },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = category,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { expandedState[category] = !isExpanded }) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        modifier = Modifier.graphicsLayer(rotationZ = rotationState)
                                    )
                                }
                            }
                            AnimatedVisibility(visible = isExpanded) {
                                Column {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    features.forEachIndexed { index, feature ->
                                        when (val parsedValue = parser.parseValue(feature.key, feature.value)) {
                                            is List<*> -> {
                                                Column(modifier = Modifier.padding(start = (feature.indentation * 16).dp, top = 4.dp, bottom = 4.dp)) {
                                                    Text(
                                                        text = feature.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Column(modifier = Modifier.padding(start = 16.dp)) {
                                                        parsedValue.forEach { item ->
                                                            Row {
                                                                Text(
                                                                    text = "•",
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.padding(end = 8.dp)
                                                                )
                                                                Text(
                                                                    text = item.toString(),
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            is String -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp)
                                                        .padding(start = (feature.indentation * 16).dp),
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
    }
}
