package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign

@Composable
fun QuickActionsDialog(
    quickActions: Map<Pair<String, ImageVector>, Any>,
    selectedQuickActions: List<String>,
    getSpanFor: (name: String) -> Pair<Int, Int>,
    setSpanFor: (name: String, w: Int, h: Int) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val selected = remember { mutableStateListOf(*selectedQuickActions.toTypedArray()) }
    val sizes = remember {
        val map = mutableStateMapOf<String, Pair<Int, Int>>()
        selectedQuickActions.forEach { name -> map[name] = getSpanFor(name) }
        map
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Quick Actions",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Select and size your quick actions.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .fillMaxWidth()
                )
                quickActions.keys.forEach { (name, icon) ->
                    val isSelected = selected.contains(name)
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                if (isSelected) selected.remove(name) else selected.add(name)
                            }
                            .fillMaxWidth(),
                        headlineContent = {
                            Text(
                                name,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = icon,
                                contentDescription = name,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selected.add(name) else selected.remove(name)
                                }
                            )
                        }
                    )

                    if (isSelected) {
                        val (w, h) = sizes[name] ?: getSpanFor(name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Size:", style = MaterialTheme.typography.labelLarge)
                            val options = listOf(1 to 1, 2 to 1, 3 to 1, 1 to 2, 2 to 2, 3 to 2, 1 to 3, 2 to 3, 3 to 3)
                            options.forEach { (ow, oh) ->
                                val selectedOpt = (w == ow && h == oh)
                                FilterChip(
                                    selected = selectedOpt,
                                    onClick = {
                                        sizes[name] = ow to oh
                                        setSpanFor(name, ow, oh)
                                    },
                                    label = { Text("${ow}x${oh}") }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selected.toList()) },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
