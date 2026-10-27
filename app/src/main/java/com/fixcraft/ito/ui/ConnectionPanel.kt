package com.fixcraft.ito.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState

@Composable
fun ConnectionPanel(state: ConnectionState, controller: ConnectionController, providerSelected: Boolean = false, authorityReady: Boolean = true) {
    // Document URIs and codes are never saved across Activity/process recreation.
    var document by remember { mutableStateOf<Uri?>(null) }
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document = it }
    var destination by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var streamService by remember { mutableStateOf("tcp") }
    var packetService by remember { mutableStateOf("udp") }
    val busy = state.phase in setOf(ConnectionPhase.Loading, ConnectionPhase.Starting, ConnectionPhase.Stopping)
    Text(stringResource(R.string.manual_transport), style = MaterialTheme.typography.titleLarge)
    SupportingText(R.string.transport_notice)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(when (state.phase) {
        ConnectionPhase.Loading -> R.string.transport_loading
        ConnectionPhase.Idle -> R.string.transport_idle
        ConnectionPhase.Starting -> R.string.transport_starting
        ConnectionPhase.Connected -> R.string.transport_connected
        ConnectionPhase.Stopping -> R.string.transport_stopping
        ConnectionPhase.Failed -> R.string.transport_failed
    }))
    if (state.failure != ConnectionFailure.None) Text(stringResource(R.string.transport_failure, state.failure.name, state.nativeCode))
    if (state.providerCode.isNotEmpty()) Text(stringResource(R.string.provider_failure, state.providerCode))
    Button(onClick = { picker.launch(arrayOf("application/octet-stream", "application/*")) }, enabled = !busy) {
        Text(stringResource(R.string.import_configuration))
    }
    state.catalog.entries.forEach { entry ->
        PreferenceChoiceText(entry.label, !providerSelected && state.catalog.selectedId == entry.id, { controller.select(entry.id) }, !busy)
        TextButton(onClick = { controller.remove(entry.id) }, enabled = !busy) {
            Text(stringResource(R.string.remove_configuration, entry.label))
        }
    }
    Text(stringResource(R.string.underlying_network), style = MaterialTheme.typography.titleMedium)
    PreferenceChoiceText(stringResource(R.string.current_network), state.selectedNetwork == null, { controller.selectNetwork(null) }, !busy)
    state.networks.forEach { network ->
        PreferenceChoiceText(stringResource(R.string.network_choice, network.handle,
            stringResource(if (network.wifi) R.string.network_wifi else R.string.network_other),
            stringResource(if (network.metered) R.string.network_metered else R.string.network_unmetered)),
            state.selectedNetwork == network.handle, { controller.selectNetwork(network.handle) }, !busy)
    }
    TextButton(onClick = { controller.refresh() }, enabled = !busy && state.phase != ConnectionPhase.Connected) {
        Text(stringResource(R.string.refresh_networks))
    }
    Button(onClick = { controller.connect() }, enabled = !busy && authorityReady && (providerSelected || state.catalog.selectedId != null) && state.phase != ConnectionPhase.Connected) {
        Text(stringResource(R.string.connect_transport))
    }
    TextButton(onClick = { controller.stop() }, enabled = state.phase in setOf(ConnectionPhase.Starting, ConnectionPhase.Connected, ConnectionPhase.Failed)) {
        Text(stringResource(R.string.stop_transport))
    }
    if (state.peer.isNotEmpty()) Text(stringResource(R.string.authenticated_peer, state.peer))
    if (state.phase == ConnectionPhase.Connected) {
        SupportingText(R.string.data_test_notice)
        OutlinedTextField(destination, { destination = it }, label = { Text(stringResource(R.string.test_destination)) }, singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.test_port)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(streamService, { streamService = it }, label = { Text(stringResource(R.string.stream_service)) }, singleLine = true)
        OutlinedTextField(packetService, { packetService = it }, label = { Text(stringResource(R.string.packet_service)) }, singleLine = true)
        Button(onClick = { controller.probe(false, streamService, destination, port.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.test_stream)) }
        Button(onClick = { controller.probe(true, packetService, destination, port.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.test_packet)) }
    }
    state.receipts.forEach { receipt -> Text(stringResource(R.string.data_receipt, receipt.kind, receipt.bytes, receipt.sha256, receipt.network)) }
    Spacer(Modifier.height(24.dp))
    document?.let { uri ->
        AlertDialog(onDismissRequest = { document = null; code = "" },
            title = { Text(stringResource(R.string.import_configuration)) },
            text = { Column {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.configuration_name)) }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.opening_code)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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

@Composable
private fun PreferenceChoiceText(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label) })
}
