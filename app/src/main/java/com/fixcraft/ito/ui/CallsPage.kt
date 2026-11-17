package com.fixcraft.ito.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.CallOutcome
import com.fixcraft.ito.call.CallOutcomes
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallView
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.call.IdentityFailure
import com.fixcraft.ito.call.InvitationText
import kotlinx.coroutines.launch

/** Navigation retains only public contact codes. */
internal const val CALLS_HOME = "home"
internal data class CallsBar(val title: String?, val back: String?)

@Composable
internal fun IdentityLoading() = PageColumn {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.identity_loading))
}

@Composable
internal fun IdentityUnreadable(reason: IdentityFailure, onRetry: () -> Unit) = PageColumn {
    Heading(R.string.identity_unreadable_title)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(when (reason) {
        IdentityFailure.Corrupt -> R.string.identity_corrupt
        IdentityFailure.KeyUnavailable -> R.string.identity_key_unavailable
        IdentityFailure.Io -> R.string.identity_io
    }))
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
}

@Composable
internal fun CreateIdentity(model: CallsModel) = PageColumn {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.ic_ito), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Heading(R.string.create_identity_title)
        SupportingTextWithInfo(R.string.create_identity_summary, R.string.create_identity_title, R.string.create_identity_description)
        Button(onClick = model::createIdentity, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.create_identity))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallScreen(view: CallView, contacts: List<Contact>, model: CallsModel, advanced: Boolean,
                        onService: () -> Unit) {
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.startAudio()
    }
    PageColumn {
    val peer = view.peer?.let { key -> contacts.firstOrNull { it.key.contentEquals(key) } }
    val name = peer?.name ?: stringResource(R.string.unknown_caller)
    // The caller already knows whom they are calling. An answering side learns it only after the proof.
    val calling = if (view.initiator && peer != null) stringResource(R.string.calling_contact, peer.name) else null
    when (view.phase) {
        CallPhase.Inviting -> Progress(R.string.call_ringing, model, calling)
        CallPhase.Connecting -> Progress(R.string.call_connecting, model, calling)
        CallPhase.Waiting -> Progress(R.string.call_waiting, model, calling)
        CallPhase.Handshaking -> Progress(R.string.call_handshaking, model, calling)
        CallPhase.Authenticating -> Progress(R.string.call_authenticating, model, calling)
        CallPhase.Consent -> {
            Heading(stringResource(R.string.consent_title, name))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.consent_description), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            peer?.let { Fingerprint(R.string.contact_fingerprint, it.fingerprint) }
            FlowActions {
                Button(onClick = { model.decide(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_call), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.accept))
                }
                OutlinedButton(onClick = { model.decide(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.decline))
                }
            }
        }
        CallPhase.Media -> {
            Heading(stringResource(R.string.call_connected, name))
            Spacer(Modifier.height(8.dp))
            SupportingText(R.string.call_connected_description)
            Spacer(Modifier.height(24.dp))
            if (!view.voiceActive) {
                FilledTonalButton(onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.start_audio)) }
                Spacer(Modifier.height(4.dp))
                SupportingText(R.string.start_audio_description)
            } else FlowActions {
                FilledTonalButton(onClick = model::muteAudio, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (view.muted) R.string.unmute_audio else R.string.mute_audio))
                }
                OutlinedButton(onClick = model::speakerAudio, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (view.speaker) R.string.audio_earpiece else R.string.audio_speaker))
                }
            }
            Spacer(Modifier.height(16.dp))
            EndCallButton(model::hangup)
            if (advanced) {
                Spacer(Modifier.height(24.dp))
                CallDetails(view)
            }
        }
        CallPhase.Ending -> Progress(R.string.call_ending, null)
        CallPhase.Ended, CallPhase.Idle -> {
            val outcome = CallOutcomes.of(view)
            // A start refusal never reached an authenticated contact, so it has no history to save.
            Heading(if (view.startFailure != null && !view.peerAuthenticated) R.string.call_not_started else R.string.call_ended)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(outcomeText(outcome)), style = MaterialTheme.typography.bodyLarge)
            if (view.peerAuthenticated && !view.historySaved) {
                Spacer(Modifier.height(4.dp))
                SupportingText(R.string.history_not_saved)
            }
            if (advanced && (view.receivedBytes > 0 || view.sentBytes > 0)) {
                Spacer(Modifier.height(16.dp))
                CallDetails(view)
            }
            Spacer(Modifier.height(16.dp))
            FlowActions {
                Button(onClick = model::dismissCall, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.done)) }
                if (outcome == CallOutcome.NotConnected) OutlinedButton(onClick = { model.dismissCall(); onService() },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_service)) }
            }
        }
    }
}
}

/** The one action that ends a connected call. It stays filled and red so it is found first. */
@Composable
private fun EndCallButton(onEnd: () -> Unit) {
    Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) {
        // The handset icon tilted down is the familiar hang-up sign.
        Icon(painterResource(R.drawable.ic_call), null, Modifier.size(20.dp).rotate(135f))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.end_call))
    }
}

/** Advanced mode only: byte counts and digests for diagnosing a call. */
@Composable
private fun CallDetails(view: CallView) {
    SectionTitle(R.string.call_details)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.bytes_sent, view.sentBytes), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.bytes_received, view.receivedBytes), style = MaterialTheme.typography.bodyMedium)
    if (view.sentSha256.isNotEmpty()) Text(stringResource(R.string.sent_digest, view.sentSha256),
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    if (view.receivedSha256.isNotEmpty()) Text(stringResource(R.string.received_digest, view.receivedSha256),
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

@StringRes
internal fun outcomeText(outcome: CallOutcome) = when (outcome) {
    CallOutcome.YouEnded -> R.string.outcome_you_ended
    CallOutcome.YouDeclined -> R.string.outcome_you_declined
    CallOutcome.DeclinedByContact -> R.string.outcome_declined_by_contact
    CallOutcome.EndedByContact -> R.string.outcome_ended_by_contact
    CallOutcome.EndedUnattributed -> R.string.outcome_ended_unattributed
    CallOutcome.TimedOut -> R.string.outcome_timed_out
    CallOutcome.AuthenticationFailed -> R.string.outcome_authentication_failed
    CallOutcome.InvitationRefused -> R.string.outcome_invitation_refused
    CallOutcome.ClockRegressed -> R.string.outcome_clock_regressed
    CallOutcome.ConnectionLost -> R.string.outcome_connection_lost
    CallOutcome.ConnectionEnded -> R.string.outcome_connection_ended
    CallOutcome.NotConnected -> R.string.outcome_not_connected
    CallOutcome.NoIdentity -> R.string.outcome_no_identity
    CallOutcome.StorageFailed -> R.string.outcome_storage_failed
    CallOutcome.Busy -> R.string.outcome_busy
    CallOutcome.Failed -> R.string.outcome_failed
}

@Composable
private fun Progress(@StringRes text: Int, model: CallsModel?, title: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        title?.let { Heading(it) }
        CircularProgressIndicator()
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        if (model != null) OutlinedButton(onClick = model::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.cancel_call))
        }
    }
}

/** The complete fingerprint in groups of four, for comparison character by character. */
@Composable
private fun Fingerprint(@StringRes label: Int, fingerprint: String) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    SelectionContainer {
        Text(fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Notice(@StringRes text: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Text(stringResource(text), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Two or more actions side by side, wrapping onto new lines at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Heading(@StringRes text: Int) = Heading(stringResource(text))

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

/** Shows a notice from [CallsModel] in the host's snackbar. */
@Composable
fun CallsNotice(model: CallsModel, show: suspend (String) -> Unit) {
    val notice by model.notice.collectAsStateWithLifecycle()
    val text = notice?.let { stringResource(it) }
    LaunchedEffect(text) {
        if (text != null) {
            show(text)
            model.dismissNotice()
        }
    }
    val messenger by model.messenger.state.collectAsStateWithLifecycle()
    val failureText = when (messenger.failure) {
        com.fixcraft.ito.messaging.MessengerFailure.None -> null
        com.fixcraft.ito.messaging.MessengerFailure.Offline -> R.string.chat_error_offline
        com.fixcraft.ito.messaging.MessengerFailure.Storage -> R.string.chat_error_storage
        com.fixcraft.ito.messaging.MessengerFailure.PairAgain -> R.string.chat_error_pair_again
        com.fixcraft.ito.messaging.MessengerFailure.InvalidCode -> R.string.chat_error_code
        com.fixcraft.ito.messaging.MessengerFailure.OtherRelay -> R.string.chat_error_relay
        com.fixcraft.ito.messaging.MessengerFailure.Unverified -> R.string.chat_error_unverified
        com.fixcraft.ito.messaging.MessengerFailure.Capacity -> R.string.chat_error_capacity
        com.fixcraft.ito.messaging.MessengerFailure.Transport -> R.string.chat_error_transport
        com.fixcraft.ito.messaging.MessengerFailure.Native -> R.string.chat_error_native
    }?.let { stringResource(it) }
    LaunchedEffect(failureText) {
        if (failureText != null) { show(failureText); model.messenger.dismissFailure() }
    }
}
