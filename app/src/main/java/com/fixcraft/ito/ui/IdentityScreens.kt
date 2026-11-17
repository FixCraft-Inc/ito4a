package com.fixcraft.ito.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.fixcraft.ito.call.IdentityFailure

@Composable
internal fun IdentityLoading() = PageColumn {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.identity_loading))
    }
}

@Composable
internal fun IdentityUnreadable(reason: IdentityFailure, onRetry: () -> Unit) = PageColumn {
    Text(stringResource(R.string.identity_unreadable_title), Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall)
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
        Icon(painterResource(R.drawable.ic_ito), null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.create_identity_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        SupportingTextWithInfo(R.string.create_identity_summary, R.string.create_identity_title, R.string.create_identity_description)
        Button(onClick = model::createIdentity, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.create_identity))
        }
    }
}
