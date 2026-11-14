package com.trailo.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailo.app.R
import com.trailo.app.domain.location.LocationPermission
import com.trailo.app.domain.location.LocationStatus
import com.trailo.app.domain.location.TrackingMode

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onRequestLocation: () -> Unit,
    onAppSettings: () -> Unit,
    onLocationSettings: () -> Unit,
    onPlayServices: () -> Unit,
    onOpenConvoy: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state, onRequestLocation, onAppSettings, onLocationSettings, onPlayServices, viewModel::recenter,
        onToggleDriving = {
            viewModel.setTrackingMode(if (state.trackingMode == TrackingMode.ACTIVE_DRIVING) TrackingMode.MAP_BROWSING else TrackingMode.ACTIVE_DRIVING)
        }, onCameraGesture = viewModel::onCameraGesture, onToggleCameraMode = viewModel::toggleCameraMode, onOpenConvoy = onOpenConvoy)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRequestLocation: () -> Unit,
    onAppSettings: () -> Unit,
    onLocationSettings: () -> Unit,
    onPlayServices: () -> Unit,
    onRecenter: () -> Unit,
    onToggleDriving: () -> Unit,
    onCameraGesture: () -> Unit,
    onToggleCameraMode: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConvoy: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = if (state.keyConfigured && state.playServicesAvailable) 160.dp else Dp.Infinity)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onToggleCameraMode) {
                            Text(stringResource(if (state.cameraMode == CameraMode.NORTH_UP) R.string.north_up else R.string.heading_follow))
                        }
                    }
                    TextButton(onClick = onOpenConvoy) { Text(stringResource(R.string.convoy_lobby)) }
                    TextButton(onClick = onToggleDriving) {
                        Text(stringResource(if (state.trackingMode == TrackingMode.ACTIVE_DRIVING) R.string.stop_driving else R.string.start_driving))
                    }

                    if (!state.keyConfigured) Text(stringResource(R.string.maps_key_missing))
                    if (!state.playServicesAvailable) {
                        Text(stringResource(R.string.play_services_missing))
                        Button(onClick = onPlayServices) { Text(stringResource(R.string.fix_play_services)) }
                    } else {
                        if (state.isLocationStale) Text(stringResource(R.string.stale_compact), color = MaterialTheme.colorScheme.error)
                        else if (state.permission == LocationPermission.APPROXIMATE) Text(stringResource(R.string.approximate_compact))
                        else if (state.location !is LocationStatus.Available) Text(stringResource(when (state.location) {
                            LocationStatus.PermissionRequired -> R.string.location_permission_explanation
                            LocationStatus.Disabled -> R.string.location_disabled
                            LocationStatus.Unavailable -> R.string.location_updates_unavailable
                            else -> R.string.location_waiting
                        }))
                        when (state.location) {
                            LocationStatus.PermissionRequired -> {
                                Button(onClick = onRequestLocation) { Text(stringResource(R.string.enable_location)) }
                                TextButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings)) }
                            }
                            LocationStatus.Disabled -> Button(onClick = onLocationSettings) { Text(stringResource(R.string.location_settings)) }
                            LocationStatus.Unavailable -> if (state.lastKnownLocation == null) {
                                Button(onClick = onLocationSettings) { Text(stringResource(R.string.location_settings)) }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.keyConfigured && state.playServicesAvailable && state.permission != LocationPermission.NONE) {
                ExtendedFloatingActionButton(onClick = onRecenter) {
                    Text(stringResource(R.string.recenter))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.keyConfigured && state.playServicesAvailable) TrailoMap(state, onCameraGesture)
        }
    }
}
