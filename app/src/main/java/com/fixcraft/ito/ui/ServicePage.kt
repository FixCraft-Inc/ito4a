package com.fixcraft.ito.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState
import java.util.Locale

/** What the status card names as the selected route, already in display form. */
data class SelectedRoute(val name: String, val place: String?)

/** A provider-declared ISO country in the user's language. Unknown stays unknown. */
fun countryName(code: String?): String? {
    if (code == null || !Regex("[A-Z]{2}").matches(code)) return null
    val name = Locale("", code).getDisplayCountry(Locale.getDefault())
    return name.takeIf { it.isNotBlank() && it != code }
}

@Composable
internal fun SectionHeading(@StringRes text: Int) = SectionTitle(text)

/**
 * The one place that says whether Ito is connected, through what, and the
 * single action that changes it. Connect appears once a route exists.
 */
@Composable
fun ConnectionStatus(state: ConnectionState, controller: ConnectionController, route: SelectedRoute?,
                     authorityReady: Boolean, advanced: Boolean, @StringRes setupHint: Int) {
    val busy = state.phase in setOf(ConnectionPhase.Loading, ConnectionPhase.Starting, ConnectionPhase.Stopping)
    val connected = state.phase == ConnectionPhase.Connected
    val failed = state.phase == ConnectionPhase.Failed
    val tone = when { connected -> StatusTone.Good; failed -> StatusTone.Problem; else -> StatusTone.Neutral }
    StatusCard(tone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(when { connected -> R.drawable.ic_check; failed -> R.drawable.ic_alert; else -> R.drawable.ic_link }), null)
            Text(stringResource(when (state.phase) {
                ConnectionPhase.Loading -> R.string.transport_loading
                ConnectionPhase.Idle -> if (route == null) R.string.transport_not_set_up else R.string.transport_idle
                ConnectionPhase.Starting -> R.string.transport_starting
                ConnectionPhase.Connected -> R.string.transport_connected
                ConnectionPhase.Stopping -> R.string.transport_stopping
                ConnectionPhase.Failed -> R.string.transport_failed
            }), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (route != null) Text(stringResource(R.string.connection_through,
            route.place?.let { "${route.name}, $it" } ?: route.name), style = MaterialTheme.typography.bodyLarge)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.failure != ConnectionFailure.None) {
            // On the error card the card's own content colour already reads as an error.
            Text(stringResource(failureText(state.failure)),
                color = if (failed) Color.Unspecified else MaterialTheme.colorScheme.error)
            if (advanced) Text(stringResource(R.string.failure_detail, state.failure.name, state.nativeCode),
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.providerCode == "providers_disabled") Text(stringResource(R.string.provider_build_disabled))
        // Each build names only the routes it offers.
        if (route == null && !connected) Text(stringResource(setupHint))
        // Disconnect sits on a tinted card, so it takes the card's own text colour to stay readable.
        val onCard = LocalContentColor.current
        when {
            connected || state.phase == ConnectionPhase.Starting -> OutlinedButton(onClick = { controller.stop() },
                modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = onCard),
                border = BorderStroke(1.dp, onCard.copy(alpha = 0.6f))) { Text(stringResource(R.string.stop_transport)) }
            // Without a route there is nothing to connect to. The choices are below.
            route != null -> Button(onClick = { controller.connect() }, modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy && authorityReady) { Text(stringResource(R.string.connect_transport)) }
        }
        if (failed) TextButton(onClick = { controller.stop() }, colors = ButtonDefaults.textButtonColors(contentColor = onCard)) {
            Text(stringResource(R.string.stop_transport))
        }
        Text(stringResource(R.string.connection_carries), style = MaterialTheme.typography.bodySmall)
    }
}

@StringRes
private fun failureText(failure: ConnectionFailure) = when (failure) {
    ConnectionFailure.NetworkUnavailable -> R.string.failure_network
    ConnectionFailure.NetworkLost -> R.string.failure_network_lost
    ConnectionFailure.Resolve -> R.string.failure_resolve
    ConnectionFailure.Native -> R.string.failure_server
    ConnectionFailure.InvalidConfiguration, ConnectionFailure.Input -> R.string.failure_configuration
    ConnectionFailure.Storage -> R.string.failure_storage
    ConnectionFailure.Provider -> R.string.failure_access
    else -> R.string.failure_other
}

/** The manual path, available in every build and without an account. */
@Composable
fun ManualConfigurations(state: ConnectionState, controller: ConnectionController, manualInUse: Boolean) {
    // Document URIs and codes are never saved across Activity/process recreation.
    var document by remember { mutableStateOf<Uri?>(null) }
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document = it }
    val busy = state.phase in setOf(ConnectionPhase.Loading, ConnectionPhase.Starting, ConnectionPhase.Stopping)
    SectionHeading(R.string.manual_transport)
    SupportingText(R.string.transport_notice)
    state.catalog.entries.forEach { entry ->
        ChoiceRow(entry.label, null, manualInUse && state.catalog.selectedId == entry.id, !busy) {
            controller.select(entry.id)
        }
        TextButton(onClick = { removing = entry.id to entry.label }, enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.remove_configuration, entry.label))
        }
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("application/octet-stream", "application/*")) }, enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.import_configuration)) }
    removing?.let { (id, name) ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_configuration_title, name)) },
            text = { Text(stringResource(R.string.remove_configuration_description)) },
            confirmButton = { TextButton(onClick = { removing = null; controller.remove(id) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.remove))
            } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    document?.let { uri ->
        AlertDialog(onDismissRequest = { document = null; code = "" },
            title = { Text(stringResource(R.string.import_configuration)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { if (it.length <= 64) label = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.configuration_name)) }, singleLine = true)
                OutlinedTextField(code, { if (it.length <= 64) code = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.opening_code)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                SupportingText(R.string.code_storage_notice)
            } },
            confirmButton = { TextButton(onClick = {
                controller.importDocument(uri, label, code.toCharArray())
                code = ""; label = ""; document = null
            }, enabled = label.isNotBlank() && code.isNotBlank()) { Text(stringResource(R.string.import_action)) } },
            dismissButton = { TextButton(onClick = { document = null; code = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One selectable choice. The radio button carries the state, not color alone. */
@Composable
internal fun ChoiceRow(title: String, detail: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Advanced mode only: network choice, authenticated peer and bounded data tests. */
@Composable
fun AdvancedConnection(state: ConnectionState, controller: ConnectionController) {
    var destination by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var streamService by remember { mutableStateOf("tcp") }
    var packetService by remember { mutableStateOf("udp") }
    val busy = state.phase in setOf(ConnectionPhase.Loading, ConnectionPhase.Starting, ConnectionPhase.Stopping)
    SectionHeading(R.string.technical_details)
    Text(stringResource(R.string.underlying_network), style = MaterialTheme.typography.titleMedium)
    SupportingText(R.string.connection_network_policy)
    ChoiceRow(stringResource(R.string.current_network), null, state.selectedNetwork == null, !busy) { controller.selectNetwork(null) }
    state.networks.forEach { network ->
        ChoiceRow(stringResource(R.string.network_choice, network.handle,
            stringResource(if (network.wifi) R.string.network_wifi else R.string.network_other),
            stringResource(if (network.metered) R.string.network_metered else R.string.network_unmetered)),
            null, state.selectedNetwork == network.handle, !busy) { controller.selectNetwork(network.handle) }
    }
    TextButton(onClick = { controller.refresh() }, enabled = !busy && state.phase != ConnectionPhase.Connected) {
        Text(stringResource(R.string.refresh_networks))
    }
    if (state.peer.isNotEmpty()) Text(stringResource(R.string.authenticated_peer, state.peer),
        style = MaterialTheme.typography.bodySmall)
    if (state.phase == ConnectionPhase.Connected) {
        SupportingText(R.string.data_test_notice)
        OutlinedTextField(destination, { destination = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.test_destination)) }, singleLine = true)
        OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.test_port)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(streamService, { streamService = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.stream_service)) }, singleLine = true)
        OutlinedTextField(packetService, { packetService = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.packet_service)) }, singleLine = true)
        OutlinedButton(onClick = { controller.probe(false, streamService, destination, port.toIntOrNull() ?: 0) }) {
            Text(stringResource(R.string.test_stream))
        }
        OutlinedButton(onClick = { controller.probe(true, packetService, destination, port.toIntOrNull() ?: 0) }) {
            Text(stringResource(R.string.test_packet))
        }
    }
    state.receipts.forEach { receipt ->
        Text(stringResource(R.string.data_receipt, receipt.kind, receipt.bytes, receipt.sha256, receipt.network),
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Groups one page section with consistent spacing. */
@Composable
internal fun Section(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(32.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** The selected configuration file, if any, as a route name. */
fun manualRoute(state: ConnectionState): SelectedRoute? =
    state.catalog.entries.firstOrNull { it.id == state.catalog.selectedId }?.let { SelectedRoute(it.label, null) }
