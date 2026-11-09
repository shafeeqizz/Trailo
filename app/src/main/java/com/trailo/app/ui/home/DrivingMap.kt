package com.trailo.app.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.trailo.app.R
import com.trailo.app.domain.location.*

/** Map rendering only. Location ownership stays in the repository/ViewModel. */
@Composable
internal fun TrailoMap(state: HomeUiState, onCameraGesture: () -> Unit) {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var hasCentered by rememberSaveable { mutableStateOf(false) }
    var manualZoom by rememberSaveable { mutableStateOf<Float?>(null) }
    var mapHeight by remember { mutableIntStateOf(0) }
    val gestureCallback by rememberUpdatedState(onCameraGesture)
    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving to camera.cameraMoveStartedReason }.collect { (moving, reason) ->
            if (reason == CameraMoveStartedReason.GESTURE) manualZoom = camera.position.zoom
            if (moving && reason == CameraMoveStartedReason.GESTURE) {
                hasCentered = true
                gestureCallback()
            }
        }
    }
    val motion = rememberVehicleMotion(state)
    FollowCamera(state, camera, mapLoaded, hasCentered, mapHeight, manualZoom, onCentered = { hasCentered = true })
    val dark = isSystemInDarkTheme()
    val style = remember(dark) { if (dark) MapStyleOptions("""[{"elementType":"geometry","stylers":[{"color":"#242f3e"}]},{"elementType":"labels.text.fill","stylers":[{"color":"#b8c7d9"}]},{"elementType":"labels.text.stroke","stylers":[{"color":"#242f3e"}]}]""") else null }
    val hasHeading = state.travelHeading != null
    val icon = remember(hasHeading, state.isLocationStale, mapLoaded) {
        if (mapLoaded) vehicleIcon(hasHeading, state.isLocationStale) else null
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize().onSizeChanged { mapHeight = it.height }, cameraPositionState = camera,
        properties = MapProperties(mapStyleOptions = style),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false, mapToolbarEnabled = false, zoomControlsEnabled = false),
        contentDescription = stringResource(R.string.map_description), onMapLoaded = { mapLoaded = true },
    ) {
        state.lastKnownLocation?.let { fix ->
            val color = if (state.isLocationStale) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color(0xFF1877E8)
            Circle(center = motion.marker.position, radius = fix.accuracyMeters.toDouble().coerceAtLeast(1.0),
                fillColor = color.copy(alpha = 0.10f), strokeColor = color.copy(alpha = 0.5f), strokeWidth = 2f)
            if (icon != null) Marker(state = motion.marker, icon = icon, anchor = Offset(0.5f, 0.5f),
                flat = true, rotation = motion.heading.value,
                title = stringResource(if (state.isLocationStale) R.string.last_known_location else R.string.your_location))
        }
    }
}

private class VehicleMotion(val marker: MarkerState, val heading: State<Float>)

/** One bounded animation from the currently rendered point to the latest measurement. */
@Composable
private fun rememberVehicleMotion(state: HomeUiState): VehicleMotion {
    val marker = remember { MarkerState(LatLng(0.0, 0.0)) }
    val heading = remember { mutableFloatStateOf(0f) }
    var previous by remember { mutableStateOf<LocationFix?>(null) }
    val fix = state.lastKnownLocation
    LaunchedEffect(fix, state.isLocationStale) {
        if (fix == null) { previous = null; return@LaunchedEffect }
        val duration = if (state.permission == LocationPermission.APPROXIMATE) 0 else
            markerAnimationMillis(previous, fix, state.isLocationStale, state.trackingPolicy)
        previous = fix
        val start = GeoPoint(marker.position.latitude, marker.position.longitude)
        val startHeading = heading.floatValue
        val endHeading = state.travelHeading ?: startHeading
        if (duration == 0) {
            val end = LatLng(fix.point.latitude, fix.point.longitude)
            if (marker.position != end) marker.position = end
            heading.floatValue = endHeading
        } else {
            Animatable(0f).animateTo(1f, tween(duration, easing = LinearEasing)) {
                val point = interpolateMarker(start, fix.point, value)
                marker.position = LatLng(point.latitude, point.longitude)
                heading.floatValue = interpolateHeading(startHeading, endHeading, value)
            }
        }
    }
    return remember { VehicleMotion(marker, heading) }
}

/** Camera animates once per changed measurement/mode, never once per animation frame. */
@Composable
private fun FollowCamera(state: HomeUiState, camera: CameraPositionState, loaded: Boolean, centered: Boolean, mapHeight: Int, manualZoom: Float?, onCentered: () -> Unit) {
    val fix = state.cameraTarget
    val density = LocalDensity.current.density
    var previousFix by remember { mutableStateOf<LocationFix?>(null) }
    var lastRecenter by remember { mutableIntStateOf(state.recenterRequest) }
    LaunchedEffect(fix?.point, state.cameraMode, state.recenterRequest, loaded, state.isFollowingCamera, mapHeight) {
        val explicitRecenter = lastRecenter != state.recenterRequest
        if (fix == null || !shouldUpdateCamera(loaded, state.isFollowingCamera, centered, state.isLocationStale, explicitRecenter)) return@LaunchedEffect
        lastRecenter = state.recenterRequest
        val zoom = followZoom(manualZoom, state.permission)
        val lookAhead = if (state.trackingMode == TrackingMode.ACTIVE_DRIVING &&
            !state.isLocationStale && state.permission == LocationPermission.PRECISE) lookAheadDistance(fix.point.latitude, zoom, mapHeight, density) else 0.0
        val target = followCameraTarget(fix.point, state.travelHeading, state.cameraMode, lookAhead)
        val position = CameraPosition(LatLng(target.point.latitude, target.point.longitude), zoom, 0f, target.bearing)
        val interrupted = previousFix?.let { fix.elapsedRealtimeMillis - it.elapsedRealtimeMillis >= state.trackingPolicy.staleAfterMillis } ?: false
        previousFix = fix
        onCentered()
        if (camera.position != position) {
            val update = CameraUpdateFactory.newCameraPosition(position)
            if (shouldSnapCamera(state.isLocationStale, interrupted, explicitRecenter)) camera.move(update) else camera.animate(update, durationMs = 800)
        }
    }
}

/** Locally drawn vector-like artwork; no asset/network dependency or default red pin. */
private fun vehicleIcon(directional: Boolean, stale: Boolean): BitmapDescriptor {
    val bitmap = createBitmap(80, 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(40f, 40f, 34f, paint)
    paint.color = if (stale) android.graphics.Color.rgb(110, 115, 120) else android.graphics.Color.rgb(24, 119, 232)
    if (directional) {
        val path = Path().apply { moveTo(40f, 10f); lineTo(65f, 65f); lineTo(40f, 53f); lineTo(15f, 65f); close() }
        canvas.drawPath(path, paint)
    } else canvas.drawCircle(40f, 40f, 23f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
