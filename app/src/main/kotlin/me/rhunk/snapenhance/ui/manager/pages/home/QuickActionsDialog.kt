package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rhunk.snapenhance.ui.util.AlertDialogs

@Composable
fun QuickActionsDialog(
    alertDialogs: AlertDialogs,
    quickActions: Map<Pair<String, ImageVector>, Any>,
    selectedQuickActions: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val selected = remember { mutableStateListOf(*selectedQuickActions.toTypedArray()) }

    Dialog(onDismissRequest = onDismiss) {
        alertDialogs.DefaultDialogCard {
            Text(
                text = "Edit Quick Actions",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Select the actions you want to see on the home screen.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            Column {
                quickActions.keys.forEach { (name, icon) ->
                    val isSelected = selected.contains(name)
                    ListItem(
                        modifier = Modifier.clickable {
                            if (isSelected) {
                                selected.remove(name)
                            } else {
                                selected.add(name)
                            }
                        },
                        headlineContent = { Text(name) },
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
                                    if (isChecked) {
                                        selected.add(name)
                                    } else {
                                        selected.remove(name)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onSave(selected) },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
