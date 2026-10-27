package com.fixcraft.ito.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fixcraft.ito.R
import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ito owns presentation; the shared controller owns every enrollment transition. */
@Composable
fun ProviderPanel(state: ServiceUiState, controller: ServiceController<ItoProviderSession>, connections: ConnectionController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.provider_services), style = MaterialTheme.typography.titleLarge)
    SupportingText(R.string.provider_scope)
    Button(onClick = { adding = true }) { Text(stringResource(R.string.provider_add)) }
    TextButton(onClick = controller::reload) { Text(stringResource(R.string.provider_reload)) }
    state.error?.let { Text(stringResource(R.string.provider_failure, it), color = MaterialTheme.colorScheme.error) }
    if (state.removed) Text(stringResource(R.string.provider_removed))
    state.entries.forEach { entry ->
        val busy = entry.provider in state.busy
        HorizontalDivider()
        Text(entry.label, style = MaterialTheme.typography.titleMedium)
        Text(entry.provider)
        entry.issuer?.let { Text(it) }
        if (entry.suspended) Text(stringResource(R.string.provider_suspended))
        if (!state.manual && state.selection?.provider == entry.provider) Text(stringResource(R.string.provider_selected))
        if (entry.issuer != null) {
            TextButton(enabled = !busy && state.authorizing == null && state.browserRegistered, onClick = {
                scope.launch {
                    try { context.startActivity(controller.authorize(entry.provider)) }
                    catch (cancelled: CancellationException) { controller.browserLaunchFailed(entry.provider); throw cancelled }
                    catch (_: ProviderCodec.Refusal) { /* The controller publishes its typed failure. */ }
                    catch (_: ActivityNotFoundException) { controller.browserLaunchFailed(entry.provider) }
                    catch (_: SecurityException) { controller.browserLaunchFailed(entry.provider) }
                }
            }) { Text(stringResource(R.string.provider_sign_in)) }
            if (!state.browserRegistered) SupportingText(R.string.provider_unregistered)
        }
        if (busy || state.authorizing == entry.provider) {
            Text(stringResource(R.string.provider_busy))
            TextButton(onClick = { controller.cancel(entry.provider) }) { Text(stringResource(R.string.cancel)) }
        }
        OutlinedButton(enabled = !busy, onClick = { controller.catalog(entry.provider) }) { Text(stringResource(R.string.provider_servers)) }
        state.catalogs[entry.provider]?.let { catalog ->
            if (catalog.services.isEmpty()) Text(stringResource(R.string.provider_no_entitlement))
            catalog.services.forEach { service ->
                Text(service.label)
                service.servers.forEach { server ->
                    Text(server.label)
                    Text(stringResource(R.string.provider_endpoint, server.host, server.port))
                    val different = entry.selection?.let { it.service != service.id || it.server != server.id } == true
                    if (different) Text(stringResource(R.string.provider_replace_requires_remove))
                    else OutlinedButton(enabled = !busy, onClick = { connections.provisionProvider(entry.provider, catalog, service.id, server.id) }) {
                        Text(stringResource(R.string.provider_provision))
                    }
                }
            }
        }
        entry.selection?.let { selection ->
            Text(stringResource(R.string.provider_endpoint, selection.host, selection.port))
            Text(stringResource(R.string.provider_identity, selection.identity))
            OutlinedButton(enabled = !busy && !entry.suspended, onClick = { connections.selectProvider(selection) }) {
                Text(stringResource(R.string.provider_select))
            }
        }
        TextButton(onClick = { removing = entry.provider }) { Text(stringResource(R.string.provider_remove)) }
    }
    if (adding) AddProviderDialog(onDismiss = { adding = false }) { origin, issuer ->
        adding = false
        controller.add(origin, issuer)
    }
    removing?.let { provider ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.provider_remove)) },
            text = { Text(stringResource(R.string.provider_remove_notice, provider)) },
            confirmButton = { TextButton(onClick = { removing = null; controller.remove(provider) }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    state.codePrompt?.let { prompt ->
        var code by remember(prompt.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { code = ""; controller.cancel(prompt.provider) },
            title = { Text(stringResource(R.string.provider_opening)) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(prompt.provider)
                    Text(prompt.grant)
                    OutlinedTextField(code, { if (it.length <= 64) code = it },
                        label = { Text(stringResource(R.string.opening_code)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            }, confirmButton = {
                TextButton(enabled = code.length in 25..64, onClick = {
                    val owned = code.toCharArray(); code = ""; controller.submitCode(prompt.id, owned)
                }) { Text(stringResource(R.string.save)) }
            }, dismissButton = { TextButton(onClick = { code = ""; controller.cancel(prompt.provider) }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var origin by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            // One window-bounded scroll owner includes the title and actions.
            // Title and action slots cannot consume a separate fixed viewport.
            val formWidth = minOf(560.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))
            val formHeight = (maxHeight - 48.dp).coerceAtLeast(0.dp)
            Surface(Modifier.widthIn(max = formWidth).heightIn(max = formHeight).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.provider_add), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(origin, { if (it.length <= 512) origin = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.provider_origin)) }, singleLine = true)
                    OutlinedTextField(issuer, { if (it.length <= 512) issuer = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.provider_issuer)) }, singleLine = true)
                    Text(stringResource(R.string.provider_consent))
                    Row { Checkbox(consent, { consent = it }); Text(stringResource(R.string.provider_accept), Modifier.weight(1f)) }
                    TextButton(enabled = consent && origin.isNotBlank(),
                        onClick = { onAdd(origin, issuer.ifEmpty { null }) }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                        Text(stringResource(R.string.provider_add))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
