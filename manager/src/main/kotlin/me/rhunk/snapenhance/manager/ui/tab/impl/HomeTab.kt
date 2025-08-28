package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rhunk.snapenhance.manager.ui.tab.Tab

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {

    override fun init(activity: ComponentActivity) {
        super.init(activity)
        // Ensure nested tabs are known to navigation
        registerNestedTab(ManualPatchTab::class)
        registerNestedTab(AutoPatchTab::class)
    }

    @Composable
    override fun Content() {
        var showAutoPatchDialog by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            // Manual Patch (existing)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { navigation.navigateTo(ManualPatchTab::class) }
            ) {
                Row(
                    Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manual Patch",
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Auto Patch (new)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .clickable { showAutoPatchDialog = true }
            ) {
                Row(
                    Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto Patch",
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (showAutoPatchDialog) {
                AlertDialog(
                    onDismissRequest = { showAutoPatchDialog = false },
                    title = { Text("Auto Patch") },
                    text = { Text("Are you sure to continue?") },
                    confirmButton = {
                        TextButton(onClick = {
                            showAutoPatchDialog = false
                            navigation.navigateTo(AutoPatchTab::class)
                        }) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAutoPatchDialog = false }) { Text("No") }
                    }
                )
            }
        }
    }
}
