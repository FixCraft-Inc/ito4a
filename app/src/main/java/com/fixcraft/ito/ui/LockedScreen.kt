package com.fixcraft.ito.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R

/**
 * Shown while the app is locked ([locked]) or its lock key is unavailable.
 * The Surface supplies the theme's content colour, so text stays readable in
 * dark mode. While an audio or messaging foreground owner exists ([canStop])
 * the screen offers Disconnect, and End call during an active call, without
 * new key access. Pending invitations offer decline or unlock for acceptance.
 */
@Composable
fun LockedScreen(locked: Boolean, canStop: Boolean, callActive: Boolean,
                 onUnlock: () -> Unit, onEndCall: () -> Unit, onStop: () -> Unit,
                 incomingCall: Boolean = false, onDecline: () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_ito), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            if (locked) Text(stringResource(R.string.app_locked_title), style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(if (locked) R.string.app_locked else R.string.app_lock_unavailable),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp))
            if (incomingCall) {
                Text(stringResource(R.string.incoming_unlock), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.decline)) }
            }
            if (callActive) Button(onClick = onEndCall, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ito.hangUp,
                    contentColor = MaterialTheme.ito.onHangUp)) {
                Icon(painterResource(R.drawable.ic_call_end), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.end_call))
            }
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (locked) R.string.unlock_app else R.string.retry))
            }
            if (canStop) OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stop_transport))
            }
        }
    }
}
