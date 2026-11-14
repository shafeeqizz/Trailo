package com.trailo.app.data.firebase

import com.trailo.app.domain.convoy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

data class SessionPointer(val convoyId: String, val expiresAtMillis: Long)
data class ServerSnapshot<T>(val value: T?, val confirmed: Boolean)

/** Narrow SDK boundary; permits repository policy tests without Android or Firebase. */
interface ConvoyGateway {
    suspend fun reconcileMutation(): OperationRecovery? = null
    suspend fun authenticate(): String
    fun pointer(uid: String): Flow<ServerSnapshot<SessionPointer>>
    fun convoy(id: String): Flow<ServerSnapshot<Convoy>>
    suspend fun call(function: String, arguments: Map<String, Any>): Map<*, *>
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseConvoyRepository(
    private val gateway: ConvoyGateway,
    private val now: () -> Long = System::currentTimeMillis,
) : ConvoyRepository {
    override suspend fun reconcileMutation() = gateway.reconcileMutation()
    override fun observeSession(): Flow<ConvoySession> = flow {
        emit(ConvoySession.Loading)
        val uid = gateway.authenticate()
        emitAll(gateway.pointer(uid).flatMapLatest { snapshot ->
            val pointer = snapshot.value
            when {
                !snapshot.confirmed -> flowOf(ConvoySession.Failed(ConvoyError.OFFLINE))
                pointer == null || pointer.expiresAtMillis <= now() -> flowOf(ConvoySession.Empty)
                else -> gateway.convoy(pointer.convoyId)
                    .onStart { emit(ServerSnapshot<Convoy>(null, false)) }
                    .transformLatest { remote ->
                    val convoy = remote.value
                    when {
                        !remote.confirmed -> emit(ConvoySession.Failed(ConvoyError.OFFLINE))
                        convoy == null || uid !in convoy.memberIds || convoy.expiresAtMillis <= now() -> emit(ConvoySession.Failed(ConvoyError.SESSION_UNAVAILABLE))
                        else -> {
                            emit(ConvoySession.Active(convoy, uid))
                            delay((convoy.expiresAtMillis - now()).coerceAtLeast(0))
                            emit(ConvoySession.Failed(ConvoyError.SESSION_UNAVAILABLE))
                        }
                    }
                }.catch { failure -> emit(ConvoySession.Failed(reason(failure))) }
            }
        })
    }.catch { failure -> emit(ConvoySession.Failed(reason(failure))) }

    override suspend fun create(name: String) {
        if (name.trim().isEmpty() || name.length > 320) throw ConvoyException(ConvoyError.INVALID_INPUT)
        gateway.call("createConvoy", mapOf("name" to name.trim()))
    }
    override suspend fun join(secretOrLink: String) {
        gateway.call("joinConvoy", mapOf("secret" to invitationSecret(secretOrLink)))
    }
    override suspend fun invite(convoyId: String): Invitation {
        val result = gateway.call("createConvoyInvitation", mapOf("convoyId" to convoyId))
        return Invitation(result["secret"] as? String ?: throw ConvoyException(ConvoyError.UNKNOWN),
            (result["expiresAt"] as? Number)?.toLong() ?: throw ConvoyException(ConvoyError.UNKNOWN))
    }
    override suspend fun leave(convoyId: String) { gateway.call("leaveConvoy", mapOf("convoyId" to convoyId)) }
    override suspend fun end(convoyId: String) { gateway.call("endConvoy", mapOf("convoyId" to convoyId)) }
}

fun reason(failure: Throwable): ConvoyError {
    if (failure is CancellationException) throw failure
    return (failure as? ConvoyException)?.reason ?: ConvoyError.UNKNOWN
}
