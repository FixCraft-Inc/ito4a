package com.fixcraft.ito.messaging

import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallBook
import com.fixcraft.ito.call.CallFailure
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.content.ChatRepository
import com.fixcraft.ito.content.ChatSettings
import com.fixcraft.ito.content.ContentException
import com.fixcraft.ito.content.ContentFailure
import com.fixcraft.ito.content.LocalContentRepository
import com.fixcraft.ito.content.RetiredInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class MessengerFailure { None, Offline, Storage, PairAgain, InvalidCode, OtherRelay, Unverified, Capacity, Transport, Native }
class MessengerRefusal(val reason: MessengerFailure) : Exception(reason.name)
/**
 * [failure] answers the person's last operation and is shown once. [stopped]
 * is not an answer to anyone: the mailbox worker gave up on this connection
 * for a reason a retry cannot fix, so chats stay offline until it reconnects.
 */
class MessengerState(val revision: Long = 0, val online: Boolean = false,
    val failure: MessengerFailure = MessengerFailure.None, val pairings: List<PairingState> = emptyList(),
    val code: ByteArray? = null, val shortCode: String = "", val link: Boolean = false,
    val stopped: Boolean = false)

/**
 * Process owner of pairing and message sessions. Public operations run on I/O
 * threads under one fair lock. A connection lends one authenticated stream;
 * neither a contact target nor retry can select another service or network.
 */
class Messenger(private val book: CallBook, val history: ChatRepository,
    private val legacy: LocalContentRepository, private val requireAccess: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hasAccess: () -> Boolean = { true },
    private val onRing: (Long, ByteArray) -> Unit = { handle, _ -> CallNative.invitationDestroy(handle) },
    private val onRingEnd: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    private val onMessage: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    private val onSealedActivity: () -> Unit = {}) {
    // Idle polling must yield to commands already waiting for this owner.
    private val access = ReentrantLock(true)
    private val mutableState = MutableStateFlow(MessengerState())
    val state = mutableState.asStateFlow()
    private val conversations = mutableMapOf<String, MessageConversation>()
    private val opened = mutableMapOf<String, Inbox>()
    private var client: MailboxClient? = null
    private var fingerprint = byteArrayOf()
    private var service = ""
    private var migrated = false
    private var reopenInboxes = false
    private var flushAt = 0L
    // Inbox reconciliation reads the store and a sealed file, so the idle loop
    // repeats it only after something may have changed, or as a slow safety net.
    @Volatile private var reconcileDue = true
    private var reconcileAt = 0L
    // The pairing whose code is on screen, so a repeated tap shows it again
    // instead of creating another pairing.
    private var displayed: ByteArray? = null
    @Volatile private var keepAliveRequested = false
    // Releases the keep-alive's hold on the CPU once its PING is answered.
    private val keepAliveDone = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    fun initialize() = operation {
        book.open()
        val ready = book.state.value as? BookState.Ready ?: throw MessengerRefusal(MessengerFailure.Storage)
        if (ready.identity != null) book.withStore { store ->
            try { MessageNative.fingerprint(store).fill(0) }
            catch (failure: CallFailure) {
                if (failure.code != MessageNative.WRONG_STATE) throw failure
                MessageNative.upgradeIdentity(store)
            }
        }
        if (!migrated) { history.migrate(legacy); migrated = true }
        reopenInboxes = true; publish()
    }

    /**
     * Shows a one-time pairing code. While a code of the same kind is on screen
     * and nobody has answered it, this keeps that code: a second pairing would
     * only leave another waiting row. Hiding the code first makes a new one,
     * for example a second link for someone else.
     */
    fun createCode(link: Boolean): Unit = operation {
        val connection = client ?: throw MessengerRefusal(MessengerFailure.Offline)
        book.withStore { store ->
            val shown = mutableState.value
            val unanswered = displayed?.let { id -> MessageNative.pairings(store).any {
                it.id.contentEquals(id) && it.role == MessageNative.OWNER && it.state == MessageNative.PAIRING_OPEN
            } } == true
            if (shown.code != null && shown.link == link && unanswered) return@withStore
            val name = history.settings().name.toByteArray(Charsets.UTF_8)
            try { MessageNative.createCode(store, if (link) MessageNative.LINK else MessageNative.IN_PERSON,
                fingerprint, service, name, clock() / 1000).use { code ->
                try { reconcile(connection, store) }
                catch (error: Exception) { MessageNative.cancel(store, code.id); throw error }
                clearCode()
                displayed = code.id.copyOf()
                mutableState.value = MessengerState(mutableState.value.revision + 1, true,
                    pairings = MessageNative.pairings(store).toList(), code = code.bytes.copyOf(), link = link)
            } } finally { name.fill(0) }
        }
    }
    fun rejectScan(legacy: Boolean = false): Unit = operation {
        throw MessengerRefusal(if (legacy) MessengerFailure.PairAgain else MessengerFailure.InvalidCode)
    }

    /** A camera scan and pasted/shared text retain their different trust meaning. */
    fun scan(code: ByteArray, fromCamera: Boolean): Unit = try { operation {
        val connection = client ?: throw MessengerRefusal(MessengerFailure.Offline)
        book.withStore { store ->
            val name = history.settings().name.toByteArray(Charsets.UTF_8)
            try { MessageNative.scan(store, code, if (fromCamera) MessageNative.SCANNED else MessageNative.FROM_LINK,
                fingerprint, service, name, clock() / 1000).use { scan ->
                try { reconcile(connection, store); deposit(connection, scan.output) }
                catch (error: Exception) { MessageNative.cancel(store, scan.id); throw error }
            } } finally { name.fill(0) }
        }
        publish()
    } } finally { code.fill(0) }

    fun decide(id: ByteArray, matches: Boolean): Unit = operation {
        book.withStore { store -> MessageNative.decide(store, id, matches, clock() / 1000).use { event ->
            if (event.kind == MessageNative.COMPLETED) history.change(event.peer) { it.removed = false }
        } }
        reopenInboxes = true; publish()
    }
    fun cancelPairing(id: ByteArray): Unit = operation {
        book.withStore { MessageNative.cancel(it, id) }; clearCode(); publish(clearCode = true)
    }
    fun hideCode() = access.withLock { clearCode(); publish(clearCode = true) }
    fun dismissFailure() = access.withLock { publish(failure = MessengerFailure.None) }

    fun paired(peer: ByteArray): PairedContact? = operation(changes = false) { book.withStore { MessageNative.contact(it, peer) } }
    fun safety(peer: ByteArray): String = operation(changes = false) { book.withStore { MessageNative.safetyCode(it, peer) } }
    fun ownFingerprint(): ByteArray = operation(changes = false) { book.withStore(MessageNative::fingerprint) }
    fun verify(peer: ByteArray, compared: String): Unit = operation {
        book.withStore { MessageNative.verify(it, peer, compared, clock() / 1000) }; reopenInboxes = true; publish()
    }
    fun settings(value: ChatSettings): Unit = operation { history.settings(value); publish() }

    fun send(peer: ByteArray, text: ByteArray, operation: ByteArray = ByteArray(16).also(java.security.SecureRandom()::nextBytes)): Unit = try { operation {
        book.withStore { store -> conversation(store, peer).send(MessageNative.TEXT, text, operation = operation) }; flushAt = 0; publish()
    } } finally { text.fill(0) }
    fun timer(peer: ByteArray, timer: Int): Unit = operation {
        book.withStore { store -> conversation(store, peer).send(MessageNative.TIMER, timer = timer) }; flushAt = 0; publish()
    }
    fun markRead(peer: ByteArray): Unit = operation {
        book.withStore { store -> conversation(store, peer).markRead(history.settings().readReceipts) }; flushAt = 0; publish()
    }
    fun deleteHistory(peer: ByteArray): Unit = operation { history.deleteHistory(peer); publish() }
    fun deleteAllHistory(): Unit = operation { history.deleteAllHistory(); publish() }
    fun deleteMessage(peer: ByteArray, id: ByteArray, outgoing: Boolean): Unit = operation {
        history.deleteMessage(peer, id, outgoing); publish()
    }
    /** Retain the close secret durably before the core stops enumerating it. */
    fun block(peer: ByteArray): Unit = operation {
        book.withStore { store ->
            val inboxes = MessageNative.inboxes(store)
            try { inboxes.filter { it.purpose == MessageNative.CONTACT_INBOX && it.peer.contentEquals(peer) }.forEach { inbox ->
                RetiredInbox(peer.copyOf(), inbox.target.fingerprint.copyOf(), inbox.target.service, inbox.secret.copyOf()).use(history::retire)
            } } finally { inboxes.forEach(Inbox::close) }
            book.block(peer)
            conversations.remove(hex(peer))?.close()
        }
        publish()
    }
    fun removeContact(peer: ByteArray): Unit = operation {
        block(peer)
        history.eraseSession(peer)
        history.deleteHistory(peer)
        history.change(peer) { state ->
            state.pending?.close(); state.pending = null
            state.deposits.forEach { it.close() }; state.deposits.clear()
            state.actions.forEach { it.close() }; state.actions.clear()
            state.removed = true
        }
        history.setPhoto(peer, null)
        publish()
    }

    fun prepareCall(peer: ByteArray): Unit = operation {
        check(client != null)
        book.withStore { store ->
            val paired = MessageNative.contact(store, peer) ?: throw MessengerRefusal(MessengerFailure.PairAgain)
            if (!paired.send.on(fingerprint, service)) throw MessengerRefusal(MessengerFailure.OtherRelay)
            if (CallNative.contacts(store).none { it.key.contentEquals(peer) && it.trust == CallNative.TRUST_VERIFIED })
                throw MessengerRefusal(MessengerFailure.Unverified)
        }
    }

    fun sendRing(peer: ByteArray, invitation: ByteArray): Unit = operation {
        prepareCall(peer)
        book.withStore { store -> conversation(store, peer).signal(MessageNative.RING, invitation).use { item ->
            val connection = client ?: throw MessengerRefusal(MessengerFailure.Offline)
            check(item.target.on(fingerprint, service))
            connection.deposit(item.target, item.bytes, ring = true)
        } }
    }

    fun endRing(peer: ByteArray, id: ByteArray, reason: Int, initiator: Boolean = true): Unit = operation {
        require(id.size == 32 && reason in 1..4)
        val payload = id + reason.toByte()
        try {
            book.withStore { store -> conversation(store, peer).signal(MessageNative.RING_END, payload).use { item ->
                history.change(peer) { state ->
                    state.deposits.add(com.fixcraft.ito.content.ChatDeposit(requireNotNull(item.id).copyOf(), item.bytes.copyOf()))
                    ChatRepository.recordRing(state, peer, payload, initiator, clock())
                }
            } }
            flushAt = 0; publish()
        } finally { payload.fill(0) }
    }

    fun callFailed(): Unit = access.withLock {
        val old = mutableState.value
        mutableState.value = MessengerState(old.revision + 1, old.online, MessengerFailure.Native,
            old.pairings, old.code, old.shortCode, old.link, old.stopped)
    }

    /**
     * Asks the worker to PING on its next turn, whatever the interval says.
     * The background keep-alive calls this while it holds the device awake,
     * and [answered] runs once the PONG arrives. A connection that cannot
     * answer never calls it, so the hold lasts until its own timeout.
     */
    fun keepAlive(answered: () -> Unit) {
        // Request first: a PONG racing this call can then only hold the CPU
        // until the timeout, never release it before the PING is sent.
        keepAliveRequested = true
        keepAliveDone.getAndSet(answered)?.invoke()
    }

    private fun keepAliveAnswered(connection: MailboxClient) {
        if (!keepAliveRequested && !connection.pingPending) keepAliveDone.getAndSet(null)?.invoke()
    }

    /** Called by the connection's owned I/O worker; returns before that endpoint is destroyed. */
    fun run(stream: Long, authenticated: ByteArray, mailboxService: String, active: () -> Boolean): Boolean {
        var retry = false
        var gaveUp = false
        var ambiguous = false
        val sealed = java.util.ArrayDeque<MailboxItem>()
        var sealedBytes = 0
        var accessSuspended = false
        fun retain(item: MailboxItem) {
            if (sealed.size >= 512 || sealedBytes > 8 * 1024 * 1024 - item.sealed.size) {
                item.close(); throw MailboxFailure(5)
            }
            sealed.add(item); sealedBytes += item.sealed.size
            onSealedActivity()
        }
        MailboxClient({ size, timeout -> MessageNative.read(stream, size, timeout) },
            { MessageNative.write(stream, it, 5000) }, active, android.os.SystemClock::elapsedRealtimeNanos).use { connection ->
            try {
                access.withLock {
                    requireAccess(); check(client == null)
                    client = connection; fingerprint = authenticated.copyOf(); service = mailboxService
                    flushAt = 0; reconcileDue = true
                    publish(stopped = false)
                }
                while (active()) access.withLock {
                    if (keepAliveRequested) { keepAliveRequested = false; connection.pingSoon() }
                    if (!hasAccess()) {
                        if (!accessSuspended) {
                            closeConversations(); clearCode()
                            mutableState.value = MessengerState(mutableState.value.revision + 1)
                            accessSuspended = true
                        }
                        connection.poll()?.let(::retain)
                        keepAliveAnswered(connection)
                        return@withLock
                    }
                    requireAccess()
                    if (accessSuspended) reconcileDue = true
                    accessSuspended = false
                    if (reconcileDue || System.nanoTime() >= reconcileAt) {
                        reconcileDue = false
                        book.withStore { store -> reconcile(connection, store) }
                        reconcileAt = System.nanoTime() + RECONCILE_INTERVAL_NANOS
                    }
                    // Idle network waits borrow no identity store. Call admission
                    // must be able to reach its native deadline on another lane.
                    val buffered = sealed.isNotEmpty()
                    val next = if (!buffered) connection.poll() else sealed.removeFirst().also { sealedBytes -= it.sealed.size }
                    keepAliveAnswered(connection)
                    if (!hasAccess()) {
                        next?.let { item ->
                            if (buffered) { sealed.addFirst(item); sealedBytes += item.sealed.size }
                            else retain(item)
                        }
                        return@withLock
                    }
                    try { book.withStore { store ->
                        // Drain one pushed item before sending more. Request replies
                        // may interleave bounded pushed items inside MailboxClient.
                        var changed = false
                        next?.let { received -> receive(connection, store, received); changed = true; flushAt = 0; reconcileDue = true }
                        if (System.nanoTime() >= flushAt) {
                            CallNative.contacts(store).filter { it.trust == CallNative.TRUST_VERIFIED }.forEach { contact ->
                                val paired = MessageNative.contact(store, contact.key) ?: return@forEach
                                if (paired.send.on(fingerprint, service)) changed = conversation(store, contact.key).flush(connection, fingerprint, service) || changed
                            }
                            flushAt = System.nanoTime() + 5_000_000_000L
                        }
                        if (changed) { reconcileDue = true; publish() }
                    } } finally { next?.close() }
                }
            } catch (failure: Exception) {
                retry = (failure is MailboxFailure && failure.status in setOf(2, 3, 5)) || (failure is CallFailure && failure.code in setOf(-19, -25))
                // The connection retries a dropped stream by itself, so a drop is
                // nobody's failed operation. Only a fault a retry cannot fix stays visible.
                gaveUp = !retry && active()
                ambiguous = failure is CallFailure && failure.code == CallNative.STORAGE_AMBIGUOUS
            } finally {
                access.withLock {
                    if (ambiguous) book.publicationAmbiguous()
                    sealed.forEach(MailboxItem::close); sealed.clear()
                    client = null; fingerprint.fill(0); fingerprint = byteArrayOf(); service = ""
                    opened.values.forEach(Inbox::close); opened.clear(); closeConversations()
                    clearCode()
                    mutableState.value = MessengerState(mutableState.value.revision + 1, stopped = gaveUp)
                }
            }
        }
        return retry
    }

    /** A new connection run is about to open the mailbox, so an earlier run's give-up no longer applies. */
    fun transportStarting(): Unit = access.withLock {
        val old = mutableState.value
        if (old.stopped) mutableState.value = MessengerState(old.revision + 1, old.online, old.failure, old.pairings, old.code,
            old.shortCode, old.link, stopped = false)
    }

    /** The connection could not open the mailbox stream and will not retry it on this run. */
    fun transportFailed(): Unit = access.withLock {
        val old = mutableState.value
        mutableState.value = MessengerState(old.revision + 1, old.online, old.failure, old.pairings, old.code,
            old.shortCode, old.link, stopped = true)
    }

    /** Background/lock release for sessions opened while offline. No new key access. */
    fun suspendAccess() = access.withLock {
        closeConversations(); clearCode()
        mutableState.value = MessengerState(mutableState.value.revision + 1)
    }

    private fun reconcile(connection: MailboxClient, store: Long) {
        val blocked = CallNative.contacts(store).filter { it.trust == CallNative.TRUST_BLOCKED }
        val retired = history.retired()
        try { retired.filter { old -> old.fingerprint.contentEquals(fingerprint) && old.service == service &&
            blocked.any { it.key.contentEquals(old.peer) } }.forEach { old ->
            connection.closeInbox(old.secret); history.retiredClosed(old.secret)
            opened.entries.filter { it.value.secret.contentEquals(old.secret) }.map { it.key }.forEach { key ->
                opened.remove(key)?.close()
            }
        } } finally { retired.forEach(RetiredInbox::close) }
        val inboxes = MessageNative.inboxes(store)
        try {
            val wanted = inboxes.filter { it.target.on(fingerprint, service) }.associateBy { hex(it.target.address) }
            val removed = opened.keys - wanted.keys
            removed.forEach { key -> opened[key]?.let { connection.closeInbox(it.secret); it.close() }; opened.remove(key) }
            val added = wanted.filterKeys { it !in opened }
            val renew = if (reopenInboxes) wanted else added
            if (renew.isNotEmpty()) connection.open(renew.values.map { it.secret })
            reopenInboxes = false
            wanted.forEach { (key, inbox) ->
                if (key !in opened || opened[key]?.purpose != inbox.purpose || !opened[key]!!.peer.contentEquals(inbox.peer)) {
                    opened.remove(key)?.close()
                    opened[key] = Inbox(inbox.purpose, inbox.target, inbox.secret.copyOf(), inbox.pairingId.copyOf(), inbox.peer.copyOf())
                }
            }
        } finally { inboxes.forEach(Inbox::close) }
    }

    private fun receive(connection: MailboxClient, store: Long, received: MailboxItem) {
        val inbox = opened[hex(received.address)] ?: throw MailboxFailure(6)
        if (inbox.purpose == MessageNative.CONTACT_INBOX) {
            val conversation = conversation(store, inbox.peer)
            val result = try {
                val invitation = conversation.receiveRing(received.sealed, fingerprint, com.fixcraft.ito.call.CallController.SERVICE)
                if (invitation != 0L) {
                    // Transfer before TAKEN: even a failed acknowledgment cannot leak the handle.
                    if (received.kind == 2) onRing(invitation, inbox.peer) else CallNative.invitationDestroy(invitation)
                    connection.taken(received.id)
                    return
                }
                conversation.receive(received.sealed)
            } catch (failure: CallFailure) { failure.code }
            if (result in setOf(0, MessageNative.AUTHENTICATION_FAILED, MessageNative.ALREADY_SPENT,
                    MessageNative.MALFORMED, MessageNative.UNSUPPORTED, MessageNative.CAPACITY, MessageNative.EXPIRED)) {
                connection.taken(received.id)
            } else if (result != MessageNative.UNTRUSTED) throw CallFailure(result, "message-receive")
        } else {
            try {
                MessageNative.receivePairing(store, received.address, received.sealed, clock() / 1000).use { event ->
                    if (event.kind in setOf(MessageNative.PAIRED, MessageNative.COMPLETED)) history.change(event.peer) { it.removed = false }
                    reconcile(connection, store)
                    event.output?.let {
                        try { deposit(connection, it) }
                        catch (failure: Exception) {
                            if (event.kind == MessageNative.REPLIED) MessageNative.cancel(store, event.id)
                            throw failure
                        }
                    }
                    // An answered one-time code is spent, so it leaves the screen for the digits to compare.
                    if (event.shortCode.isNotEmpty()) {
                        clearCode()
                        mutableState.value = MessengerState(mutableState.value.revision + 1,
                            true, pairings = MessageNative.pairings(store).toList(), shortCode = event.shortCode.toString(Charsets.US_ASCII))
                    }
                }
                connection.taken(received.id)
            } catch (failure: CallFailure) {
                if (failure.code !in setOf(MessageNative.AUTHENTICATION_FAILED, MessageNative.ALREADY_SPENT,
                        MessageNative.MALFORMED, MessageNative.UNSUPPORTED, MessageNative.EXPIRED, MessageNative.UNTRUSTED)) throw failure
                connection.taken(received.id)
            }
        }
    }

    private fun conversation(store: Long, peer: ByteArray): MessageConversation = conversations.getOrPut(hex(peer)) {
        val contact = MessageNative.contact(store, peer) ?: throw MessengerRefusal(MessengerFailure.PairAgain)
        if (contact.role == MessageNative.SCANNER) {
            val confirm = try { MessageNative.confirm(store, peer) }
            catch (failure: CallFailure) { if (failure.code != MessageNative.WRONG_STATE) throw failure; null }
            confirm?.use { deposit(client ?: throw MessengerRefusal(MessengerFailure.Offline), it) }
        }
        MessageConversation(store, peer.copyOf(), history, clock, onMessage, onRingEnd)
    }
    private fun deposit(connection: MailboxClient, item: SealedItem) {
        if (!item.target.on(fingerprint, service)) throw MessengerRefusal(MessengerFailure.OtherRelay)
        connection.deposit(item.target, item.bytes)
    }
    private fun closeConversations() { conversations.values.forEach(MessageConversation::close); conversations.clear() }
    /** Wipes the code bytes on screen. The caller publishes the state without them. */
    private fun clearCode() { mutableState.value.code?.fill(0); displayed?.fill(0); displayed = null }
    private fun publish(clearCode: Boolean = false, failure: MessengerFailure = mutableState.value.failure,
                        stopped: Boolean = mutableState.value.stopped) {
        val current = mutableState.value
        val pairings = if (book.state.value is BookState.Ready) book.withStore { MessageNative.pairings(it).toList() } else emptyList()
        // Digits to compare mean nothing once no pairing is pending, so they leave with the last one.
        mutableState.value = MessengerState(current.revision + 1, client != null, failure, pairings,
            if (clearCode) null else current.code, if (pairings.isEmpty()) "" else current.shortCode, current.link,
            stopped && client == null)
        book.open()
    }
    private fun failed(failure: Exception) {
        closeConversations()
        val reason = when (failure) {
            is MessengerRefusal -> failure.reason
            is ContentException -> MessengerFailure.Storage
            is com.fixcraft.ito.security.AppLockedException -> MessengerFailure.Storage
            is MailboxFailure -> MessengerFailure.Transport
            is CallFailure -> when (failure.code) {
                -14, MessageNative.WRONG_STATE -> MessengerFailure.PairAgain
                MessageNative.UNTRUSTED -> MessengerFailure.Unverified
                MessageNative.CAPACITY -> MessengerFailure.Capacity
                -15, -16 -> MessengerFailure.Storage
                -1, MessageNative.MALFORMED, MessageNative.EXPIRED, MessageNative.ALREADY_SPENT -> MessengerFailure.InvalidCode
                else -> MessengerFailure.Native
            }
            else -> MessengerFailure.Native
        }
        if (failure is CallFailure && failure.code == CallNative.STORAGE_AMBIGUOUS) book.publicationAmbiguous()
        val old = mutableState.value
        mutableState.value = MessengerState(old.revision + 1, client != null, reason, old.pairings, old.code, old.shortCode, old.link,
            old.stopped && client == null)
    }
    /** A person's operation. One that [changes] pairings or contacts makes the worker reconcile inboxes after it. */
    private fun <T> operation(changes: Boolean = true, action: () -> T): T = access.withLock {
        try { requireAccess(); action() }
        catch (failure: Exception) { failed(failure); throw failure }
        finally { if (changes) reconcileDue = true }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        /** A safety net for inbox changes no operation announced, such as an expired pairing. */
        const val RECONCILE_INTERVAL_NANOS = 60_000_000_000L
    }
}
