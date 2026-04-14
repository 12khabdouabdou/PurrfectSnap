package me.eternal.purrfectsnap.core.features.impl.experiments.router

import org.osmdroid.util.GeoPoint
import java.util.UUID

enum class OsrmProfile(val profileName: String, val defaultSpeedKmh: Double, val minSpeedKmh: Double, val maxSpeedKmh: Double) {
    WALKING("foot", 5.0, 3.0, 7.0),
    DRIVING("car", 30.0, 20.0, 130.0),
    CYCLING("bike", 15.0, 10.0, 40.0);

    fun clampSpeed(speedKmh: Double): Double {
        return speedKmh.coerceIn(minSpeedKmh, maxSpeedKmh)
    }
}

data class Route(
    val id: String = UUID.randomUUID().toString(),
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val profile: OsrmProfile,
    val coordinates: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
) {
    val distanceKm: Double
        get() = distanceMeters / 1000.0

    val durationMinutes: Double
        get() = durationSeconds / 60.0

    fun getEstimatedTimeAtSpeed(speedKmh: Double): Double {
        val clampedSpeed = profile.clampSpeed(speedKmh)
        val speedMetersPerSecond = clampedSpeed * 1000.0 / 3600.0
        return distanceMeters / speedMetersPerSecond
    }

    fun getStartPoint(): GeoPoint = GeoPoint(startLat, startLng)

    fun getEndPoint(): GeoPoint = GeoPoint(endLat, endLng)
}

data class OsrmResponse(
    val code: String,
    val routes: List<OsrmRoute>,
)

data class OsrmRoute(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometry,
)

data class OsrmGeometry(
    val coordinates: List<List<Double>>,
)

sealed class RouteStatus {
    object Idle : RouteStatus()
    object Calculating : RouteStatus()
    object Ready : RouteStatus()
    object Playing : RouteStatus()
    object Paused : RouteStatus()
    object Completed : RouteStatus()
    data class Error(val message: String) : RouteStatus()
}

data class RouteState(
    val route: Route? = null,
    val status: RouteStatus = RouteStatus.Idle,
    val progress: Float = 0f,
    val startTimeMs: Long = 0L,
    val pausedProgress: Float = 0f,
    val speedKmh: Double = 5.0,
    val startGeopoint: GeoPoint? = null,
    val endGeopoint: GeoPoint? = null,
    val selectedProfile: OsrmProfile = OsrmProfile.WALKING,
) {
    fun isSimulating(): Boolean {
        return status == RouteStatus.Playing || status == RouteStatus.Paused
    }

    fun getProgressPercent(): Int {
        return (progress * 100).toInt().coerceIn(0, 100)
    }

    fun getRemainingDistanceKm(): Double {
        val route = route ?: return 0.0
        return route.distanceKm * (1.0 - progress)
    }

    fun getRemainingTimeMinutes(): Double {
        val route = route ?: return 0.0
        val remainingProgress = 1.0 - progress
        return route.getEstimatedTimeAtSpeed(speedKmh) / 60.0 * remainingProgress
    }
}

sealed class RouteResult<out T> {
    data class Success<T>(val data: T) : RouteResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : RouteResult<Nothing>()
}
