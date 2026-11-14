package com.trailo.app.data.firebase

import com.trailo.app.domain.convoy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseConvoyRepositoryTest {
    private class Gateway : ConvoyGateway {
        val pointer = MutableStateFlow(ServerSnapshot<SessionPointer>(null, true))
        val convoy = MutableStateFlow(ServerSnapshot<Convoy>(null, true))
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()
        var authCount = 0
        var failure: Exception? = null
        override suspend fun authenticate(): String { authCount++; failure?.let { throw it }; return "member" }
        override fun pointer(uid: String): Flow<ServerSnapshot<SessionPointer>> = pointer
        override fun convoy(id: String): Flow<ServerSnapshot<Convoy>> = convoy
        override suspend fun call(function: String, arguments: Map<String, Any>): Map<*, *> {
            failure?.let { throw it }; calls += function to arguments
            return mapOf("secret" to "A".repeat(26), "expiresAt" to 60_000L)
        }
    }
    private fun convoy(id: String = "convoy") = Convoy(id,"Trip","owner","owner",listOf("owner","member"),60_000)

    @Test fun `all mutation contracts carry only permitted fields`() = runTest {
        val gateway = Gateway(); val repository = FirebaseConvoyRepository(gateway)
        repository.create(" Trip "); repository.join("trailo://convoy/join#"+"A".repeat(26))
        repository.invite("id"); repository.leave("id"); repository.end("id")
        assertEquals(listOf("createConvoy","joinConvoy","createConvoyInvitation","leaveConvoy","endConvoy"),gateway.calls.map { it.first })
        assertEquals(mapOf("name" to "Trip"),gateway.calls[0].second)
        assertEquals(setOf("secret"),gateway.calls[1].second.keys)
        gateway.calls.drop(2).forEach { assertEquals(mapOf("convoyId" to "id"),it.second) }
    }
    @Test fun `cached pointers and cached convoys never disclose membership`() = runTest {
        val g=Gateway(); val r=FirebaseConvoyRepository(g) { testScheduler.currentTime }
        val states=mutableListOf<ConvoySession>(); backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.observeSession().toList(states) }
        g.pointer.value=ServerSnapshot(SessionPointer("convoy",60_000),false); runCurrent()
        assertEquals(ConvoySession.Failed(ConvoyError.OFFLINE),states.last())
        g.pointer.value=ServerSnapshot(SessionPointer("convoy",60_000),true)
        g.convoy.value=ServerSnapshot(convoy(),false); runCurrent()
        assertFalse(states.any { it is ConvoySession.Active })
        g.convoy.value=ServerSnapshot(convoy(),true); runCurrent()
        assertTrue(states.last() is ConvoySession.Active)
        g.convoy.value=ServerSnapshot(convoy(),false); runCurrent()
        assertTrue(states.last() is ConvoySession.Failed)
    }
    @Test fun `expiry hides session even without a further server event`() = runTest {
        val g=Gateway(); g.pointer.value=ServerSnapshot(SessionPointer("convoy",60_000),true); g.convoy.value=ServerSnapshot(convoy(),true)
        val states=mutableListOf<ConvoySession>(); backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { FirebaseConvoyRepository(g) { testScheduler.currentTime }.observeSession().toList(states) }
        runCurrent(); assertTrue(states.last() is ConvoySession.Active)
        advanceTimeBy(60_000); runCurrent(); assertEquals(ConvoySession.Failed(ConvoyError.SESSION_UNAVAILABLE),states.last())
    }
    @Test fun `revoked roster and deleted pointer remove active state`() = runTest {
        val g=Gateway(); g.pointer.value=ServerSnapshot(SessionPointer("convoy",60_000),true); g.convoy.value=ServerSnapshot(convoy(),true)
        val states=mutableListOf<ConvoySession>(); backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { FirebaseConvoyRepository(g) { 0 }.observeSession().toList(states) }
        runCurrent(); g.convoy.value=ServerSnapshot(convoy().copy(memberIds=listOf("owner")),true); runCurrent()
        assertEquals(ConvoySession.Failed(ConvoyError.SESSION_UNAVAILABLE),states.last())
        g.pointer.value=ServerSnapshot(null,true); runCurrent(); assertEquals(ConvoySession.Empty,states.last())
    }
    @Test fun `recollection authenticates and restores pointer rather than creating a convoy`() = runTest {
        val g=Gateway(); g.pointer.value=ServerSnapshot(SessionPointer("convoy",60_000),true); g.convoy.value=ServerSnapshot(convoy(),true)
        repeat(2) {
            val states=mutableListOf<ConvoySession>(); val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { FirebaseConvoyRepository(g) { 0 }.observeSession().toList(states) }
            runCurrent(); assertTrue(states.last() is ConvoySession.Active); job.cancel()
        }
        assertEquals(2,g.authCount); assertTrue(g.calls.isEmpty())
    }
    @Test fun `expired pointer is empty and malformed join never calls network`() = runTest {
        val g=Gateway(); g.pointer.value=ServerSnapshot(SessionPointer("old",0),true)
        val states=mutableListOf<ConvoySession>(); backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { FirebaseConvoyRepository(g) { 1 }.observeSession().toList(states) }
        runCurrent(); assertEquals(ConvoySession.Empty,states.last())
        try { FirebaseConvoyRepository(g).join("bad"); fail() } catch (failure: ConvoyException) { assertEquals(ConvoyError.INVALID_INVITATION,failure.reason) }
        assertTrue(g.calls.isEmpty())
    }
    @Test fun `auth network failure maps to a private error state`() = runTest {
        val g=Gateway(); g.failure=ConvoyException(ConvoyError.OFFLINE)
        assertEquals(ConvoySession.Failed(ConvoyError.OFFLINE),FirebaseConvoyRepository(g).observeSession().last())
    }
    @Test fun `cancellation is propagated instead of displayed as an error`() = runTest {
        val g=Gateway(); g.failure=CancellationException("cancel")
        try { FirebaseConvoyRepository(g).observeSession().toList(); fail() } catch (_: CancellationException) { }
    }
    @Test fun `invitation links have strict host path and padding`() {
        val code="A".repeat(26)
        assertEquals(code,invitationSecret(" aaaaa-aaaaa-aaaaa-aaaaa-aaaaaa "))
        assertEquals(code,invitationSecret("trailo://convoy/join#$code"))
        for (input in listOf("https://evil.example/#$code","trailo://evil/join#$code","trailo://convoy/other#$code","trailo://convoy/join?x=y#$code","A".repeat(25)+"B")) {
            try { invitationSecret(input); fail("Invalid input accepted") } catch (_: ConvoyException) { }
        }
        assertFalse(Invitation(code,1).toString().contains(code))
    }
}
