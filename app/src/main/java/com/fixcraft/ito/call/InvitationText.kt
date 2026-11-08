package com.fixcraft.ito.call

import java.util.Base64

/**
 * The text an invitation QR code carries: `ito-invitation:1:` and the
 * unpadded base64url of the signed invitation. It holds the invitation PSK.
 * Only the secure invitation screen draws it and only the scanner reads it.
 * It never reaches the clipboard, a share sheet, an Intent or saved state
 * (CALL_SECURITY). This frames the bytes; the core validates them.
 */
object InvitationText {
    const val PREFIX = "ito-invitation:1:"
    /** ITO_MAX_INVITATION_BYTES. */
    const val MAX_BYTES = 512
    const val MAX_CHARS = PREFIX.length + (MAX_BYTES * 4 + 2) / 3

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(invitation: ByteArray): String {
        require(invitation.size in 1..MAX_BYTES)
        return PREFIX + encoder.encodeToString(invitation)
    }

    fun looksLikeInvitation(text: String) = text.startsWith(PREFIX)

    /**
     * The invitation bytes, which the caller owns and wipes, or null for
     * anything other than one canonical invitation text.
     */
    fun decode(text: String): ByteArray? {
        if (text.length > MAX_CHARS || !text.startsWith(PREFIX)) return null
        val body = text.substring(PREFIX.length)
        if (body.isEmpty() || body.length % 4 == 1 || !body.all(::isAlphabet)) return null
        val bytes = try { Base64.getUrlDecoder().decode(body) } catch (_: IllegalArgumentException) { return null }
        // Unused trailing bits must be zero, so one invitation has one text.
        if (bytes.size !in 1..MAX_BYTES || encoder.encodeToString(bytes) != body) {
            bytes.fill(0)
            return null
        }
        return bytes
    }

    private fun isAlphabet(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}
