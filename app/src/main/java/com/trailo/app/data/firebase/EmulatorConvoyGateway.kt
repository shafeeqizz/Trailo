package com.trailo.app.data.firebase

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.AuthResult
import com.google.firebase.firestore.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONObject
import com.trailo.app.BuildConfig
import com.trailo.app.domain.convoy.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** No production endpoint or resource-based Firebase options are accepted. */
class EmulatorConvoyGateway private constructor(
    private val app: FirebaseApp,
    context: Context,
    private val callableWaitMillis: Long,
) : ConvoyGateway {
    private val auth = FirebaseAuth.getInstance(app).also { it.useEmulator("10.0.2.2", 9099) }
    private val store = FirebaseFirestore.getInstance(app).also {
        it.useEmulator("10.0.2.2", 8080)
        it.firestoreSettings = FirebaseFirestoreSettings.Builder(it.firestoreSettings)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
    }
    private val functions = FirebaseFunctions.getInstance(app, "us-central1").also {
        it.useEmulator("10.0.2.2", 5001)
    }
    private val authentication = Mutex()
    private var pendingAuthentication: Task<AuthResult>? = null
    private val mutations = Mutex()
    private val journal = context.getSharedPreferences("convoy-operations-${app.name}", Context.MODE_PRIVATE)

    private fun pending(uid: String): Pair<String, ConvoyOperation>? {
        val raw = journal.getString(uid, null) ?: return null
        return try {
            val value = JSONObject(raw)
            val id = value.getString("id")
            check(id.matches(Regex("[a-f0-9]{32}")))
            id to ConvoyOperation.valueOf(value.getString("kind"))
        } catch (_: Exception) { throw ConvoyException(ConvoyError.SETUP) }
    }

    private fun clear(uid: String, id: String) {
        if (pending(uid)?.first == id && !journal.edit().remove(uid).commit()) {
            throw ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        }
    }

    override suspend fun authenticate(): String = authentication.withLock {
        guarded {
            auth.currentUser?.uid ?: run {
                // Cancelling a coroutine cannot cancel Firebase's underlying sign-in.
                // Reuse that task across lifecycle cancellation/timeouts, avoiding a
                // second anonymous identity while the first request is still running.
                val pending = pendingAuthentication?.takeUnless { it.isComplete && !it.isSuccessful }
                    ?: auth.signInAnonymously().also { pendingAuthentication = it }
                pending.waitForResult()
                pendingAuthentication = null
                auth.currentUser?.uid ?: throw ConvoyException(ConvoyError.AUTH)
            }
        }
    }

    override fun pointer(uid: String) = watch("userSessions/$uid", uid) { snapshot ->
        if (!snapshot.exists()) null else SessionPointer(
            snapshot.getString("convoyId") ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
            snapshot.getTimestamp("expiresAt")?.toDate()?.time ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
        )
    }

    override fun convoy(id: String): Flow<ServerSnapshot<Convoy>> = watch("convoys/$id", auth.currentUser?.uid) { snapshot ->
        if (!snapshot.exists() || snapshot.getString("status") != "active") null else Convoy(
            id = id,
            name = snapshot.getString("name") ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
            creatorId = snapshot.getString("creatorId") ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
            leaderId = snapshot.getString("leaderId") ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
            memberIds = (snapshot.get("members") as? Map<*, *>)?.keys?.mapNotNull { it as? String }?.sorted()
                ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
            expiresAtMillis = snapshot.getTimestamp("expiresAt")?.toDate()?.time ?: throw ConvoyException(ConvoyError.SESSION_UNAVAILABLE),
        )
    }

    private fun <T> watch(path: String, uid: String?, decode: (DocumentSnapshot) -> T?): Flow<ServerSnapshot<T>> = callbackFlow {
        val authListener = FirebaseAuth.AuthStateListener {
            if (uid == null || it.currentUser?.uid != uid) {
                trySend(ServerSnapshot<T>(null, false))
                close(ConvoyException(ConvoyError.AUTH))
            }
        }
        auth.addAuthStateListener(authListener)
        val listener = store.document(path).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) close(mapped(error))
            else if (snapshot != null) {
                try {
                    // Never decode/present private cached records while awaiting authorization.
                    val confirmed = !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()
                    trySend(ServerSnapshot(if (confirmed) decode(snapshot) else null, confirmed))
                } catch (_: Exception) { close(ConvoyException(ConvoyError.SESSION_UNAVAILABLE)) }
            }
        }
        awaitClose { listener.remove(); auth.removeAuthStateListener(authListener) }
    }

    override suspend fun call(function: String, arguments: Map<String, Any>): Map<*, *> {
        val uid = authenticate()
        val kind = ConvoyOperation.entries.first { it.callable == function }
        val id = mutations.withLock {
            if (pending(uid) != null) throw ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
            val id = UUID.randomUUID().toString().replace("-", "")
            // Durable before dispatch. No names, invitation secrets, tokens or arguments.
            val record = JSONObject().put("id", id).put("kind", kind.name).toString()
            if (!journal.edit().putString(uid, record).commit()) throw ConvoyException(ConvoyError.SETUP)
            id
        }
        try {
            val task = functions.getHttpsCallable(function).withTimeout(60, TimeUnit.SECONDS)
                .call(arguments + ("operationId" to id))
            val result = withTimeout(callableWaitMillis) { task.waitForResult() }
            mutations.withLock { clear(uid, id) }
            return result.data as? Map<*, *> ?: throw ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        } catch (_: TimeoutCancellationException) {
            throw ConvoyException(ConvoyError.OUTCOME_UNKNOWN)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val mapped = mapped(failure, function)
            if (mapped.reason !in listOf(ConvoyError.OUTCOME_UNKNOWN, ConvoyError.UNKNOWN)) {
                mutations.withLock { clear(uid, id) }
            }
            throw mapped
        }
    }

    override suspend fun reconcileMutation(): OperationRecovery? {
        val uid = authenticate()
        val op = mutations.withLock { pending(uid) } ?: return null
        val (id, kind) = op
        val existing = readRecovery(uid, id, kind)
        if (existing != null) {
            if (existing.status != RecoveryStatus.UNRESOLVED) mutations.withLock { clear(uid, id) }
            return existing
        }
        try {
            // This is a new idempotent recovery request, not a wait on the old Task.
            withTimeout(30_000) {
                functions.getHttpsCallable("resolveConvoyOperation").withTimeout(30, TimeUnit.SECONDS)
                    .call(mapOf("operationId" to id, "kind" to kind.callable)).waitForResult()
            }
        } catch (_: TimeoutCancellationException) {
            // The fence may still have committed; always check its authorized receipt.
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        val recovery = readRecovery(uid, id, kind) ?: OperationRecovery(kind, RecoveryStatus.UNRESOLVED)
        if (recovery.status != RecoveryStatus.UNRESOLVED) mutations.withLock { clear(uid, id) }
        return recovery
    }

    private suspend fun readRecovery(uid: String, id: String, kind: ConvoyOperation): OperationRecovery? {
        return try {
            val receipt = withTimeout(10_000) {
                store.document("userOperations/$uid/requests/$id").get(Source.SERVER).waitForResult()
            }
            if (!receipt.exists()) return null
            val status = when (receipt.getString("status")) {
                "committed" -> RecoveryStatus.COMMITTED
                "cancelled" -> RecoveryStatus.CANCELLED
                else -> RecoveryStatus.UNRESOLVED
            }
            if (receipt.getString("kind") != kind.callable || status == RecoveryStatus.UNRESOLVED) {
                OperationRecovery(kind, RecoveryStatus.UNRESOLVED)
            } else {
                OperationRecovery(kind, status)
            }
        } catch (_: TimeoutCancellationException) {
            OperationRecovery(kind, RecoveryStatus.UNRESOLVED)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            OperationRecovery(kind, RecoveryStatus.UNRESOLVED)
        }
    }

    private suspend fun <T> guarded(operation: String? = null, action: suspend () -> T): T = try {
        withTimeout(15_000) { action() }
    } catch (_: TimeoutCancellationException) { throw ConvoyException(ConvoyError.OFFLINE) }
    catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        throw mapped(failure, operation)
    }

    private fun mapped(failure: Exception, operation: String? = null): ConvoyException {
        if (failure is ConvoyException) return failure
        val error = when (failure) {
            is FirebaseNetworkException -> if (operation == null) ConvoyError.OFFLINE else ConvoyError.OUTCOME_UNKNOWN
            is FirebaseAuthException -> ConvoyError.AUTH
            is FirebaseFunctionsException -> when (failure.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN -> ConvoyError.OUTCOME_UNKNOWN
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> ConvoyError.AUTH
                FirebaseFunctionsException.Code.ALREADY_EXISTS -> ConvoyError.ALREADY_IN_SESSION
                FirebaseFunctionsException.Code.INVALID_ARGUMENT -> ConvoyError.INVALID_INPUT
                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> if (operation == "joinConvoy") ConvoyError.INVALID_INVITATION else ConvoyError.SESSION_UNAVAILABLE
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> if (failure.message == "Convoy is full.") ConvoyError.FULL else ConvoyError.RATE_LIMITED
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> ConvoyError.SESSION_UNAVAILABLE
                else -> ConvoyError.UNKNOWN
            }
            is FirebaseFirestoreException -> when (failure.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE -> ConvoyError.OFFLINE
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> ConvoyError.AUTH
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> ConvoyError.SESSION_UNAVAILABLE
                else -> ConvoyError.UNKNOWN
            }
            else -> ConvoyError.UNKNOWN
        }
        return ConvoyException(error)
    }

    companion object {
        private val gateways = mutableMapOf<String, EmulatorConvoyGateway>()
        @Synchronized fun create(context: Context, name: String = "convoy-demo", callableWaitMillis: Long = 65_000): EmulatorConvoyGateway {
            check(BuildConfig.CONVOY_EMULATORS) { "Convoy Firebase access is disabled in this build." }
            require(callableWaitMillis in 100..65_000)
            return gateways.getOrPut(name) {
                val options = FirebaseOptions.Builder().setProjectId("demo-trailo")
                    .setApplicationId("1:1234567890:android:0123456789abcdef0123456789abcdef")
                    // Functions' transitive Instance ID SDK validates key syntax even
                    // with useEmulator(). This is a fabricated, noncredential value.
                    .setApiKey("AIza" + "0".repeat(35)).build()
                val app = FirebaseApp.getApps(context).firstOrNull { it.name == name }
                    ?: FirebaseApp.initializeApp(context.applicationContext, options, name)
                check(app.options.projectId == "demo-trailo")
                EmulatorConvoyGateway(app, context.applicationContext, callableWaitMillis)
            }
        }
    }
}

internal suspend fun <T> Task<T>.waitForResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
