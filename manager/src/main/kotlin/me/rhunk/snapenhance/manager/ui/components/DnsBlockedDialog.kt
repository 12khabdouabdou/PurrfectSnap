package me.rhunk.snapenhance.manager.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun DnsBlockedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = { Text("Connection Problem") },
        text = {
            Text(
                "Unable to resolve or connect to the download server. This is often caused by network filtering in some regions.\n\n" +
                "To fix:\n• Go to Settings → Network & Internet → Private DNS\n• Choose: \"Private DNS provider hostname\"\n• Enter: one.one.one.one\n\n" +
                "Or install and enable the 1.1.1.1 app from Cloudflare."
            )
        }
    )
}
