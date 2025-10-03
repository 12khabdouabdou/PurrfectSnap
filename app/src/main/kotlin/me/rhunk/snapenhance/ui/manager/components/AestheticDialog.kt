package me.rhunk.snapenhance.ui.manager.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rhunk.snapenhance.ui.util.Motion

@Composable
fun AestheticDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    icon: ImageVector,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    dismissButtonText: String? = null,
    onDismiss: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(onDismissRequest = onDismissRequest) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = Motion.tweenFloatSpec(180)) + scaleIn(animationSpec = Motion.tweenFloatSpec(220)),
            exit = fadeOut(animationSpec = Motion.tweenFloatSpec(150)) + scaleOut(animationSpec = Motion.tweenFloatSpec(180))
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
            ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    if (dismissButtonText != null && onDismiss != null) {
                        Button(onClick = onDismiss) {
                            Text(dismissButtonText)
                        }
                    }
                    Button(onClick = onConfirm) {
                        Text(confirmButtonText)
                    }
                }
            }
            }
        }
    }
}
