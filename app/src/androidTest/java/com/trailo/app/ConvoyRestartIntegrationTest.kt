package com.trailo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.domain.convoy.*
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run these methods in separate instrumentation invocations with force-stop between them. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ConvoyRestartIntegrationTest {
    companion object {
        @BeforeClass @JvmStatic fun prepareFunctions() = warmConvoyEmulators()
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun session(repository: ConvoyRepository) = withTimeout(30_000) {
        repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
    }
    private fun digest(uid: String) = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun aSeedSession() = runBlocking {
        val repository = FirebaseConvoyRepository(EmulatorConvoyGateway.create(context))
        val old = session(repository) as? ConvoySession.Active
        if (old != null) {
            if (old.userId == old.convoy.creatorId) repository.end(old.convoy.id)
            else repository.leave(old.convoy.id)
        }
        repository.create("Restart restoration check")
        val active = session(repository) as ConvoySession.Active
        // Test-only marker stores a hash, never a token, secret, raw UID or session ID.
        assertTrue(context.getSharedPreferences("restart-test-proof", 0).edit()
            .putString("uidHash", digest(active.userId)).commit())
    }

    @Test fun bRestoreAndClean() = runBlocking {
        val repository = FirebaseConvoyRepository(EmulatorConvoyGateway.create(context))
        val active = session(repository) as ConvoySession.Active
        assertTrue(context.getSharedPreferences("restart-test-proof", 0).getString("uidHash", null) == digest(active.userId))
        assertEquals("Restart restoration check", active.convoy.name)
        repository.end(active.convoy.id)
        assertEquals(ConvoySession.Empty, session(repository))
        context.getSharedPreferences("restart-test-proof", 0).edit().clear().commit()
        Unit
    }

    @Test fun cSeedUnresolvedMutation() = runBlocking {
        val gateway=EmulatorConvoyGateway.create(context,"unresolved-restart",2_000)
        val repository=prepareConvoyFixture(context,"unresolved-restart",2_000)
        val functions=FirebaseFunctions.getInstance(FirebaseApp.getInstance("unresolved-restart"),"us-central1")
        functions.useEmulator("10.0.2.2",5004)
        try { repository.create("Unresolved restart recovery"); fail("Expected truncated response") }
        catch(failure:ConvoyException){assertEquals(ConvoyError.OUTCOME_UNKNOWN,failure.reason)}
        assertFalse(context.getSharedPreferences("convoy-operations-unresolved-restart",0).all.isEmpty())
        assertTrue(context.getSharedPreferences("restart-test-proof",0).edit()
            .putString("unresolvedUidHash",digest(gateway.authenticate())).commit())
    }

    @Test fun dRecoverUnresolvedAfterProcessRestart() = runBlocking {
        val gateway=EmulatorConvoyGateway.create(context,"unresolved-restart",2_000)
        val repository=FirebaseConvoyRepository(gateway)
        assertEquals(context.getSharedPreferences("restart-test-proof",0).getString("unresolvedUidHash",null),digest(gateway.authenticate()))
        val active=session(repository) as ConvoySession.Active
        assertEquals(OperationRecovery(ConvoyOperation.CREATE,RecoveryStatus.COMMITTED),repository.reconcileMutation())
        assertEquals("Unresolved restart recovery",active.convoy.name)
        repository.end(active.convoy.id)
        assertEquals(ConvoySession.Empty,session(repository))
        assertTrue(context.getSharedPreferences("convoy-operations-unresolved-restart",0).all.isEmpty())
        context.getSharedPreferences("restart-test-proof",0).edit().remove("unresolvedUidHash").commit()
        Unit
    }
}
