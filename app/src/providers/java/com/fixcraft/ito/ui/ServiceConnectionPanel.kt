package com.fixcraft.ito.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionState
import com.fixcraft.yume.consumer.ServiceController

/** The provider build's Service page: status, provider sign-in and server choice, then files. */
@Composable
fun ServiceConnectionPanel(connection: ConnectionState, connections: ConnectionController,
    services: ServiceController<ItoProviderSession>, advanced: Boolean) {
    val state by services.state.collectAsStateWithLifecycle()
    // Three cases: a usable provider route, manual use, or neither, as after the
    // chosen provider was removed. Neither has no Connect and is never manual.
    val selection = state.selection.takeIf { !state.manual }
    val route = when {
        selection != null -> {
            val entry = state.entries.firstOrNull { it.provider == selection.provider }
            SelectedRoute(selection.label, countryName(entry?.server?.country))
        }
        state.manual -> manualRoute(connection)
        else -> null
    }
    ConnectionStatus(connection, connections, route, state.loaded && state.busy.isEmpty(), advanced,
        R.string.connection_choose_first)
    Section { ProviderPanel(state, services, connections, advanced) }
    Section { ManualConfigurations(connection, connections, manualInUse = state.manual) }
    if (advanced) Section { AdvancedConnection(connection, connections) }
}
