package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rhunk.snapenhance.manager.ui.tab.Tab

class AutoPatchTab : Tab("autopatch", icon = Icons.Default.Build) {
    @Composable
    override fun Content() {
        // Placeholder for Auto Patch content
        Text("Welcome to the Auto Patch section!", color = MaterialTheme.colorScheme.onBackground)
        // We'll fill this in future steps
    }
}
