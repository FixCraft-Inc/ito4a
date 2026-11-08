package com.fixcraft.ito.ui

import androidx.compose.runtime.Composable
import com.fixcraft.ito.R
import com.fixcraft.ito.integration.MinimalServices
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionState

/** The minimal build has no provider code. Files are the only route. */
@Composable
fun ServiceConnectionPanel(connection: ConnectionState, connections: ConnectionController,
    @Suppress("UNUSED_PARAMETER") services: MinimalServices, advanced: Boolean) {
    ConnectionStatus(connection, connections, manualRoute(connection), true, advanced, R.string.connection_import_first)
    Section { ManualConfigurations(connection, connections, manualInUse = true) }
    if (advanced) Section { AdvancedConnection(connection, connections) }
}
