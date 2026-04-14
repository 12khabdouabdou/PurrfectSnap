package me.eternal.purrfectsnap.core.features.impl.experiments.router.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.eternal.purrfectsnap.core.features.impl.experiments.router.OsrmProfile

@Composable
fun TransportModeSelector(
    selectedProfile: OsrmProfile,
    onProfileSelected: (OsrmProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportChip(
            selected = selectedProfile == OsrmProfile.WALKING,
            onClick = { onProfileSelected(OsrmProfile.WALKING) },
            label = "Walking",
            icon = Icons.Default.DirectionsWalk,
            contentDescription = "Walking mode"
        )

        Spacer(modifier = Modifier.width(8.dp))

        TransportChip(
            selected = selectedProfile == OsrmProfile.DRIVING,
            onClick = { onProfileSelected(OsrmProfile.DRIVING) },
            label = "Driving",
            icon = Icons.Default.DirectionsCar,
            contentDescription = "Driving mode"
        )

        Spacer(modifier = Modifier.width(8.dp))

        TransportChip(
            selected = selectedProfile == OsrmProfile.CYCLING,
            onClick = { onProfileSelected(OsrmProfile.CYCLING) },
            label = "Cycling",
            icon = Icons.Default.DirectionsBike,
            contentDescription = "Cycling mode"
        )
    }
}

@Composable
private fun TransportChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: ImageVector,
    contentDescription: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.width(FilterChipDefaults.IconSize)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
