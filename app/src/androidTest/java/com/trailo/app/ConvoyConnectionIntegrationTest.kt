package com.trailo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.domain.convoy.ConvoySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Validates the Android transport without creating or altering any convoy. */
@RunWith(AndroidJUnit4::class)
class ConvoyConnectionIntegrationTest {
    @Test fun anonymousSessionAndAuthenticatedCallableValidation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = EmulatorConvoyGateway.create(context, "connection-regression")
        gateway.authenticate()
        assertEquals(ConvoySession.Empty, withTimeout(30_000) {
            FirebaseConvoyRepository(gateway).observeSession().first { it == ConvoySession.Empty }
        })
        val app = FirebaseApp.getInstance("connection-regression")
        assertEquals("demo-trailo", app.options.projectId)
        try {
            Tasks.await(FirebaseFunctions.getInstance(app, "us-central1")
                .getHttpsCallable("createConvoy").call(mapOf("name" to "")), 30, TimeUnit.SECONDS)
            fail("An empty name must be rejected without creating a convoy")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            assertEquals("SDK failure type: ${cause?.javaClass?.name}",
                FirebaseFunctionsException::class.java, cause?.javaClass)
            assertEquals(FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                (cause as FirebaseFunctionsException).code)
        }
    }
}
