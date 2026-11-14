package com.trailo.app.ui.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailo.app.R
import com.trailo.app.domain.convoy.*

@Composable
fun ConvoyRoute(viewModel: ConvoyViewModel, onClose: () -> Unit, onShare: (String) -> Unit) {
    LifecycleStartEffect(viewModel) { viewModel.start(); onStopOrDispose { viewModel.stop() } }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConvoyScreen(state, viewModel::editName, viewModel::editJoin,
        { viewModel.create(state.name) }, { viewModel.join(state.joinInput) }, viewModel::invite,
        viewModel::leave, viewModel::end, viewModel::retry, viewModel::dismissInvitation,
        onClose, { state.invitation?.let { onShare(it.shareText()) } })
}

@Composable
fun ConvoyScreen(
    state: ConvoyUiState,
    onName: (String) -> Unit,
    onJoinInput: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRetry: () -> Unit,
    onDismissInvitation: () -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.convoy_lobby), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text(stringResource(R.string.convoy_back)) }
            Text(stringResource(R.string.convoy_emulator_notice))
            if (state.busy || state.session == ConvoySession.Loading) {
                CircularProgressIndicator()
                Text(stringResource(if (state.busy) R.string.convoy_request_running else R.string.convoy_connecting))
            }
            if (state.mutation == MutationStatus.UNKNOWN) Text(stringResource(R.string.convoy_outcome_unknown))
            if (state.mutation == MutationStatus.RECONCILED) Text(stringResource(R.string.convoy_reconciled))
            state.recovery?.let { recovery ->
                Text(stringResource(recoveryResource(recovery)))
                if (recovery.status == RecoveryStatus.UNRESOLVED) {
                    Button(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.convoy_check_pending)) }
                }
            }
            state.error?.let { Text(stringResource(errorResource(it)), color = MaterialTheme.colorScheme.error) }
            if (state.message) Text(stringResource(R.string.convoy_success))
            val active = state.session as? ConvoySession.Active
            when (val session = state.session) {
                is ConvoySession.Failed -> {
                    Text(stringResource(errorResource(session.reason)), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.convoy_retry)) }
                }
                ConvoySession.Empty -> Text(stringResource(R.string.convoy_empty))
                else -> Unit
            }
            if (active == null && state.session != ConvoySession.Loading &&
                state.session != ConvoySession.Failed(ConvoyError.SETUP)) {
                OutlinedTextField(state.name, onName, label = { Text(stringResource(R.string.convoy_name)) },
                    enabled = state.canMutate, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = onCreate, enabled = state.canMutate && state.name.isNotBlank()) { Text(stringResource(R.string.convoy_create)) }
                OutlinedTextField(state.joinInput, onJoinInput, label = { Text(stringResource(R.string.convoy_secret_or_link)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = state.canMutate, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = onJoin, enabled = state.canMutate && state.joinInput.isNotBlank()) { Text(stringResource(R.string.convoy_join)) }
            }
            if (active != null) {
                val convoy = active.convoy
                Text(convoy.name, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.convoy_active_count, convoy.memberIds.size))
                convoy.memberIds.forEachIndexed { index, uid ->
                    val roles = listOfNotNull(
                        stringResource(R.string.convoy_participant, index + 1),
                        if (uid == active.userId) stringResource(R.string.convoy_you) else null,
                        if (uid == convoy.creatorId) stringResource(R.string.convoy_creator) else null,
                        if (uid == convoy.leaderId) stringResource(R.string.convoy_leader) else null,
                    ).joinToString(" · ")
                    Text(roles)
                }
                if (active.userId == convoy.creatorId) {
                    Button(onClick = onInvite, enabled = state.canMutate) { Text(stringResource(R.string.convoy_invite)) }
                    TextButton(onClick = { confirmEnd = true }, enabled = state.canMutate) { Text(stringResource(R.string.convoy_end)) }
                } else Button(onClick = onLeave, enabled = state.canMutate) { Text(stringResource(R.string.convoy_leave)) }
                state.invitation?.let {
                    Text(stringResource(R.string.convoy_secret_warning))
                    Text(it.secret)
                    Button(onClick = onShare, enabled = !state.busy) { Text(stringResource(R.string.convoy_share)) }
                    TextButton(onClick = onDismissInvitation) { Text(stringResource(R.string.convoy_hide_secret)) }
                }
            }
        }
    }
    val current = state.session as? ConvoySession.Active
    if (confirmEnd && current != null && current.userId == current.convoy.creatorId) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text(stringResource(R.string.convoy_end)) },
        text = { Text(stringResource(R.string.convoy_end_warning)) },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onEnd() }, enabled = state.canMutate) { Text(stringResource(R.string.convoy_end)) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.convoy_cancel)) } },
    )
}

private fun recoveryResource(recovery: OperationRecovery): Int = when {
    recovery.status == RecoveryStatus.UNRESOLVED -> R.string.convoy_unresolved
    recovery.status == RecoveryStatus.CANCELLED -> R.string.convoy_cancelled_before_commit
    recovery.operation == ConvoyOperation.CREATE -> R.string.convoy_recovered_create
    recovery.operation == ConvoyOperation.JOIN -> R.string.convoy_recovered_join
    recovery.operation == ConvoyOperation.LEAVE -> R.string.convoy_recovered_leave
    recovery.operation == ConvoyOperation.END -> R.string.convoy_recovered_end
    else -> R.string.convoy_recovered_invite
}

private fun errorResource(error: ConvoyError) = when (error) {
    ConvoyError.OFFLINE -> R.string.convoy_offline
    ConvoyError.OUTCOME_UNKNOWN -> R.string.convoy_outcome_unknown
    ConvoyError.INVALID_INVITATION -> R.string.convoy_invalid_invitation
    ConvoyError.FULL -> R.string.convoy_full
    ConvoyError.RATE_LIMITED -> R.string.convoy_rate_limited
    ConvoyError.ALREADY_IN_SESSION -> R.string.convoy_already_active
    ConvoyError.INVALID_INPUT -> R.string.convoy_invalid_input
    ConvoyError.SESSION_UNAVAILABLE -> R.string.convoy_session_unavailable
    ConvoyError.AUTH -> R.string.convoy_auth_error
    ConvoyError.SETUP -> R.string.convoy_disabled
    ConvoyError.UNKNOWN -> R.string.convoy_unknown_error
}
