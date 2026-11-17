package com.fixcraft.ito.content

import android.content.Context
import com.fixcraft.ito.security.AppLockController
import java.io.File
import java.security.SecureRandom

/**
 * Process-owned encrypted local messages and communication history. No audio,
 * invitation secret, account metadata or cloud operation enters this owner.
 * Calls and messages are supplied only by the authenticated application channel.
 * Synchronous disk operations belong on an I/O dispatcher.
 */
class LocalContentRepository internal constructor(
    private val directory: File,
    private val useKey: ((ByteArray) -> ByteArray) -> ByteArray,
    private val requireAccess: () -> Unit,
    private val syncDirectory: (File) -> Unit,
    private val clock: () -> Long,
) {
    constructor(context: Context, lock: AppLockController) : this(
        File(context.applicationContext.noBackupFilesDir, "ito-content"),
        { operation -> lock.withContentKey(operation) }, lock::requireAccess,
        ::syncContentDirectory, { System.currentTimeMillis() / 1000 },
    )

    @Synchronized fun recordCall(peer: ByteArray, initiator: Boolean, result: Int, timeUnix: Long, durationMs: Long) {
        if (peer.size != 32 || timeUnix < 0 || durationMs !in 0..ContentCodec.MAX_DURATION_MS)
            throw ContentException(ContentFailure.InvalidInput)
        val record = LocalCall(ByteArray(16).also(SecureRandom()::nextBytes), peer.copyOf(), initiator, timeUnix, durationMs, result)
        try {
            ContentCodec.requireCall(record)
            change { it.calls.add(record.copyRecord()) }
        } finally { record.close() }
    }

    /** Duplicate IDs for this peer/direction are idempotent only when their UTF-8 content is identical. */
    @Synchronized fun recordMessage(id: ByteArray, peer: ByteArray, outgoing: Boolean, utf8: ByteArray, timeUnix: Long) {
        if (id.size != 16 || peer.size != 32 || timeUnix < 0 || !ContentCodec.validText(utf8))
            throw ContentException(ContentFailure.InvalidInput)
        val record = LocalMessage(id.copyOf(), peer.copyOf(), outgoing, timeUnix, utf8.copyOf())
        try {
            ContentCodec.requireMessage(record)
            change { snapshot ->
                val prior = snapshot.messages.firstOrNull { it.id.contentEquals(id) && it.peer.contentEquals(peer) && it.outgoing == outgoing }
                if (prior != null) {
                    if (!prior.utf8.contentEquals(utf8)) throw ContentException(ContentFailure.InvalidInput)
                } else snapshot.messages.add(record.copyRecord())
            }
        } finally { record.close() }
    }

    /** The caller owns the returned records and should close them after display/use. */
    @Synchronized fun messages(): List<LocalMessage> = read { it.messages.map(LocalMessage::copyRecord) }
    @Synchronized fun history(): List<LocalCall> = read { it.calls.map(LocalCall::copyRecord) }

    /** Deletes only current local content for the exact communication key. It preserves contact trust and identity. */
    @Synchronized fun deletePeer(peer: ByteArray) {
        if (peer.size != 32) throw ContentException(ContentFailure.InvalidInput)
        change { snapshot ->
            snapshot.messages.removeAll { if (it.peer.contentEquals(peer)) { it.close(); true } else false }
            snapshot.calls.removeAll { if (it.peer.contentEquals(peer)) { it.close(); true } else false }
        }
    }

    @Synchronized fun deleteMessage(id: ByteArray, peer: ByteArray, outgoing: Boolean) {
        if (id.size != 16 || peer.size != 32) throw ContentException(ContentFailure.InvalidInput)
        change { snapshot ->
            snapshot.messages.removeAll {
                if (it.id.contentEquals(id) && it.peer.contentEquals(peer) && it.outgoing == outgoing) { it.close(); true } else false
            }
        }
    }

    /** Offline authenticated deletion, not flash erasure or remote deletion. */
    @Synchronized fun clear() = change { snapshot -> snapshot.close(); snapshot.messages.clear(); snapshot.calls.clear() }

    private fun change(operation: (ContentSnapshot) -> Unit) {
        requireAccess()
        load().use { snapshot ->
            operation(snapshot)
            ContentCodec.trim(snapshot, clock())
            persist(snapshot)
        }
    }

    private fun <T> read(operation: (ContentSnapshot) -> T): T {
        requireAccess()
        return load().use { snapshot ->
            if (ContentCodec.trim(snapshot, clock())) persist(snapshot)
            operation(snapshot)
        }
    }

    /** Migration reads every retained record before applying a new retention policy. */
    @Synchronized internal fun migrationSnapshot(): ContentSnapshot {
        requireAccess()
        return load()
    }

    private val file = EncryptedContentFile(directory, "snapshot.bin", ".snapshot.tmp",
        byteArrayOf(73, 84, 79, 67, 1), "ito/content-storage/snapshot/1".toByteArray(Charsets.US_ASCII),
        ContentCodec.MAX_SNAPSHOT_BYTES, useKey, requireAccess, syncDirectory)

    private fun load(): ContentSnapshot {
        val plain = file.load() ?: return ContentSnapshot()
        return try { ContentCodec.decode(plain) } finally { plain.fill(0) }
    }

    private fun persist(snapshot: ContentSnapshot) {
        val plain = ContentCodec.encode(snapshot)
        try { file.replace(plain) } finally { plain.fill(0) }
    }
}
