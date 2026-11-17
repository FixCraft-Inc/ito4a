package com.fixcraft.ito.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R
import com.fixcraft.ito.call.AudioRoute
import com.fixcraft.ito.call.AudioRouteKind
import com.fixcraft.ito.call.CallOutcome
import com.fixcraft.ito.call.CallOutcomes
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallView
import com.fixcraft.ito.call.Contact

/**
 * The one call, full screen. Audio starts by itself once the call connects,
 * because Call and Answer already asked for the microphone. [onMinimize]
 * returns to chats while the call continues.
 */
@Composable
internal fun CallScreen(view: CallView, contacts: List<Contact>, model: CallsModel, advanced: Boolean,
                        snackbar: SnackbarHostState = remember { SnackbarHostState() }, onMinimize: () -> Unit = {},
                        onService: () -> Unit) {
    val extras = MaterialTheme.ito
    val peer = view.peer?.let { key -> contacts.firstOrNull { it.key.contentEquals(key) } }
    // The peer is the contact the caller chose, or the one whose ringing the core authenticated.
    val name = peer?.name ?: stringResource(R.string.unknown_caller)
    val background = Brush.verticalGradient(listOf(extras.callBackground, extras.callBackgroundEnd))
    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (view.phase.active) IconButton(onClick = onMinimize) {
                    Icon(painterResource(R.drawable.ic_minimize), stringResource(R.string.call_minimize), tint = extras.onCallBackground)
                }
                Spacer(Modifier.weight(1f))
                if (view.phase == CallPhase.Media && view.peerAuthenticated) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(14.dp), tint = extras.onCallBackground.copy(alpha = 0.8f))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.encrypted_end_to_end), style = MaterialTheme.typography.labelMedium,
                        color = extras.onCallBackground.copy(alpha = 0.8f))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.height(24.dp))
                if (peer != null) ContactAvatar(model, peer, 128.dp)
                else Avatar("", null, 128.dp, Trust.None, null)
                Spacer(Modifier.height(20.dp))
                Text(name, style = MaterialTheme.typography.headlineMedium, color = extras.onCallBackground, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(callStatus(view), style = MaterialTheme.typography.titleMedium, color = extras.onCallBackground.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                CallDetailsFor(view, peer, advanced, model, onService)
            }
            CallControls(view, model)
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

/** The line under the name: progress before media, the duration during it, the outcome after. */
@Composable
private fun callStatus(view: CallView): String = when (view.phase) {
    CallPhase.Idle, CallPhase.Connecting -> stringResource(R.string.call_connecting)
    CallPhase.Inviting -> stringResource(R.string.call_ringing)
    CallPhase.Waiting -> stringResource(R.string.call_waiting)
    CallPhase.Handshaking -> stringResource(R.string.call_handshaking)
    CallPhase.Authenticating -> stringResource(R.string.call_authenticating)
    CallPhase.Consent -> stringResource(R.string.consent_description)
    CallPhase.Media -> if (view.voiceActive) callDuration(view) else stringResource(R.string.call_audio_starting)
    CallPhase.Ending -> stringResource(R.string.call_ending)
    // A start refusal never reached an authenticated contact, so it has no history to save.
    CallPhase.Ended -> stringResource(if (view.startFailure != null && !view.peerAuthenticated) R.string.call_not_started
        else R.string.call_ended)
}

/** Lines that only some states need, under the status. */
@Composable
private fun CallDetailsFor(view: CallView, peer: Contact?, advanced: Boolean, model: CallsModel, onService: () -> Unit) {
    val extras = MaterialTheme.ito
    val context = LocalContext.current
    val withMicrophone = rememberMicrophoneGate(model::microphoneRefused)
    Column(Modifier.padding(top = 20.dp).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (view.phase == CallPhase.Consent && peer != null && advanced) Fingerprint(peer.fingerprint)
        // Android can revoke the microphone during a call. The call stays up and asks once more.
        if (view.phase == CallPhase.Media && !view.voiceActive &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Text(stringResource(R.string.call_microphone_off), color = extras.onCallBackground, textAlign = TextAlign.Center)
            Button(onClick = { withMicrophone(model::startAudio) }) { Text(stringResource(R.string.call_allow_microphone)) }
        }
        if (view.phase == CallPhase.Ended) {
            Text(stringResource(outcomeText(CallOutcomes.of(view))), color = extras.onCallBackground,
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (view.peerAuthenticated && !view.historySaved) Text(stringResource(R.string.history_not_saved),
                color = extras.onCallBackground.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (CallOutcomes.of(view) == CallOutcome.NotConnected) OutlinedButton(onClick = { model.dismissCall(); onService() }) {
                Text(stringResource(R.string.open_service), color = extras.onCallBackground)
            }
        }
        if (advanced && (view.receivedBytes > 0 || view.sentBytes > 0)) CallDetails(view)
    }
}

/** The round buttons at the bottom. Only what the current state can do is shown. */
@Composable
private fun CallControls(view: CallView, model: CallsModel) {
    var choosingRoute by remember { mutableStateOf(false) }
    val extras = MaterialTheme.ito
    // Buttons share the row equally, so long labels at large text sizes wrap instead of squeezing a neighbour.
    Row(Modifier.fillMaxWidth().widthIn(max = 480.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
        val slot = Modifier.weight(1f)
        when (view.phase) {
            CallPhase.Consent -> {
                CallButton(R.drawable.ic_call_end, stringResource(R.string.decline), extras.hangUp, extras.onHangUp, slot) { model.decide(false) }
                CallButton(R.drawable.ic_call, stringResource(R.string.accept), extras.answer, extras.onAnswer, slot) { model.decide(true) }
            }
            CallPhase.Media -> {
                // A switched-on control turns white, like the phone's own call screen.
                CallButton(if (view.muted) R.drawable.ic_mic_off else R.drawable.ic_mic, stringResource(R.string.mute_audio),
                    if (view.muted) extras.onCallBackground else extras.callControl,
                    if (view.muted) extras.callBackground else extras.onCallBackground, slot,
                    enabled = view.voiceActive, checked = view.muted, onClick = model::muteAudio)
                val route = view.audioRoute
                val away = route != null && route.kind != AudioRouteKind.Earpiece
                CallButton(routeIcon(route?.kind ?: AudioRouteKind.Earpiece), route?.let { routeLabel(it) } ?: stringResource(R.string.call_audio_device),
                    if (away) extras.onCallBackground else extras.callControl, if (away) extras.callBackground else extras.onCallBackground,
                    slot, enabled = view.voiceActive && view.audioRoutes.size > 1, onClick = {
                        // With only the phone and its speaker there is nothing to choose between: switch.
                        val other = view.audioRoutes.singleOrNull { it != route }
                        if (view.audioRoutes.size == 2 && other != null) model.selectRoute(other.id) else choosingRoute = true
                    })
                CallButton(R.drawable.ic_call_end, stringResource(R.string.end_call), extras.hangUp, extras.onHangUp, slot,
                    onClick = model::hangup)
            }
            CallPhase.Ending -> CircularProgressIndicator(color = extras.onCallBackground)
            CallPhase.Ended -> CallButton(R.drawable.ic_close, stringResource(R.string.call_close), extras.callControl,
                extras.onCallBackground, slot, onClick = model::dismissCall)
            else -> CallButton(R.drawable.ic_call_end, stringResource(R.string.cancel_call), extras.hangUp, extras.onHangUp, slot,
                onClick = model::cancel)
        }
    }
    if (choosingRoute) AudioRouteSheet(view, onDismiss = { choosingRoute = false }) { id ->
        model.selectRoute(id); choosingRoute = false
    }
}

/**
 * A labelled round call button. The label is part of the button for screen
 * readers and tests. A [checked] button is a switch, such as Mute.
 */
@Composable
internal fun CallButton(@DrawableRes icon: Int, label: String, container: Color, content: Color,
                        modifier: Modifier = Modifier, enabled: Boolean = true, checked: Boolean? = null,
                        size: Dp = 68.dp, onClick: () -> Unit) {
    val action = if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { onClick() }
    // The press and focus highlight follows a rounded shape instead of the slot's rectangle.
    Column(modifier.clip(MaterialTheme.shapes.large).then(action).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.size(size), shape = CircleShape, color = if (enabled) container else container.copy(alpha = 0.4f),
            contentColor = if (enabled) content else content.copy(alpha = 0.5f)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(size * 0.42f)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.ito.onCallBackground, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRouteSheet(view: CallView, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.call_audio_device), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            view.audioRoutes.forEach { route ->
                val current = route == view.audioRoute
                ListRow(routeIcon(route.kind), routeLabel(route),
                    Modifier.selectable(selected = current, role = Role.RadioButton) { onChoose(route.id) },
                    tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    titleColor = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) {
                    if (current) Icon(painterResource(R.drawable.ic_check), stringResource(R.string.call_route_current),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@DrawableRes
private fun routeIcon(kind: AudioRouteKind) = when (kind) {
    AudioRouteKind.Earpiece -> R.drawable.ic_phone_in_talk
    AudioRouteKind.Speaker -> R.drawable.ic_volume_up
    AudioRouteKind.Wired -> R.drawable.ic_headset_mic
    AudioRouteKind.Usb -> R.drawable.ic_usb
    AudioRouteKind.Bluetooth -> R.drawable.ic_bluetooth
    AudioRouteKind.HearingAid -> R.drawable.ic_hearing
}

/** The phone's own devices by role, external ones by the name Android reports. */
@Composable
private fun routeLabel(route: AudioRoute): String = when (route.kind) {
    AudioRouteKind.Earpiece -> stringResource(R.string.route_phone)
    AudioRouteKind.Speaker -> stringResource(R.string.route_speaker)
    AudioRouteKind.Wired -> route.name.ifBlank { stringResource(R.string.route_wired) }
    AudioRouteKind.Usb -> route.name.ifBlank { stringResource(R.string.route_usb) }
    AudioRouteKind.Bluetooth -> route.name.ifBlank { stringResource(R.string.route_bluetooth) }
    AudioRouteKind.HearingAid -> route.name.ifBlank { stringResource(R.string.route_hearing_aid) }
}

/** Advanced mode only: byte counts and digests for diagnosing a call. */
@Composable
private fun CallDetails(view: CallView) {
    val ink = MaterialTheme.ito.onCallBackground.copy(alpha = 0.8f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.bytes_sent, view.sentBytes), style = MaterialTheme.typography.bodySmall, color = ink)
        Text(stringResource(R.string.bytes_received, view.receivedBytes), style = MaterialTheme.typography.bodySmall, color = ink)
        if (view.sentSha256.isNotEmpty()) Text(stringResource(R.string.sent_digest, view.sentSha256),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = ink)
        if (view.receivedSha256.isNotEmpty()) Text(stringResource(R.string.received_digest, view.receivedSha256),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = ink)
    }
}

/** The complete fingerprint in groups of four, for comparison character by character. */
@Composable
private fun Fingerprint(fingerprint: String) {
    SelectionContainer {
        Text(fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ito.onCallBackground, textAlign = TextAlign.Center)
    }
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
