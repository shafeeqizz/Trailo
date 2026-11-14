package com.trailo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.android.gms.tasks.TaskCompletionSource
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.data.firebase.waitForResult
import com.trailo.app.domain.convoy.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ConvoyStuckTaskIntegrationTest {
    companion object {
        @BeforeClass @JvmStatic fun prepareFunctions() = warmConvoyEmulators()
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun functions(name: String) = FirebaseFunctions.getInstance(FirebaseApp.getInstance(name), "us-central1")
    private suspend fun session(repository: FirebaseConvoyRepository) = withTimeout(30_000) {
        repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
    }
    private suspend fun lost(action: suspend () -> Unit) {
        try { action(); fail("Expected an unknown outcome") }
        catch (failure: ConvoyException) { assertEquals(ConvoyError.OUTCOME_UNKNOWN, failure.reason) }
    }
    private suspend fun recovered(repository: FirebaseConvoyRepository, kind: ConvoyOperation) {
        assertEquals(OperationRecovery(kind, RecoveryStatus.COMMITTED), repository.reconcileMutation())
    }
    private suspend fun prepared(name: String): FirebaseConvoyRepository {
        return prepareConvoyFixture(context,name,2_000)
    }

    @Test fun neverCompletingSdkTaskReturnsControlWithoutCancellingTask() = runBlocking {
        val task=TaskCompletionSource<String>().task
        try { withTimeout(100) {task.waitForResult()}; fail("Wait must be bounded") }
        catch(_:TimeoutCancellationException) {
            assertFalse(task.isComplete)
            assertFalse(task.isCanceled)
        }
    }

    @Test fun truncatedRepliesRecoverAllFiveOperationsAndTwoUsers() = runBlocking {
        val owner = prepared("stuck-owner")
        val member = prepared("stuck-member")
        functions("stuck-owner").useEmulator("10.0.2.2",5004)
        lost { owner.create("Truncated recovery") }
        recovered(owner,ConvoyOperation.CREATE)
        val convoy = (session(owner) as ConvoySession.Active).convoy
        lost { owner.invite(convoy.id) }
        recovered(owner,ConvoyOperation.INVITE)
        functions("stuck-owner").useEmulator("10.0.2.2",5001)
        val invitation = owner.invite(convoy.id)
        functions("stuck-member").useEmulator("10.0.2.2",5004)
        lost { member.join(invitation.secret) }
        assertFalse(context.getSharedPreferences("convoy-operations-stuck-member",0).all.values.any {
            it.toString().contains(invitation.secret)
        })
        recovered(member,ConvoyOperation.JOIN)
        assertEquals(2,(session(member) as ConvoySession.Active).convoy.memberIds.size)
        lost { member.leave(convoy.id) }
        recovered(member,ConvoyOperation.LEAVE)
        assertEquals(ConvoySession.Empty,session(member))
        functions("stuck-member").useEmulator("10.0.2.2",5001)
        member.join(invitation.secret)
        functions("stuck-owner").useEmulator("10.0.2.2",5004)
        lost { owner.end(convoy.id) }
        recovered(owner,ConvoyOperation.END)
        assertEquals(ConvoySession.Empty,session(owner))
        assertEquals(ConvoySession.Empty,session(member))
        functions("stuck-owner").useEmulator("10.0.2.2",5001)
        owner.create("After stuck task recovery")
        owner.end((session(owner) as ConvoySession.Active).convoy.id)
    }

    @Test fun uncommittedRequestIsFencedBeforeLateDeliveryAndRetry() = runBlocking {
        val repository = prepared("stuck-uncommitted")
        functions("stuck-uncommitted").useEmulator("10.0.2.2",5005)
        lost { repository.create("Must not appear late") }
        lost { repository.create("Must not dispatch twice") }
        assertEquals(OperationRecovery(ConvoyOperation.CREATE,RecoveryStatus.CANCELLED),repository.reconcileMutation())
        assertEquals(ConvoySession.Empty,session(repository))
        val release=URL("http://10.0.2.2:5005/release").openConnection() as HttpURLConnection
        try { release.connectTimeout=5_000; release.readTimeout=5_000; assertEquals(200,release.responseCode) }
        finally {release.disconnect()}
        functions("stuck-uncommitted").useEmulator("10.0.2.2",5001)
        repository.create("Safe retry after cancellation")
        val current=session(repository) as ConvoySession.Active
        assertEquals("Safe retry after cancellation",current.convoy.name)
        repository.end(current.convoy.id)
    }
}
