package com.fixcraft.ito.call

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A contact as screens show it. The name has no authority. */
data class Contact(
    val key: ByteArray,
    val name: String,
    val trust: Int,
    val code: String,
    val fingerprint: String,
    val replacedBy: ByteArray?,
    val replaces: ByteArray?,
) {
    val verified: Boolean get() = trust == CallNative.TRUST_VERIFIED
    val blocked: Boolean get() = trust == CallNative.TRUST_BLOCKED
    override fun equals(other: Any?) = other is Contact && key.contentEquals(other.key) &&
        name == other.name && trust == other.trust && replacedBy.contentEquals(other.replacedBy)
    override fun hashCode() = key.contentHashCode()
}

data class OwnIdentity(val key: ByteArray, val code: String, val fingerprint: String) {
    override fun equals(other: Any?) = other is OwnIdentity && key.contentEquals(other.key)
    override fun hashCode() = key.contentHashCode()
}

sealed interface BookState {
    data object Loading : BookState
    /** The store cannot be read. Its ciphertext is preserved and nothing is regenerated. */
    data class Failed(val reason: IdentityFailure) : BookState
    data class Ready(val identity: OwnIdentity?, val contacts: List<Contact>) : BookState
}

/** Typed refusals screens explain. Everything else is a CallFailure. */
enum class BookRefusal { InvalidCode, InvalidName, OwnCode, Exists, NotVerifiedFingerprint, Blocked, Capacity, Storage }

class BookException(val refusal: BookRefusal) : Exception(refusal.name)

/**
 * The process owner of the local communication identity and its contacts:
 * one native call-core store, sealed by [IdentityStore]. Operations are
 * synchronous and bounded and belong on an I/O dispatcher. The persistence
 * callback runs inside the core's store lock, so it never takes this
 * object's monitor.
 */
class CallBook internal constructor(private val storage: IdentityStore, private val clock: () -> Long,
    private val requireAccess: () -> Unit) {
    internal constructor(storage: IdentityStore, clock: () -> Long) : this(storage, clock, {})
    constructor(context: Context, requireAccess: () -> Unit = {}) : this(
        IdentityStore(File(context.applicationContext.noBackupFilesDir, "ito-identity"), requireAccess),
        { System.currentTimeMillis() / 1000 },
        requireAccess,
    )

    private val mutableState = MutableStateFlow<BookState>(BookState.Loading)
    val state: StateFlow<BookState> = mutableState
    private var context = 0L
    private var store = 0L
    // Set when a publication's outcome is unknown. The core refuses every
    // later change until the store is reopened from the durable snapshot.
    private var ambiguous = false

    /**
     * Opens the stored snapshot. A failure leaves the store closed and
     * visible. After an ambiguous publication it reopens the durable state,
     * once no call still borrows the old store.
     */
    @Synchronized fun open() {
        try { requireAccess() } catch (_: Exception) { mutableState.value = BookState.Loading; return }
        if (store != 0L && !ambiguous) { publish(); return }
        if (store != 0L) {
            try { CallNative.storeDestroy(store) } catch (_: CallFailure) { return }
            store = 0L
            CallNative.contextDestroy(context)
            context = 0L
            ambiguous = false
        }
        val snapshot = try { storage.load() } catch (failure: IdentityStorageException) {
            mutableState.value = BookState.Failed(failure.reason)
            return
        }
        try {
            val created = CallNative.contextCreate()
            try {
                store = CallNative.storeOpen(created, snapshot, SnapshotPersistence(storage::persist))
                context = created
            } catch (failure: CallFailure) {
                CallNative.contextDestroy(created)
                // Only a snapshot the core refuses is corrupt. Anything else may
                // pass, and must not suggest that the data is lost.
                mutableState.value = BookState.Failed(when (failure.code) {
                    CORRUPT_STATE, MALFORMED, UNSUPPORTED -> IdentityFailure.Corrupt
                    else -> IdentityFailure.Io
                })
                return
            }
        } finally { snapshot?.fill(0) }
        publish()
    }

    /**
     * Runs block with the native store under this object's monitor, so a
     * reopen cannot free the store before the call or invitation that block
     * creates has taken its native borrow. Refused while a publication is
     * ambiguous.
     */
    @Synchronized fun <T> withStore(block: (store: Long) -> T): T {
        requireAccess()
        check(store != 0L && !ambiguous)
        return block(store)
    }

    /** A call's spend could not confirm its publication, so the store latched. */
    @Synchronized fun publicationAmbiguous() {
        ambiguous = true
        mutableState.value = BookState.Failed(IdentityFailure.Io)
    }

    @Synchronized fun createIdentity() = mutate(null, null) { CallNative.createIdentity(store, clock()) }

    /** Parses a public contact code and adds it Unverified. */
    @Synchronized fun addContact(code: String, name: String): ByteArray {
        requireAccess()
        check(store != 0L)
        val key = parse(code)
        // The core refuses the own key and a bad name with the same status.
        if (CallNative.identity(store)?.contentEquals(key) == true) throw BookException(BookRefusal.OwnCode)
        mutate(BookRefusal.InvalidName, BookRefusal.Exists) { CallNative.addContact(store, key, name.toByteArray(Charsets.UTF_8), clock()) }
        return key
    }

    /** Verified only by the complete fingerprint the user compared. */
    @Synchronized fun verify(key: ByteArray, compared: String) =
        mutate(BookRefusal.NotVerifiedFingerprint, BookRefusal.Blocked) { CallNative.verifyContact(store, key, compared, clock()) }

    @Synchronized fun block(key: ByteArray) = mutate(null, null) { CallNative.blockContact(store, key) }

    @Synchronized fun rename(key: ByteArray, name: String) =
        mutate(BookRefusal.InvalidName, null) { CallNative.renameContact(store, key, name.toByteArray(Charsets.UTF_8)) }

    /** A known contact presented a new key: the old one is blocked, the new one starts unverified. */
    @Synchronized fun replace(oldKey: ByteArray, newCode: String): ByteArray {
        requireAccess()
        check(store != 0L)
        val key = parse(newCode)
        if (CallNative.identity(store)?.contentEquals(key) == true) throw BookException(BookRefusal.OwnCode)
        mutate(BookRefusal.InvalidCode, BookRefusal.Blocked) { CallNative.replaceContact(store, oldKey, key, clock()) }
        return key
    }

    fun contactCode(key: ByteArray): String = CallNative.contactCodeFormat(key)

    @Synchronized fun fingerprint(key: ByteArray): String {
        check(context != 0L)
        return CallNative.fingerprint(context, key)
    }

    private fun parse(code: String): ByteArray = try { CallNative.contactCodeParse(code.trim()) }
    catch (_: CallFailure) { throw BookException(BookRefusal.InvalidCode) }

    /**
     * [invalid] and [wrong] name what INVALID_ARGUMENT and WRONG_STATE mean
     * for this operation. Without a meaning the failure stays a CallFailure.
     */
    private fun mutate(invalid: BookRefusal?, wrong: BookRefusal?, change: () -> Unit) {
        requireAccess()
        check(store != 0L)
        try { change() } catch (failure: CallFailure) {
            // An ambiguous publication cannot show Ready (CALL_SECURITY).
            if (failure.code == CallNative.STORAGE_AMBIGUOUS) ambiguous = true
            throw BookException(when (failure.code) {
                INVALID_ARGUMENT -> invalid ?: throw failure
                WRONG_STATE -> wrong ?: throw failure
                AUTHENTICATION_FAILED -> BookRefusal.NotVerifiedFingerprint
                UNTRUSTED -> BookRefusal.Blocked
                CAPACITY -> BookRefusal.Capacity
                STORAGE_FAILED, CallNative.STORAGE_AMBIGUOUS -> BookRefusal.Storage
                else -> throw failure
            })
        } finally {
            if (ambiguous) mutableState.value = BookState.Failed(IdentityFailure.Io) else publish()
        }
    }

    private fun publish() {
        val own = CallNative.identity(store)?.let { key ->
            OwnIdentity(key, CallNative.contactCodeFormat(key), CallNative.fingerprint(context, key))
        }
        val contacts = CallNative.contacts(store).map { record ->
            Contact(record.key, record.name, record.trust, CallNative.contactCodeFormat(record.key),
                CallNative.fingerprint(context, record.key), record.replacedBy, record.replaces)
        }
        mutableState.value = BookState.Ready(own, contacts)
    }

    private companion object {
        // ito_status values from call.h.
        const val INVALID_ARGUMENT = -1
        const val WRONG_STATE = -3
        const val MALFORMED = -5
        const val UNSUPPORTED = -6
        const val UNTRUSTED = -7
        const val AUTHENTICATION_FAILED = -8
        const val CAPACITY = -12
        const val CORRUPT_STATE = -14
        const val STORAGE_FAILED = -15
    }
}
