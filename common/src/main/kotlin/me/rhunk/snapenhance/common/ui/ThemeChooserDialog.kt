package me.rhunk.snapenhance.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ThemeChooserDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text("Choose App Theme", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                ThemeMode.values().forEach { mode ->
                    val (icon, label) = when (mode) {
                        ThemeMode.LIGHT -> Icons.Filled.LightMode to "Light"
                        ThemeMode.DARK -> Icons.Filled.DarkMode to "Dark"
                        ThemeMode.SYSTEM -> Icons.Filled.Brightness4 to "System Default"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(mode)
                                onDismiss()
                            }
                            .background(
                                if (mode == selected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.small
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (mode == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(label, color = if (mode == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        if (mode == selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if(mode != ThemeMode.SYSTEM) Spacer(Modifier.height(7.dp))
                }
            }
        }
    )
}
