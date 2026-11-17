package com.fixcraft.ito.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.messaging.IncomingCall

/** Shown only for an invitation authenticated by the ratchet, before any responder JOIN. */
@Composable
fun IncomingCallScreen(incoming: IncomingCall, model: CallsModel) {
    val book by model.book.collectAsStateWithLifecycle()
    val contact = (book as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(incoming.peer) }
    var refused by remember { mutableStateOf(false) }
    val withMicrophone = rememberMicrophoneGate { refused = true }
    IncomingCallContent(contact, rememberPhoto(model, incoming.peer),
        onAccept = { withMicrophone { model.acceptRing(incoming.actionToken) } },
        onDecline = { model.declineRing(incoming.actionToken) }, microphoneRefused = refused)
}

/**
 * Answer and Decline for one caller. Answer asks for the microphone first when
 * Android has not granted it. Refusing keeps the call ringing until its own
 * deadline, and Decline stays available.
 */
@Composable
internal fun IncomingCallContent(contact: Contact?, photo: ImageBitmap?, onAccept: () -> Unit, onDecline: () -> Unit,
                                 microphoneRefused: Boolean = false) {
    val extras = MaterialTheme.ito
    val name = contact?.name ?: stringResource(R.string.unknown_caller)
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(extras.callBackground, extras.callBackgroundEnd)))
        .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(14.dp), tint = extras.onCallBackground.copy(alpha = 0.8f))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.incoming_voice_call), style = MaterialTheme.typography.labelLarge,
                color = extras.onCallBackground.copy(alpha = 0.8f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Avatar(contact?.name ?: "", photo, 136.dp, contact?.trustRing ?: Trust.None, contact?.key)
            Spacer(Modifier.height(24.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, color = extras.onCallBackground, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.incoming_call), style = MaterialTheme.typography.titleMedium,
                color = extras.onCallBackground.copy(alpha = 0.85f))
            if (microphoneRefused) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.call_microphone_needed), color = extras.onCallBackground, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 360.dp))
            }
        }
        Row(Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(bottom = 24.dp)) {
            CallButton(R.drawable.ic_call_end, stringResource(R.string.decline), extras.hangUp, extras.onHangUp, Modifier.weight(1f),
                size = 76.dp, onClick = onDecline)
            CallButton(R.drawable.ic_call, stringResource(R.string.accept), extras.answer, extras.onAnswer, Modifier.weight(1f),
                size = 76.dp, onClick = onAccept)
        }
    }
}
