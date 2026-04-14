package me.eternal.purrfectsnap.core.features.impl.experiments.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.eternal.purrfectsnap.core.features.impl.experiments.router.Result.Companion.Error
import me.eternal.purrfectsnap.core.features.impl.experiments.router.Result.Companion.Success
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

class OsrmClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val BASE_URL = "https://router.project-osrm.org"
        private const val ROUTE_ENDPOINT = "/route/v1"
    }

    suspend fun calculateRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        profile: OsrmProfile,
    ): Result<Route> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(startLng, startLat, endLng, endLat, profile)
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Error("Server error: ${response.code}")
            }

            val responseBody = response.body?.string()
                ?: return@withContext Error("Empty response from server")

            val osrmResponse = parseOsrmResponse(responseBody)

            if (osrmResponse.code != "Ok") {
                return@withContext Error("No route found between these points")
            }

            if (osrmResponse.routes.isEmpty()) {
                return@withContext Error("No route available")
            }

            val osrmRoute = osrmResponse.routes[0]
            val coordinates = parseCoordinates(osrmRoute.geometry.coordinates)

            val route = Route(
                startLat = startLat,
                startLng = startLng,
                endLat = endLat,
                endLng = endLng,
                profile = profile,
                coordinates = coordinates,
                distanceMeters = osrmRoute.distance,
                durationSeconds = osrmRoute.duration,
            )

            Success(route)
        } catch (e: Exception) {
            Error("Failed to calculate route: ${e.message}", e)
        }
    }

    private fun buildUrl(
        startLng: Double,
        startLat: Double,
        endLng: Double,
        endLat: Double,
        profile: OsrmProfile,
    ): String {
        return "$BASE_URL$ROUTE_ENDPOINT/${profile.profileName}/" +
                "$startLng,$startLat;$endLng,$endLat" +
                "?overview=full&geometries=geojson"
    }

    private fun parseOsrmResponse(json: String): OsrmResponse {
        val jsonObject = JSONObject(json)
        val code = jsonObject.optString("code", "")

        val routesArray = jsonObject.optJSONArray("routes") ?: return OsrmResponse(code, emptyList())

        val routes = (0 until routesArray.length()).mapNotNull { i ->
            val routeJson = routesArray.getJSONObject(i)
            val distance = routeJson.optDouble("distance", 0.0)
            val duration = routeJson.optDouble("duration", 0.0)

            val geometryJson = routeJson.getJSONObject("geometry")
            val coordinatesArray = geometryJson.optJSONArray("coordinates") ?: return@mapNotNull null

            val coordinates = (0 until coordinatesArray.length()).mapNotNull { j ->
                val coordArray = coordinatesArray.getJSONArray(j)
                val lng = coordArray.optDouble(0, 0.0)
                val lat = coordArray.optDouble(1, 0.0)
                listOf(lng, lat)
            }

            OsrmRoute(
                distance = distance,
                duration = duration,
                geometry = OsrmGeometry(coordinates)
            )
        }

        return OsrmResponse(code, routes)
    }

    private fun parseCoordinates(coordinates: List<List<Double>>): List<GeoPoint> {
        return coordinates.map { coord ->
            val lng = coord[0]
            val lat = coord[1]
            GeoPoint(lat, lng)
        }
    }
}
