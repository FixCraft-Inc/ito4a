package com.fixcraft.ito.domain

enum class MediaPath { YumeRelay, Direct }

enum class CallRefusal { PeerNotAuthenticated, MediaNotEncrypted, DirectNotAllowed, YumeNotAuthenticated }

sealed interface MediaPathAuthorization {
    data object Allowed : MediaPathAuthorization
    data class Rejected(val reason: CallRefusal) : MediaPathAuthorization
}

/** Facts must eventually come from the native transport and media security adapters. */
data class CallSecurityFacts(
    val peerAuthenticated: Boolean,
    val mediaEndToEndEncrypted: Boolean,
    val yumeAuthenticated: Boolean,
)

/** Audio quality cannot relax authorization: it is deliberately not an input. */
fun authorizeMediaPath(
    preference: RoutingPreference,
    path: MediaPath,
    security: CallSecurityFacts,
): MediaPathAuthorization = when {
    !security.peerAuthenticated -> MediaPathAuthorization.Rejected(CallRefusal.PeerNotAuthenticated)
    !security.mediaEndToEndEncrypted -> MediaPathAuthorization.Rejected(CallRefusal.MediaNotEncrypted)
    path == MediaPath.Direct && preference != RoutingPreference.DirectAllowed ->
        MediaPathAuthorization.Rejected(CallRefusal.DirectNotAllowed)
    path == MediaPath.YumeRelay && !security.yumeAuthenticated ->
        MediaPathAuthorization.Rejected(CallRefusal.YumeNotAuthenticated)
    else -> MediaPathAuthorization.Allowed
}
