package me.eternal.purrfectsnap.core.features.impl.experiments.routing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

class InterpolatorEngineTest {

    @Test
    fun testHaversineDistance() {
        // Paris to London is approximately 344km
        val paris = GeoPoint(48.8566, 2.3522)
        val london = GeoPoint(51.5074, -0.1278) // London is West of Prime Meridian
        
        val distance = InterpolatorEngine.haversineDistance(paris, london)
        // Haversine distance on sphere is approx 344,000 meters.
        assertEquals(344000.0, distance, 5000.0) // Large tolerance for spherical vs geoid distance
    }

    @Test
    fun testCalculateStep() {
        val start = GeoPoint(0.0, 0.0)
        val end = GeoPoint(1.0, 0.0) // 1 degree North is ~111km
        
        val totalDistance = InterpolatorEngine.haversineDistance(start, end)
        val midPoint = InterpolatorEngine.calculateStep(start, end, totalDistance / 2.0)
        
        assertEquals(0.5, midPoint.latitude, 0.001)
        assertEquals(0.0, midPoint.longitude, 0.001)
    }

    @Test
    fun testGetPositionAtDistance() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 1.0) // East by 1 degree
        val p3 = GeoPoint(1.0, 1.0) // North by 1 degree
        val route = listOf(p1, p2, p3)
        
        val d1 = InterpolatorEngine.haversineDistance(p1, p2)
        val d2 = InterpolatorEngine.haversineDistance(p2, p3)
        
        // 1. Exactly at start
        var pos = InterpolatorEngine.getPositionAtDistance(route, 0.0)
        assertEquals(p1.latitude, pos.latitude, 0.00001)
        assertEquals(p1.longitude, pos.longitude, 0.00001)
        
        // 2. Midway through first segment
        pos = InterpolatorEngine.getPositionAtDistance(route, d1 / 2.0)
        assertEquals(0.0, pos.latitude, 0.00001)
        assertEquals(0.5, pos.longitude, 0.00001)
        
        // 3. Exactly at point 2
        pos = InterpolatorEngine.getPositionAtDistance(route, d1)
        assertEquals(0.0, pos.latitude, 0.00001)
        assertEquals(1.0, pos.longitude, 0.00001)
        
        // 4. Midway through second segment
        pos = InterpolatorEngine.getPositionAtDistance(route, d1 + (d2 / 2.0))
        assertEquals(0.5, pos.latitude, 0.00001)
        assertEquals(1.0, pos.longitude, 0.00001)
        
        // 5. Exactly at end
        pos = InterpolatorEngine.getPositionAtDistance(route, d1 + d2)
        assertEquals(p3.latitude, pos.latitude, 0.00001)
        assertEquals(p3.longitude, pos.longitude, 0.00001)
        
        // 6. Beyond end (clamp)
        pos = InterpolatorEngine.getPositionAtDistance(route, d1 + d2 + 1000.0)
        assertEquals(p3.latitude, pos.latitude, 0.00001)
        assertEquals(p3.longitude, pos.longitude, 0.00001)
    }
}
