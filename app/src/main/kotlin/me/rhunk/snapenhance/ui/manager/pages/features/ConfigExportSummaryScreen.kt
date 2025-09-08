package me.rhunk.snapenhance.ui.manager.pages.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import me.rhunk.snapenhance.common.config.ConfigContainer
import me.rhunk.snapenhance.common.config.PropertyPair
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.util.saveFile

class ConfigExportSummaryScreen : Routes.Route() {

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val exportSensitiveData = it.arguments?.getString("exportSensitiveData")?.toBoolean() ?: false
        val tempConfig = remember {
            me.rhunk.snapenhance.common.config.RootConfig().also {
                it.loadFromString(context.config.exportToString(true))
            }
        }
        var isInEditMode by remember { mutableStateOf(false) }
        var editingProperty by remember { mutableStateOf<PropertyPair<*>?>(null) }

        if (editingProperty != null) {
            PropertyDialog(
                property = editingProperty!!,
                routes = routes,
                onDismiss = { editingProperty = null }
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
                        if (isInEditMode) {
                            IconButton(onClick = { isInEditMode = false }) {
                                Icon(Icons.Default.Done, contentDescription = "Apply Changes")
                            }
                        } else {
                            IconButton(onClick = { isInEditMode = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            TextButton(onClick = {
                                routes.activityLauncher.saveFile("config.json", "application/json") { uri ->
                                    runCatching {
                                        context.androidContext.contentResolver.openOutputStream(android.net.Uri.parse(uri))?.use {
                                            it.write(tempConfig.exportToString(exportSensitiveData).toByteArray())
                                            context.shortToast(context.translation["manager.sections.features.config_export_success_toast"])
                                        }
                                    }.onFailure {
                                        context.longToast(context.translation.format("manager.sections.features.config_export_failure_toast", "error" to it.message.toString()))
                                    }
                                }
                            }) {
                                Text("Save")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val properties = remember(exportSensitiveData, tempConfig) {
                val configStr = tempConfig.exportToString(exportSensitiveData)
                val config = me.rhunk.snapenhance.common.config.RootConfig().also {
                    it.loadFromString(configStr)
                }
                config.properties.flatMap { it.value.get<ConfigContainer>().properties }.map { PropertyPair(it.key, it.value) }
            }
            Box(modifier = Modifier.padding(padding)) {
                PropertiesView(
                    properties = properties,
                    routes = routes,
                    isInEditMode = isInEditMode,
                    onEdit = { editingProperty = it }
                )
            }
        }
    }
}
