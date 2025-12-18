package me.rhunk.snapenhance.core.util

import kotlin.math.*

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val bearing: Float
)

data class Route(
    val points: List<RoutePoint>,
    val totalDistance: Double, // meters
    val totalDuration: Long     // milliseconds
)

enum class PlaybackState { STOPPED, PLAYING, PAUSED }


interface RouteGenerator {
    suspend fun generateRoute(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        durationMs: Long,
        updateIntervalMs: Long?
    ): Route
}

class LinearRouteGenerator : RouteGenerator {
    override suspend fun generateRoute(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        durationMs: Long,
        updateIntervalMs: Long?
    ): Route {
        val routeEngine = RouteEngine()
        val distance = routeEngine.calculateDistance(startLat, startLng, endLat, endLng)
        val speedKmh = (distance / 1000.0) / (durationMs / 3600000.0)
        
        val actualUpdateIntervalMs = updateIntervalMs ?: routeEngine.calculateUpdateInterval(speedKmh)
        val numPoints = (durationMs / actualUpdateIntervalMs).toInt().coerceAtLeast(1)
        val bearing = routeEngine.calculateBearing(startLat, startLng, endLat, endLng)
        
        val points = (0..numPoints).map { i ->
            val progress = i.toDouble() / numPoints
            RoutePoint(
                latitude = startLat + (endLat - startLat) * progress,
                longitude = startLng + (endLng - startLng) * progress,
                timestamp = i * actualUpdateIntervalMs,
                bearing = bearing
            )
        }

        return Route(points, distance, durationMs)
    }
}

class OSRMRouteGenerator : RouteGenerator {
    override suspend fun generateRoute(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        durationMs: Long,
        updateIntervalMs: Long?
    ): Route {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=geojson"
            val request = okhttp3.Request.Builder().url(url).build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) throw Exception("OSRM API failed: ${response.code}")
            
            val json = org.json.JSONObject(response.body?.string() ?: throw Exception("Empty response"))
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) throw Exception("No routes found")
            
            val routeJson = routes.getJSONObject(0)
            val geometry = routeJson.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            
            // Extract raw points
            val rawPoints = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                rawPoints.add(coord.getDouble(1) to coord.getDouble(0)) // lat, lng
            }
            
            val routeEngine = RouteEngine()
            // Calculate total distance closely following the path
            var totalDistance = 0.0
            for (i in 0 until rawPoints.size - 1) {
                totalDistance += routeEngine.calculateDistance(
                    rawPoints[i].first, rawPoints[i].second,
                    rawPoints[i+1].first, rawPoints[i+1].second
                )
            }
            
            // Adjust speed based on actual road distance (which is > linear distance)
            // We keep the duration constant as requested, so speed will auto-adjust
            val speedKmh = (totalDistance / 1000.0) / (durationMs / 3600000.0)
            val actualUpdateIntervalMs = updateIntervalMs ?: routeEngine.calculateUpdateInterval(speedKmh)
            
            // Resample points based on time
            val numPoints = (durationMs / actualUpdateIntervalMs).toInt().coerceAtLeast(1)
            val stepDistance = totalDistance / numPoints
            
            val finalPoints = mutableListOf<RoutePoint>()
            var currentDist = 0.0
            var rawIndex = 0
            var segmentDistProcessed = 0.0
            
            for (i in 0..numPoints) {
                val targetDist = i * stepDistance
                
                // Advance raw points until we find the segment containing targetDist
                while (rawIndex < rawPoints.size - 1) {
                    val p1 = rawPoints[rawIndex]
                    val p2 = rawPoints[rawIndex + 1]
                    val segmentDist = routeEngine.calculateDistance(p1.first, p1.second, p2.first, p2.second)
                    
                    if (currentDist + segmentDist >= targetDist) {
                        // Interpolate within this segment
                        val remainingDist = targetDist - currentDist
                        val fraction = if (segmentDist > 0) remainingDist / segmentDist else 0.0
                        
                        val interpLat = p1.first + (p2.first - p1.first) * fraction
                        val interpLng = p1.second + (p2.second - p1.second) * fraction
                        val bearing = routeEngine.calculateBearing(p1.first, p1.second, p2.first, p2.second)
                        
                        finalPoints.add(RoutePoint(interpLat, interpLng, i * actualUpdateIntervalMs, bearing))
                        break
                    }
                    
                    currentDist += segmentDist
                    rawIndex++
                }
            }
            // Ensure we have at least start and end if math is slightly off
            if (finalPoints.isEmpty()) { 
                return LinearRouteGenerator().generateRoute(startLat, startLng, endLat, endLng, durationMs, updateIntervalMs) 
            }
            
            return Route(finalPoints, totalDistance, durationMs)

        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to linear
            return LinearRouteGenerator().generateRoute(startLat, startLng, endLat, endLng, durationMs, updateIntervalMs)
        }
    }
}

class RouteEngine {
    private var currentRoute: Route? = null
    private var playbackState: PlaybackState = PlaybackState.STOPPED
    private var startTime: Long = 0
    private var pauseTime: Long = 0
    
    // Haversine formula for great-circle distance
    fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δφ = Math.toRadians(lat2 - lat1)
        val Δλ = Math.toRadians(lng2 - lng1)

        val a = sin(Δφ / 2).pow(2) +
                cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    // Calculate initial bearing (heading) between two points
    fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lng2 - lng1)

        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val θ = atan2(y, x)

        return ((Math.toDegrees(θ) + 360) % 360).toFloat()
    }

    suspend fun generateRoute(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        durationMs: Long,
        updateIntervalMs: Long? = null,
        useRealRoads: Boolean = false
    ): Route {
        val generator = if (useRealRoads) OSRMRouteGenerator() else LinearRouteGenerator()
        return generator.generateRoute(startLat, startLng, endLat, endLng, durationMs, updateIntervalMs)
    }

    fun calculateDefaultSpeed(distanceMeters: Double): Double {
        return when {
            distanceMeters < 1000 -> 5.0   // < 1km: walking (5 km/h)
            distanceMeters < 10000 -> 20.0  // 1-10km: cycling (20 km/h)
            else -> 60.0              // > 10km: driving (60 km/h)
        }
    }

    fun calculateUpdateInterval(speedKmh: Double): Long {
        return when {
            speedKmh > 40 -> 1000  // High speed: 1 Hz
            else -> 2000        // Low speed: 0.5 Hz
        }
    }

    fun startRoute(route: Route) {
        currentRoute = route
        startTime = System.currentTimeMillis()
        playbackState = PlaybackState.PLAYING
    }

    fun pauseRoute() {
        if (playbackState == PlaybackState.PLAYING) {
            playbackState = PlaybackState.PAUSED
            pauseTime = System.currentTimeMillis()
        }
    }

    fun resumeRoute() {
        if (playbackState == PlaybackState.PAUSED) {
            // Adjust start time to account for pause duration
            val pauseDuration = System.currentTimeMillis() - pauseTime
            startTime += pauseDuration
            playbackState = PlaybackState.PLAYING
        }
    }

    fun stopRoute() {
        playbackState = PlaybackState.STOPPED
        currentRoute = null
    }

    fun isPlaying(): Boolean = playbackState == PlaybackState.PLAYING

    fun getCurrentLocation(): RoutePoint? {
        if (playbackState != PlaybackState.PLAYING) return null
        
        val route = currentRoute ?: return null
        val elapsed = System.currentTimeMillis() - startTime
        
        if (elapsed >= route.totalDuration) {
            stopRoute()
            return null
        }

        // Find the closest point based on elapsed time
        return route.points.lastOrNull { it.timestamp <= elapsed } ?: route.points.firstOrNull()
    }
}
