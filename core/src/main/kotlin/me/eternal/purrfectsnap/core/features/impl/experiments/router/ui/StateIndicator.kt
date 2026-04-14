package me.eternal.purrfectsnap.core.features.impl.experiments.router.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.eternal.purrfectsnap.core.features.impl.experiments.router.RouteStatus

@Composable
fun StateIndicator(
    status: RouteStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is RouteStatus.Idle -> {
            StateChip(
                icon = null,
                text = "Ready",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is RouteStatus.Calculating -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Calculating...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is RouteStatus.Ready -> {
            StateChip(
                icon = Icons.Default.CheckCircle,
                text = "Ready to start",
                color = Color(0xFF4CAF50)
            )
        }
        is RouteStatus.Playing -> {
            StateChip(
                icon = Icons.Default.PlayCircle,
                text = "Simulating",
                color = Color(0xFF4CAF50)
            )
        }
        is RouteStatus.Paused -> {
            StateChip(
                icon = Icons.Default.PauseCircle,
                text = "Paused",
                color = Color(0xFFFFC107)
            )
        }
        is RouteStatus.Completed -> {
            StateChip(
                icon = Icons.Default.CheckCircle,
                text = "Complete",
                color = MaterialTheme.colorScheme.primary
            )
        }
        is RouteStatus.Error -> {
            StateChip(
                icon = Icons.Default.Error,
                text = "Error",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StateChip(
    icon: ImageVector?,
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
