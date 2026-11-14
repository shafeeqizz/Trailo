package com.trailo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.domain.convoy.ConvoyError
import com.trailo.app.domain.convoy.ConvoyException
import com.trailo.app.domain.convoy.ConvoySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith

/** Ports 5002/5003 are local fault proxies; all upstream operations remain demo-trailo. */
@RunWith(AndroidJUnit4::class)
class ConvoyReliabilityIntegrationTest {
    companion object {
        @BeforeClass @JvmStatic fun prepareFunctions() = warmConvoyEmulators()
    }
    private suspend fun active(repository: FirebaseConvoyRepository) = withTimeout(30_000) {
        repository.observeSession().first { it is ConvoySession.Active } as ConvoySession.Active
    }

    @Test fun creationSurvivesDelayedReply() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = prepareConvoyFixture(context,"reliability-delay")
        val functions = FirebaseFunctions.getInstance(FirebaseApp.getInstance("reliability-delay"), "us-central1")
        functions.useEmulator("10.0.2.2", 5002)
        try {
            repository.create("Delayed local reply")
            val session = active(repository)
            assertEquals(1, session.convoy.memberIds.size)
        } finally {
            functions.useEmulator("10.0.2.2", 5001)
            repository.reconcileMutation()
            val session = withTimeout(30_000) {
                repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
            }
            if (session is ConvoySession.Active) repository.end(session.convoy.id)
        }
    }

    @Test fun committedWriteWithLostReplyRestoresSessionAndRejectsDuplicate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = prepareConvoyFixture(context,"reliability-lost")
        val functions = FirebaseFunctions.getInstance(FirebaseApp.getInstance("reliability-lost"), "us-central1")
        functions.useEmulator("10.0.2.2", 5003)
        try {
            try {
                repository.create("Lost local reply")
                fail("The fault proxy must lose this response")
            } catch (failure: ConvoyException) {
                assertEquals(ConvoyError.OUTCOME_UNKNOWN, failure.reason)
            }
            repository.reconcileMutation()
            val restored = active(repository)
            functions.useEmulator("10.0.2.2", 5001)
            try {
                repository.create("Duplicate must not be created")
                fail("The server must reject a second active convoy")
            } catch (failure: ConvoyException) {
                assertEquals(ConvoyError.ALREADY_IN_SESSION, failure.reason)
            }
            assertEquals(restored.convoy.id, active(repository).convoy.id)
        } finally {
            functions.useEmulator("10.0.2.2", 5001)
            repository.reconcileMutation()
            val session = withTimeout(30_000) {
                repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
            }
            if (session is ConvoySession.Active) repository.end(session.convoy.id)
        }
    }
}
