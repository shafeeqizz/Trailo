package com.trailo.app.ui.convoy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailo.app.domain.convoy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Secrets stay transient: never SavedStateHandle, saved instance state, disk or logs. */
enum class MutationStatus { IDLE, RUNNING, UNKNOWN, RECONCILED, CONFIRMED, FAILED }

data class ConvoyUiState(
    val name: String = "",
    val joinInput: String = "",
    val session: ConvoySession = ConvoySession.Loading,
    val busy: Boolean = false,
    val error: ConvoyError? = null,
    val message: Boolean = false,
    val invitation: Invitation? = null,
    val reconciling: Boolean = session == ConvoySession.Loading || session is ConvoySession.Failed,
    val mutation: MutationStatus = MutationStatus.IDLE,
    val recovery: OperationRecovery? = null,
) {
    val canMutate: Boolean get() = !busy && !reconciling && recovery?.status != RecoveryStatus.UNRESOLVED &&
        (session == ConvoySession.Empty || session is ConvoySession.Active)
}

class ConvoyViewModel(private val repository: ConvoyRepository, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val mutable = MutableStateFlow(ConvoyUiState())
    val uiState = mutable.asStateFlow()
    private var listener: Job? = null
    private var action: Job? = null
    private var expiry: Job? = null
    private var visible = false
    fun start() {
        visible = true
        if (listener?.isActive == true) return
        mutable.update { it.copy(session = ConvoySession.Loading, invitation = null, reconciling = true) }
        listener = viewModelScope.launch {
            try {
                do {
                val recovery = try {
                    withTimeout(45_000) { repository.reconcileMutation() }
                } catch (_: TimeoutCancellationException) {
                    OperationRecovery(null, RecoveryStatus.UNRESOLVED)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    OperationRecovery(null, RecoveryStatus.UNRESOLVED)
                }
                mutable.update { it.copy(recovery = recovery) }
                repository.observeSession().collect { session ->
                    mutable.update { old ->
                        val previousId = (old.session as? ConvoySession.Active)?.convoy?.id
                        val newId = (session as? ConvoySession.Active)?.convoy?.id
                        val confirmed = session == ConvoySession.Empty || session is ConvoySession.Active
                        val resolved = recovery?.status != RecoveryStatus.UNRESOLVED
                        old.copy(session = session,
                            reconciling = !confirmed,
                            error = if (confirmed && resolved && (old.mutation == MutationStatus.UNKNOWN || recovery != null)) null else old.error,
                            mutation = if (!resolved) MutationStatus.UNKNOWN else if (confirmed && (old.mutation == MutationStatus.UNKNOWN || recovery != null)) MutationStatus.RECONCILED else old.mutation,
                            invitation = old.invitation.takeIf { newId != null && newId == previousId })
                    }
                }
                val recoverable = (mutable.value.session as? ConvoySession.Failed)?.reason in
                    listOf(ConvoyError.OFFLINE, ConvoyError.AUTH)
                if (recoverable) delay(3_000)
                } while (visible && recoverable)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(session = ConvoySession.Failed(error(failure)), invitation = null) } }
        }
    }
    fun stop() {
        visible = false
        listener?.cancel(); listener = null
        action?.cancel(); action = null
        expiry?.cancel(); expiry = null
        mutable.value = ConvoyUiState()
    }
    fun retry() { listener?.cancel(); listener = null; start() }
    fun editName(value: String) { mutable.update { it.copy(name = value.take(321)) } }
    fun editJoin(value: String) { mutable.update { it.copy(joinInput = value.take(257)) } }
    fun create(name: String) { if (mutable.value.session == ConvoySession.Empty) perform { repository.create(name) } }
    fun join(input: String) { if (mutable.value.session == ConvoySession.Empty) perform { repository.join(input) } }
    fun leave() = activeAction(creatorOnly = false) { repository.leave(it) }
    fun end() = activeAction(creatorOnly = true) { repository.end(it) }
    fun invite() = activeAction(creatorOnly = true) { id ->
        val invitation = repository.invite(id)
        val current = mutable.value.session as? ConvoySession.Active
        if (current?.convoy?.id != id || current.userId != current.convoy.creatorId) return@activeAction
        mutable.update { it.copy(invitation = invitation) }
        expiry?.cancel()
        expiry = viewModelScope.launch {
            delay((invitation.expiresAtMillis - now()).coerceAtLeast(0))
            mutable.update { it.copy(invitation = null) }
        }
    }
    fun dismissInvitation() { expiry?.cancel(); mutable.update { it.copy(invitation = null) } }
    private fun activeAction(creatorOnly: Boolean, block: suspend (String) -> Unit) {
        val session = mutable.value.session as? ConvoySession.Active ?: return
        if ((session.userId == session.convoy.creatorId) != creatorOnly) return
        perform { block(session.convoy.id) }
    }
    private fun perform(block: suspend () -> Unit) {
        if (!visible || !mutable.value.canMutate) return
        mutable.update { it.copy(busy = true, error = null, message = false, invitation = null, mutation = MutationStatus.RUNNING) }
        action = viewModelScope.launch {
            try {
                block()
                mutable.update { it.copy(message = true, mutation = MutationStatus.CONFIRMED) }
                // Do not infer membership from the callable response. Reload the server pointer.
                if (mutable.value.invitation == null) retry()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = error(failure)
                val uncertain = reason in listOf(ConvoyError.OUTCOME_UNKNOWN, ConvoyError.UNKNOWN, ConvoyError.OFFLINE)
                mutable.update { it.copy(error = reason, invitation = null,
                    mutation = if (uncertain) MutationStatus.UNKNOWN else MutationStatus.FAILED,
                    reconciling = true) }
                // A timeout/lost response may have committed; never blindly repeat a mutation.
                retry()
            } finally { mutable.update { it.copy(busy = false, joinInput = "") } }
        }
    }
    private fun error(failure: Exception) = (failure as? ConvoyException)?.reason ?: ConvoyError.UNKNOWN
}
