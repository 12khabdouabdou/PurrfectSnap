package me.eternal.purrfectsnap.core.features.impl.experiments.router

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

class RouteMockHandlerTest {
    
    private lateinit var handler: RouteMockHandler
    
    @Before
    fun setup() {
        handler = RouteMockHandler()
    }
    
    @Test
    fun `initial state is Idle`() {
        val state = handler.getState()
        assertEquals(RouteStatus.Idle, state.status)
        assertNull(state.route)
        assertNull(state.startGeopoint)
        assertNull(state.endGeopoint)
    }
    
    @Test
    fun `setStartPoint sets start geopoint`() {
        val point = GeoPoint(37.7749, -122.4194)
        val state = handler.setStartPoint(point)
        
        assertEquals(point, state.startGeopoint)
        assertNull(state.endGeopoint)
        assertNull(state.route)
        assertEquals(RouteStatus.Idle, state.status)
    }
    
    @Test
    fun `setEndPoint sets end geopoint`() {
        val startPoint = GeoPoint(37.7749, -122.4194)
        val endPoint = GeoPoint(37.7849, -122.4294)
        
        handler.setStartPoint(startPoint)
        val state = handler.setEndPoint(endPoint)
        
        assertEquals(startPoint, state.startGeopoint)
        assertEquals(endPoint, state.endGeopoint)
    }
    
    @Test
    fun `clearPoints resets to initial state`() {
        handler.setStartPoint(GeoPoint(37.7749, -122.4194))
        handler.setEndPoint(GeoPoint(37.7849, -122.4294))
        
        val state = handler.clearPoints()
        
        assertNull(state.startGeopoint)
        assertNull(state.endGeopoint)
        assertNull(state.route)
        assertEquals(RouteStatus.Idle, state.status)
    }
    
    @Test
    fun `setProfile updates selected profile`() {
        val state = handler.setProfile(OsrmProfile.DRIVING)
        
        assertEquals(OsrmProfile.DRIVING, state.selectedProfile)
        assertEquals(OsrmProfile.DRIVING.defaultSpeedKmh, state.speedKmh, 0.01)
    }
    
    @Test
    fun `setSpeed clamps to valid range for Walking`() {
        handler.setProfile(OsrmProfile.WALKING)
        
        val stateBelow = handler.setSpeed(1.0)
        assertTrue(stateBelow.speedKmh >= OsrmProfile.WALKING.minSpeedKmh)
        
        val stateAbove = handler.setSpeed(100.0)
        assertTrue(stateAbove.speedKmh <= OsrmProfile.WALKING.maxSpeedKmh)
        
        val stateValid = handler.setSpeed(5.0)
        assertEquals(5.0, stateValid.speedKmh, 0.01)
    }
    
    @Test
    fun `setSpeed clamps to valid range for Driving`() {
        handler.setProfile(OsrmProfile.DRIVING)
        
        val stateBelow = handler.setSpeed(10.0)
        assertTrue(stateBelow.speedKmh >= OsrmProfile.DRIVING.minSpeedKmh)
        
        val stateAbove = handler.setSpeed(200.0)
        assertTrue(stateAbove.speedKmh <= OsrmProfile.DRIVING.maxSpeedKmh)
    }
    
    @Test
    fun `setSpeed clamps to valid range for Cycling`() {
        handler.setProfile(OsrmProfile.CYCLING)
        
        val stateBelow = handler.setSpeed(5.0)
        assertTrue(stateBelow.speedKmh >= OsrmProfile.CYCLING.minSpeedKmh)
        
        val stateAbove = handler.setSpeed(100.0)
        assertTrue(stateAbove.speedKmh <= OsrmProfile.CYCLING.maxSpeedKmh)
    }
    
    @Test
    fun `validateCoordinates accepts valid coordinates`() {
        assertTrue(handler.validateCoordinates(37.7749, -122.4194))
        assertTrue(handler.validateCoordinates(0.0, 0.0))
        assertTrue(handler.validateCoordinates(-90.0, -180.0))
        assertTrue(handler.validateCoordinates(90.0, 180.0))
    }
    
    @Test
    fun `validateCoordinates rejects invalid coordinates`() {
        assertFalse(handler.validateCoordinates(91.0, 0.0))
        assertFalse(handler.validateCoordinates(-91.0, 0.0))
        assertFalse(handler.validateCoordinates(0.0, 181.0))
        assertFalse(handler.validateCoordinates(0.0, -181.0))
    }
    
    @Test
    fun `isValidForCalculation returns false without points`() {
        assertFalse(handler.isValidForCalculation())
    }
    
    @Test
    fun `isValidForCalculation returns true with valid points`() {
        handler.setStartPoint(GeoPoint(37.7749, -122.4194))
        handler.setEndPoint(GeoPoint(37.7849, -122.4294))
        
        assertTrue(handler.isValidForCalculation())
    }
    
    @Test
    fun `pause on Idle state does nothing`() {
        val stateBefore = handler.getState()
        val stateAfter = handler.pause()
        assertEquals(stateBefore.status, stateAfter.status)
    }
    
    @Test
    fun `stop resets route state`() {
        handler.setStartPoint(GeoPoint(37.7749, -122.4194))
        handler.setEndPoint(GeoPoint(37.7849, -122.4294))
        
        val state = handler.stop()
        
        assertNull(state.route)
        assertEquals(RouteStatus.Idle, state.status)
    }
    
    @Test
    fun `getCurrentPosition returns null when not playing`() {
        assertNull(handler.getCurrentPosition())
    }
    
    @Test
    fun `OsrmProfile clampSpeed works correctly`() {
        assertEquals(5.0, OsrmProfile.WALKING.clampSpeed(5.0), 0.01)
        assertEquals(3.0, OsrmProfile.WALKING.clampSpeed(1.0), 0.01)
        assertEquals(7.0, OsrmProfile.WALKING.clampSpeed(100.0), 0.01)
        
        assertEquals(30.0, OsrmProfile.DRIVING.clampSpeed(30.0), 0.01)
        assertEquals(20.0, OsrmProfile.DRIVING.clampSpeed(10.0), 0.01)
        assertEquals(130.0, OsrmProfile.DRIVING.clampSpeed(200.0), 0.01)
        
        assertEquals(15.0, OsrmProfile.CYCLING.clampSpeed(15.0), 0.01)
        assertEquals(10.0, OsrmProfile.CYCLING.clampSpeed(5.0), 0.01)
        assertEquals(40.0, OsrmProfile.CYCLING.clampSpeed(100.0), 0.01)
    }
}
