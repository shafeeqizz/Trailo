package com.trailo.app.ui.home

import com.trailo.app.domain.location.GeoPoint
import com.trailo.app.domain.location.LocationFix
import com.trailo.app.domain.location.LocationTrackingPolicy

fun markerAnimationMillis(previous: LocationFix?, next: LocationFix, stale: Boolean, policy: LocationTrackingPolicy): Int {
    if (previous == null || stale || previous.point == next.point) return 0
    if (previous.accuracyMeters > 30f || next.accuracyMeters > 30f) return 0
    val gap = next.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
    if (gap <= 0 || gap >= policy.staleAfterMillis) return 0
    return gap.coerceAtMost(policy.maxAnimationMillis.toLong()).toInt()
}

/** Bounded interpolation between measured endpoints, never extrapolation. */
fun interpolateMarker(start: GeoPoint, end: GeoPoint, fraction: Float): GeoPoint {
    val progress = fraction.coerceIn(0f, 1f).toDouble()
    val longitudeDelta = ((end.longitude - start.longitude + 540) % 360) - 180
    val longitude = ((start.longitude + longitudeDelta * progress + 540) % 360) - 180
    return GeoPoint(start.latitude + (end.latitude - start.latitude) * progress, longitude)
}
