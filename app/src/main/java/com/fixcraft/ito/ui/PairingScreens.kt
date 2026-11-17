package com.fixcraft.ito.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.call.InvitationText
import com.fixcraft.ito.messaging.MessageNative
import com.fixcraft.ito.messaging.SafetyQr

/** Pair with someone: show a one-time code, scan theirs, or exchange a link. */
@Composable
internal fun AddContactScreen(model: CallsModel, chrome: ScreenChrome, onRoute: (ChatsRoute) -> Unit) {
    val messenger by model.messenger.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.chat_share_link)
    DetailScaffold(chrome, onBack = { onRoute(ChatsRoute.Home) }, title = { Text(stringResource(R.string.add_contact)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SecureWindow()
            Text(stringResource(R.string.chat_pair_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            ListRow(R.drawable.ic_qr_code, stringResource(R.string.chat_show_code), Modifier.clickable(role = Role.Button) {
                model.message { createCode(false) }
            }.padding(horizontal = 8.dp), supporting = stringResource(R.string.chat_show_code_description), tint = MaterialTheme.colorScheme.primary)
            ListRow(R.drawable.ic_qr_code_scanner, stringResource(R.string.chat_scan), Modifier.clickable(role = Role.Button) {
                onRoute(ChatsRoute.Scan(ChatsRoute.AddContact))
            }.padding(horizontal = 8.dp), supporting = stringResource(R.string.chat_scan_code_description), tint = MaterialTheme.colorScheme.primary)
            ListRow(R.drawable.ic_link, stringResource(R.string.chat_make_link), Modifier.clickable(role = Role.Button) {
                model.message { createCode(true) }
            }.padding(horizontal = 8.dp), supporting = stringResource(R.string.chat_make_link_description), tint = MaterialTheme.colorScheme.primary)
            messenger.code?.toString(Charsets.US_ASCII)?.let { code ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 12.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 1.dp) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QrCode(code, stringResource(R.string.chat_pair_qr), Modifier.widthIn(max = 280.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (messenger.link) Button(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, code), shareTitle))
                            }) { Text(stringResource(R.string.chat_share_link)) }
                            TextButton(onClick = { model.message { hideCode() } }) { Text(stringResource(R.string.chat_hide_code)) }
                        }
                    }
                }
            }
            PairingProgress(model)
            PastedLink(model)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PastedLink(model: CallsModel) {
    var pasted by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(pasted, { if (it.length <= 8192) pasted = it },
            label = { Text(stringResource(R.string.chat_paste_link)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        OutlinedButton(onClick = {
            val code = pasted
            pasted = ""
            model.message { scan(code.toByteArray(Charsets.US_ASCII), false) }
        }, enabled = pasted.startsWith("ito-pair:")) { Text(stringResource(R.string.chat_open_link)) }
    }
}

/**
 * One scanner for every Ito code. A pairing code starts pairing, a safety code
 * offers verification of the matching contact, and anything else is explained.
 */
@Composable
internal fun ScanScreen(model: CallsModel, contacts: List<Contact>, chrome: ScreenChrome, onBack: () -> Unit) {
    var safetyMatch by remember { mutableStateOf<Pair<Contact, String>?>(null) }
    DetailScaffold(chrome, onBack = onBack, title = { Text(stringResource(R.string.chat_scan)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            SecureWindow()
            // Accept every decoded value. Dispatch explains unsupported and legacy codes.
            QrScanner(R.string.chat_scan_description, { true }, { text ->
                onBack()
                when {
                    text.startsWith("ito-pair:") -> model.message { scan(text.toByteArray(Charsets.US_ASCII), true) }
                    InvitationText.looksLikeInvitation(text) -> model.message { rejectScan(legacy = true) }
                    text.startsWith("ito-contact:") -> model.message { rejectScan(legacy = true) }
                    else -> {
                        var matched: Pair<Contact, String>? = null
                        model.message(onSuccess = { safetyMatch = matched }) {
                            val qr = SafetyQr.parse(text) ?: return@message rejectScan()
                            val own = ownFingerprint().let(::hex)
                            val other = qr.other(own) ?: return@message rejectScan()
                            matched = contacts.firstOrNull { contact ->
                                val paired = paired(contact.key)
                                paired != null && hex(paired.fingerprint) == other && safety(contact.key) == qr.digits
                            }?.let { it to qr.digits }
                            if (matched == null) rejectScan()
                        }
                    }
                }
            }, onBack)
        }
    }
    safetyMatch?.let { (contact, digits) -> AlertDialog(onDismissRequest = { safetyMatch = null },
        title = { Text(stringResource(R.string.chat_safety_match, contact.name)) },
        text = { Text(digits.chunked(5).joinToString(" ")) },
        confirmButton = { TextButton(onClick = { model.message { verify(contact.key, digits) }; safetyMatch = null }) {
            Text(stringResource(R.string.chat_matches)) } },
        dismissButton = { TextButton(onClick = { safetyMatch = null }) { Text(stringResource(R.string.cancel)) } }) }
}

/** Pairings in progress, with the six digits both people compare. */
@Composable
internal fun PairingProgress(model: CallsModel) {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    state.pairings.forEach { pairing ->
        Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pairing.name.ifEmpty { stringResource(R.string.chat_pair_waiting) }, style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
                if (pairing.shortCode.isNotEmpty()) {
                    Text(stringResource(R.string.chat_compare_digits), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Text(pairing.shortCode.toString(Charsets.US_ASCII).chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall)
                }
                // Wraps at large text or narrow widths instead of squeezing a label.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pairing.role == MessageNative.OWNER && pairing.shortCode.isNotEmpty()) {
                        Button(onClick = { model.message { decide(pairing.id, true) } }) { Text(stringResource(R.string.chat_matches)) }
                        OutlinedButton(onClick = { model.message { decide(pairing.id, false) } }) {
                            Text(stringResource(R.string.chat_does_not_match))
                        }
                    }
                    TextButton(onClick = { model.message { cancelPairing(pairing.id) } }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
    if (state.shortCode.isNotEmpty() && state.pairings.none { it.shortCode.isNotEmpty() })
        Text(stringResource(R.string.chat_short_code, state.shortCode), Modifier.padding(vertical = 12.dp))
}

internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
