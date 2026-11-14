package com.trailo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.domain.convoy.*
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires demo-trailo emulators; no production options or credentials. */
@RunWith(AndroidJUnit4::class)
class ConvoyEmulatorIntegrationTest {
    private suspend fun session(repository: ConvoyRepository) = withTimeout(30_000) {
        repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
    }
    private suspend fun clean(repository: ConvoyRepository) {
        val previous = session(repository) as? ConvoySession.Active ?: return
        if (previous.userId == previous.convoy.creatorId) repository.end(previous.convoy.id)
        else repository.leave(previous.convoy.id)
    }
    @Test fun twoUsersCreateRotateJoinRestoreLeaveAndEnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = EmulatorConvoyGateway.create(context, "instrument-owner")
        val b = EmulatorConvoyGateway.create(context, "instrument-member")
        val owner = FirebaseConvoyRepository(a)
        val member = FirebaseConvoyRepository(b)
        clean(owner); clean(member)
        assertTrue(a.authenticate() != b.authenticate())
        owner.create("Android emulator convoy")
        val created = session(owner) as ConvoySession.Active
        try {
            val revoked = owner.invite(created.convoy.id)
            val current = owner.invite(created.convoy.id)
            try { member.join(revoked.secret); fail("Revoked invitation accepted") }
            catch (failure: ConvoyException) { assertEquals(ConvoyError.INVALID_INVITATION, failure.reason) }
            try { member.join("bad"); fail("Malformed invitation accepted") }
            catch (failure: ConvoyException) { assertEquals(ConvoyError.INVALID_INVITATION, failure.reason) }
            member.join(current.shareText())
            val joined = session(member) as ConvoySession.Active
            assertEquals(2, joined.convoy.memberIds.size)
            val restored = session(FirebaseConvoyRepository(b)) as ConvoySession.Active
            assertTrue(restored.userId == joined.userId && restored.convoy.id == joined.convoy.id)
            member.leave(joined.convoy.id)
            assertEquals(ConvoySession.Empty, session(member))
            member.join(current.secret)
            owner.end(created.convoy.id)
            assertEquals(ConvoySession.Empty, session(owner))
            assertEquals(ConvoySession.Empty, session(member))
            try { member.join(current.secret); fail("Ended invitation accepted") }
            catch (failure: ConvoyException) { assertEquals(ConvoyError.INVALID_INVITATION, failure.reason) }
        } finally { clean(owner); clean(member) }
    }

    @Test fun cacheIsHiddenAndUnreachableFunctionDoesNotReportSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = EmulatorConvoyGateway.create(context, "instrument-offline")
        val repository = FirebaseConvoyRepository(gateway)
        clean(repository)
        repository.create("Offline check")
        val active = session(repository) as ConvoySession.Active
        val app = FirebaseApp.getInstance("instrument-offline")
        val store = FirebaseFirestore.getInstance(app)
        val functions = FirebaseFunctions.getInstance(app, "us-central1")
        try {
            store.disableNetwork()
            withTimeout(20_000) {
                repository.observeSession().first { it == ConvoySession.Failed(ConvoyError.OFFLINE) }
            }
            store.enableNetwork()
            assertTrue(session(repository) is ConvoySession.Active)
            functions.useEmulator("10.0.2.2", 5002)
            try { repository.invite(active.convoy.id); fail("Unreachable function reported success") }
            catch (failure: ConvoyException) {
                assertEquals(ConvoyError.OUTCOME_UNKNOWN, failure.reason)
            } finally { functions.useEmulator("10.0.2.2", 5001) }
        } finally { store.enableNetwork(); repository.reconcileMutation(); clean(repository) }
    }
}
