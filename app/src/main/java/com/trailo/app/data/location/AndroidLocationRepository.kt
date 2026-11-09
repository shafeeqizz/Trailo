package com.trailo.app.data.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trailo.app.domain.location.GeoPoint
import com.trailo.app.domain.location.LocationFix
import com.trailo.app.domain.location.LocationRepository
import com.trailo.app.domain.location.LocationTrackingPolicy
import com.trailo.app.domain.location.TrackingAccuracy
import com.trailo.app.domain.location.LocationPermission
import com.trailo.app.domain.location.LocationStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.callbackFlow

class AndroidLocationRepository(context: Context) : LocationRepository {
    private val context = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(this.context)
    private val manager = this.context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // Caller checks permission; revocation is also handled below.
    override fun observe(permission: LocationPermission, policy: LocationTrackingPolicy) = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var generation = 0
        var callback: LocationCallback? = null
        val timeout = Runnable { trySend(LocationStatus.Unavailable) }

        fun restart() {
            generation++
            val requestGeneration = generation
            callback?.let(client::removeLocationUpdates)
            callback = null
            handler.removeCallbacks(timeout)
            if (permission == LocationPermission.NONE) {
                trySend(LocationStatus.PermissionRequired)
                return
            }
            if (!LocationManagerCompat.isLocationEnabled(manager)) {
                trySend(LocationStatus.Disabled)
                return
            }
            trySend(LocationStatus.Waiting)
            val next = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (closed || requestGeneration != generation) return
                    for (location in result.locations) {
                        handler.removeCallbacks(timeout)
                        trySend(LocationStatus.Available(LocationFix(
                            GeoPoint(location.latitude, location.longitude), location.accuracy,
                            location.elapsedRealtimeNanos / 1_000_000,
                            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                            bearingAccuracyDegrees = location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() },
                            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                            speedAccuracyMetersPerSecond = location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
                        )))
                    }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!closed && requestGeneration == generation && !availability.isLocationAvailable) {
                        trySend(LocationStatus.Unavailable)
                    }
                }
            }
            callback = next
            val priority = if (policy.accuracy == TrackingAccuracy.HIGH) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val request = LocationRequest.Builder(priority, policy.intervalMillis)
                .setMinUpdateIntervalMillis(policy.minIntervalMillis)
                .setMinUpdateDistanceMeters(policy.minDistanceMeters)
                .setMaxUpdateAgeMillis(policy.initialMaxAgeMillis)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .build()
            try {
                client.requestLocationUpdates(request, next, Looper.getMainLooper())
                    .addOnSuccessListener {
                        // Registration can finish after cancellation or a settings change.
                        if (closed || requestGeneration != generation) client.removeLocationUpdates(next)
                    }
                    .addOnFailureListener { error ->
                        if (!closed && requestGeneration == generation) {
                            handler.removeCallbacks(timeout)
                            trySend(if (error is SecurityException) LocationStatus.PermissionRequired else LocationStatus.Unavailable)
                        }
                    }
                handler.postDelayed(timeout, 20_000)
            } catch (_: SecurityException) {
                trySend(LocationStatus.PermissionRequired)
            } catch (_: Exception) {
                trySend(LocationStatus.Unavailable)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = restart()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            restart()
            awaitCancellation()
        } finally {
            closed = true
            generation++
            handler.removeCallbacks(timeout)
            callback?.let(client::removeLocationUpdates)
            context.unregisterReceiver(receiver)
        }
    }

}
