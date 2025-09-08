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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.config.ConfigContainer
import me.rhunk.snapenhance.common.config.PropertyPair
import me.rhunk.snapenhance.ui.manager.Routes

class ConfigImportConfirmationScreen : Routes.Route() {

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = {
        val tempConfig = remember {
            me.rhunk.snapenhance.common.config.RootConfig().also {
                it.loadFromString(routes.configJsonForImport ?: "{}")
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
                    title = { Text("Confirm Import") },
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
                                runCatching {
                                    context.config.loadFromString(tempConfig.exportToString(true))
                                }.onFailure {
                                    context.longToast(context.translation.format("config_import_failure_toast", "error" to it.message.toString()))
                                    return@TextButton
                                }
                                context.shortToast("Config Imported!")
                                context.coroutineScope.launch(Dispatchers.Main) {
                                    routes.features.navigateReload()
                                }
                            }) {
                                Text("Confirm")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val properties = remember {
                tempConfig.properties.flatMap { it.value.get<ConfigContainer>().properties }.map { PropertyPair(it.key, it.value) }
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
