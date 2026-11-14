package com.trailo.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.trailo.app.data.firebase.EmulatorConvoyGateway
import com.trailo.app.data.firebase.FirebaseConvoyRepository
import com.trailo.app.domain.convoy.ConvoyOperation
import com.trailo.app.domain.convoy.ConvoySession
import com.trailo.app.domain.convoy.RecoveryStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Short-deadline fault tests isolate response faults from emulator cold startup. */
internal fun warmConvoyEmulators() {
    val executor = Executors.newFixedThreadPool(6)
    try {
        val futures = (ConvoyOperation.entries.map { it.callable } + "resolveConvoyOperation").map { endpoint ->
            executor.submit(Callable {
                val connection = URL("http://10.0.2.2:5001/demo-trailo/us-central1/$endpoint")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 60_000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write("{\"data\":{}}".toByteArray(Charsets.UTF_8)) }
                    check(connection.responseCode == 401) { "Expected local unauthenticated warm-up rejection" }
                } finally { connection.disconnect() }
            })
        }
        futures.forEach { it.get(70, TimeUnit.SECONDS) }
    } finally { executor.shutdownNow() }
}

/** Only dedicated named test identities are passed here; never the user's lobby app. */
internal suspend fun prepareConvoyFixture(context: Context, name: String, waitMillis: Long = 65_000): FirebaseConvoyRepository {
    val repository=FirebaseConvoyRepository(EmulatorConvoyGateway.create(context,name,waitMillis))
    FirebaseFunctions.getInstance(FirebaseApp.getInstance(name),"us-central1").useEmulator("10.0.2.2",5001)
    suspend fun session() = withTimeout(45_000) {
        repository.observeSession().first { it is ConvoySession.Active || it == ConvoySession.Empty }
    }
    session() // Establish an authorized server read before testing a short response deadline.
    check(repository.reconcileMutation()?.status != RecoveryStatus.UNRESOLVED) { "Local fixture recovery is not confirmed" }
    val previous=session() as? ConvoySession.Active
    if(previous != null) {
        if(previous.userId == previous.convoy.creatorId) repository.end(previous.convoy.id)
        else repository.leave(previous.convoy.id)
    }
    return repository
}
