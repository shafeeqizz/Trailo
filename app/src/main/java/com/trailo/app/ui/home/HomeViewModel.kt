package com.trailo.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailo.app.domain.location.LocationFix
import com.trailo.app.domain.location.LocationPermission
import com.trailo.app.domain.location.LocationRepository
import com.trailo.app.domain.location.LocationStatus
import com.trailo.app.domain.location.LocationTrackingPolicy
import com.trailo.app.domain.location.TrackingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val permission: LocationPermission = LocationPermission.NONE,
    val playServicesAvailable: Boolean = false,
    val keyConfigured: Boolean = false,
    val trackingMode: TrackingMode = TrackingMode.MAP_BROWSING,
    val trackingPolicy: LocationTrackingPolicy = LocationTrackingPolicy.Browsing,
    val isFollowingCamera: Boolean = false,
    val cameraMode: CameraMode = CameraMode.NORTH_UP,
    val travelHeading: Float? = null,
    val location: LocationStatus = LocationStatus.PermissionRequired,
    val lastKnownLocation: LocationFix? = null,
    val isLocationStale: Boolean = false,
    val cameraTarget: LocationFix? = null,
    val recenterRequest: Int = 0,
)

class HomeViewModel(
    private val repository: LocationRepository,
    private val elapsedRealtimeMillis: () -> Long,
    private val policyForMode: (TrackingMode) -> LocationTrackingPolicy = LocationTrackingPolicy::forMode,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(trackingPolicy = policyForMode(TrackingMode.MAP_BROWSING)))
    val uiState = mutableState.asStateFlow()
    private var subscription: Job? = null
    private var staleTimer: Job? = null
    private var foreground = false
    private var activeMode: TrackingMode? = null
    private var generation = 0

    fun start(permission: LocationPermission, playServicesAvailable: Boolean, keyConfigured: Boolean) {
        val previous = mutableState.value
        if (subscription?.isActive == true && previous.permission == permission &&
            previous.playServicesAvailable == playServicesAvailable && previous.keyConfigured == keyConfigured &&
            activeMode == previous.trackingMode
        ) return
        stop()
        foreground = true
        val clearLocation = permission == LocationPermission.NONE ||
            (previous.permission == LocationPermission.PRECISE && permission == LocationPermission.APPROXIMATE)
        mutableState.update {
            it.copy(permission = permission, playServicesAvailable = playServicesAvailable, keyConfigured = keyConfigured,
                lastKnownLocation = if (clearLocation) null else it.lastKnownLocation,
                isLocationStale = !clearLocation && it.lastKnownLocation != null,
                cameraTarget = if (clearLocation) null else it.cameraTarget,
                travelHeading = if (clearLocation) null else it.travelHeading,
                location = if (permission == LocationPermission.NONE) LocationStatus.PermissionRequired else LocationStatus.Waiting)
        }
        if (permission == LocationPermission.NONE || !playServicesAvailable) return
        activeMode = previous.trackingMode
        val requestGeneration = generation
        subscription = viewModelScope.launch {
            try {
                repository.observe(permission, mutableState.value.trackingPolicy).takeWhile { status ->
                    if (requestGeneration != generation) return@takeWhile false
                    if (status == LocationStatus.PermissionRequired) updateLocation(status)
                    status != LocationStatus.PermissionRequired
                }.collect { status ->
                    if (requestGeneration == generation) updateLocation(status)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                if (requestGeneration == generation) updateLocation(LocationStatus.PermissionRequired)
            } catch (_: Exception) {
                if (requestGeneration == generation) updateLocation(LocationStatus.Unavailable)
            }
        }
    }

    fun setTrackingMode(mode: TrackingMode) {
        val state = mutableState.value
        if (state.trackingMode == mode) return
        val wasForeground = foreground
        mutableState.update {
            it.copy(trackingMode = mode, trackingPolicy = policyForMode(mode),
                isFollowingCamera = mode == TrackingMode.ACTIVE_DRIVING,
                cameraTarget = if (mode == TrackingMode.ACTIVE_DRIVING) it.lastKnownLocation else it.cameraTarget)
        }
        if (wasForeground) start(state.permission, state.playServicesAvailable, state.keyConfigured)
    }

    fun onCameraGesture() {
        mutableState.update { it.copy(isFollowingCamera = false) }
    }

    fun toggleCameraMode() {
        mutableState.update { it.copy(cameraMode = if (it.cameraMode == CameraMode.NORTH_UP) CameraMode.HEADING_FOLLOW else CameraMode.NORTH_UP) }
    }

    fun recenter() {
        val state = mutableState.value
        if (state.permission == LocationPermission.NONE || !state.playServicesAvailable) return
        mutableState.update {
            it.copy(isFollowingCamera = true, cameraTarget = it.lastKnownLocation, recenterRequest = it.recenterRequest + 1)
        }
    }

    fun stop() {
        generation++
        foreground = false
        activeMode = null
        subscription?.cancel()
        subscription = null
        staleTimer?.cancel()
        staleTimer = null
        mutableState.update { it.copy(location = LocationStatus.Waiting, isLocationStale = it.lastKnownLocation != null) }
    }

    private fun updateLocation(status: LocationStatus) {
        val fix = (status as? LocationStatus.Available)?.fix
        val previous = mutableState.value.lastKnownLocation
        if (fix != null) {
            if (!fix.point.latitude.isFinite() || fix.point.latitude !in -90.0..90.0 ||
                !fix.point.longitude.isFinite() || fix.point.longitude !in -180.0..180.0 ||
                !fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0 || fix.elapsedRealtimeMillis < 0
            ) return
            if (previous != null && fix.elapsedRealtimeMillis <= previous.elapsedRealtimeMillis) return
        }
        staleTimer?.cancel()
        staleTimer = null
        if (status == LocationStatus.PermissionRequired) {
            generation++
            subscription?.cancel()
            mutableState.update {
                it.copy(location = status, lastKnownLocation = null, cameraTarget = null,
                    travelHeading = null,
                    isLocationStale = false, permission = LocationPermission.NONE)
            }
            return
        }
        val age = fix?.let { (elapsedRealtimeMillis() - it.elapsedRealtimeMillis).coerceAtLeast(0) } ?: 0L
        val staleAfter = mutableState.value.trackingPolicy.staleAfterMillis
        mutableState.update { state ->
            state.copy(location = status, lastKnownLocation = fix ?: state.lastKnownLocation,
                travelHeading = if (fix != null && age < staleAfter) {
                    val recentPrevious = previous?.takeIf { fix.elapsedRealtimeMillis - it.elapsedRealtimeMillis < staleAfter }
                    reliableHeading(recentPrevious, fix, state.permission) ?: state.travelHeading.takeIf { recentPrevious != null }
                } else state.travelHeading,
                isLocationStale = if (fix != null) age >= staleAfter else state.lastKnownLocation != null,
                cameraTarget = if (fix != null && state.isFollowingCamera) fix else state.cameraTarget ?: fix)
        }
        if (fix != null && age < staleAfter) {
            staleTimer = viewModelScope.launch {
                delay(staleAfter - age)
                mutableState.update { it.copy(isLocationStale = it.lastKnownLocation != null) }
            }
        }
    }
}
