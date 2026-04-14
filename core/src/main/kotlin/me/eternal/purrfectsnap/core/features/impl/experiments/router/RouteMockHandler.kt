package me.eternal.purrfectsnap.core.features.impl.experiments.router

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class RouteMockHandler(
    private val osrmClient: OsrmClient = OsrmClient(),
) {
    private var state: RouteState = RouteState()

    @Synchronized
    fun getState(): RouteState = state

    @Synchronized
    fun setStartPoint(point: GeoPoint): RouteState {
        state = state.copy(
            startGeopoint = point,
            endGeopoint = null,
            route = null,
            status = RouteStatus.Idle,
            progress = 0f,
        )
        return state
    }

    @Synchronized
    fun setEndPoint(point: GeoPoint): RouteState {
        val startPoint = state.startGeopoint ?: return state

        state = state.copy(
            endGeopoint = point,
            status = RouteStatus.Idle,
        )
        return state
    }

    @Synchronized
    fun clearPoints(): RouteState {
        state = RouteState()
        return state
    }

    @Synchronized
    fun setProfile(profile: OsrmProfile): RouteState {
        val needsRecalculation = state.route != null && state.selectedProfile != profile

        state = state.copy(
            selectedProfile = profile,
            speedKmh = profile.defaultSpeedKmh,
        )

        if (needsRecalculation && state.startGeopoint != null && state.endGeopoint != null) {
            state = state.copy(status = RouteStatus.Idle, route = null)
        }

        return state
    }

    @Synchronized
    fun setSpeed(speedKmh: Double): RouteState {
        val clampedSpeed = state.selectedProfile.clampSpeed(speedKmh)
        state = state.copy(speedKmh = clampedSpeed)
        return state
    }

    suspend fun calculateRoute(): RouteState {
        val startPoint = state.startGeopoint
        val endPoint = state.endGeopoint
        val profile = state.selectedProfile

        if (startPoint == null || endPoint == null) {
            state = state.copy(status = RouteStatus.Error("Start or end point not set"))
            return state
        }

        @Synchronized
        fun updateToCalculating() {
            state = state.copy(status = RouteStatus.Calculating)
        }
        updateToCalculating()

        val result = osrmClient.calculateRoute(
            startLat = startPoint.latitude,
            startLng = startPoint.longitude,
            endLat = endPoint.latitude,
            endLng = endPoint.longitude,
            profile = profile,
        )

    @Synchronized
    fun updateWithResult() {
        state = when (result) {
            is RouteResult.Success -> state.copy(
                route = result.data,
                status = RouteStatus.Ready,
                progress = 0f,
            )
            is RouteResult.Error -> state.copy(
                status = RouteStatus.Error(result.message)
            )
        }
    }
        updateWithResult()

        return state
    }

    @Synchronized
    fun start(): RouteState {
        val route = state.route ?: return state

        if (state.status != RouteStatus.Ready && state.status != RouteStatus.Paused) {
            return state
        }

        state = state.copy(
            status = RouteStatus.Playing,
            startTimeMs = System.currentTimeMillis(),
            progress = state.pausedProgress,
        )
        return state
    }

    @Synchronized
    fun pause(): RouteState {
        if (state.status != RouteStatus.Playing) {
            return state
        }

        state = state.copy(
            status = RouteStatus.Paused,
            pausedProgress = state.progress,
        )
        return state
    }

    @Synchronized
    fun resume(): RouteState {
        if (state.status != RouteStatus.Paused) {
            return state
        }

        state = state.copy(
            status = RouteStatus.Playing,
            startTimeMs = System.currentTimeMillis(),
        )
        return state
    }

    @Synchronized
    fun stop(): RouteState {
        state = RouteState(
            startGeopoint = state.startGeopoint,
            endGeopoint = state.endGeopoint,
            selectedProfile = state.selectedProfile,
            speedKmh = state.selectedProfile.defaultSpeedKmh,
        )
        return state
    }

    @Synchronized
    fun getCurrentPosition(): GeoPoint? {
        if (state.status != RouteStatus.Playing) {
            return null
        }

        val route = state.route ?: return null

        val elapsedMs = System.currentTimeMillis() - state.startTimeMs
        val speedMetersPerSecond = state.speedKmh * 1000.0 / 3600.0
        val totalDurationMs = (route.distanceMeters / speedMetersPerSecond) * 1000.0

        val adjustedElapsedMs = elapsedMs + (state.pausedProgress * totalDurationMs)
        val rawProgress = (adjustedElapsedMs / totalDurationMs).toFloat()

        if (rawProgress >= 1.0f) {
            state = state.copy(
                status = RouteStatus.Completed,
                progress = 1.0f,
            )
            return route.getEndPoint()
        }

        val progressWithJitter = applyTimingJitter(rawProgress)
        val position = calculatePositionAt(route.coordinates, progressWithJitter)

        state = state.copy(progress = rawProgress)

        return position
    }

    private fun applyTimingJitter(progress: Float): Float {
        val jitterMs = (Math.random() - 0.5) * 1000
        val progressPerMs = 1.0f / 60000.0f
        val jitteredProgress = progress + (jitterMs.toFloat() * progressPerMs)
        return jitteredProgress.coerceIn(0.0f, 1.0f)
    }

    private fun calculatePositionAt(coordinates: List<GeoPoint>, progress: Float): GeoPoint {
        if (coordinates.isEmpty()) return GeoPoint(0.0, 0.0)
        if (progress <= 0.0f) return coordinates.first()
        if (progress >= 1.0f) return coordinates.last()

        val segmentDistances = calculateSegmentDistances(coordinates)
        val totalDistance = segmentDistances.sum()
        val targetDistance = progress * totalDistance

        var accumulatedDistance = 0.0
        for (i in segmentDistances.indices) {
            val segmentDistance = segmentDistances[i]
            if (accumulatedDistance + segmentDistance >= targetDistance) {
                val segmentProgress = (targetDistance - accumulatedDistance) / segmentDistance
                return interpolatePosition(
                    coordinates[i],
                    coordinates[i + 1],
                    segmentProgress
                )
            }
            accumulatedDistance += segmentDistance
        }

        return coordinates.last()
    }

    private fun calculateSegmentDistances(coordinates: List<GeoPoint>): List<Double> {
        if (coordinates.size < 2) return emptyList()

        return (0 until coordinates.size - 1).map { i ->
            haversineDistance(coordinates[i], coordinates[i + 1])
        }
    }

    private fun haversineDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val earthRadius = 6371000.0

        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLng = Math.toRadians(p2.longitude - p1.longitude)

        val sinDeltaLat = sin(deltaLat / 2)
        val sinDeltaLng = sin(deltaLng / 2)
        val a = sinDeltaLat * sinDeltaLat + cos(lat1) * cos(lat2) * sinDeltaLng * sinDeltaLng
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    private fun interpolatePosition(start: GeoPoint, end: GeoPoint, progress: Double): GeoPoint {
        val lat = start.latitude + (end.latitude - start.latitude) * progress
        val lng = start.longitude + (end.longitude - start.longitude) * progress

        val positionVariance = (Math.random() - 0.5) * 0.00005
        val latWithVariance = lat + positionVariance
        val lngWithVariance = lng + positionVariance * cos(Math.toRadians(lat))

        return GeoPoint(latWithVariance, lngWithVariance)
    }

    fun validateCoordinates(lat: Double, lng: Double): Boolean {
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    fun isValidForCalculation(): Boolean {
        val start = state.startGeopoint ?: return false
        val end = state.endGeopoint ?: return false
        return validateCoordinates(start.latitude, start.longitude) &&
               validateCoordinates(end.latitude, end.longitude)
    }
}


