package com.trailo.app.domain.convoy

import java.net.URI
import kotlinx.coroutines.flow.Flow

/** Private session data is only presented after a server-confirmed authorized read. */
data class Convoy(
    val id: String,
    val name: String,
    val creatorId: String,
    val leaderId: String,
    val memberIds: List<String>,
    val expiresAtMillis: Long,
)

data class Invitation(val secret: String, val expiresAtMillis: Long) {
    override fun toString() = "Invitation([redacted])"
    fun shareText() = "trailo://convoy/join#$secret"
}

enum class ConvoyError { OFFLINE, OUTCOME_UNKNOWN, INVALID_INVITATION, FULL, RATE_LIMITED, ALREADY_IN_SESSION, INVALID_INPUT, SESSION_UNAVAILABLE, AUTH, SETUP, UNKNOWN }
class ConvoyException(val reason: ConvoyError) : Exception(reason.name)

enum class ConvoyOperation(val callable: String) {
    CREATE("createConvoy"), INVITE("createConvoyInvitation"), JOIN("joinConvoy"), LEAVE("leaveConvoy"), END("endConvoy")
}
enum class RecoveryStatus { COMMITTED, CANCELLED, UNRESOLVED }
data class OperationRecovery(val operation: ConvoyOperation?, val status: RecoveryStatus)

sealed interface ConvoySession {
    data object Loading : ConvoySession
    data object Empty : ConvoySession
    data class Active(val convoy: Convoy, val userId: String) : ConvoySession
    data class Failed(val reason: ConvoyError) : ConvoySession
}

interface ConvoyRepository {
    /** Resolve/fence an operation, then independently read current session state. Never replay a mutation. */
    suspend fun reconcileMutation(): OperationRecovery? = null
    fun observeSession(): Flow<ConvoySession>
    suspend fun create(name: String)
    suspend fun join(secretOrLink: String)
    suspend fun invite(convoyId: String): Invitation
    suspend fun leave(convoyId: String)
    suspend fun end(convoyId: String)
}

fun invitationSecret(input: String): String {
    if (input.length > 256) throw ConvoyException(ConvoyError.INVALID_INVITATION)
    val trimmed = input.trim()
    val raw = if (trimmed.startsWith("trailo://")) {
        val uri = try { URI(trimmed) } catch (_: Exception) { throw ConvoyException(ConvoyError.INVALID_INVITATION) }
        if (uri.scheme != "trailo" || uri.host != "convoy" || uri.path != "/join" || uri.rawQuery != null || uri.userInfo != null || uri.port != -1) {
            throw ConvoyException(ConvoyError.INVALID_INVITATION)
        }
        uri.rawFragment ?: throw ConvoyException(ConvoyError.INVALID_INVITATION)
    } else trimmed
    if (!raw.matches(Regex("(?:[A-Za-z2-7]{26}|(?:[A-Za-z2-7]{5}-){4}[A-Za-z2-7]{6})"))) {
        throw ConvoyException(ConvoyError.INVALID_INVITATION)
    }
    val secret = raw.replace("-", "").uppercase(java.util.Locale.ROOT)
    if (!secret.matches(Regex("[A-Z2-7]{25}[AEIMQUY4]"))) throw ConvoyException(ConvoyError.INVALID_INVITATION)
    return secret
}
