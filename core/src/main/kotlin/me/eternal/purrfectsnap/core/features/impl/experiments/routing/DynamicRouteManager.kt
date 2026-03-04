package me.eternal.purrfectsnap.core.features.impl.experiments.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.eternal.purrfectsnap.core.ModContext
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.util.GeoPoint

/**
 * Status of the current dynamic route.
 */
enum class RouteStatus {
    Idle, Routing, Finished, Error
}

/**
 * Singleton manager to coordinate routing state, background fetching from OSRM, 
 * and persistent travel progress.
 * [Source: docs/architecture.md#Section-2]
 */
data class RouteProgress(
    val distanceRemaining: Double,
    val totalDistance: Double,
    val speedKmh: Double
)

object DynamicRouteManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val roadManager = OSRMRoadManager(null, "PurrfectSnap-Spoofer") 

    private val _routeState = MutableStateFlow(RouteStatus.Idle)
    val routeState = _routeState.asStateFlow()

    private var modContext: ModContext? = null
    private const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "routing_notifications"

    fun initialize(context: ModContext) {
        this.modContext = context
    }

    private fun updateNotification(status: RouteStatus) {
        val context = modContext?.androidContext ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (status == RouteStatus.Routing) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "PurrfectSnap Routing", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("PurrfectSnap Routing...")
                .setContentText("Dynamic location spoofing is active")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } else {
            nm.cancel(NOTIFICATION_ID)
        }
    }

    private val _currentRoute = MutableStateFlow<List<GeoPoint>>(emptyList())
    val currentRoute = _currentRoute.asStateFlow()

    private val _currentPosition = MutableStateFlow<GeoPoint?>(null)
    val currentPosition = _currentPosition.asStateFlow()

    private val _progress = MutableStateFlow<RouteProgress?>(null)
    val progress = _progress.asStateFlow()

    private var startTimeMs = 0L
    private var velocityMs = 0.0 // Meters per second conversion

    private var totalDistanceCalculated = 0.0

    /**
     * Starts the background routing process.
     * [Source: docs/epics.md#Story-2]
     */
    fun startRoute(start: GeoPoint, end: GeoPoint, speedKmh: Double) {
        if (_routeState.value == RouteStatus.Routing) return

        _routeState.value = RouteStatus.Routing
        updateNotification(RouteStatus.Routing)
        velocityMs = speedKmh / 3.6 // km/h to m/s conversion [Source: docs/architecture.md#Section-4]

        scope.launch {
            try {
                // Ensure OSRM service is available and non-blocking
                val waypoints = arrayListOf(start, end)
                val road = roadManager.getRoad(waypoints)

                if (road.mStatus == 0) { // Success status in osmbonuspack
                    _currentRoute.value = road.mRouteHigh
                    startTimeMs = System.currentTimeMillis()
                    totalDistanceCalculated = road.mLength * 1000.0 // km to meters
                    startTicker()
                } else {
                    _routeState.value = RouteStatus.Error
                    updateNotification(RouteStatus.Error)
                }
            } catch (e: Exception) {
                _routeState.value = RouteStatus.Error
                updateNotification(RouteStatus.Error)
            }
        }
    }

    private fun startTicker() {
        scope.launch {
            while (isActive && _routeState.value == RouteStatus.Routing) {
                val currentPos = getCurrentPosition()
                _currentPosition.value = currentPos
                
                val elapsedTimeSeconds = (System.currentTimeMillis() - startTimeMs) / 1000.0
                val distanceTraveled = elapsedTimeSeconds * velocityMs
                val remaining = (totalDistanceCalculated - distanceTraveled).coerceAtLeast(0.0)
                
                _progress.value = RouteProgress(
                    distanceRemaining = remaining,
                    totalDistance = totalDistanceCalculated,
                    speedKmh = velocityMs * 3.6
                )
                
                delay(1000)
            }
        }
    }

    /**
     * Stops the current routing process.
     */
    fun stopRoute() {
        _routeState.value = RouteStatus.Idle
        updateNotification(RouteStatus.Idle)
        _currentRoute.value = emptyList()
        _currentPosition.value = null
        _progress.value = null
    }

    /**
     * Calculates the current interpolated position based on elapsed time.
     * Uses lazy evaluation - calculated only when requested by location hooks.
     * [Source: docs/architecture.md#Section-2]
     */
    fun getCurrentPosition(): GeoPoint? {
        val st = _routeState.value
        if (st != RouteStatus.Routing && st != RouteStatus.Finished) return null
        
        val route = _currentRoute.value
        if (route.isEmpty()) return null

        val elapsedTimeSeconds = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val distanceTraveled = elapsedTimeSeconds * velocityMs

        if (distanceTraveled >= totalDistanceCalculated) {
            _routeState.value = RouteStatus.Finished
            updateNotification(RouteStatus.Finished)
            return route.last()
        }

        return InterpolatorEngine.getPositionAtDistance(route, distanceTraveled)
    }
}
