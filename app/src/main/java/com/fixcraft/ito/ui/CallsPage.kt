package com.fixcraft.ito.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.CallOutcome
import com.fixcraft.ito.call.CallOutcomes
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallView
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.call.IdentityFailure
import com.fixcraft.ito.call.InvitationText
import com.fixcraft.ito.call.OwnIdentity
import com.fixcraft.ito.transport.ConnectionPhase
import java.text.Collator
import kotlinx.coroutines.launch

/** Where the Calls tab is. Saved state holds only public contact codes. */
internal sealed interface CallsScreen {
    data object Home : CallsScreen
    data object Add : CallsScreen
    data object ScanInvitation : CallsScreen
    data class Details(val key: String) : CallsScreen
    data class Replace(val key: String) : CallsScreen
}

private fun encodeScreen(screen: CallsScreen) = when (screen) {
    CallsScreen.Home -> CALLS_HOME
    CallsScreen.Add -> "add"
    CallsScreen.ScanInvitation -> "scan-invitation"
    is CallsScreen.Details -> "details:${screen.key}"
    is CallsScreen.Replace -> "replace:${screen.key}"
}

private fun decodeScreen(value: String): CallsScreen = when {
    value == "add" -> CallsScreen.Add
    value == "scan-invitation" -> CallsScreen.ScanInvitation
    value.startsWith("details:") -> CallsScreen.Details(value.removePrefix("details:"))
    value.startsWith("replace:") -> CallsScreen.Replace(value.removePrefix("replace:"))
    else -> CallsScreen.Home
}

/** The Calls tab's first screen. */
internal const val CALLS_HOME = "home"

/** Where Back goes from a Calls screen, or null at the first screen. */
private fun parentOf(screen: CallsScreen): CallsScreen? = when (screen) {
    CallsScreen.Home -> null
    is CallsScreen.Replace -> CallsScreen.Details(screen.key)
    else -> CallsScreen.Home
}

/** What the top bar shows on the Calls tab: a title, and where Back goes. */
internal data class CallsBar(val title: String?, val back: String?)

@Composable
internal fun callsBar(model: CallsModel, route: String): CallsBar {
    val book by model.book.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val identity = (book as? BookState.Ready)?.identity
    // A call in progress owns the screen. It ends through its own controls.
    if (view.phase != CallPhase.Idle) return CallsBar(stringResource(R.string.call_title), null)
    if (identity == null) return CallsBar(null, null)
    val screen = decodeScreen(route)
    val title = when (screen) {
        CallsScreen.Home -> null
        CallsScreen.Add -> stringResource(R.string.add_contact)
        CallsScreen.ScanInvitation -> stringResource(R.string.answer_invitation)
        is CallsScreen.Details -> (book as BookState.Ready).contacts.byCode(screen.key)?.name
        is CallsScreen.Replace -> stringResource(R.string.key_changed_title)
    }
    return CallsBar(title, parentOf(screen)?.let(::encodeScreen))
}

/** Contacts are addressed in navigation by their public code. */
private fun List<Contact>.byCode(code: String) = firstOrNull { it.code == code }

/**
 * The Calls tab. [route] is owned by the caller so the top bar can name the
 * screen and offer Back. [onService] opens the Service tab.
 */
@Composable
fun CallsPage(model: CallsModel, route: String = CALLS_HOME, onRoute: (String) -> Unit = {},
              advanced: Boolean = false, onService: () -> Unit) {
    val book by model.book.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val screen = decodeScreen(route)
    // Callbacks such as a camera result read the screen shown when they run.
    val current by rememberUpdatedState(screen)
    val go = { next: CallsScreen -> onRoute(encodeScreen(next)) }
    val parent = parentOf(screen)
    BackHandler(enabled = parent != null && view.phase == CallPhase.Idle) { parent?.let(go) }
    val connected = connection.phase == ConnectionPhase.Connected

    when (val state = book) {
        BookState.Loading -> IdentityLoading()
        is BookState.Failed -> IdentityUnreadable(state.reason, model::open)
        is BookState.Ready -> {
            val identity = state.identity
            when {
                view.phase != CallPhase.Idle -> CallScreen(view, state.contacts, model, advanced, onService)
                identity == null -> CreateIdentity(model)
                else -> when (screen) {
                    CallsScreen.Home -> Home(identity, state.contacts, connected, onService, go)
                    CallsScreen.Add -> AddContact(model, onDone = { key ->
                        go(if (key == null) CallsScreen.Home else CallsScreen.Details(CallNative.contactCodeFormat(key)))
                    })
                    CallsScreen.ScanInvitation -> PageColumn {
                        SecureWindow()
                        QrScanner(R.string.scan_invitation_rationale, InvitationText::looksLikeInvitation,
                            onResult = { text ->
                                // A code decoded in the frame before Cancel or Back took
                                // effect is dropped: the scan is no longer what is shown.
                                if (current == CallsScreen.ScanInvitation) {
                                    go(CallsScreen.Home)
                                    val bytes = InvitationText.decode(text)
                                    if (bytes == null) model.refuseInvitation() else model.answer(bytes)
                                }
                            },
                            onCancel = { go(CallsScreen.Home) })
                    }
                    is CallsScreen.Details -> when (val contact = state.contacts.byCode(screen.key)) {
                        null -> LaunchedEffect(screen) { go(CallsScreen.Home) }
                        else -> ContactDetails(contact, state.contacts, connected, model, go, onService)
                    }
                    is CallsScreen.Replace -> when (val contact = state.contacts.byCode(screen.key)) {
                        null -> LaunchedEffect(screen) { go(CallsScreen.Home) }
                        else -> ReplaceKey(contact, model, onDone = { key ->
                            go(CallsScreen.Details(if (key == null) contact.code else CallNative.contactCodeFormat(key)))
                        })
                    }
                }
            }
        }
    }
}

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
internal fun Home(identity: OwnIdentity, contacts: List<Contact>, connected: Boolean, onService: () -> Unit,
                 go: (CallsScreen) -> Unit) = PageColumn {
    ConnectionHint(connected, onService)
    Spacer(Modifier.height(16.dp))
    // Actions come before the list, so they stay in reach however many contacts there are.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { go(CallsScreen.Add) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.add_contact))
        }
        OutlinedButton(onClick = { go(CallsScreen.ScanInvitation) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.answer_invitation))
        }
    }
    Spacer(Modifier.height(24.dp))
    SectionTitle(R.string.contacts)
    if (contacts.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        SupportingText(R.string.no_contacts)
    }
    val collator = remember { Collator.getInstance() }
    contacts.sortedWith { a, b -> collator.compare(a.name, b.name) }.forEach { contact ->
        ContactRow(contact) { go(CallsScreen.Details(contact.code)) }
    }
    Spacer(Modifier.height(32.dp))
    ContactCode(identity, initiallyShown = contacts.isEmpty())
}

/** Calls go through the relay service, so the first thing to know is whether it is up. */
@Composable
private fun ConnectionHint(connected: Boolean, onService: () -> Unit) {
    StatusCard(if (connected) StatusTone.Good else StatusTone.Neutral) {
        Text(stringResource(if (connected) R.string.calls_ready else R.string.calls_need_connection),
            style = MaterialTheme.typography.bodyLarge)
        if (!connected) FilledTonalButton(onClick = onService, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.open_service))
        }
    }
}

/** One contact. The whole row opens it, and the monogram is decoration. */
@Composable
private fun ContactRow(contact: Contact, onOpen: () -> Unit) {
    val trusted = contact.verified && !contact.blocked && contact.replacedBy == null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.open_contact), onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Monogram(contact.name, trusted)
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(trustLabel(contact)), style = MaterialTheme.typography.bodyMedium,
                color = if (contact.blocked || contact.replacedBy != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trusted) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The first letter of a local display name in a circle. Screen readers read the name instead. */
@Composable
private fun Monogram(name: String, trusted: Boolean, diameter: Int = 40) {
    val trimmed = name.trim()
    val initial = if (trimmed.isEmpty()) "" else String(Character.toChars(trimmed.codePointAt(0))).uppercase()
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(diameter.dp).clip(CircleShape)
        .background(if (trusted) colors.primaryContainer else colors.surfaceContainerHighest)
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(initial, style = if (diameter > 40) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (trusted) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
}

/**
 * The public contact code. A new user needs it to be added, so it starts open
 * until the first contact exists, then stays out of the way of the list.
 */
@Composable
private fun ColumnScope.ContactCode(identity: OwnIdentity, initiallyShown: Boolean) {
    val context = LocalContext.current
    val codeLabel = stringResource(R.string.your_contact_code)
    val shareTitle = stringResource(R.string.share_code)
    var shown by rememberSaveable { mutableStateOf(initiallyShown) }
    SectionTitle(R.string.your_contact_code)
    Spacer(Modifier.height(4.dp))
    SupportingText(R.string.your_contact_code_description)
    if (!shown) {
        TextButton(onClick = { shown = true }) { Text(stringResource(R.string.show_contact_code)) }
        return
    }
    Spacer(Modifier.height(16.dp))
    QrCode(identity.code, stringResource(R.string.your_contact_code_qr), Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(12.dp))
    SelectionContainer { Text(identity.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(12.dp))
    Fingerprint(R.string.your_fingerprint, identity.fingerprint)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A contact code is public, so the clipboard and share sheet may carry it.
        // An invitation never goes either way.
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(codeLabel, identity.code))
        }) { Text(stringResource(R.string.copy_code)) }
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, identity.code)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }) { Text(stringResource(R.string.share_code)) }
    }
    TextButton(onClick = { shown = false }) { Text(stringResource(R.string.hide_contact_code)) }
}

@StringRes
private fun trustLabel(contact: Contact) = when {
    contact.replacedBy != null -> R.string.trust_replaced
    contact.blocked -> R.string.trust_blocked
    contact.verified -> R.string.trust_verified
    else -> R.string.trust_unverified
}

/** A contact code is ASCII: no capitals, suggestions or autocorrection. */
private val codeKeyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)

@Composable
internal fun AddContact(model: CallsModel, onDone: (ByteArray?) -> Unit) = PageColumn {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val ready = name.isNotBlank() && code.isNotBlank()
    val add = { scope.launch { model.addContact(code, name)?.let(onDone) }; Unit }
    SupportingText(R.string.add_contact_description)
    Spacer(Modifier.height(16.dp))
    if (scanning) {
        QrScanner(R.string.scan_contact_rationale, { it.startsWith(CONTACT_PREFIX) },
            onResult = { code = it; scanning = false }, onCancel = { scanning = false })
        return@PageColumn
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contact_name)) },
        singleLine = true, keyboardOptions = KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Next))
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contact_code)) },
        keyboardOptions = codeKeyboard, keyboardActions = KeyboardActions(onDone = { if (ready) add() }))
    TextButton(onClick = { scanning = true }) { Text(stringResource(R.string.scan_code)) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = add, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.add)) }
        TextButton(onClick = { onDone(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
internal fun ContactDetails(contact: Contact, contacts: List<Contact>, connected: Boolean, model: CallsModel,
                           go: (CallsScreen) -> Unit, onService: () -> Unit) = PageColumn {
    val scope = rememberCoroutineScope()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var blocking by rememberSaveable { mutableStateOf(false) }
    val trusted = contact.verified && !contact.blocked && contact.replacedBy == null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Monogram(contact.name, trusted, diameter = 56)
        Column(Modifier.weight(1f)) {
            // The top bar cuts a long name to one line, so the complete name is here.
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trusted) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(stringResource(trustLabel(contact)), style = MaterialTheme.typography.bodyMedium,
                    color = if (contact.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val replacement = contact.replacedBy?.let { key -> contacts.firstOrNull { it.key.contentEquals(key) } }
    if (contact.replacedBy != null) {
        Notice(R.string.key_replaced_notice)
        replacement?.let { next ->
            TextButton(onClick = { go(CallsScreen.Details(next.code)) }) { Text(stringResource(R.string.open_new_key)) }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (contact.replaces != null && !contact.verified) {
        Notice(R.string.key_new_notice)
        Spacer(Modifier.height(16.dp))
    }
    if (contact.verified) {
        // A call needs the relay service. Say so here instead of starting a call that cannot begin.
        Button(onClick = { model.call(contact.key) }, enabled = connected, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_call), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.call_contact))
        }
        Spacer(Modifier.height(8.dp))
        if (connected) SupportingText(R.string.call_description) else {
            SupportingText(R.string.call_needs_connection)
            TextButton(onClick = onService) { Text(stringResource(R.string.open_service)) }
        }
        Spacer(Modifier.height(24.dp))
    }
    Fingerprint(R.string.contact_fingerprint, contact.fingerprint)
    if (!contact.verified && !contact.blocked) {
        SupportingText(R.string.verify_description)
        Spacer(Modifier.height(12.dp))
        if (scanning) {
            QrScanner(R.string.scan_contact_rationale, { it.startsWith(CONTACT_PREFIX) },
                onResult = { scanned ->
                    scanning = false
                    // In person, the scanned key must be this contact's key exactly.
                    if (scanned == contact.code) scope.launch { model.verify(contact.key, contact.fingerprint) }
                    else model.refuseScannedKey()
                },
                onCancel = { scanning = false })
            return@PageColumn
        }
        FlowActions {
            Button(onClick = { scope.launch { model.verify(contact.key, contact.fingerprint) } },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fingerprints_match)) }
            OutlinedButton(onClick = { scanning = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.scan_to_verify))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (!contact.blocked) {
        TextButton(onClick = { go(CallsScreen.Replace(contact.code)) }) { Text(stringResource(R.string.key_changed)) }
        TextButton(onClick = { blocking = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.block_contact))
        }
    }
    if (blocking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { blocking = false },
        title = { Text(stringResource(R.string.block_contact_title)) },
        text = { Text(stringResource(R.string.block_contact_description), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { blocking = false; model.block(contact.key) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.block))
        } },
        dismissButton = { TextButton(onClick = { blocking = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ReplaceKey(contact: Contact, model: CallsModel, onDone: (ByteArray?) -> Unit) = PageColumn {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val replace = { scope.launch { model.replace(contact.key, code)?.let(onDone) }; Unit }
    Text(stringResource(R.string.key_changed_description, contact.name), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    if (scanning) {
        QrScanner(R.string.scan_contact_rationale, { it.startsWith(CONTACT_PREFIX) },
            onResult = { code = it; scanning = false }, onCancel = { scanning = false })
        return@PageColumn
    }
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.new_contact_code)) },
        keyboardOptions = codeKeyboard, keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) replace() }))
    TextButton(onClick = { scanning = true }) { Text(stringResource(R.string.scan_code)) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = replace, enabled = code.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.replace_key))
        }
        TextButton(onClick = { onDone(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
    }
}

/** One call's screen. Internal so instrumentation can render each phase from a fixed [CallView]. */
@Composable
internal fun CallScreen(view: CallView, contacts: List<Contact>, model: CallsModel, advanced: Boolean,
                        onService: () -> Unit) {
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.startAudio()
    }
    PageColumn {
    val peer = view.peer?.let { key -> contacts.firstOrNull { it.key.contentEquals(key) } }
    val name = peer?.name ?: stringResource(R.string.unknown_caller)
    val invitation = view.invitation
    // The caller already knows whom they are calling. An answering side learns it only after the proof.
    val calling = if (view.initiator && peer != null) stringResource(R.string.calling_contact, peer.name) else null
    when (view.phase) {
        CallPhase.Inviting -> if (invitation != null) {
            SecureWindow()
            Heading(R.string.invitation_title)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.invitation_description, name), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            InvitationCode(invitation)
            Spacer(Modifier.height(16.dp))
            Notice(R.string.invitation_warning)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = model::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel_call))
            }
        } else Progress(R.string.call_preparing, model, calling)
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

@Composable
private fun InvitationCode(invitation: ByteArray) {
    // The String cannot be wiped. The controller wipes the bytes when the
    // handshake starts or the attempt ends, and the matrix is cleared here.
    val text = remember(invitation) { InvitationText.encode(invitation) }
    QrCode(text, stringResource(R.string.invitation_qr), Modifier.fillMaxWidth())
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

private const val CONTACT_PREFIX = "ito-contact:1:"

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
}
