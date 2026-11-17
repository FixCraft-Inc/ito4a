package com.fixcraft.ito.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.content.ChatSettings
import com.fixcraft.ito.content.ContentException
import com.fixcraft.ito.messaging.MessageNative
import com.fixcraft.ito.security.AppLockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A contact's profile, read with its chat's state. */
@Composable
internal fun ContactProfileScreen(model: CallsModel, contact: Contact, advanced: Boolean, chrome: ScreenChrome,
                                  onRoute: (ChatsRoute) -> Unit) {
    val back = { onRoute(ChatsRoute.Chat(contact.code)) }
    when (val load = rememberChatDisplay(model, contact)) {
        ChatLoad.Loading -> DetailScaffold(chrome, back, { }) {}
        ChatLoad.Unreadable -> DetailScaffold(chrome, back, { }) { padding ->
            Box(Modifier.padding(padding).padding(24.dp)) { SupportingText(R.string.chat_history_unavailable) }
        }
        is ChatLoad.Ready -> DetailScaffold(chrome, back, { }) { padding ->
            ContactProfileContent(model, contact, load.display, advanced, onRoute, padding)
        }
    }
}

@Composable
internal fun ContactProfileContent(model: CallsModel, contact: Contact, data: ChatDisplay, advanced: Boolean,
                                   onRoute: (ChatsRoute) -> Unit, padding: PaddingValues = PaddingValues()) {
    var renaming by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var choosingTimer by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val withMicrophone = rememberMicrophoneGate(model::microphoneRefused)
    val colors = MaterialTheme.colorScheme
    val canTalk = contact.verified && !contact.blocked
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
        ProfileHeader(model, contact.key, contact.name, contact.trustRing, rememberPhoto(model, contact.key),
            trustLine(contact, data), data.pairedAt.takeIf { it > 0 }?.let {
                stringResource(R.string.chat_paired_on, DateFormat.getDateInstance().format(Date(it * 1000)))
            })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileAction(R.drawable.ic_chat, stringResource(R.string.chat_message), Modifier.weight(1f), enabled = !contact.blocked) {
                onRoute(ChatsRoute.Chat(contact.code))
            }
            ProfileAction(R.drawable.ic_call, stringResource(R.string.call_contact), Modifier.weight(1f),
                enabled = canTalk && data.reachable) { withMicrophone { model.call(contact.key) } }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            if (!data.paired) {
                ListHeading(R.string.profile_security)
                ListRow(R.drawable.ic_link, stringResource(R.string.chat_pair_again), Modifier.clickable(role = Role.Button) {
                    onRoute(ChatsRoute.AddContact)
                }, supporting = stringResource(R.string.chat_needs_pairing))
            } else {
                ListHeading(R.string.profile_security)
                ListRow(R.drawable.ic_verified_user, stringResource(R.string.chat_compare_safety),
                    Modifier.clickable(role = Role.Button) { comparing = !comparing },
                    supporting = stringResource(if (contact.verified) R.string.profile_safety_verified else R.string.profile_safety_needed),
                    tint = if (contact.verified) colors.primary else colors.onSurfaceVariant)
                if (comparing) SafetyComparison(model, contact, data, onRoute)
                ListRow(R.drawable.ic_timer, stringResource(R.string.chat_auto_delete),
                    Modifier.clickable(role = Role.Button, enabled = canTalk) { choosingTimer = true },
                    supporting = stringResource(timerLabel(data.timer)))
            }
            ListHeading(R.string.profile_on_this_phone)
            ListRow(R.drawable.ic_edit, stringResource(R.string.profile_edit_name), Modifier.clickable(role = Role.Button) { renaming = true },
                supporting = contact.name)
            if (advanced) {
                ListRow(R.drawable.ic_code, stringResource(R.string.contact_fingerprint), supporting = null)
                SelectionContainer {
                    Text(contact.fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                }
                if (data.relay.isNotEmpty()) Text(data.relay, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)
            ListRow(R.drawable.ic_delete, stringResource(R.string.chat_delete_history), Modifier.clickable(role = Role.Button) { deleting = true },
                tint = colors.error, titleColor = colors.error)
            if (!contact.blocked) ListRow(R.drawable.ic_block, stringResource(R.string.block_contact),
                Modifier.clickable(role = Role.Button) { blocking = true }, tint = colors.error, titleColor = colors.error)
            ListRow(R.drawable.ic_person_remove, stringResource(R.string.chat_remove_contact),
                Modifier.clickable(role = Role.Button) { removing = true }, tint = colors.error, titleColor = colors.error)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (renaming) NameDialog(R.string.contact_name, contact.name, onDismiss = { renaming = false }) { name ->
        model.rename(contact.key, name); renaming = false
    }
    if (choosingTimer) TimerDialog(data.timer, onDismiss = { choosingTimer = false }) { choice ->
        model.message { timer(contact.key, choice) }; choosingTimer = false
    }
    if (deleting) ConfirmDialog(R.string.chat_delete_history, R.string.chat_delete_history_description, R.string.chat_delete,
        onConfirm = { model.message { deleteHistory(contact.key) }; deleting = false }, onDismiss = { deleting = false })
    if (blocking) ConfirmDialog(R.string.block_contact_title, R.string.block_contact_description, R.string.block,
        onConfirm = { model.message { block(contact.key) }; blocking = false }, onDismiss = { blocking = false })
    if (removing) ConfirmDialog(R.string.chat_remove_contact, R.string.chat_remove_contact_description, R.string.remove,
        onConfirm = { model.message(onSuccess = { onRoute(ChatsRoute.Home) }) { removeContact(contact.key) }; removing = false },
        onDismiss = { removing = false })
}

@Composable
private fun trustLine(contact: Contact, data: ChatDisplay): String = stringResource(when {
    contact.blocked -> R.string.trust_blocked
    !contact.verified -> R.string.trust_unverified
    data.verifiedBy == MessageNative.VERIFIED_IN_PERSON -> R.string.chat_verified_in_person
    data.verifiedBy == MessageNative.VERIFIED_SAFETY_CODE -> R.string.chat_verified_safety_code
    else -> R.string.trust_verified
})

/** The large portrait, name and trust lines at the top of a profile. Tapping the portrait sets a photo. */
@Composable
private fun ProfileHeader(model: CallsModel, peer: ByteArray?, name: String, trust: Trust, photo: ImageBitmap?,
                          line: String, secondLine: String?) {
    var choosing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked?.let { model.setPhoto(peer, it) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.photo_choose)) { choosing = true }) {
                Avatar(name, photo, 112.dp, trust, peer)
            }
            SmallFloatingActionButton(onClick = { choosing = true }, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp),
                shape = CircleShape) {
                Icon(painterResource(R.drawable.ic_photo_camera), stringResource(R.string.photo_choose), Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
        Text(line, style = MaterialTheme.typography.bodyMedium,
            color = if (trust == Trust.Verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        secondLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.photo_title)) },
        text = { Text(stringResource(R.string.photo_description)) },
        confirmButton = { TextButton(onClick = {
            choosing = false
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(stringResource(R.string.photo_choose)) } },
        dismissButton = { Row {
            if (photo != null) TextButton(onClick = { choosing = false; model.removePhoto(peer) }) {
                Text(stringResource(R.string.photo_remove))
            }
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
        } })
}

@Composable
private fun ProfileAction(icon: Int, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The 60-digit safety code, its QR, and the comparison that verifies a contact. */
@Composable
private fun SafetyComparison(model: CallsModel, contact: Contact, data: ChatDisplay, onRoute: (ChatsRoute) -> Unit) {
    var compared by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SelectionContainer {
                Text(data.safety.chunked(5).chunked(4).joinToString("\n") { it.joinToString(" ") }, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            QrCode(data.qr, stringResource(R.string.chat_safety_qr), Modifier.widthIn(max = 240.dp))
            SupportingText(R.string.chat_safety_description)
            if (!contact.verified) {
                OutlinedTextField(compared, { compared = it }, label = { Text(stringResource(R.string.chat_compared_code)) },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.message { verify(contact.key, compared) } }) { Text(stringResource(R.string.chat_matches)) }
                    TextButton(onClick = { onRoute(ChatsRoute.Scan(ChatsRoute.Profile(contact.code))) }) {
                        Text(stringResource(R.string.chat_scan))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDialog(current: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.chat_auto_delete)) },
        text = { Column {
            SupportingText(R.string.chat_auto_delete_description)
            Spacer(Modifier.height(8.dp))
            (0..3).forEach { timer ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = current == timer, role = Role.RadioButton,
                    onClick = { onChoose(timer) }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(current == timer, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(timerLabel(timer)))
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun NameDialog(label: Int, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(label)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** The owner's row at the top of Settings, opening [OwnProfile]. */
@Composable
internal fun OwnProfileRow(model: CallsModel, onOpen: () -> Unit) {
    val settings = rememberOwnSettings(model)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(settings?.name ?: "", rememberPhoto(model, null), 60.dp, Trust.None, null, describe = false)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(settings?.name ?: stringResource(R.string.chat_your_profile), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.profile_own_summary), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_edit), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The name offered to new contacts, the owner's photo on this phone, and read receipts. */
@Composable
internal fun OwnProfile(model: CallsModel) {
    val current = rememberOwnSettings(model)
    if (current == null) { PageColumn { SupportingText(R.string.chat_history_unavailable) }; return }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ProfileHeader(model, null, current.name, Trust.None, rememberPhoto(model, null),
            stringResource(R.string.profile_own_summary), null)
        Column(Modifier.padding(horizontal = 24.dp)) {
            ListRow(R.drawable.ic_edit, stringResource(R.string.chat_your_name), Modifier.clickable(role = Role.Button) { renaming = true },
                supporting = current.name)
            ListRow(R.drawable.ic_done_all, stringResource(R.string.chat_read_receipts),
                Modifier.toggleable(current.readReceipts, role = Role.Switch) { enabled ->
                    model.message { settings(ChatSettings(current.name, enabled)) }
                }, supporting = stringResource(R.string.chat_read_receipts_description)) {
                Switch(current.readReceipts, null)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(R.drawable.ic_delete, stringResource(R.string.chat_delete_all_history), Modifier.clickable(role = Role.Button) { deleting = true },
                tint = MaterialTheme.colorScheme.error, titleColor = MaterialTheme.colorScheme.error)
        }
    }
    if (renaming) NameDialog(R.string.chat_your_name, current.name, onDismiss = { renaming = false }) { name ->
        model.message { settings(ChatSettings(name, current.readReceipts)) }; renaming = false
    }
    if (deleting) ConfirmDialog(R.string.chat_delete_all_history, R.string.chat_delete_history_description, R.string.chat_delete,
        onConfirm = { model.message { deleteAllHistory() }; deleting = false }, onDismiss = { deleting = false })
}

@Composable
private fun rememberOwnSettings(model: CallsModel): ChatSettings? {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    val settings by produceState<ChatSettings?>(null, state.revision) {
        value = withContext(Dispatchers.IO) {
            try { model.history.settings() } catch (_: ContentException) { null } catch (_: AppLockedException) { null }
        }
    }
    return settings
}
