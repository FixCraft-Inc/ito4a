package com.fixcraft.ito.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.messaging.IncomingCall

/** Shown only for an invitation authenticated by the ratchet, before any responder JOIN. */
@Composable
fun IncomingCallScreen(incoming: IncomingCall, model: CallsModel) {
    val book by model.book.collectAsStateWithLifecycle()
    val contact = (book as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(incoming.peer) }
    IncomingCallContent(contact, { model.acceptRing(incoming.actionToken) }, { model.declineRing(incoming.actionToken) })
}

@Composable
internal fun IncomingCallContent(contact: com.fixcraft.ito.call.Contact?, onAccept: () -> Unit, onDecline: () -> Unit) {
    val name = contact?.name ?: stringResource(R.string.unknown_caller)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
            contact?.let { TrustPortrait(it) }
            Text(name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.incoming_call), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onAccept, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.accept)) }
            OutlinedButton(onClick = onDecline, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.decline)) }
        }
    }
}
