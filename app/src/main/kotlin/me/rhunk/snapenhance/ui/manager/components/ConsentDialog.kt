package me.rhunk.snapenhance.ui.manager.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ConsentDialog(
    title: String,
    text: String,
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title)
        },
        text = {
            Text(text = text)
        },
        confirmButton = {
            Button(
                onClick = {
                    onAgree()
                    onDismiss()
                }
            ) {
                Text(text = "I Agree")
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    onDisagree()
                    onDismiss()
                }
            ) {
                Text(text = "I Disagree")
            }
        }
    )
}
