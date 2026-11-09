package com.trailo.app.ui.home

import com.trailo.app.domain.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val updates = MutableSharedFlow<LocationStatus>()
    private var subscriptions = 0
    private var cancellations = 0
    private var active = 0
    private var maximumActive = 0
    private var observedPermission: LocationPermission? = null
    private var observedPolicy: LocationTrackingPolicy? = null
    private val repository = object : LocationRepository {
        override fun observe(permission: LocationPermission, policy: LocationTrackingPolicy): Flow<LocationStatus> = flow {
            subscriptions++
            active++
            maximumActive = maxOf(maximumActive, active)
            observedPermission = permission
            observedPolicy = policy
            try { emitAll(updates) } finally { active--; cancellations++ }
        }
    }
    private lateinit var model: HomeViewModel
    private val first = LocationFix(GeoPoint(1.3, 103.8), 5f, 0)
    private val second = LocationFix(GeoPoint(1.31, 103.81), 5f, 1)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = HomeViewModel(repository, { dispatcher.scheduler.currentTime })
    }
    @After fun tearDown() { model.stop(); Dispatchers.resetMain() }
    private fun start(permission: LocationPermission = LocationPermission.PRECISE) = model.start(permission, true, true)
    private fun drive() { model.setTrackingMode(TrackingMode.ACTIVE_DRIVING); start() }

    @Test fun `camera orientation preserves paused follow and subscription`() {
        drive()
        model.onCameraGesture()
        val before = subscriptions
        model.toggleCameraMode()
        assertEquals(CameraMode.HEADING_FOLLOW, model.uiState.value.cameraMode)
        assertFalse(model.uiState.value.isFollowingCamera)
        assertEquals(before, subscriptions)
        model.recenter()
        assertTrue(model.uiState.value.isFollowingCamera)
        assertEquals(CameraMode.HEADING_FOLLOW, model.uiState.value.cameraMode)
        model.toggleCameraMode()
        assertEquals(CameraMode.NORTH_UP, model.uiState.value.cameraMode)
    }
    @Test fun `heading survives stationary fixes but clears after loss or permission downgrade`() = runTest {
        drive()
        val moving = first.copy(bearingDegrees = 90f, bearingAccuracyDegrees = 5f, speedMetersPerSecond = 10f)
        updates.emit(LocationStatus.Available(moving))
        assertEquals(90f, model.uiState.value.travelHeading!!, 0f)
        updates.emit(LocationStatus.Available(first.copy(elapsedRealtimeMillis = 1)))
        assertEquals(90f, model.uiState.value.travelHeading!!, 0f)
        advanceTimeBy(11000)
        updates.emit(LocationStatus.Available(first.copy(elapsedRealtimeMillis = 11000)))
        assertNull(model.uiState.value.travelHeading)
        updates.emit(LocationStatus.Available(moving.copy(elapsedRealtimeMillis = 11001)))
        start(LocationPermission.APPROXIMATE)
        assertNull(model.uiState.value.travelHeading)
    }
    @Test fun `denied permission never subscribes`() {
        start(LocationPermission.NONE)
        model.recenter()
        assertEquals(0, subscriptions)
        assertEquals(LocationStatus.PermissionRequired, model.uiState.value.location)
        assertNull(model.uiState.value.lastKnownLocation)
    }
    @Test fun `missing play services never subscribes`() {
        model.start(LocationPermission.PRECISE, false, true)
        assertEquals(0, subscriptions)
    }
    @Test fun `browsing uses battery efficient policy`() {
        start()
        assertEquals(TrackingAccuracy.BALANCED, observedPolicy!!.accuracy)
        assertEquals(10_000L, observedPolicy!!.intervalMillis)
        assertFalse(model.uiState.value.isFollowingCamera)
    }
    @Test fun `driving requests continuous high accuracy with initial one second policy`() {
        drive()
        assertEquals(TrackingAccuracy.HIGH, observedPolicy!!.accuracy)
        assertEquals(1_000L, observedPolicy!!.intervalMillis)
        assertEquals(500L, observedPolicy!!.minIntervalMillis)
        assertEquals(0f, observedPolicy!!.minDistanceMeters)
        assertTrue(model.uiState.value.isFollowingCamera)
    }
    @Test fun `policy parameters can be configured without changing subscription logic`() {
        val custom = LocationTrackingPolicy.Driving.copy(intervalMillis = 2_000, minIntervalMillis = 1_000)
        val configurable = HomeViewModel(repository, { 0L }, { custom })
        configurable.start(LocationPermission.PRECISE, true, true)
        assertEquals(custom, observedPolicy)
        configurable.stop()
    }
    @Test fun `invalid policy constraints are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LocationTrackingPolicy.Driving.copy(minIntervalMillis = 2_000) }
        assertThrows(IllegalArgumentException::class.java) { LocationTrackingPolicy.Driving.copy(minDistanceMeters = -1f) }
    }
    @Test fun `mode switch replaces subscription without overlap`() {
        start()
        model.setTrackingMode(TrackingMode.ACTIVE_DRIVING)
        assertEquals(2, subscriptions)
        assertEquals(1, cancellations)
        assertEquals(1, maximumActive)
        assertEquals(LocationTrackingPolicy.Driving, observedPolicy)
    }
    @Test fun `repeated resume or mode selection does not duplicate subscriptions`() {
        drive(); start(); model.setTrackingMode(TrackingMode.ACTIVE_DRIVING)
        assertEquals(1, subscriptions)
    }
    @Test fun `changing mode while stopped does not start tracking`() {
        start(); model.stop(); model.setTrackingMode(TrackingMode.ACTIVE_DRIVING)
        assertEquals(1, subscriptions)
        assertEquals(0, active)
        start()
        assertEquals(LocationTrackingPolicy.Driving, observedPolicy)
    }
    @Test fun `newer driving fixes update marker and follow camera automatically`() = runTest {
        drive()
        updates.emit(LocationStatus.Available(first))
        updates.emit(LocationStatus.Available(second))
        assertEquals(second, model.uiState.value.lastKnownLocation)
        assertEquals(second, model.uiState.value.cameraTarget)
        assertEquals(1, subscriptions)
    }
    @Test fun `panning pauses camera but continues marker tracking`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first))
        model.onCameraGesture()
        updates.emit(LocationStatus.Available(second))
        assertEquals(second, model.uiState.value.lastKnownLocation)
        assertEquals(first, model.uiState.value.cameraTarget)
        assertFalse(model.uiState.value.isFollowingCamera)
        assertEquals(1, subscriptions)
    }
    @Test fun `panning before the first fix preserves the paused follow intent`() = runTest {
        drive(); model.onCameraGesture()
        updates.emit(LocationStatus.Available(first))
        assertFalse(model.uiState.value.isFollowingCamera)
        assertEquals(first, model.uiState.value.lastKnownLocation)
        assertEquals(1, subscriptions)
    }
    @Test fun `recenter resumes following for subsequent measurements without a new request`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first)); model.onCameraGesture()
        updates.emit(LocationStatus.Available(second))
        model.recenter()
        assertTrue(model.uiState.value.isFollowingCamera)
        assertEquals(second, model.uiState.value.cameraTarget)
        val third = second.copy(elapsedRealtimeMillis = 2)
        updates.emit(LocationStatus.Available(third))
        assertEquals(third, model.uiState.value.cameraTarget)
        assertEquals(1, subscriptions)
    }
    @Test fun `browsing initially centers but does not force follow`() = runTest {
        start(); updates.emit(LocationStatus.Available(first)); updates.emit(LocationStatus.Available(second))
        assertEquals(second, model.uiState.value.lastKnownLocation)
        assertEquals(first, model.uiState.value.cameraTarget)
    }
    @Test fun `stationary fixes renew freshness without requesting camera animation`() = runTest(dispatcher) {
        drive(); updates.emit(LocationStatus.Available(first))
        advanceTimeBy(5_000)
        val stationary = first.copy(elapsedRealtimeMillis = dispatcher.scheduler.currentTime)
        updates.emit(LocationStatus.Available(stationary))
        assertEquals(first.point, model.uiState.value.cameraTarget!!.point)
        assertEquals(0, model.uiState.value.recenterRequest)
        assertEquals(0, markerAnimationMillis(first, stationary, false, LocationTrackingPolicy.Driving))
        assertFalse(model.uiState.value.isLocationStale)
    }
    @Test fun `older and equal timestamp fixes cannot overwrite coordinates or cancel freshness timer`() = runTest(dispatcher) {
        drive(); updates.emit(LocationStatus.Available(second))
        updates.emit(LocationStatus.Available(first))
        updates.emit(LocationStatus.Available(second.copy(point = first.point)))
        assertEquals(second, model.uiState.value.lastKnownLocation)
        advanceTimeBy(LocationTrackingPolicy.Driving.staleAfterMillis)
        runCurrent()
        assertTrue(model.uiState.value.isLocationStale)
    }
    @Test fun `invalid positions and accuracy are ignored`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first))
        for (invalid in listOf(second.copy(point = GeoPoint(Double.NaN, 0.0)), second.copy(point = GeoPoint(91.0, 0.0)), second.copy(accuracyMeters = -1f))) {
            updates.emit(LocationStatus.Available(invalid))
        }
        assertEquals(first, model.uiState.value.lastKnownLocation)
    }
    @Test fun `driving silence becomes stale at policy boundary without moving marker`() = runTest(dispatcher) {
        drive(); updates.emit(LocationStatus.Available(first))
        advanceTimeBy(LocationTrackingPolicy.Driving.staleAfterMillis - 1); runCurrent()
        assertFalse(model.uiState.value.isLocationStale)
        advanceTimeBy(1); runCurrent()
        assertTrue(model.uiState.value.isLocationStale)
        assertEquals(first, model.uiState.value.lastKnownLocation)
        assertEquals(first, model.uiState.value.cameraTarget)
    }
    @Test fun `temporary unavailable and disabled states retain stale marker`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first))
        for (status in listOf(LocationStatus.Unavailable, LocationStatus.Disabled)) {
            updates.emit(status)
            assertTrue(model.uiState.value.isLocationStale)
            assertEquals(first, model.uiState.value.lastKnownLocation)
        }
        updates.emit(LocationStatus.Available(second))
        assertFalse(model.uiState.value.isLocationStale)
        assertEquals(second, model.uiState.value.cameraTarget)
    }
    @Test fun `unavailable without any fix has no marker`() = runTest {
        drive(); updates.emit(LocationStatus.Unavailable)
        assertNull(model.uiState.value.lastKnownLocation)
        assertFalse(model.uiState.value.isLocationStale)
    }
    @Test fun `stop cancels and resume restores selected policy with retained stale fix`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first)); model.stop()
        assertEquals(0, active)
        assertEquals(first, model.uiState.value.lastKnownLocation)
        assertTrue(model.uiState.value.isLocationStale)
        start()
        assertEquals(2, subscriptions)
        assertEquals(LocationTrackingPolicy.Driving, observedPolicy)
    }
    @Test fun `old freshness deadline is cancelled across lifecycle restart`() = runTest(dispatcher) {
        drive(); updates.emit(LocationStatus.Available(first)); advanceTimeBy(6_000)
        model.stop(); start()
        updates.emit(LocationStatus.Available(first.copy(elapsedRealtimeMillis = dispatcher.scheduler.currentTime)))
        advanceTimeBy(4_000); runCurrent()
        assertFalse(model.uiState.value.isLocationStale)
    }
    @Test fun `permission revocation clears coordinates and cancels observer`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first)); updates.emit(LocationStatus.PermissionRequired)
        assertEquals(0, active)
        assertNull(model.uiState.value.lastKnownLocation)
        assertNull(model.uiState.value.cameraTarget)
        assertEquals(LocationPermission.NONE, model.uiState.value.permission)
    }
    @Test fun `precision downgrade clears precise coordinates without upgrading permission`() = runTest {
        drive(); updates.emit(LocationStatus.Available(first)); start(LocationPermission.APPROXIMATE)
        assertNull(model.uiState.value.lastKnownLocation)
        assertEquals(LocationPermission.APPROXIMATE, observedPermission)
        assertEquals(LocationTrackingPolicy.Driving, observedPolicy)
    }
    @Test fun `source failure retains last known fix and exposes stale status`() {
        val failing = HomeViewModel(object : LocationRepository {
            override fun observe(permission: LocationPermission, policy: LocationTrackingPolicy): Flow<LocationStatus> = flow {
                emit(LocationStatus.Available(first)); throw IllegalStateException()
            }
        }, { 0L })
        failing.start(LocationPermission.PRECISE, true, true)
        assertEquals(LocationStatus.Unavailable, failing.uiState.value.location)
        assertEquals(first, failing.uiState.value.lastKnownLocation)
        assertTrue(failing.uiState.value.isLocationStale)
        failing.stop()
    }
    @Test fun `security error clears retained coordinates`() {
        val failing = HomeViewModel(object : LocationRepository {
            override fun observe(permission: LocationPermission, policy: LocationTrackingPolicy): Flow<LocationStatus> = flow { throw SecurityException() }
        }, { 0L })
        failing.start(LocationPermission.PRECISE, true, true)
        assertEquals(LocationStatus.PermissionRequired, failing.uiState.value.location)
        failing.stop()
    }
}
