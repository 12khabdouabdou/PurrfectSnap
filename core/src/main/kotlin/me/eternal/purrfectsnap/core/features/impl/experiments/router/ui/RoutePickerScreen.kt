package me.eternal.purrfectsnap.core.features.impl.experiments.router.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.core.features.impl.experiments.router.OsrmProfile
import me.eternal.purrfectsnap.core.features.impl.experiments.router.RouteMockHandler
import me.eternal.purrfectsnap.core.features.impl.experiments.router.RouteState
import me.eternal.purrfectsnap.core.features.impl.experiments.router.RouteStatus
import org.osmdroid.util.GeoPoint

@Composable
fun RoutePickerScreen(
    handler: RouteMockHandler,
    onBack: () -> Unit,
    onRouteStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(handler.getState()) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showEmptyState by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        showDisclaimer = true
    }

    LaunchedEffect(state.status) {
        if (state.status == RouteStatus.Completed) {
            showEmptyState = true
        }
    }

    if (showDisclaimer) {
        RiskDisclaimerDialog(
            onAccept = { showDisclaimer = false },
            onDecline = {
                showDisclaimer = false
                onBack()
            }
        )
    }

    Scaffold { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.startGeopoint == null && state.route == null) {
                RoutePickerMap(
                    startGeopoint = state.startGeopoint,
                    endGeopoint = state.endGeopoint,
                    route = state.route,
                    onMapTap = { point ->
                        when {
                            state.startGeopoint == null -> {
                                state = handler.setStartPoint(point)
                            }
                            state.endGeopoint == null -> {
                                state = handler.setEndPoint(point)
                                scope.launch {
                                    if (handler.isValidForCalculation()) {
                                        state = handler.calculateRoute()
                                        showEmptyState = false
                                    }
                                }
                            }
                            else -> {
                                state = handler.clearPoints()
                                state = handler.setStartPoint(point)
                            }
                        }
                    }
                )
            } else {
                RoutePickerMap(
                    startGeopoint = state.startGeopoint,
                    endGeopoint = state.endGeopoint,
                    route = state.route,
                    onMapTap = { point ->
                        state = handler.clearPoints()
                        state = handler.setStartPoint(point)
                    }
                )
            }

            if (!showEmptyState || state.route != null) {
                RouteControlCard(
                    state = state,
                    handler = handler,
                    onStateUpdate = { state = it },
                    onStart = {
                        scope.launch {
                            when (state.status) {
                                RouteStatus.Ready -> {
                                    state = handler.start()
                                    onRouteStarted()
                                }
                                RouteStatus.Paused -> {
                                    state = handler.resume()
                                    onRouteStarted()
                                }
                                else -> {}
                            }
                        }
                    },
                    onPause = {
                        state = handler.pause()
                    },
                    onStop = {
                        state = handler.stop()
                        showEmptyState = true
                    },
                    onClear = {
                        state = handler.clearPoints()
                        showEmptyState = true
                    },
                    onProfileChange = { profile ->
                        state = handler.setProfile(profile)
                        scope.launch {
                            if (handler.isValidForCalculation()) {
                                state = handler.calculateRoute()
                            }
                        }
                    },
                    onSpeedChange = { speed ->
                        state = handler.setSpeed(speed)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }

            if (showEmptyState && state.startGeopoint == null && state.route == null) {
                EmptyStateGuidance(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            }
        }
    }
}

@Composable
private fun RiskDisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Route Mocking Disclaimer") },
        text = {
            Text(
                """
                Route Mocking simulates movement for privacy purposes. This feature may violate platform Terms of Service. You accept full responsibility for any consequences including account suspension or social repercussions. This tool is not intended for deception that harms others.
                """.trimIndent()
            )
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
        }
    )
}

@Composable
private fun EmptyStateGuidance(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Tap the map to set start and end points",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pinch to zoom, drag to pan",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteControlCard(
    state: RouteState,
    handler: RouteMockHandler,
    onStateUpdate: (RouteState) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onProfileChange: (OsrmProfile) -> Unit,
    onSpeedChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            StateIndicator(status = state.status)

            if (state.status in listOf(RouteStatus.Playing, RouteStatus.Paused)) {
                Spacer(modifier = Modifier.height(12.dp))
                RouteProgressCard(state = state)
            }

            if (state.route != null && state.status in listOf(
                RouteStatus.Ready,
                RouteStatus.Playing,
                RouteStatus.Paused
            )) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Distance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${String.format("%.2f", state.route!!.distanceKm)} km",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Est. Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${state.route!!.getEstimatedTimeAtSpeed(state.speedKmh).toInt() / 60} min",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            if (state.status in listOf(RouteStatus.Ready, RouteStatus.Idle)) {
                Spacer(modifier = Modifier.height(12.dp))

                TransportModeSelector(
                    selectedProfile = state.selectedProfile,
                    onProfileSelected = onProfileChange
                )

                Spacer(modifier = Modifier.height(12.dp))

                SpeedSliderWithLabel(
                    speedKmh = state.speedKmh,
                    profile = state.selectedProfile,
                    onSpeedChange = onSpeedChange
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state.status) {
                    RouteStatus.Ready -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Text("Start")
                        }
                        OutlinedButton(onClick = onClear) {
                            Text("Clear")
                        }
                    }
                    RouteStatus.Playing -> {
                        FilledTonalButton(
                            onClick = onPause,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Pause,
                                contentDescription = null
                            )
                            Text("Pause")
                        }
                        OutlinedButton(onClick = onStop) {
                            Text("Stop")
                        }
                    }
                    RouteStatus.Paused -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Text("Resume")
                        }
                        OutlinedButton(onClick = onStop) {
                            Text("Stop")
                        }
                    }
                    RouteStatus.Calculating -> {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Calculating...")
                        }
                    }
                    RouteStatus.Completed -> {
                        Button(
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("New Route")
                        }
                    }
                    is RouteStatus.Error -> {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (handler.isValidForCalculation()) {
                                        onStateUpdate(handler.calculateRoute())
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retry")
                        }
                        OutlinedButton(onClick = onClear) {
                            Text("Clear")
                        }
                    }
                    RouteStatus.Idle -> {
                        if (state.startGeopoint != null && state.endGeopoint != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (handler.isValidForCalculation()) {
                                            onStateUpdate(handler.calculateRoute())
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Calculate Route")
                            }
                        }
                    }
                }
            }

            (state.status as? RouteStatus.Error)?.let { errorStatus ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorStatus.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
