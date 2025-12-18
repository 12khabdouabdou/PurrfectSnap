package me.rhunk.snapenhance.ui.manager.pages.location

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rhunk.snapenhance.bridge.location.LocationCoordinates
import me.rhunk.snapenhance.common.bridge.wrapper.LocaleWrapper
import me.rhunk.snapenhance.core.util.RouteEngine
import me.rhunk.snapenhance.ui.util.AlertDialogs
import kotlin.math.roundToInt

@Composable
fun RouteConfigurationDialog(
    alertDialogs: AlertDialogs,
    translation: LocaleWrapper,
    startCoords: LocationCoordinates,
    endCoords: LocationCoordinates,
    smartMode: Boolean,
    onSmartModeChange: (Boolean) -> Unit,
    useRealRoads: Boolean,
    onUseRealRoadsChange: (Boolean) -> Unit,
    onStartRoute: (start: LocationCoordinates, end: LocationCoordinates, duration: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var durationMinutes by remember { mutableStateOf(10f) }
    var startLat by remember { mutableStateOf(startCoords.latitude.toString()) }
    var startLng by remember { mutableStateOf(startCoords.longitude.toString()) }
    var endLat by remember { mutableStateOf(endCoords.latitude.toString()) }
    var endLng by remember { mutableStateOf(endCoords.longitude.toString()) }

    var isCalculating by remember { mutableStateOf(false) }

    val routeEngine = remember { RouteEngine() }

    val distanceKm by remember {
        derivedStateOf {
            val sLat = startLat.toDoubleOrNull() ?: 0.0
            val sLng = startLng.toDoubleOrNull() ?: 0.0
            val eLat = endLat.toDoubleOrNull() ?: 0.0
            val eLng = endLng.toDoubleOrNull() ?: 0.0
            routeEngine.calculateDistance(sLat, sLng, eLat, eLng) / 1000.0 // km
        }
    }

    LaunchedEffect(distanceKm, smartMode) {
        if (smartMode) {
            val defaultSpeedKmh = routeEngine.calculateDefaultSpeed(distanceKm * 1000.0)
            if (defaultSpeedKmh > 0) {
                val calculatedDuration = (distanceKm / defaultSpeedKmh * 60).toFloat()
                durationMinutes = calculatedDuration.coerceIn(1f, 120f)
            }
        }
    }

    val speedKmh = if (durationMinutes > 0) (distanceKm / (durationMinutes / 60)) else 0.0

    alertDialogs.DefaultDialogCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title + Smart Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(translation["route_config_title"] ?: "Route Configuration", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Smart Mode", fontSize = 12.sp)
                    Switch(
                        checked = smartMode,
                        onCheckedChange = onSmartModeChange,
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }

            // Route Type Toggle (Straight Line vs Follow Roads)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (useRealRoads) "🛣️ Follow Roads" else "📏 Straight Line", fontSize = 14.sp)
                Switch(
                    checked = useRealRoads,
                    onCheckedChange = onUseRealRoadsChange,
                    modifier = Modifier.scale(0.8f)
                )
            }
            if (useRealRoads) {
                Text(
                    "⚠️ Requires internet. May fall back to linear if offline.",
                    fontSize = 10.sp, 
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 12.sp
                )
            }

            // Duration Slider
            Text("Duration: ${durationMinutes.toInt()} min")
            if (smartMode) {
                Text(
                    text = "Auto-calculated: ~${durationMinutes.toInt()} min (${String.format("%.0f", routeEngine.calculateDefaultSpeed(distanceKm * 1000.0))} km/h for ${String.format("%.2f", distanceKm)} km)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Slider(
                value = durationMinutes,
                onValueChange = { durationMinutes = it },
                valueRange = 1f..120f,
                steps = 119,
                enabled = !smartMode
            )

            // Transport Modes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { 
                        val newDuration = (distanceKm / 5.0 * 60).coerceIn(1.0, 120.0).toFloat()
                        durationMinutes = newDuration
                    },
                    enabled = !smartMode
                ) { Text("Walk") }
                Button(
                    onClick = { 
                        val newDuration = (distanceKm / 20.0 * 60).coerceIn(1.0, 120.0).toFloat()
                        durationMinutes = newDuration
                    },
                    enabled = !smartMode
                ) { Text("Cycle") }
                Button(
                    onClick = { 
                        val newDuration = (distanceKm / 60.0 * 60).coerceIn(1.0, 120.0).toFloat()
                        durationMinutes = newDuration
                    },
                    enabled = !smartMode
                ) { Text("Drive") }
            }

            HorizontalDivider()

            // Coordinates Inputs
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start Point", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = startLat,
                        onValueChange = { startLat = it },
                        label = { Text("Lat") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = startLng,
                        onValueChange = { startLng = it },
                        label = { Text("Lng") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("End Point", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = endLat,
                        onValueChange = { endLat = it },
                        label = { Text("Lat") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = endLng,
                        onValueChange = { endLng = it },
                        label = { Text("Lng") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Stats
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Distance: %.2f km (Linear)".format(distanceKm))
                    Text("Speed: %.1f km/h".format(speedKmh))
                    if (useRealRoads) {
                        Text("(Approximate until route generated)", fontSize = 10.sp)
                    }
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCalculating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Calculating...", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !isCalculating) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isCalculating,
                    onClick = {
                        val start = LocationCoordinates().apply {
                            latitude = startLat.toDoubleOrNull() ?: 0.0
                            longitude = startLng.toDoubleOrNull() ?: 0.0
                        }
                        val end = LocationCoordinates().apply {
                            latitude = endLat.toDoubleOrNull() ?: 0.0
                            longitude = endLng.toDoubleOrNull() ?: 0.0
                        }
                        
                        if (useRealRoads) {
                            isCalculating = true
                        }
                        onStartRoute(start, end, (durationMinutes * 60 * 1000).toLong())
                    }
                ) {
                    Text("Start Route")
                }
            }
        }
    }
}
