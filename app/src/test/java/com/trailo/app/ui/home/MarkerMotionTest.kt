package com.trailo.app.ui.home

import com.trailo.app.domain.location.GeoPoint
import com.trailo.app.domain.location.LocationFix
import com.trailo.app.domain.location.LocationTrackingPolicy
import org.junit.Assert.*
import org.junit.Test

class MarkerMotionTest {
    private val policy = LocationTrackingPolicy.Driving
    private val first = LocationFix(GeoPoint(1.0, 103.0), 5f, 0)
    @Test fun `normal measured transition has bounded duration`() {
        val next = first.copy(point = GeoPoint(1.01, 103.01), elapsedRealtimeMillis = 1_000)
        assertEquals(800, markerAnimationMillis(first, next, false, policy))
    }
    @Test fun `stationary measurements do not move marker`() {
        assertEquals(0, markerAnimationMillis(first, first.copy(elapsedRealtimeMillis = 1_000), false, policy))
    }
    @Test fun `prolonged loss and stale state never interpolate missing travel`() {
        val next = first.copy(point = GeoPoint(1.1, 103.1), elapsedRealtimeMillis = 30_000)
        assertEquals(0, markerAnimationMillis(first, next, false, policy))
        assertEquals(0, markerAnimationMillis(first, next.copy(elapsedRealtimeMillis = 1_000), true, policy))
    }
    @Test fun `interpolation clamps endpoints rather than extrapolating`() {
        val end = GeoPoint(2.0, 104.0)
        assertEquals(first.point, interpolateMarker(first.point, end, -1f))
        assertEquals(end, interpolateMarker(first.point, end, 2f))
    }
    @Test fun `antimeridian transition takes the short path`() {
        val middle = interpolateMarker(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0), 0.5f)
        assertEquals(-180.0, middle.longitude, 0.00001)
        assertEquals(0.0, middle.latitude, 0.00001)
    }
}
