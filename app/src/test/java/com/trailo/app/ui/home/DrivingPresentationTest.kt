package com.trailo.app.ui.home

import com.trailo.app.domain.location.*
import org.junit.Assert.*
import org.junit.Test

class DrivingPresentationTest {
    @Test fun `recenter on fresh data animates even after a long manual pause`() {
        assertFalse(shouldSnapCamera(false, true, true))
        assertTrue(shouldSnapCamera(true, true, true))
        assertTrue(shouldSnapCamera(false, true, false))
        assertFalse(shouldSnapCamera(false, false, false))
    }
    @Test fun `stale banner resize never moves camera automatically`() {
        assertFalse(shouldUpdateCamera(true, true, true, true, false))
        assertTrue(shouldUpdateCamera(true, true, true, true, true))
        assertFalse(shouldUpdateCamera(true, false, true, false, false))
        assertFalse(shouldUpdateCamera(false, true, false, false, true))
        assertTrue(shouldUpdateCamera(true, true, true, false, false))
        assertTrue(shouldUpdateCamera(true, false, false, true, false))
    }
    private val first = LocationFix(GeoPoint(1.3, 103.8), 4f, 0)
    private val measured = first.copy(elapsedRealtimeMillis = 1000, bearingDegrees = 90f,
        bearingAccuracyDegrees = 5f, speedMetersPerSecond = 12f, speedAccuracyMetersPerSecond = 1f)

    @Test fun `following zoom is independent of interrupted camera animation`() {
        assertEquals(17f, followZoom(null, LocationPermission.PRECISE), 0f)
        assertEquals(12f, followZoom(null, LocationPermission.APPROXIMATE), 0f)
        assertEquals(18f, followZoom(18f, LocationPermission.PRECISE), 0f)
        assertEquals(17f, followZoom(Float.NaN, LocationPermission.PRECISE), 0f)
    }

    @Test fun `measured accurate moving course is used`() {
        assertEquals(90f, reliableHeading(first, measured, LocationPermission.PRECISE)!!, 0f)
    }
    @Test fun `coarse stationary uncertain and invalid headings are not trusted`() {
        assertNull(reliableHeading(first, measured, LocationPermission.APPROXIMATE))
        assertNull(reliableHeading(first, measured.copy(speedMetersPerSecond = 0f), LocationPermission.PRECISE))
        assertNull(reliableHeading(first, measured.copy(bearingAccuracyDegrees = 30f), LocationPermission.PRECISE))
        assertNull(reliableHeading(first, measured.copy(bearingDegrees = Float.NaN), LocationPermission.PRECISE))
        assertNull(reliableHeading(first, measured.copy(accuracyMeters = 100f), LocationPermission.PRECISE))
    }
    @Test fun `clearly separated gps measurements can supply course`() {
        val next = first.copy(point = GeoPoint(1.3002, 103.8), elapsedRealtimeMillis = 1000)
        assertEquals(0f, reliableHeading(first, next, LocationPermission.PRECISE)!!, 0.01f)
        assertNull(reliableHeading(first, next.copy(elapsedRealtimeMillis = 20000), LocationPermission.PRECISE))
        assertNull(reliableHeading(first, next.copy(point = GeoPoint(1.300001, 103.8)), LocationPermission.PRECISE))
    }
    @Test fun `heading interpolation takes shortest arc and never extrapolates`() {
        assertEquals(0f, interpolateHeading(350f, 10f, .5f), .01f)
        assertEquals(350f, interpolateHeading(350f, 10f, -1f), .01f)
        assertEquals(10f, interpolateHeading(350f, 10f, 2f), .01f)
    }
    @Test fun `look ahead scales to viewport and zoom to keep vehicle visible`() {
        val wide = lookAheadDistance(1.3, 17f, 1800)
        val close = lookAheadDistance(1.3, 22f, 1800)
        assertEquals(32.0, wide / close, .001)
        assertEquals(0.0, lookAheadDistance(1.3, 17f, 0), 0.0)
        assertTrue(wide in 0.0..1000.0)
        assertEquals(wide / 2.75, lookAheadDistance(1.3, 17f, 1800, 2.75f), .001)
    }
    @Test fun `uncertain location does not animate an inferred journey`() {
        val next = first.copy(point = GeoPoint(1.3002, 103.8), accuracyMeters = 300f, elapsedRealtimeMillis = 1000)
        assertEquals(0, markerAnimationMillis(first, next, false, LocationTrackingPolicy.Driving))
        assertEquals(0, markerAnimationMillis(first.copy(accuracyMeters = 300f), next.copy(accuracyMeters = 5f), false, LocationTrackingPolicy.Driving))
    }
    @Test fun `north up and heading up have bounded look ahead without moving measured marker`() {
        val north = followCameraTarget(first.point, 90f, CameraMode.NORTH_UP, 65.0)
        assertEquals(0f, north.bearing, 0f)
        assertTrue(north.point.longitude > first.point.longitude)
        val east = followCameraTarget(first.point, 90f, CameraMode.HEADING_FOLLOW, 65.0)
        assertEquals(90f, east.bearing, 0f)
        assertTrue(east.point.longitude > first.point.longitude)
        val stopped = followCameraTarget(first.point, 90f, CameraMode.HEADING_FOLLOW, 0.0)
        assertEquals(first.point.latitude, stopped.point.latitude, .0000001)
        assertEquals(first.point.longitude, stopped.point.longitude, .0000001)
    }
}
