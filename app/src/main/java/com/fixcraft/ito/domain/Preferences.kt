package com.fixcraft.ito.domain

import java.net.URI
import java.util.UUID

enum class RoutingPreference { ProtectedOnly, DirectAllowed }

enum class AudioPreference { Adaptive, Economy, Emergency }

/** A user-entered address, not an authenticated provider or an enrollment. */
@JvmInline
value class ServiceOrigin private constructor(val value: String) {
    companion object {
        fun parse(input: String): ServiceOrigin? {
            if (input.length !in 1..2048 || input.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri = try {
                URI(input)
            } catch (_: java.net.URISyntaxException) {
                return null
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath !in listOf(null, "", "/") || uri.port !in -1..65535 || uri.port == 0 ||
                uri.rawAuthority.endsWith(":")) return null
            val host = uri.host.lowercase(java.util.Locale.ROOT)
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return ServiceOrigin("https://$host$port")
        }
    }
}

data class SavedService(val id: UUID, val name: String, val origin: ServiceOrigin) {
    init {
        require(validServiceName(name))
    }
}

fun validServiceName(value: String): Boolean =
    value.length in 1..64 && value == value.trim() && value.none { it.isISOControl() }

data class Preferences(
    val routing: RoutingPreference = RoutingPreference.ProtectedOnly,
    val audio: AudioPreference = AudioPreference.Adaptive,
    val services: List<SavedService> = emptyList(),
) {
    init {
        require(services.size <= MAX_SERVICES)
        require(services.map { it.id }.distinct().size == services.size)
        require(services.map { it.origin }.distinct().size == services.size)
    }

    companion object {
        const val MAX_SERVICES = 8
    }
}
