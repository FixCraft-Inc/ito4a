package com.fixcraft.ito.messaging

/** Public correlation fields only. Authentication and deadlines belong to the core. */
internal object RingToken {
    private val domain = "ito/invitation/2\u0000".toByteArray(Charsets.US_ASCII)

    /** Only for a locally created core invitation, never to admit peer input. */
    fun outgoing(invitation: ByteArray): ByteArray {
        require(invitation.size in 262..325 && invitation.copyOfRange(0, domain.size).contentEquals(domain))
        return invitation.copyOfRange(domain.size, domain.size + 32)
    }

    fun matches(peer: ByteArray, id: ByteArray, otherPeer: ByteArray, end: ByteArray) =
        end.size == 33 && peer.contentEquals(otherPeer) && id.contentEquals(end.copyOfRange(0, 32))

    const val CANCELED = 1
    const val DECLINED = 2
    const val BUSY = 3
    const val MISSED = 4
}
