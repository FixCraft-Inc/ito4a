package com.fixcraft.ito.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.content.ChatMessage
import com.fixcraft.ito.content.ChatSummary
import com.fixcraft.ito.content.ContentException
import com.fixcraft.ito.messaging.MessageNative
import com.fixcraft.ito.security.AppLockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the Chats tab is. Saved state holds only these names and public contact codes. */
internal sealed interface ChatsRoute {
    /** Where Back goes, or null on the chat list. */
    val parent: ChatsRoute?

    data object Home : ChatsRoute { override val parent: ChatsRoute? = null }
    data object AddContact : ChatsRoute { override val parent: ChatsRoute = Home }
    data class Scan(override val parent: ChatsRoute = Home) : ChatsRoute
    data class Chat(val code: String) : ChatsRoute { override val parent: ChatsRoute = Home }
    data class Profile(val code: String) : ChatsRoute { override val parent: ChatsRoute = Chat(code) }

    companion object {
        val Saver: Saver<ChatsRoute, String> = Saver(save = { encode(it) }, restore = { decode(it) })

        private fun encode(route: ChatsRoute): String = when (route) {
            Home -> "home"
            AddContact -> "add"
            is Scan -> "scan|" + encode(route.parent)
            is Chat -> "chat|" + route.code
            is Profile -> "profile|" + route.code
        }

        private fun decode(text: String): ChatsRoute {
            val kind = text.substringBefore('|')
            val rest = text.substringAfter('|', "")
            return when (kind) {
                "add" -> AddContact
                "scan" -> Scan(decode(rest).takeIf { it !is Scan } ?: Home)
                "chat" -> if (rest.isEmpty()) Home else Chat(rest)
                "profile" -> if (rest.isEmpty()) Home else Profile(rest)
                else -> Home
            }
        }
    }
}

/** The Chats tab's first screen: identity setup, then the list of chats. */
@Composable
internal fun ChatsPage(model: CallsModel, onRoute: (ChatsRoute) -> Unit, onService: () -> Unit) {
    val book by model.book.collectAsStateWithLifecycle()
    when (val ready = book) {
        BookState.Loading -> IdentityLoading()
        is BookState.Failed -> IdentityUnreadable(ready.reason, model::open)
        is BookState.Ready -> if (ready.identity == null) CreateIdentity(model)
            else ChatList(model, ready.contacts, onRoute, onService)
    }
}

/** A full-screen page of the Chats tab. Unknown contact codes return to the list. */
@Composable
internal fun ChatsDetail(model: CallsModel, route: ChatsRoute, onRoute: (ChatsRoute) -> Unit, advanced: Boolean,
                         chrome: ScreenChrome) {
    val book by model.book.collectAsStateWithLifecycle()
    val ready = book as? BookState.Ready
    if (ready?.identity == null) {
        // Opening the identity can take a moment after unlock. A missing one sends the person to set it up.
        if (book !is BookState.Loading) LaunchedEffect(Unit) { onRoute(ChatsRoute.Home) }
        return
    }
    fun contact(code: String) = ready.contacts.firstOrNull { it.code == code }
    when (route) {
        ChatsRoute.Home -> Unit
        ChatsRoute.AddContact -> AddContactScreen(model, chrome, onRoute)
        is ChatsRoute.Scan -> ScanScreen(model, ready.contacts, chrome, onBack = { onRoute(route.parent) })
        is ChatsRoute.Chat -> contact(route.code)?.let { ChatScreen(model, it, chrome, onRoute) }
            ?: LaunchedEffect(route) { onRoute(ChatsRoute.Home) }
        is ChatsRoute.Profile -> contact(route.code)?.let { ContactProfileScreen(model, it, advanced, chrome, onRoute) }
            ?: LaunchedEffect(route) { onRoute(ChatsRoute.Home) }
    }
}

@Composable
private fun ChatList(model: CallsModel, contacts: List<Contact>, onRoute: (ChatsRoute) -> Unit, onService: () -> Unit) {
    val messenger by model.messenger.state.collectAsStateWithLifecycle()
    val summaries by produceState<Map<String, ChatSummary?>>(emptyMap(), messenger.revision, contacts) {
        value = withContext(Dispatchers.IO) {
            contacts.associate { contact ->
                contact.code to try { model.history.summary(contact.key) } catch (_: ContentException) { null }
                    catch (_: AppLockedException) { null }
            }
        }
    }
    // Turning read receipts off also hides others' read marks, so an unreadable setting shows none.
    val readShown by produceState(false, messenger.revision) {
        value = withContext(Dispatchers.IO) {
            try { model.history.settings().readReceipts } catch (_: ContentException) { false } catch (_: AppLockedException) { false }
        }
    }
    val shown = contacts.filter { summaries[it.code]?.removed != true }.sortedByDescending { summaries[it.code]?.time ?: 0 }
    Box(Modifier.fillMaxSize()) {
        if (shown.isEmpty() && messenger.pairings.isEmpty()) EmptyChats(messenger.online, onService)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item { Box(Modifier.padding(horizontal = 16.dp)) { PairingProgress(model) } }
            items(shown, key = { it.code }) { contact ->
                ChatRow(model, contact, summaries[contact.code], summaries.containsKey(contact.code), readShown) {
                    onRoute(ChatsRoute.Chat(contact.code))
                }
            }
        }
        AddContactButton(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { onRoute(ChatsRoute.AddContact) }
    }
}

/**
 * The chat list's one primary action. The content-slot form keeps the visible
 * text as the button's spoken label. The icon-and-text form hides the text from
 * screen readers.
 */
@Composable
internal fun AddContactButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ExtendedFloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_person_add), null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.add_contact))
    }
}

@Composable
private fun EmptyChats(online: Boolean, onService: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_ito), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 360.dp))
        if (!online) Text(stringResource(R.string.chat_mailbox_required), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 360.dp).clickable(role = Role.Button, onClick = onService).padding(8.dp))
    }
}

@Composable
private fun ChatRow(model: CallsModel, contact: Contact, summary: ChatSummary?, loaded: Boolean, readShown: Boolean,
                    onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button, onClick = onOpen)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(model, contact, 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (summary != null && summary.time > 0) Text(listTime(summary.time), style = MaterialTheme.typography.labelMedium,
                    color = if (summary.unread > 0) colors.primary else colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary != null && summary.outgoing && summary.kind == MessageNative.TEXT) {
                    DeliveryMark(summary.status, Modifier.size(16.dp), readShown)
                    Spacer(Modifier.width(4.dp))
                }
                Text(preview(contact, summary, loaded), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (summary != null && summary.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    val unread = stringResource(R.string.chat_unread, summary.unread)
                    Badge(containerColor = colors.primary, contentColor = colors.onPrimary,
                        modifier = Modifier.semantics { contentDescription = unread }) {
                        Text(summary.unread.toString(), Modifier.clearAndSetSemantics { })
                    }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 84.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
}

/** The last line of a chat, or what the contact needs next when there is nothing yet. */
@Composable
private fun preview(contact: Contact, summary: ChatSummary?, loaded: Boolean): String = when {
    !loaded -> ""
    summary == null -> stringResource(R.string.chat_history_unavailable)
    contact.blocked -> stringResource(R.string.trust_blocked)
    summary.kind == ChatSummary.CALL -> stringResource(callLabel(summary.outgoing, summary.callResult))
    summary.kind == MessageNative.TEXT -> summary.text
    summary.kind == MessageNative.TIMER -> stringResource(R.string.chat_auto_delete)
    summary.kind == MessageNative.PROFILE -> stringResource(R.string.chat_name_hint, summary.text)
    contact.verified -> stringResource(R.string.chat_say_hello)
    else -> stringResource(R.string.chat_compare_to_start)
}

/** A message's delivery state as Ito's ticks: a clock, one tick, two ticks, two ticks in colour once read. */
@Composable
internal fun DeliveryMark(status: Int, modifier: Modifier = Modifier, readShown: Boolean = true,
                          tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val (icon, label) = when {
        status >= ChatMessage.READ && readShown -> R.drawable.ic_done_all to R.string.chat_read
        status >= ChatMessage.DELIVERED -> R.drawable.ic_done_all to R.string.chat_delivered
        status == ChatMessage.SENT -> R.drawable.ic_check to R.string.chat_sent
        else -> R.drawable.ic_schedule to R.string.chat_queued
    }
    val color = if (status >= ChatMessage.READ && readShown) MaterialTheme.ito.read else tint
    Icon(painterResource(icon), stringResource(label), modifier, tint = color)
}
