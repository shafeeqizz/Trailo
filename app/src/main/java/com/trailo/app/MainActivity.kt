package com.trailo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.trailo.app.data.location.AndroidLocationRepository
import com.trailo.app.domain.location.LocationPermission
import com.trailo.app.ui.home.HomeRoute
import com.trailo.app.ui.home.HomeViewModel
import com.trailo.app.ui.theme.TrailoTheme

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AndroidLocationRepository(applicationContext), SystemClock::elapsedRealtime) as T
            }
        }
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshEnvironment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrailoTheme {
                HomeRoute(
                    viewModel = homeViewModel,
                    onRequestLocation = {
                        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    onAppSettings = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
                    onLocationSettings = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onPlayServices = {
                        val availability = GoogleApiAvailability.getInstance()
                        availability.getErrorDialog(this, availability.isGooglePlayServicesAvailable(this), 100)?.show()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
    }

    override fun onStop() {
        homeViewModel.stop()
        super.onStop()
    }

    private fun refreshEnvironment() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val permission = when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
            else -> LocationPermission.NONE
        }
        homeViewModel.start(permission,
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS,
            BuildConfig.MAPS_KEY_CONFIGURED)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(intent: Intent) {
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }
}
