package me.eternal.purrfectsnap.core.features.impl.experiments.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Pure mathematical engine for coordinate interpolation and distance calculation.
 * Follows the logic defined in Story 1.1.
 */
object InterpolatorEngine {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the distance in meters between two [GeoPoint] objects using the Haversine formula.
     * [Source: docs/research-technical-2026.md#Coordinate-Interpolation]
     */
    fun haversineDistance(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Returns a new [GeoPoint] that is [distanceMeters] away from [start] along the path toward [end].
     * Uses linear interpolation of coordinates which is sufficiently accurate for road segments.
     */
    fun calculateStep(start: GeoPoint, end: GeoPoint, distanceMeters: Double): GeoPoint {
        val totalDistance = haversineDistance(start, end)
        if (totalDistance <= 0.0 || distanceMeters <= 0.0) return GeoPoint(start.latitude, start.longitude)
        if (distanceMeters >= totalDistance) return GeoPoint(end.latitude, end.longitude)

        val fraction = distanceMeters / totalDistance

        val newLat = start.latitude + (end.latitude - start.latitude) * fraction
        val newLon = start.longitude + (end.longitude - start.longitude) * fraction

        return GeoPoint(newLat, newLon)
    }

    /**
     * Finds the [GeoPoint] at [totalDistanceTraveled] meters along a [route] (a list of points).
     * [Source: docs/epics.md#Story-1]
     */
    fun getPositionAtDistance(route: List<GeoPoint>, totalDistanceTraveled: Double): GeoPoint {
        if (route.isEmpty()) throw IllegalArgumentException("Route cannot be empty")
        if (totalDistanceTraveled <= 0.0) return route.first()

        var currentDistance = 0.0
        for (i in 0 until route.size - 1) {
            val p1 = route[i]
            val p2 = route[i + 1]
            val segmentDistance = haversineDistance(p1, p2)

            if (currentDistance + segmentDistance >= totalDistanceTraveled) {
                val remainingOnSegment = totalDistanceTraveled - currentDistance
                return calculateStep(p1, p2, remainingOnSegment)
            }
            currentDistance += segmentDistance
        }

        // If distance traveled exceeds total route length, return last point.
        return route.last()
    }
}
