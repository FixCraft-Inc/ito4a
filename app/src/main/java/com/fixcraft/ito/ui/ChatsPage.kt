package com.fixcraft.ito.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.call.InvitationText
import com.fixcraft.ito.content.ChatSettings
import com.fixcraft.ito.messaging.MessageNative
import com.fixcraft.ito.messaging.SafetyQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal data class ChatBubble(val id: ByteArray, val outgoing: Boolean, val kind: Int, val text: String,
    val sent: Long, val arrived: Long, val status: Int, val resent: Boolean, val timer: Int)
internal class ChatCall(val at: Long, val duration: Long, val result: Int)
internal class ChatDisplay(val paired: Boolean, val safety: String, val qr: String, val relay: String,
    val messages: List<ChatBubble>, val calls: List<ChatCall>, val timer: Int, val settings: ChatSettings,
    val reachable: Boolean, val pairedAt: Long, val verifiedBy: Int)
private sealed class ChatEvent(val time: Long) {
    class Message(val bubble: ChatBubble) : ChatEvent(bubble.arrived)
    class Call(val at: Long, val duration: Long, val result: Int) : ChatEvent(at)
}

@Composable
internal fun chatBar(model: CallsModel, route: String): CallsBar {
    val book by model.book.collectAsStateWithLifecycle()
    val call by model.view.collectAsStateWithLifecycle()
    if (call.phase != CallPhase.Idle) return CallsBar(stringResource(R.string.call_title), null)
    val code = route.substringAfter(':', "")
    val name = (book as? BookState.Ready)?.contacts?.firstOrNull { it.code == code }?.name
    return CallsBar(when {
        route == "add" -> stringResource(R.string.add_contact)
        route == "scan" -> stringResource(R.string.chat_scan)
        route == "me" -> stringResource(R.string.chat_your_profile)
        name != null -> name
        else -> stringResource(R.string.chats)
    }, if (route == CALLS_HOME) null else if (route.startsWith("profile:")) "chat:$code" else CALLS_HOME)
}

/** The messenger uses the existing Ito palette, typography and Material controls. */
@Composable
internal fun ChatsPage(model: CallsModel, route: String, onRoute: (String) -> Unit,
    advanced: Boolean, onService: () -> Unit) {
    val book by model.book.collectAsStateWithLifecycle()
    val call by model.view.collectAsStateWithLifecycle()
    val messenger by model.messenger.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.chat_share_link)
    var safetyMatch by remember { mutableStateOf<Pair<Contact, String>?>(null) }
    val back = chatBar(model, route).back
    BackHandler(back != null && call.phase == CallPhase.Idle) { back?.let(onRoute) }
    when (val ready = book) {
        BookState.Loading -> IdentityLoading()
        is BookState.Failed -> IdentityUnreadable(ready.reason, model::open)
        is BookState.Ready -> when {
            call.phase != CallPhase.Idle -> CallScreen(call, ready.contacts, model, advanced, onService)
            ready.identity == null -> CreateIdentity(model)
            route == "scan" -> PageColumn {
                SecureWindow()
                // Accept every decoded value. Dispatch explains unsupported and legacy codes.
                QrScanner(R.string.chat_scan_description, { true }, { text ->
                    onRoute(CALLS_HOME)
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
                                matched = ready.contacts.firstOrNull { contact ->
                                    val paired = paired(contact.key)
                                    paired != null && hex(paired.fingerprint) == other && safety(contact.key) == qr.digits
                                }?.let { it to qr.digits }
                                if (matched == null) rejectScan()
                            }
                        }
                    }
                }, { onRoute(CALLS_HOME) })
            }
            route == "add" -> PageColumn {
                SecureWindow()
                val displayCode = messenger.code?.toString(Charsets.US_ASCII)
                SupportingText(R.string.chat_pair_description)
                Button(onClick = { model.message { createCode(false) } }) { Text(stringResource(R.string.chat_show_code)) }
                OutlinedButton(onClick = { onRoute("scan") }) { Text(stringResource(R.string.chat_scan)) }
                OutlinedButton(onClick = { model.message { createCode(true) } }) { Text(stringResource(R.string.chat_make_link)) }
                if (displayCode != null) {
                    QrCode(displayCode, stringResource(R.string.chat_pair_qr))
                    if (messenger.link) TextButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, displayCode), shareTitle))
                    }) { Text(stringResource(R.string.chat_share_link)) }
                    TextButton(onClick = { model.message { hideCode() } }) { Text(stringResource(R.string.chat_hide_code)) }
                }
                var pasted by remember { mutableStateOf("") }
                OutlinedTextField(pasted, { if (it.length <= 8192) pasted = it },
                    label = { Text(stringResource(R.string.chat_paste_link)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                TextButton(onClick = {
                    val code = pasted; pasted = ""
                    model.message { scan(code.toByteArray(Charsets.US_ASCII), false) }
                }, enabled = pasted.startsWith("ito-pair:")) { Text(stringResource(R.string.chat_open_link)) }
                PairingProgress(model)
            }
            route == "me" -> OwnProfile(model)
            route.startsWith("chat:") || route.startsWith("profile:") -> {
                val contact = ready.contacts.firstOrNull { it.code == route.substringAfter(':') }
                if (contact == null) LaunchedEffect(route) { onRoute(CALLS_HOME) }
                else ConversationPage(model, contact, route.startsWith("profile:"), advanced, onRoute)
            }
            else -> PageColumn {
                val summaries by produceState<Map<String, com.fixcraft.ito.content.ChatSummary?>>(emptyMap(), messenger.revision, ready.contacts) {
                    value = withContext(Dispatchers.IO) { ready.contacts.associate { contact ->
                        contact.code to try { model.history.summary(contact.key) } catch (_: com.fixcraft.ito.content.ContentException) { null }
                        catch (_: com.fixcraft.ito.security.AppLockedException) { null }
                    } }
                }
                TextButton(onClick = onService) { Text(stringResource(if (messenger.online) R.string.chat_connected else R.string.chat_connecting)) }
                if (!messenger.online) SupportingText(R.string.chat_mailbox_required)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRoute("add") }) { Text(stringResource(R.string.add_contact)) }
                    TextButton(onClick = { onRoute("scan") }) { Text(stringResource(R.string.chat_scan)) }
                    TextButton(onClick = { onRoute("me") }) { Text(stringResource(R.string.chat_your_profile)) }
                }
                PairingProgress(model)
                if (ready.contacts.isEmpty()) SupportingText(R.string.chat_empty)
                ready.contacts.filter { summaries[it.code]?.removed != true }.sortedByDescending { summaries[it.code]?.time ?: 0 }.forEach { contact ->
                    val summary = summaries[contact.code]
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { onRoute("chat:${contact.code}") }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrustPortrait(contact)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when { contact.blocked -> R.string.trust_blocked
                                contact.verified -> R.string.trust_verified; else -> R.string.trust_unverified }))
                            if (summary == null) Text(stringResource(R.string.chat_history_unavailable), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else if (summary.kind != 0) Text(when (summary.kind) {
                                1 -> summary.text
                                100 -> stringResource(R.string.call_title)
                                9 -> stringResource(R.string.chat_auto_delete)
                                else -> stringResource(R.string.chat_name_hint, summary.text)
                            }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (summary != null && summary.time > 0) Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(summary.time)), style = MaterialTheme.typography.labelSmall)
                            if (summary != null && summary.unread > 0) Text(stringResource(R.string.chat_unread, summary.unread), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    safetyMatch?.let { (contact, digits) -> AlertDialog(onDismissRequest = { safetyMatch = null },
        title = { Text(stringResource(R.string.chat_safety_match, contact.name)) },
        text = { Text(digits.chunked(5).joinToString(" ")) },
        confirmButton = { TextButton(onClick = { model.message { verify(contact.key, digits) }; safetyMatch = null }) { Text(stringResource(R.string.chat_matches)) } },
        dismissButton = { TextButton(onClick = { safetyMatch = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun PairingProgress(model: CallsModel) {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    state.pairings.forEach { pairing ->
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pairing.name.ifEmpty { stringResource(R.string.chat_pair_waiting) })
            if (pairing.shortCode.isNotEmpty()) Text(pairing.shortCode.toString(Charsets.US_ASCII), style = MaterialTheme.typography.headlineMedium)
            if (pairing.role == MessageNative.OWNER && pairing.shortCode.isNotEmpty()) {
                Button(onClick = { model.message { decide(pairing.id, true) } }) { Text(stringResource(R.string.chat_matches)) }
                TextButton(onClick = { model.message { decide(pairing.id, false) } }) { Text(stringResource(R.string.chat_does_not_match)) }
            }
            TextButton(onClick = { model.message { cancelPairing(pairing.id) } }) { Text(stringResource(R.string.cancel)) }
        }
    }
    if (state.shortCode.isNotEmpty() && state.pairings.none { it.shortCode.isNotEmpty() })
        Text(stringResource(R.string.chat_short_code, state.shortCode), Modifier.padding(vertical = 12.dp))
}

@Composable
private fun ConversationPage(model: CallsModel, contact: Contact, profile: Boolean, advanced: Boolean, onRoute: (String) -> Unit) {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    val display by produceState<ChatDisplay?>(null, contact.code, state.revision) {
        value = withContext(Dispatchers.IO) {
            try {
                val paired = model.messenger.paired(contact.key)
                val safety = if (paired == null) "" else model.messenger.safety(contact.key)
                val qr = if (paired == null) "" else SafetyQr(hex(model.messenger.ownFingerprint()), hex(paired.fingerprint), safety).encode()
                val messages = model.history.messages(contact.key).map { m -> m.use {
                    ChatBubble(it.id.copyOf(), it.outgoing, it.kind, it.text.toString(Charsets.UTF_8), it.sent,
                        it.arrived, it.status, it.flags and 1 != 0, it.timer)
                } }
                val calls = model.history.history(contact.key).map { c -> c.use { ChatCall(it.timeUnix * 1000, it.durationMs, it.result) } }
                ChatDisplay(paired != null, safety, qr, paired?.send?.let { hex(it.fingerprint) + "/" + it.service } ?: "",
                    messages, calls, model.history.read(contact.key) { it.timer }, model.history.settings(),
                    paired?.send?.let { target -> model.connection.value.peer == hex(target.fingerprint) &&
                        model.connection.value.mailboxAvailable && target.service == "ito-mailbox" } ?: false,
                    paired?.pairedUnix ?: 0, paired?.verifiedBy ?: 0)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    val data = display
    if (data == null) { PageColumn { SupportingText(R.string.chat_history_unavailable) }; return }
    LaunchedEffect(contact.code, data.messages.count { !it.outgoing && it.status < 3 }) {
        if (!profile && data.paired && contact.verified && data.messages.any { !it.outgoing && it.status < 3 }) model.message { markRead(contact.key) }
    }
    if (profile) ContactProfile(model, contact, data, advanced, onRoute)
    else ChatTimeline(model, contact, data, onRoute)
}

@Composable
internal fun ChatTimeline(model: CallsModel, contact: Contact, data: ChatDisplay, onRoute: (String) -> Unit) {
    var draft by remember(contact.code) { mutableStateOf("") }
    var operation by remember(contact.code) { mutableStateOf(ByteArray(16).also(java.security.SecureRandom()::nextBytes)) }
    var sending by remember(contact.code) { mutableStateOf(false) }
    var details by remember { mutableStateOf<ChatBubble?>(null) }
    val context = LocalContext.current
    val scroll = rememberLazyListState()
    val events = remember(data) { (data.messages.map { ChatEvent.Message(it) } +
        data.calls.map { ChatEvent.Call(it.at, it.duration, it.result) }).sortedBy { it.time } }
    LaunchedEffect(events.size) { if (events.isNotEmpty()) scroll.animateScrollToItem(events.lastIndex) }
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onRoute("profile:${contact.code}") }, modifier = Modifier.weight(1f)) {
                TrustPortrait(contact); Text(contact.name, Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { model.call(contact.key) }, enabled = contact.verified && !contact.blocked && data.reachable) {
                Icon(painterResource(R.drawable.ic_call), stringResource(R.string.call_title))
            }
        }
        if (data.paired && !data.reachable) SupportingText(R.string.chat_other_relay_or_offline)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            events.forEachIndexed { index, event -> item(key = index) {
                if (event is ChatEvent.Call) {
                    val label = when (event.result) {
                        -1101 -> R.string.ring_canceled
                        -1102 -> R.string.ring_declined
                        -1103 -> R.string.ring_busy
                        -1104 -> R.string.ring_missed
                        else -> null
                    }
                    val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.at))
                    Text(if (label == null) stringResource(R.string.chat_call_record, at, event.duration / 1000)
                        else stringResource(R.string.ring_record, stringResource(label), at),
                        Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                } else {
                val bubble = (event as ChatEvent.Message).bubble
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(bubble.sent))
                val mark = stringResource(when { !bubble.outgoing -> R.string.chat_received
                    bubble.status >= 3 && data.settings.readReceipts -> R.string.chat_read
                    bubble.status >= 2 -> R.string.chat_delivered
                    bubble.status == 1 -> R.string.chat_sent; else -> R.string.chat_queued })
                val body = when (bubble.kind) { 9 -> stringResource(R.string.chat_timer_changed, stringResource(timerLabel(bubble.timer)))
                    8 -> stringResource(R.string.chat_name_hint, bubble.text); else -> bubble.text }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (bubble.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth(.88f).combinedClickable(onClick = { details = bubble },
                        onLongClick = { details = bubble }), shape = MaterialTheme.shapes.medium,
                        color = if (bubble.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(12.dp)) {
                            Text(body)
                            Text("$time · $mark", style = MaterialTheme.typography.labelSmall)
                            if (bubble.resent) Text(stringResource(R.string.chat_resent), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                }
            } }
        }
        when {
            contact.blocked -> SupportingText(R.string.trust_blocked)
            !data.paired -> TextButton(onClick = { onRoute("add") }) { Text(stringResource(R.string.chat_pair_again)) }
            !contact.verified -> TextButton(onClick = { onRoute("profile:${contact.code}") }) { Text(stringResource(R.string.chat_compare_safety)) }
            else -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { if (it.toByteArray(Charsets.UTF_8).size <= 4096) { draft = it; operation = ByteArray(16).also(java.security.SecureRandom()::nextBytes) } },
                    label = { Text(stringResource(R.string.chat_message)) }, modifier = Modifier.weight(1f), maxLines = 6, enabled = !sending)
                TextButton(onClick = {
                    val text = draft; val token = operation; sending = true
                    model.message(onSuccess = {
                        if (draft == text) { draft = ""; operation = ByteArray(16).also(java.security.SecureRandom()::nextBytes) }
                    }, onFinished = { sending = false }) { send(contact.key, text.toByteArray(Charsets.UTF_8), token) }
                }, enabled = !sending && draft.isNotBlank()) { Text(stringResource(R.string.chat_send)) }
            }
        }
    }
    details?.let { bubble -> AlertDialog(onDismissRequest = { details = null }, title = { Text(stringResource(R.string.chat_details)) },
        text = { Column { Text(stringResource(R.string.chat_written, DateFormat.getDateTimeInstance().format(Date(bubble.sent))))
            Text(stringResource(R.string.chat_arrived, DateFormat.getDateTimeInstance().format(Date(bubble.arrived))))
            if (bubble.resent) Text(stringResource(R.string.chat_resent))
            if (bubble.kind == 1) TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", bubble.text))
            }) { Text(stringResource(R.string.chat_copy)) } } },
        confirmButton = { TextButton(onClick = { model.message { deleteMessage(contact.key, bubble.id, bubble.outgoing) }; details = null }) {
            Text(stringResource(R.string.chat_delete_for_me)) } },
        dismissButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.done)) } }) }
}

@Composable
internal fun ContactProfile(model: CallsModel, contact: Contact, data: ChatDisplay, advanced: Boolean, onRoute: (String) -> Unit) = PageColumn {
    var name by remember(contact.code) { mutableStateOf(contact.name) }
    var compared by remember { mutableStateOf("") }
    var showingSafety by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    TrustPortrait(contact)
    Text(stringResource(when {
        contact.blocked -> R.string.trust_blocked
        !contact.verified -> R.string.trust_unverified
        data.verifiedBy == 1 -> R.string.chat_verified_in_person
        data.verifiedBy == 2 -> R.string.chat_verified_safety_code
        else -> R.string.trust_unverified
    }))
    if (data.pairedAt > 0) Text(stringResource(R.string.chat_paired_on,
        DateFormat.getDateInstance().format(Date(data.pairedAt * 1000))))
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contact_name)) }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { model.rename(contact.key, name.trim()) }) { Text(stringResource(R.string.chat_save_name)) }
    TextButton(onClick = { onRoute("chat:${contact.code}") }) { Text(stringResource(R.string.chat_message)) }
    TextButton(onClick = { model.call(contact.key) }, enabled = contact.verified && !contact.blocked && data.reachable) {
        Text(stringResource(R.string.call_title))
    }
    if (!data.paired) TextButton(onClick = { onRoute("add") }) { Text(stringResource(R.string.chat_pair_again)) }
    else {
        TextButton(onClick = { showingSafety = !showingSafety }) { Text(stringResource(R.string.chat_compare_safety)) }
        if (showingSafety) {
            SelectionContainer { Text(data.safety.chunked(5).joinToString(" ")) }
            QrCode(data.qr, stringResource(R.string.chat_safety_qr))
            SupportingText(R.string.chat_safety_description)
            OutlinedTextField(compared, { compared = it }, label = { Text(stringResource(R.string.chat_compared_code)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.message { verify(contact.key, compared) } }) { Text(stringResource(R.string.chat_matches)) }
            TextButton(onClick = { onRoute("scan") }) { Text(stringResource(R.string.chat_scan)) }
        }
        Text(stringResource(R.string.chat_auto_delete), style = MaterialTheme.typography.titleMedium)
        SupportingText(R.string.chat_auto_delete_description)
        (0..3).forEach { timer ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = data.timer == timer,
                enabled = contact.verified && !contact.blocked, role = Role.RadioButton,
                onClick = { model.message { timer(contact.key, timer) } }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(data.timer == timer, null); Text(stringResource(timerLabel(timer)))
            }
        }
    }
    if (advanced) { SelectionContainer { Text(contact.fingerprint) }; Text(data.relay) }
    TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.chat_delete_history)) }
    TextButton(onClick = { model.message { block(contact.key) } }) { Text(stringResource(R.string.block_contact)) }
    TextButton(onClick = { removing = true }) { Text(stringResource(R.string.chat_remove_contact)) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.chat_delete_history)) },
        text = { Text(stringResource(R.string.chat_delete_history_description)) },
        confirmButton = { TextButton(onClick = { model.message { deleteHistory(contact.key) }; deleting = false }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(stringResource(R.string.chat_remove_contact)) },
        text = { Text(stringResource(R.string.chat_remove_contact_description)) },
        confirmButton = { TextButton(onClick = { model.message(onSuccess = { onRoute(CALLS_HOME) }) { removeContact(contact.key) }; removing = false }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun OwnProfile(model: CallsModel) = PageColumn {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    val settings by produceState<ChatSettings?>(null, state.revision) { value = withContext(Dispatchers.IO) {
        try { model.history.settings() } catch (_: com.fixcraft.ito.content.ContentException) { null }
                        catch (_: com.fixcraft.ito.security.AppLockedException) { null }
    } }
    val current = settings
    if (current == null) { SupportingText(R.string.chat_history_unavailable); return@PageColumn }
    var name by remember { mutableStateOf(current.name) }
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.chat_your_name)) }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { model.message { settings(ChatSettings(name.trim(), current.readReceipts)) } }) { Text(stringResource(R.string.chat_save_name)) }
    Row(Modifier.fillMaxWidth().toggleable(current.readReceipts, role = Role.Switch, onValueChange = { enabled ->
        model.message { settings(ChatSettings(current.name, enabled)) }
    }).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(stringResource(R.string.chat_read_receipts)); SupportingText(R.string.chat_read_receipts_description) }
        Switch(current.readReceipts, null)
    }
    var deleting by remember { mutableStateOf(false) }
    TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.chat_delete_all_history)) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.chat_delete_all_history)) },
        text = { Text(stringResource(R.string.chat_delete_history_description)) },
        confirmButton = { TextButton(onClick = { model.message { deleteAllHistory() }; deleting = false }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun TrustPortrait(contact: Contact) {
    val label = stringResource(when { contact.blocked -> R.string.trust_blocked; contact.verified -> R.string.trust_verified; else -> R.string.trust_unverified })
    val color = MaterialTheme.colorScheme.outline
    Box(Modifier.size(44.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 2.dp.toPx()
            drawCircle(color, radius = size.minDimension / 2 - width / 2,
                style = Stroke(width, pathEffect = if (contact.verified) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }
        Text(contact.name.take(1), style = MaterialTheme.typography.titleLarge)
    }
}
private fun timerLabel(timer: Int) = when (timer) { 1 -> R.string.chat_timer_day; 2 -> R.string.chat_timer_week; 3 -> R.string.chat_timer_month; else -> R.string.chat_timer_off }
private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
