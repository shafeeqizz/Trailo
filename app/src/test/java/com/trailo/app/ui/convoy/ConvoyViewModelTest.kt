package com.trailo.app.ui.convoy

import com.trailo.app.domain.convoy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvoyViewModelTest {
    private val dispatcher=UnconfinedTestDispatcher()
    private class Repository : ConvoyRepository {
        val session=MutableStateFlow<ConvoySession>(ConvoySession.Empty)
        var subscriptions=0; var cancellations=0; var calls=0
        var failure: Exception?=null; var suspended=false
        var responseDelay=0L; var reconciliationDelay=0L
        var committed: ConvoySession?=null
        var recovery: OperationRecovery?=null
        var neverReconcile=false
        override suspend fun reconcileMutation(): OperationRecovery? { if(neverReconcile) awaitCancellation(); delay(reconciliationDelay); return recovery }
        override fun observeSession()=flow { subscriptions++; try { emitAll(session) } finally { cancellations++ } }
        private suspend fun act() { calls++; if(suspended) awaitCancellation(); delay(responseDelay); committed?.let { session.value=it }; failure?.let { throw it } }
        override suspend fun create(name:String)=act()
        override suspend fun join(secretOrLink:String)=act()
        override suspend fun invite(convoyId:String):Invitation { act(); return Invitation("A".repeat(26),60_000) }
        override suspend fun leave(convoyId:String)=act()
        override suspend fun end(convoyId:String)=act()
    }
    private lateinit var repository:Repository
    private lateinit var model:ConvoyViewModel
    private val active=ConvoySession.Active(Convoy("id","Trip","owner","owner",listOf("owner","member"),60_000),"owner")
    @Before fun setup() { Dispatchers.setMain(dispatcher); repository=Repository(); model=ConvoyViewModel(repository) { dispatcher.scheduler.currentTime }; model.start() }
    @After fun cleanup() { model.stop(); Dispatchers.resetMain() }
    @Test fun `start is idempotent and stop cancels private listeners`() {
        model.start(); assertEquals(1,repository.subscriptions); model.stop()
        assertEquals(1,repository.cancellations); assertEquals(ConvoySession.Loading,model.uiState.value.session)
    }
    @Test fun `success is reported only after the request returns`() {
        repository.suspended=true; model.create("Trip")
        assertTrue(model.uiState.value.busy); assertFalse(model.uiState.value.message)
        model.stop(); assertFalse(model.uiState.value.message)
    }
    @Test fun `duplicate button clicks do not issue concurrent mutations`() {
        repository.suspended=true; model.create("Trip"); model.join("code"); assertEquals(1,repository.calls)
    }
    @Test fun `offline error never reports success and refreshes membership`() {
        repository.failure=ConvoyException(ConvoyError.OFFLINE); model.join("code")
        assertEquals(MutationStatus.RECONCILED,model.uiState.value.mutation); assertFalse(model.uiState.value.message)
        assertFalse(model.uiState.value.busy); assertEquals(2,repository.subscriptions)
    }
    @Test fun `invalid full and revoked errors remain distinct`() {
        for(error in listOf(ConvoyError.INVALID_INVITATION,ConvoyError.FULL,ConvoyError.SESSION_UNAVAILABLE)) {
            repository.failure=ConvoyException(error); model.join("code"); assertEquals(error,model.uiState.value.error)
        }
    }
    @Test fun `successful command reloads session rather than inventing membership`() {
        model.create("Trip"); assertTrue(model.uiState.value.message)
        assertEquals(ConvoySession.Empty,model.uiState.value.session); assertEquals(2,repository.subscriptions)
    }
    @Test fun `invitation is transient and cleared on revocation`() {
        repository.session.value=active; model.invite(); assertNotNull(model.uiState.value.invitation)
        repository.session.value=ConvoySession.Failed(ConvoyError.SESSION_UNAVAILABLE)
        assertNull(model.uiState.value.invitation)
    }
    @Test fun `invitation disappears on expiry and lifecycle stop`() = runTest(dispatcher) {
        repository.session.value=active; model.invite(); advanceTimeBy(60_000); runCurrent(); assertNull(model.uiState.value.invitation)
        model.editJoin("private-input"); model.stop(); assertEquals("",model.uiState.value.joinInput)
    }
    @Test fun `leave and end use current session and refresh`() {
        repository.session.value=active; model.end(); repository.session.value=active.copy(userId="member"); model.leave()
        assertEquals(2,repository.calls); assertEquals(3,repository.subscriptions)
    }
    @Test fun `stop and restart obtains session again without repeating actions`() {
        repository.session.value=active; model.stop(); model.start()
        assertEquals(active,model.uiState.value.session); assertEquals(0,repository.calls)
    }
    @Test fun `cancellation does not become a user error`() {
        repository.failure=CancellationException("cancel"); model.create("Trip")
        assertNull(model.uiState.value.error); assertFalse(model.uiState.value.message)
    }
    @Test fun `delayed callable remains loading beyond former fifteen second limit`() = runTest(dispatcher) {
        repository.responseDelay=22_000
        model.create("Trip")
        advanceTimeBy(15_001); runCurrent()
        assertTrue(model.uiState.value.busy)
        assertEquals(MutationStatus.RUNNING,model.uiState.value.mutation)
        model.create("Trip"); assertEquals(1,repository.calls)
        advanceTimeBy(7_000); runCurrent()
        assertFalse(model.uiState.value.busy)
        assertEquals(MutationStatus.CONFIRMED,model.uiState.value.mutation)
    }
    @Test fun `completed write with lost response restores membership without replay`() {
        repository.committed=active
        repository.failure=ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        model.create("Trip")
        assertEquals(active,model.uiState.value.session)
        assertEquals(MutationStatus.RECONCILED,model.uiState.value.mutation)
        assertFalse(model.uiState.value.message)
        model.create("Trip"); model.join("code"); model.retry()
        assertEquals(1,repository.calls)
    }
    @Test fun `unknown request blocks mutations until settlement and fresh session read`() = runTest(dispatcher) {
        repository.failure=ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        repository.reconciliationDelay=20_000
        model.create("Trip")
        assertEquals(MutationStatus.UNKNOWN,model.uiState.value.mutation)
        assertFalse(model.uiState.value.canMutate)
        model.create("Trip"); model.join("code")
        assertEquals(1,repository.calls)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(MutationStatus.RECONCILED,model.uiState.value.mutation)
        assertTrue(model.uiState.value.canMutate)
        repository.failure=null; repository.reconciliationDelay=0
        model.create("Trip"); assertEquals(2,repository.calls)
    }
    @Test fun `offline refresh cannot enable retry and reconnection never replays mutation`() {
        repository.failure=ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        repository.session.value=ConvoySession.Failed(ConvoyError.OFFLINE)
        model.retry()
        model.create("Trip"); assertEquals(0,repository.calls)
        repository.session.value=ConvoySession.Empty
        assertTrue(model.uiState.value.canMutate)
        model.create("Trip"); assertEquals(1,repository.calls)
        repository.session.value=ConvoySession.Failed(ConvoyError.OFFLINE)
        assertFalse(model.uiState.value.canMutate)
        repository.session.value=active
        assertEquals(1,repository.calls)
        assertEquals(active,model.uiState.value.session)
    }
    @Test fun `never completing recovery still reads session and exposes a safe refresh`() = runTest(dispatcher) {
        repository.neverReconcile=true
        model.retry()
        advanceTimeBy(45_001); runCurrent()
        assertEquals(ConvoySession.Empty,model.uiState.value.session)
        assertEquals(RecoveryStatus.UNRESOLVED,model.uiState.value.recovery?.status)
        assertFalse(model.uiState.value.busy)
        assertFalse(model.uiState.value.canMutate)
        repeat(5) { model.create("Trip") }; assertEquals(0,repository.calls)
        model.stop(); model.start()
        repository.neverReconcile=false
        repository.recovery=OperationRecovery(ConvoyOperation.CREATE,RecoveryStatus.CANCELLED)
        model.retry()
        assertTrue(model.uiState.value.canMutate)
        model.create("Trip"); assertEquals(1,repository.calls)
    }
    @Test fun `each operation has a reconciled outcome independent of old task`() {
        for (kind in ConvoyOperation.entries) {
            repository.recovery=OperationRecovery(kind,RecoveryStatus.COMMITTED)
            repository.session.value=if(kind in listOf(ConvoyOperation.LEAVE,ConvoyOperation.END)) ConvoySession.Empty else active
            model.retry()
            assertEquals(kind,model.uiState.value.recovery?.operation)
            assertEquals(MutationStatus.RECONCILED,model.uiState.value.mutation)
            assertTrue(model.uiState.value.canMutate)
        }
        assertEquals(0,repository.calls)
    }
    @Test fun `unresolved recovery keeps confirmed membership visible while offline then recovers`() {
        repository.recovery=OperationRecovery(ConvoyOperation.INVITE,RecoveryStatus.UNRESOLVED)
        repository.session.value=active
        model.retry()
        assertEquals(active,model.uiState.value.session)
        assertFalse(model.uiState.value.canMutate)
        model.invite(); model.end(); assertEquals(0,repository.calls)
        repository.session.value=ConvoySession.Failed(ConvoyError.OFFLINE)
        assertFalse(model.uiState.value.canMutate)
        repository.recovery=OperationRecovery(ConvoyOperation.INVITE,RecoveryStatus.COMMITTED)
        repository.session.value=active
        model.retry()
        assertTrue(model.uiState.value.canMutate)
    }
}
