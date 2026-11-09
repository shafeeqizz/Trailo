package com.trailo.app.ui.home

import com.trailo.app.domain.location.GeoPoint
import com.trailo.app.domain.location.LocationFix
import com.trailo.app.domain.location.LocationPermission
import kotlin.math.*

enum class CameraMode { NORTH_UP, HEADING_FOLLOW }

fun shouldUpdateCamera(loaded: Boolean, following: Boolean, centered: Boolean, stale: Boolean, explicitRecenter: Boolean): Boolean =
    loaded && (following || !centered) && (!stale || !centered || explicitRecenter)

fun shouldSnapCamera(stale: Boolean, interrupted: Boolean, explicitRecenter: Boolean): Boolean =
    stale || (interrupted && !explicitRecenter)

/** Use the intended zoom, never an interrupted animation's intermediate value. */
fun followZoom(manualZoom: Float?, permission: LocationPermission): Float =
    manualZoom?.takeIf { it.isFinite() && it in 10f..21f } ?: if (permission == LocationPermission.APPROXIMATE) 12f else 17f

/** Course over ground, not a compass. Never infer direction from coarse or noisy fixes. */
fun reliableHeading(previous: LocationFix?, next: LocationFix, permission: LocationPermission): Float? {
    if (permission != LocationPermission.PRECISE || next.accuracyMeters > 30f) return null
    val speed = next.speedMetersPerSecond
    val speedError = next.speedAccuracyMetersPerSecond ?: 0f
    val bearing = next.bearingDegrees
    val bearingError = next.bearingAccuracyDegrees
    if (speed != null && (!speed.isFinite() || !speedError.isFinite() || speedError < 0f || speed - speedError < 2f)) return null
    if (speed != null && speed.isFinite() && speedError.isFinite() && speedError >= 0f &&
        speed - speedError >= 2f && bearing != null && bearing.isFinite() &&
        bearingError != null && bearingError.isFinite() && bearingError in 0f..20f
    ) return normalizeAngle(bearing)

    // Some providers omit bearing. Only derive course from clearly separated GPS measurements.
    if (previous == null || previous.accuracyMeters > 30f) return null
    val seconds = (next.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1000.0
    if (seconds !in 0.5..5.0) return null
    val a = previous.point
    val b = next.point
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val delta = Math.toRadians(b.longitude - a.longitude)
    val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(delta / 2).pow(2)
    val distance = 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    if (distance < max(10.0, (previous.accuracyMeters + next.accuracyMeters).toDouble()) ||
        distance / seconds !in 2.0..65.0
    ) return null
    return normalizeAngle(Math.toDegrees(atan2(sin(delta) * cos(lat2),
        cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(delta))).toFloat())
}

fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

fun interpolateHeading(start: Float, end: Float, fraction: Float): Float {
    val delta = ((end - start + 540f) % 360f) - 180f
    return normalizeAngle(start + delta * fraction.coerceIn(0f, 1f))
}

data class FollowCameraTarget(val point: GeoPoint, val bearing: Float)

fun lookAheadDistance(latitude: Double, zoom: Float, viewportHeightPixels: Int, density: Float = 1f): Double =
    (156543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom.toDouble()) *
        viewportHeightPixels.coerceAtLeast(0) / density.takeIf { it.isFinite() && it > 0f }.let { it ?: 1f } * 0.18).coerceIn(0.0, 1000.0)

/** Look ahead along travel while keeping camera orientation independent of course. */
fun followCameraTarget(point: GeoPoint, heading: Float?, mode: CameraMode, lookAheadMeters: Double): FollowCameraTarget {
    val bearing = if (mode == CameraMode.HEADING_FOLLOW) heading ?: 0f else 0f
    val angular = lookAheadMeters.coerceIn(0.0, 1000.0) / 6_371_000
    val lat = Math.toRadians(point.latitude)
    val lon = Math.toRadians(point.longitude)
    val direction = Math.toRadians((heading ?: 0f).toDouble())
    val targetLat = asin((sin(lat) * cos(angular) + cos(lat) * sin(angular) * cos(direction)).coerceIn(-1.0, 1.0))
    val targetLon = lon + atan2(sin(direction) * sin(angular) * cos(lat), cos(angular) - sin(lat) * sin(targetLat))
    return FollowCameraTarget(GeoPoint(Math.toDegrees(targetLat), ((Math.toDegrees(targetLon) + 540) % 360) - 180), bearing)
}
