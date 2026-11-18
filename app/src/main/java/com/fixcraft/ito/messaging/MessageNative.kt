package com.fixcraft.ito.messaging

import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.SnapshotPersistence

/** A core-selected destination. It conveys no permission to choose another route. */
class MailboxTarget(val fingerprint: ByteArray, val service: String, val address: ByteArray) {
    fun on(fingerprint: ByteArray, service: String) =
        this.fingerprint.contentEquals(fingerprint) && this.service == service
}

/** A sealed item owned by its caller. Pairing items have no message ID. */
class SealedItem(val target: MailboxTarget, val bytes: ByteArray, val id: ByteArray?) : AutoCloseable {
    override fun close() { bytes.fill(0); id?.fill(0) }
}

class PairingCode(val id: ByteArray, val bytes: ByteArray) : AutoCloseable {
    override fun close() { id.fill(0); bytes.fill(0) }
}

class PairingScan(val id: ByteArray, val output: SealedItem) : AutoCloseable {
    override fun close() { id.fill(0); output.close() }
}

class PairingState(val id: ByteArray, val role: Int, val kind: Int, val state: Int,
    val deadlineUnix: Long, val matched: Boolean, val peer: ByteArray,
    val shortCode: ByteArray, name: ByteArray) {
    val name = name.toString(Charsets.UTF_8)
}

class PairingEvent(val kind: Int, val role: Int, val pairingKind: Int,
    val id: ByteArray, val peer: ByteArray, val trust: Int, val shortCode: ByteArray,
    name: ByteArray, val output: SealedItem?) : AutoCloseable {
    val name = name.toString(Charsets.UTF_8)
    override fun close() { output?.close() }
}

/** The secret is only for opening/closing the inbox; clear it after use. */
class Inbox(val purpose: Int, val target: MailboxTarget, val secret: ByteArray,
    val pairingId: ByteArray, val peer: ByteArray) : AutoCloseable {
    override fun close() { secret.fill(0) }
}

class PairedContact(val fingerprint: ByteArray, val send: MailboxTarget,
    val receive: MailboxTarget, val pairedUnix: Long, val verifiedBy: Int, val role: Int)

/** Borrowed for one sink call. The adapter clears all arrays after that call. */
class MessageValue(val kind: Int, val id: ByteArray, val sentMs: Long, val flags: Int,
    val text: ByteArray, val receipt: Int, val ids: ByteArray, val timer: Int) : AutoCloseable {
    override fun close() { id.fill(0); text.fill(0); ids.fill(0) }
}

fun interface MessageSink {
    /** Commit history before returning COMMITTED; never call back into this session. */
    fun deliver(message: MessageValue): Int
}

class OutboxEntry(val id: ByteArray, val kind: Int, val sentMs: Long,
    val sealedUnix: Long, val checkedUnix: Long)

/**
 * The same libitocall as CallNative. Kotlin owns every returned handle and
 * byte array. Sessions borrow the book, and must close before it can reopen.
 * All mutations and their storage callbacks run on one I/O owner, never Main.
 */
object MessageNative {
    init { CallNative.componentVersion() }

    const val WRONG_STATE = -3
    const val MALFORMED = -5
    const val UNSUPPORTED = -6
    const val UNTRUSTED = -7
    const val AUTHENTICATION_FAILED = -8
    const val EXPIRED = -9
    const val CAPACITY = -12
    const val ALREADY_SPENT = -11
    const val MAX_SESSION_BYTES = 717492

    const val IN_PERSON = 1
    const val LINK = 2
    // PairedContact.verifiedBy, as ITO_VERIFIED_* in ito/message.h.
    const val VERIFIED_IN_PERSON = 1
    const val VERIFIED_SAFETY_CODE = 2
    const val SCANNED = 1
    const val FROM_LINK = 2
    const val OWNER = 1
    const val SCANNER = 2
    // PairingState.state, as ITO_PAIRING_STATE_* in ito/message.h. OPEN: issued, nobody answered yet.
    const val PAIRING_OPEN = 1
    const val REPLIED = 1
    const val PAIRED = 2
    const val CONFIRMED = 3
    const val MATCHED = 4
    const val COMPLETED = 5
    const val REJECTED = 6
    const val CONTACT_INBOX = 4

    const val TEXT = 1
    const val RECEIPT = 2
    const val CHECK = 3
    const val MISSING = 4
    const val RING = 5
    const val RING_END = 6
    const val PROFILE = 8
    const val TIMER = 9
    const val DELIVERED = 1
    const val READ = 2

    external fun upgradeIdentity(store: Long)
    external fun fingerprint(store: Long): ByteArray
    external fun createCode(store: Long, kind: Int, fingerprint: ByteArray, service: String,
        name: ByteArray, nowUnix: Long): PairingCode
    external fun code(store: Long, id: ByteArray): ByteArray
    external fun scan(store: Long, code: ByteArray, source: Int, fingerprint: ByteArray,
        service: String, name: ByteArray, nowUnix: Long): PairingScan
    external fun pairings(store: Long): Array<PairingState>
    external fun receivePairing(store: Long, address: ByteArray, item: ByteArray, nowUnix: Long): PairingEvent
    external fun decide(store: Long, id: ByteArray, matches: Boolean, nowUnix: Long): PairingEvent
    external fun cancel(store: Long, id: ByteArray)
    external fun confirm(store: Long, peer: ByteArray): SealedItem
    external fun inboxes(store: Long): Array<Inbox>
    /** Null for identity-1 contacts, which must pair again. */
    external fun contact(store: Long, peer: ByteArray): PairedContact?
    external fun safetyCode(store: Long, peer: ByteArray): String
    external fun verify(store: Long, peer: ByteArray, compared: String, nowUnix: Long)

    external fun openSession(store: Long, peer: ByteArray, snapshot: ByteArray?,
        persistence: SnapshotPersistence, nowMs: Long): Long
    external fun closeSession(session: Long)
    external fun send(session: Long, kind: Int, text: ByteArray, receipt: Int,
        ids: ByteArray, timer: Int, nowMs: Long): SealedItem
    /** Exact core status; forged/replayed items and storage refusals need different mailbox actions. */
    external fun receive(session: Long, item: ByteArray, nowMs: Long, sink: MessageSink): Int
    /** Zero for a non-ring, without consuming it. Other refusals retain their core status. */
    external fun receiveRing(session: Long, item: ByteArray, fingerprint: ByteArray,
        service: String, nowMs: Long): Long
    external fun resend(session: Long, id: ByteArray, nowMs: Long): SealedItem
    external fun check(session: Long, holdSeconds: Long, nowMs: Long): SealedItem?
    external fun outbox(session: Long): Array<OutboxEntry>
    external fun liveSessions(): Long

    /** The connection run lends this stream; these methods never close it. */
    external fun read(stream: Long, capacity: Int, timeoutMs: Int): ByteArray?
    external fun write(stream: Long, frame: ByteArray, timeoutMs: Int)
}
