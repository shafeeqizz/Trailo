package com.trailo.app.domain.location

import kotlinx.coroutines.flow.Flow

data class GeoPoint(val latitude: Double, val longitude: Double)

data class LocationFix(
    val point: GeoPoint,
    val accuracyMeters: Float,
    /** Monotonic elapsed time of the fix, not callback delivery time. */
    val elapsedRealtimeMillis: Long,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
)

enum class LocationPermission { NONE, APPROXIMATE, PRECISE }

sealed interface LocationStatus {
    data object Waiting : LocationStatus
    data object Disabled : LocationStatus
    data object Unavailable : LocationStatus
    data object PermissionRequired : LocationStatus
    data class Available(val fix: LocationFix) : LocationStatus
}

interface LocationRepository {
    /** A foreground-only subscription. Cancellation must release every listener. */
    fun observe(permission: LocationPermission, policy: LocationTrackingPolicy): Flow<LocationStatus>
}
