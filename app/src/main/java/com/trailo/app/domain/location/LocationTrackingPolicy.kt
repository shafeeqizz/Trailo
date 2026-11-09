package com.trailo.app.domain.location

enum class TrackingMode { MAP_BROWSING, ACTIVE_DRIVING }
enum class TrackingAccuracy { BALANCED, HIGH }

/** Requested starting parameters, not a promise of sensor or callback frequency. */
data class LocationTrackingPolicy(
    val accuracy: TrackingAccuracy,
    val intervalMillis: Long,
    val minIntervalMillis: Long,
    val minDistanceMeters: Float,
    val initialMaxAgeMillis: Long,
    val staleAfterMillis: Long,
    val maxAnimationMillis: Int = 800,
) {
    init {
        require(intervalMillis > 0 && minIntervalMillis in 0..intervalMillis)
        require(minDistanceMeters.isFinite() && minDistanceMeters >= 0)
        require(initialMaxAgeMillis >= 0 && staleAfterMillis >= intervalMillis)
        require(maxAnimationMillis in 0..1000)
    }

    companion object {
        val Browsing = LocationTrackingPolicy(TrackingAccuracy.BALANCED, 10_000, 5_000, 10f, 10_000, 60_000)
        val Driving = LocationTrackingPolicy(TrackingAccuracy.HIGH, 1_000, 500, 0f, 0, 10_000)

        fun forMode(mode: TrackingMode) = when (mode) {
            TrackingMode.MAP_BROWSING -> Browsing
            TrackingMode.ACTIVE_DRIVING -> Driving
        }
    }
}
