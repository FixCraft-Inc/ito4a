package com.fixcraft.ito.ui

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.integration.provisionProvider
import com.fixcraft.ito.integration.selectProvider
import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionState
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The provider build's Service page: status, provider sign-in and server choice, then files. */
@Composable
fun ServiceConnectionPanel(connection: ConnectionState, connections: ConnectionController,
    services: ServiceController<ItoProviderSession>, advanced: Boolean) {
    val state by services.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember(services, connections, context, scope) {
        ServiceActions(
            add = services::add,
            authorize = { provider ->
                scope.launch {
                    try { context.startActivity(services.authorize(provider)) }
                    catch (cancelled: CancellationException) { services.browserLaunchFailed(provider); throw cancelled }
                    catch (_: ProviderCodec.Refusal) { /* The controller publishes its typed failure. */ }
                    catch (_: ActivityNotFoundException) { services.browserLaunchFailed(provider) }
                    catch (_: SecurityException) { services.browserLaunchFailed(provider) }
                }
            },
            catalog = services::catalog,
            install = { provider, catalog, service, server -> connections.provisionProvider(services, provider, catalog, service, server) },
            select = { connections.selectProvider(services, it) },
            cancel = services::cancel,
            remove = services::remove,
            code = services::submitCode,
        )
    }
    ServiceConnectionPage(connection, connections, state, actions, advanced)
}

/** The page drawn from one state, so instrumentation can show any of them. */
@Composable
internal fun ServiceConnectionPage(connection: ConnectionState, connections: ConnectionController, state: ServiceUiState,
    actions: ServiceActions, advanced: Boolean) {
    // Three cases: a usable provider route, manual use, or neither, as after the
    // chosen provider was removed. Neither has no Connect and is never manual.
    val selection = state.selection.takeIf { !state.manual }
    val route = when {
        selection != null -> {
            val entry = state.entries.firstOrNull { it.provider == selection.provider }
            SelectedRoute(selection.label, serverPlace(entry?.server?.country))
        }
        state.manual -> manualRoute(connection)
        else -> null
    }
    ConnectionStatus(connection, connections, route, state.loaded && state.busy.isEmpty(), advanced,
        R.string.connection_choose_first)
    Section { ProviderPanel(state, actions, advanced) }
    Section { ManualConfigurations(connection, connections, manualInUse = state.manual) }
    if (advanced) Section { AdvancedConnection(connection, connections) }
}
