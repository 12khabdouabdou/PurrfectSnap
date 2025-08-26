package me.rhunk.snapenhance.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ThemeChooser(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "App Theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            ThemeMode.values().forEach { mode ->
                val (icon, label) = when (mode) {
                    ThemeMode.LIGHT -> Icons.Filled.LightMode to "Light"
                    ThemeMode.DARK -> Icons.Filled.DarkMode to "Dark"
                    ThemeMode.SYSTEM -> Icons.Filled.Brightness4 to "System Default"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { onSelect(mode) }
                        .then(
                            if (selected == mode) Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected == mode) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
