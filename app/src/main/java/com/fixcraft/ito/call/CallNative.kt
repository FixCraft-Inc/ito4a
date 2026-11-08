package com.fixcraft.ito.call

/** A call core status that is not an expected value, with a fixed operation name. */
class CallFailure(val code: Int, val operation: String) : RuntimeException("$operation failed ($code)")

/** Seals and publishes one plaintext snapshot. Returns a PERSIST_* result. */
fun interface SnapshotPersistence {
    fun persist(snapshot: ByteArray): Int
}

/** One contact as the core stores it. The name is UTF-8 local display data. */
class ContactRecord(
    val key: ByteArray,
    val trust: Int,
    val flags: Int,
    val replacedBy: ByteArray?,
    val replaces: ByteArray?,
    val addedUnix: Long,
    val verifiedUnix: Long,
    nameBytes: ByteArray,
) {
    val name: String = nameBytes.toString(Charsets.UTF_8)
}

class CallStatus(private val values: LongArray, val peerKey: ByteArray) {
    val state: Int get() = values[0].toInt()
    val flags: Int get() = values[1].toInt()
    val result: Int get() = values[2].toInt()
    val localEnd: Int get() = values[3].toInt()
    val remoteEnd: Int get() = values[4].toInt()
    val relayRefusal: Int get() = values[5].toInt()
    val dataSent: Long get() = values[6]
    val dataReceived: Long get() = values[7]
    val framesSent: Long get() = values[8]
    val framesReceived: Long get() = values[9]
    val keyUpdatesSent: Int get() = values[10].toInt()
    val keyUpdatesReceived: Int get() = values[11].toInt()
    fun has(flag: Int) = flags and flag != 0
}

/**
 * Ito's adapter to the portable call core (libitocall) through libitojni.
 * Handles have one Kotlin owner each. A call is destroyed only after
 * [callRun] returned, a store only after its calls and invitations, and the
 * context last. Secrets in returned arrays belong to the caller, which wipes
 * them after use.
 */
object CallNative {
    init {
        System.loadLibrary("itojni")
        initialize()
    }

    const val OK = 0
    const val CLOCK_REGRESSED = -10
    const val NOT_FOUND = -13
    const val STORAGE_AMBIGUOUS = -16
    const val CANCELED = -20
    const val PERSIST_COMMITTED = 0
    const val PERSIST_NOT_COMMITTED = 1
    const val PERSIST_AMBIGUOUS = 2

    const val TRUST_UNVERIFIED = 1
    const val TRUST_VERIFIED = 2
    const val TRUST_BLOCKED = 3
    const val CONTACT_REPLACED = 1
    const val CONTACT_REPLACES = 2

    const val STATE_IDLE = 0
    const val STATE_PREPARING = 1
    const val STATE_JOINED = 2
    const val STATE_HANDSHAKING = 3
    const val STATE_AUTHENTICATING = 4
    const val STATE_AWAITING_ACCEPTANCE = 5
    const val STATE_MEDIA_AUTHORIZED = 6
    const val STATE_CLOSING = 7
    const val STATE_CLOSED = 8

    const val FLAG_INITIATOR = 1
    const val FLAG_INVITATION_READY = 2
    const val FLAG_PEER_AUTHENTICATED = 4
    const val FLAG_CONSENT_PENDING = 8
    const val FLAG_REMOTE_END_ATTRIBUTED = 16

    const val MAX_DATA_BYTES = 16384

    private external fun initialize()
    external fun componentVersion(): String
    external fun contentEncode(kind: Int, sequence: Long, payload: ByteArray): ByteArray
    external fun contentDecode(frame: ByteArray): ContentFrame

    external fun contextCreate(): Long
    external fun contextDestroy(context: Long)
    external fun fingerprint(context: Long, key: ByteArray): String
    external fun contactCodeFormat(key: ByteArray): String
    external fun contactCodeParse(code: String): ByteArray

    external fun storeOpen(context: Long, snapshot: ByteArray?, persistence: SnapshotPersistence): Long
    external fun storeDestroy(store: Long)
    external fun createIdentity(store: Long, nowUnix: Long)
    /** The own public key, or null before an identity exists. */
    external fun identity(store: Long): ByteArray?
    external fun addContact(store: Long, key: ByteArray, name: ByteArray, nowUnix: Long)
    external fun verifyContact(store: Long, key: ByteArray, compared: String, nowUnix: Long)
    external fun blockContact(store: Long, key: ByteArray)
    external fun renameContact(store: Long, key: ByteArray, name: ByteArray)
    external fun replaceContact(store: Long, oldKey: ByteArray, newKey: ByteArray, nowUnix: Long)
    external fun contacts(store: Long): Array<ContactRecord>

    external fun invitationOpen(store: Long, invitation: ByteArray, fingerprint: ByteArray, service: String, nowUnix: Long): Long
    external fun invitationInitiator(invitation: Long): ByteArray
    external fun invitationProfile(invitation: Long): Int
    external fun invitationDestroy(invitation: Long)

    external fun callCreateInitiator(store: Long, peer: ByteArray, fingerprint: ByteArray, service: String,
                                     lifetimeSeconds: Int, nowUnix: Long, profile: Int = 1): Long
    /** On success the call owns the invitation: its handle must not be used again. */
    external fun callCreateResponder(store: Long, invitation: Long, fingerprint: ByteArray, service: String, nowUnix: Long): Long
    /** Blocks until the attempt ends. Returns its final status. */
    external fun callRun(call: Long, stream: Long): Int
    external fun callStatus(call: Long): CallStatus
    /** The signed invitation while the initiator shows it, else null. A secret. */
    external fun callInvitation(call: Long): ByteArray?
    external fun callDecide(call: Long, accept: Boolean)
    /** False under backpressure. */
    external fun callSend(call: Long, data: ByteArray): Boolean
    /** The oldest received DATA payload, or null. */
    external fun callReceive(call: Long): ByteArray?
    external fun callHangup(call: Long)
    external fun callCancel(call: Long)
    external fun callAuthorityLost(call: Long)
    external fun callDestroy(call: Long)

    /** Live core handles by kind (contexts, stores, invitations, calls), for tests. */
    external fun liveHandles(): LongArray
}
