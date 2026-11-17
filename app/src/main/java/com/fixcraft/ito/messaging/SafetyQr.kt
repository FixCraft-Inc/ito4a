package com.fixcraft.ito.messaging

/** App-owned safety QR: two identity-2 fingerprints and the exact pair's 60 digits. */
internal class SafetyQr(val first: String, val second: String, val digits: String) {
    fun encode() = "ito-safety:1:$first:$second:$digits"
    fun other(own: String): String? = when (own) { first -> second; second -> first; else -> null }
    companion object {
        fun parse(text: String): SafetyQr? {
            if (text.length != "ito-safety:1:".length + 190 || !text.startsWith("ito-safety:1:")) return null
            val fields = text.removePrefix("ito-safety:1:").split(':')
            if (fields.size != 3 || fields.take(2).any { !it.matches(Regex("[0-9a-f]{64}")) } ||
                !fields[2].matches(Regex("[0-9]{60}")) || fields[0] == fields[1]) return null
            return SafetyQr(fields[0], fields[1], fields[2])
        }
    }
}
