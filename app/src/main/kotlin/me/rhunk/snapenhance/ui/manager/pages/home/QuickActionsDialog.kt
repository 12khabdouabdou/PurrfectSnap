package me.rhunk.snapenhance.ui.manager.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Column {
                quickActions.keys.forEach { (name, _) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = selected.contains(name),
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    selected.add(name)
                                } else {
                                    selected.remove(name)
                                }
                            }
                        )
                        Text(text = name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
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
