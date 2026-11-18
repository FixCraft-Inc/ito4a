package com.fixcraft.ito.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.content.ChatMessage
import com.fixcraft.ito.content.ChatSettings
import com.fixcraft.ito.messaging.MessageNative
import com.fixcraft.ito.messaging.SafetyQr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal data class ChatBubble(val id: ByteArray, val outgoing: Boolean, val kind: Int, val text: String,
    val sent: Long, val arrived: Long, val status: Int, val resent: Boolean, val timer: Int)
internal class ChatCall(val at: Long, val duration: Long, val result: Int, val outgoing: Boolean)

/** Everything a chat and its profile show, read once per change of the messenger. */
internal class ChatDisplay(val paired: Boolean, val safety: String, val qr: String, val relay: String,
    val messages: List<ChatBubble>, val calls: List<ChatCall>, val timer: Int, val settings: ChatSettings,
    val reachable: Boolean, val pairedAt: Long, val verifiedBy: Int)

/** A chat's display state: still reading, unreadable (its files are kept), or ready. */
internal sealed interface ChatLoad {
    data object Loading : ChatLoad
    data object Unreadable : ChatLoad
    class Ready(val display: ChatDisplay) : ChatLoad
}

@Composable
internal fun rememberChatDisplay(model: CallsModel, contact: Contact): ChatLoad {
    val state by model.messenger.state.collectAsStateWithLifecycle()
    val load by produceState<ChatLoad>(ChatLoad.Loading, contact.code, state.revision) {
        value = withContext(Dispatchers.IO) {
            try { ChatLoad.Ready(readChat(model, contact)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { ChatLoad.Unreadable }
        }
    }
    return load
}

private fun readChat(model: CallsModel, contact: Contact): ChatDisplay {
    val paired = model.messenger.paired(contact.key)
    val safety = if (paired == null) "" else model.messenger.safety(contact.key)
    val qr = if (paired == null) "" else SafetyQr(hex(model.messenger.ownFingerprint()), hex(paired.fingerprint), safety).encode()
    val messages = model.history.messages(contact.key).map { record -> record.use {
        ChatBubble(it.id.copyOf(), it.outgoing, it.kind, it.text.toString(Charsets.UTF_8), it.sent,
            it.arrived, it.status, it.flags and ChatMessage.RESENT != 0, it.timer)
    } }
    val calls = model.history.history(contact.key).map { record -> record.use {
        ChatCall(it.timeUnix * 1000, it.durationMs, it.result, it.initiator)
    } }
    val connection = model.connection.value
    val reachable = paired?.send?.let { target ->
        connection.peer == hex(target.fingerprint) && connection.mailboxAvailable && target.service == "ito-mailbox"
    } ?: false
    return ChatDisplay(paired != null, safety, qr, paired?.send?.let { hex(it.fingerprint) + "/" + it.service } ?: "",
        messages, calls, model.history.read(contact.key) { it.timer }, model.history.settings(), reachable,
        paired?.pairedUnix ?: 0, paired?.verifiedBy ?: 0)
}

/** One verified contact's messages and calls, with the composer. */
@Composable
internal fun ChatScreen(model: CallsModel, contact: Contact, chrome: ScreenChrome, onRoute: (ChatsRoute) -> Unit) {
    when (val load = rememberChatDisplay(model, contact)) {
        ChatLoad.Loading -> DetailScaffold(chrome, { onRoute(ChatsRoute.Home) }, { ChatTitle(model, contact, null) {} }) {}
        ChatLoad.Unreadable -> DetailScaffold(chrome, { onRoute(ChatsRoute.Home) }, { ChatTitle(model, contact, null) {} }) { padding ->
            Box(Modifier.padding(padding).padding(24.dp)) { SupportingText(R.string.chat_history_unavailable) }
        }
        is ChatLoad.Ready -> {
            val data = load.display
            val unread = data.messages.count { !it.outgoing && it.status < ChatMessage.READ }
            LaunchedEffect(contact.code, unread) {
                if (unread > 0 && data.paired && contact.verified) model.message { markRead(contact.key) }
            }
            ChatScreenContent(model, contact, data, chrome, onRoute)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreenContent(model: CallsModel, contact: Contact, data: ChatDisplay, chrome: ScreenChrome,
                               onRoute: (ChatsRoute) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChatBubble?>(null) }
    var details by remember { mutableStateOf<ChatBubble?>(null) }
    val withMicrophone = rememberMicrophoneGate(model::microphoneRefused)
    val profile = { onRoute(ChatsRoute.Profile(contact.code)) }
    val status = when {
        contact.blocked -> stringResource(R.string.trust_blocked)
        !data.paired -> stringResource(R.string.chat_needs_pairing)
        !contact.verified -> stringResource(R.string.trust_unverified)
        !data.reachable -> stringResource(R.string.chat_status_waiting)
        else -> stringResource(R.string.encrypted_end_to_end)
    }
    DetailScaffold(chrome, onBack = { onRoute(ChatsRoute.Home) },
        title = { ChatTitle(model, contact, status, profile) },
        actions = {
            IconButton(onClick = { withMicrophone { model.call(contact.key) } },
                enabled = contact.verified && !contact.blocked && data.reachable) {
                Icon(painterResource(R.drawable.ic_call), stringResource(R.string.call_contact))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.chat_view_profile)) }, { menu = false; profile() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_person), null) })
                    DropdownMenuItem({ Text(stringResource(R.string.chat_delete_history)) }, { menu = false; deleting = true },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) })
                }
            }
        },
        bottomBar = { ChatFooter(model, contact, data, onRoute) },
        containerColor = MaterialTheme.ito.chatBackground) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (data.paired && contact.verified && !data.reachable) WaitingBanner()
            Timeline(data, Modifier.weight(1f), onSelect = { selected = it })
        }
    }
    selected?.let { bubble ->
        MessageActions(bubble, onDismiss = { selected = null }, onDetails = { selected = null; details = bubble },
            onDelete = { selected = null; model.message { deleteMessage(contact.key, bubble.id, bubble.outgoing) } })
    }
    details?.let { bubble -> MessageDetails(bubble, data.settings.readReceipts) { details = null } }
    if (deleting) ConfirmDialog(R.string.chat_delete_history, R.string.chat_delete_history_description, R.string.chat_delete,
        onConfirm = { model.message { deleteHistory(contact.key) }; deleting = false }, onDismiss = { deleting = false })
}

/** The contact's portrait, name and one status line. Tapping it opens their profile. */
@Composable
private fun ChatTitle(model: CallsModel, contact: Contact, status: String?, onClick: () -> Unit) {
    Row(Modifier.clickable(role = Role.Button, onClick = onClick, onClickLabel = stringResource(R.string.chat_view_profile)),
        verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(model, contact, 40.dp, describe = false)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun WaitingBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_offline), null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.chat_other_relay_or_offline), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What a timeline row is. Messages and calls share one order, by local arrival. */
private sealed interface TimelineEntry {
    val key: String
    class Day(val label: String) : TimelineEntry { override val key = "day:$label" }
    class Message(val bubble: ChatBubble, val groupEnd: Boolean) : TimelineEntry {
        override val key = "message:" + hex(bubble.id) + bubble.outgoing
    }
    class Call(val call: ChatCall, index: Int) : TimelineEntry { override val key = "call:$index:${call.at}" }
}

@Composable
private fun Timeline(data: ChatDisplay, modifier: Modifier, onSelect: (ChatBubble) -> Unit) {
    val today = stringResource(R.string.chat_today)
    val yesterday = stringResource(R.string.chat_yesterday)
    val entries = remember(data, today, yesterday) { timeline(data, today, yesterday) }
    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.ito.incomingBubble,
                contentColor = MaterialTheme.ito.onIncomingBubble) {
                Text(stringResource(R.string.chat_no_messages), Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        return
    }
    // Newest at the bottom, where the list starts, so new messages need no scrolling.
    LazyColumn(modifier.fillMaxWidth(), reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        items(entries.asReversed(), key = { it.key }) { entry ->
            when (entry) {
                is TimelineEntry.Day -> DayChip(entry.label)
                is TimelineEntry.Message -> MessageRow(entry.bubble, entry.groupEnd, data.settings.readReceipts, onSelect)
                is TimelineEntry.Call -> CallEntry(entry.call)
            }
        }
    }
}

private fun timeline(data: ChatDisplay, today: String, yesterday: String): List<TimelineEntry> {
    val events = (data.messages.map { it.arrived to it } + data.calls.map { it.at to it }).sortedBy { it.first }
    val now = Calendar.getInstance()
    val result = mutableListOf<TimelineEntry>()
    var day = -1
    events.forEachIndexed { index, (time, event) ->
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val dayIndex = then.get(Calendar.YEAR) * 400 + then.get(Calendar.DAY_OF_YEAR)
        if (dayIndex != day) {
            day = dayIndex
            result += TimelineEntry.Day(when (daysBetween(then, now)) {
                0 -> today
                1 -> yesterday
                else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
            })
        }
        when (event) {
            is ChatBubble -> {
                val next = events.getOrNull(index + 1)?.second as? ChatBubble
                val groupEnd = next == null || next.outgoing != event.outgoing || !sameDay(time, next.arrived)
                result += TimelineEntry.Message(event, groupEnd)
            }
            is ChatCall -> result += TimelineEntry.Call(event, index)
        }
    }
    return result
}

private fun sameDay(first: Long, second: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        ChatNote(label, Modifier.semantics { heading() })
    }
}

/** A small centred note on the chat background: a day label or a notice such as a timer change. */
@Composable
private fun ChatNote(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.ito.chatNote, contentColor = MaterialTheme.ito.onChatNote) {
        Text(text, modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(bubble: ChatBubble, groupEnd: Boolean, readShown: Boolean, onSelect: (ChatBubble) -> Unit) {
    // A notice such as a timer change reads as part of the chat, not as someone's words.
    if (bubble.kind == MessageNative.TIMER || bubble.kind == MessageNative.PROFILE) {
        val text = if (bubble.kind == MessageNative.TIMER) stringResource(R.string.chat_timer_changed, stringResource(timerLabel(bubble.timer)))
            else stringResource(R.string.chat_name_hint, bubble.text)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) { ChatNote(text) }
        return
    }
    val colors = MaterialTheme.colorScheme
    val outgoing = bubble.outgoing
    val large = 18.dp
    val tail = if (groupEnd) 4.dp else large
    val shape = if (outgoing) RoundedCornerShape(large, large, tail, large) else RoundedCornerShape(large, large, large, tail)
    val container = if (outgoing) colors.primaryContainer else MaterialTheme.ito.incomingBubble
    val ink = if (outgoing) colors.onPrimaryContainer else MaterialTheme.ito.onIncomingBubble
    // A bubble wraps its text, up to most of the row, so the other side stays readable.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = if (groupEnd) 6.dp else 0.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(Modifier.widthIn(max = (maxWidth * 0.82f).coerceAtMost(420.dp))
            .combinedClickable(onClick = { onSelect(bubble) }, onLongClick = { onSelect(bubble) }),
            shape = shape, color = container, contentColor = ink) {
            Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
                Text(bubble.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (bubble.resent) {
                        Text(stringResource(R.string.chat_resent), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(bubbleTime(bubble.sent), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                    if (outgoing) {
                        Spacer(Modifier.width(4.dp))
                        DeliveryMark(bubble.status, Modifier.size(15.dp), readShown, tint = ink.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CallEntry(call: ChatCall) {
    val missed = callMissed(call.outgoing, call.result)
    val icon = when {
        missed -> R.drawable.ic_call_missed
        call.outgoing -> R.drawable.ic_call_made
        else -> R.drawable.ic_call_received
    }
    val connected = com.fixcraft.ito.content.LocalCall.ringEnd(call.result) == null && call.duration > 0
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.ito.incomingBubble, contentColor = MaterialTheme.ito.onIncomingBubble) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(16.dp),
                    tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                val label = stringResource(callLabel(call.outgoing, call.result))
                val text = if (connected) stringResource(R.string.chat_call_entry_duration, label, bubbleTime(call.at),
                    formatDuration(call.duration)) else stringResource(R.string.chat_call_entry, label, bubbleTime(call.at))
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The composer for a verified contact, or the one step that unlocks it. */
@Composable
private fun ChatFooter(model: CallsModel, contact: Contact, data: ChatDisplay, onRoute: (ChatsRoute) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Box(Modifier.navigationBarsPadding().imePadding()) {
            when {
                // The title bar already says why. The footer offers only the step that unlocks the chat.
                contact.blocked -> FooterNotice(stringResource(R.string.chat_blocked_notice), null) {}
                !data.paired -> FooterNotice(null, stringResource(R.string.chat_pair_again)) { onRoute(ChatsRoute.AddContact) }
                !contact.verified -> FooterNotice(null, stringResource(R.string.chat_compare_safety)) {
                    onRoute(ChatsRoute.Profile(contact.code))
                }
                else -> Composer(model, contact)
            }
        }
    }
}

@Composable
private fun FooterNotice(text: String?, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        action?.let { Button(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun Composer(model: CallsModel, contact: Contact) {
    var draft by remember(contact.code) { mutableStateOf("") }
    // A stable operation ID per draft lets a retried send after an ambiguous save stay one message.
    var operation by remember(contact.code) { mutableStateOf(newOperation()) }
    var sending by remember(contact.code) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        TextField(draft, { if (it.toByteArray(Charsets.UTF_8).size <= 4096) { draft = it; operation = newOperation() } },
            placeholder = { Text(stringResource(R.string.chat_message)) }, modifier = Modifier.weight(1f), maxLines = 6,
            enabled = !sending, shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = {
            val text = draft
            val token = operation
            sending = true
            model.message(onSuccess = { if (draft == text) { draft = ""; operation = newOperation() } },
                onFinished = { sending = false }) { send(contact.key, text.toByteArray(Charsets.UTF_8), token) }
        }, enabled = !sending && draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
            Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send))
        }
    }
}

private fun newOperation() = ByteArray(16).also(SecureRandom()::nextBytes)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(bubble: ChatBubble, onDismiss: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (bubble.kind == MessageNative.TEXT) ListRow(R.drawable.ic_copy, stringResource(R.string.chat_copy),
                Modifier.clickable(role = Role.Button) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", bubble.text))
                    onDismiss()
                })
            ListRow(R.drawable.ic_info, stringResource(R.string.chat_details), Modifier.clickable(role = Role.Button, onClick = onDetails))
            ListRow(R.drawable.ic_delete, stringResource(R.string.chat_delete_for_me), Modifier.clickable(role = Role.Button, onClick = onDelete),
                tint = MaterialTheme.colorScheme.error, titleColor = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MessageDetails(bubble: ChatBubble, readShown: Boolean, onDismiss: () -> Unit) {
    val format = DateFormat.getDateTimeInstance()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.chat_details)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.chat_written, format.format(Date(bubble.sent))))
            Text(stringResource(R.string.chat_arrived, format.format(Date(bubble.arrived))))
            if (bubble.outgoing) Text(stringResource(when {
                bubble.status >= ChatMessage.READ && readShown -> R.string.chat_read
                bubble.status >= ChatMessage.DELIVERED -> R.string.chat_delivered
                bubble.status == ChatMessage.SENT -> R.string.chat_sent
                else -> R.string.chat_queued
            }))
            if (bubble.resent) Text(stringResource(R.string.chat_resent))
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
}

/** A destructive choice, confirmed in a dialog whose confirm action names what happens. */
@Composable
internal fun ConfirmDialog(title: Int, text: Int, confirm: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

internal fun timerLabel(timer: Int) = when (timer) {
    1 -> R.string.chat_timer_day
    2 -> R.string.chat_timer_week
    3 -> R.string.chat_timer_month
    else -> R.string.chat_timer_off
}
