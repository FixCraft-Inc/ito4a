package com.fixcraft.ito.transport

/** Route authority only; Wi-Fi and metering labels do not select a path. */
internal data class ConnectionNetwork(val handle: Long, val internet: Boolean, val vpn: Boolean)

/** Both routes are captured once. A replacement requires another Connect action. */
internal data class CapturedConnectionNetwork(
    val network: ConnectionNetwork,
    val defaultNetwork: ConnectionNetwork,
) {
    fun acceptsDefault(current: ConnectionNetwork?): Boolean = current == defaultNetwork
    fun acceptsNetwork(current: ConnectionNetwork?): Boolean = current == network
}

/** A physical selection must not bypass a VPN applied to Ito's default route. */
internal fun selectableConnectionNetworks(
    defaultNetwork: ConnectionNetwork?,
    networks: List<ConnectionNetwork>,
): List<ConnectionNetwork> = if (defaultNetwork == null || !defaultNetwork.internet || defaultNetwork.vpn) emptyList()
else networks.filter { it.internet && !it.vpn }

internal fun captureConnectionNetwork(
    selected: Long?,
    defaultNetwork: ConnectionNetwork?,
    networks: List<ConnectionNetwork>,
): CapturedConnectionNetwork? {
    if (defaultNetwork == null || !defaultNetwork.internet) return null
    val network = if (selected == null) defaultNetwork
    else selectableConnectionNetworks(defaultNetwork, networks).firstOrNull { it.handle == selected } ?: return null
    return CapturedConnectionNetwork(network, defaultNetwork)
}
