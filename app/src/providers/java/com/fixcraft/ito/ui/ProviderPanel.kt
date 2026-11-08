package com.fixcraft.ito.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fixcraft.ito.BuildConfig
import com.fixcraft.ito.R
import com.fixcraft.ito.integration.provisionProvider
import com.fixcraft.ito.integration.selectProvider
import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Ito owns presentation. The shared controller owns every enrollment
 * transition. The ordinary path is: continue with a provider, sign in, choose
 * a server. Server addresses and identities appear only in Advanced mode.
 */
@Composable
fun ProviderPanel(state: ServiceUiState, controller: ServiceController<ItoProviderSession>,
                  connections: ConnectionController, advanced: Boolean) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    SectionHeading(R.string.provider_services)
    SupportingText(R.string.provider_scope)
    state.error?.let { Text(stringResource(providerMessage(it), it), color = MaterialTheme.colorScheme.error) }
    if (state.removed) Text(stringResource(R.string.provider_removed))
    if (state.loaded && state.entries.isEmpty()) {
        val suggested = BuildConfig.SUGGESTED_PROVIDER
        if (suggested.isNotEmpty() && state.browserRegistered) SuggestedProvider(suggested, BuildConfig.SUGGESTED_ISSUER,
            busy = suggested in state.busy) { controller.add(suggested, BuildConfig.SUGGESTED_ISSUER.ifEmpty { null }) }
        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.provider_other)) }
    }
    state.entries.forEach { entry ->
        ProviderEntry(entry, state, controller, connections, advanced) { removing = entry.provider }
    }
    // Several providers can coexist. Each still needs its own consent in the dialog.
    if (state.loaded && state.entries.isNotEmpty()) TextButton(onClick = { adding = true }) {
        Text(stringResource(R.string.provider_add_another))
    }
    if (adding) AddProviderDialog(onDismiss = { adding = false }) { origin, issuer ->
        adding = false
        controller.add(origin, issuer)
    }
    removing?.let { provider ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.provider_remove)) },
            text = { Text(stringResource(R.string.provider_remove_notice, host(provider))) },
            confirmButton = { TextButton(onClick = { removing = null; controller.remove(provider) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    state.codePrompt?.let { prompt ->
        // Only anonymous providers deliver an opening code out of band.
        var code by remember(prompt.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { code = ""; controller.cancel(prompt.provider) },
            title = { Text(stringResource(R.string.provider_opening)) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(host(prompt.provider))
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

/** States what will be contacted before anything is. Nothing is added until Continue. */
@Composable
private fun SuggestedProvider(origin: String, issuer: String, busy: Boolean, onContinue: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.provider_suggested_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.provider_suggested_body, host(origin), host(issuer.ifEmpty { origin })),
                style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = onContinue, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.provider_continue))
            }
        }
    }
}

@Composable
private fun ProviderEntry(entry: ServiceEntry, state: ServiceUiState, controller: ServiceController<ItoProviderSession>,
                          connections: ConnectionController, advanced: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = entry.provider in state.busy || state.authorizing == entry.provider
    val inUse = !state.manual && state.selection?.provider == entry.provider
    // One card per provider keeps its sign-in, servers and removal together.
    OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(entry.label, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.provider_role_relay), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.authorized && !entry.suspended) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(when {
                entry.suspended -> R.string.provider_suspended
                entry.authorized -> R.string.provider_signed_in
                else -> R.string.provider_signed_out
            }), style = MaterialTheme.typography.bodyMedium,
                color = if (entry.suspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (advanced || !entry.authorized) {
            Text(stringResource(R.string.provider_origin_detail, host(entry.provider)), style = MaterialTheme.typography.bodySmall)
            entry.issuer?.let { Text(stringResource(R.string.provider_issuer_detail, host(it)), style = MaterialTheme.typography.bodySmall) }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.provider_busy), Modifier.weight(1f))
                TextButton(onClick = { controller.cancel(entry.provider) }) { Text(stringResource(R.string.cancel)) }
            }
        }
        val issuer = entry.issuer
        // Signing in again cannot lift a suspension. Choosing the server again can.
        if (issuer != null && !entry.authorized) {
            if (state.browserRegistered) {
                Button(enabled = !busy && state.authorizing == null, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    scope.launch {
                        try { context.startActivity(controller.authorize(entry.provider)) }
                        catch (cancelled: CancellationException) { controller.browserLaunchFailed(entry.provider); throw cancelled }
                        catch (_: ProviderCodec.Refusal) { /* The controller publishes its typed failure. */ }
                        catch (_: ActivityNotFoundException) { controller.browserLaunchFailed(entry.provider) }
                        catch (_: SecurityException) { controller.browserLaunchFailed(entry.provider) }
                    }
                }) { Text(stringResource(R.string.provider_sign_in)) }
                SupportingText(stringResource(R.string.provider_sign_in_note, host(issuer)))
            } else SupportingText(R.string.provider_unregistered)
        }
        if (entry.authorized) ServerChoice(entry, state, controller, connections, advanced, busy)
        if (entry.selection != null && !inUse) OutlinedButton(enabled = !busy && !entry.suspended,
            onClick = { connections.selectProvider(controller, entry.selection!!) }) { Text(stringResource(R.string.provider_select)) }
        TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.provider_remove))
        }
    }
    }
}

/**
 * Lists the account's servers by place and name. Choosing one requests a
 * device lease and selects it. An installed choice cannot silently move to
 * another server. Choosing it again renews its lease once that is allowed.
 */
@Composable
private fun ServerChoice(entry: ServiceEntry, state: ServiceUiState, controller: ServiceController<ItoProviderSession>,
                         connections: ConnectionController, advanced: Boolean, busy: Boolean) {
    val catalog = state.catalogs[entry.provider]
    val installed = entry.selection
    val access = grantAccess(entry, System.currentTimeMillis() / 1000)
    Text(stringResource(R.string.provider_choose_server), style = MaterialTheme.typography.titleSmall)
    if (catalog == null) {
        installed?.let { selection ->
            ChoiceRow(countryName(entry.server?.country) ?: selection.label,
                if (countryName(entry.server?.country) != null) selection.label else null, true, false) {}
            access?.let { GrantAccessText(it, entry.expiresAt) }
        }
        OutlinedButton(enabled = !busy, onClick = { controller.catalog(entry.provider) }) {
            Text(stringResource(R.string.provider_servers))
        }
    } else {
        if (catalog.services.isEmpty()) SupportingText(R.string.provider_no_entitlement)
        catalog.services.forEach { service ->
            service.servers.forEach { server ->
                val current = installed?.service == service.id && installed.server == server.id
                val place = countryName(server.country)
                ChoiceRow(place ?: server.label, if (place != null) server.label else stringResource(R.string.provider_location_unknown),
                    current, enabled = !busy && (installed == null || current)) {
                    // Decide at the tap: the screen may have been open since the lease could renew.
                    val renew = current && grantAccess(entry, System.currentTimeMillis() / 1000) != GrantAccess.Active
                    if (!current || renew) connections.provisionProvider(controller, entry.provider, catalog, service.id, server.id)
                }
                if (current) access?.let { GrantAccessText(it, entry.expiresAt) }
                if (advanced) Text(stringResource(R.string.provider_server_address, server.host, server.port),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (installed != null && catalog.services.sumOf { it.servers.size } > 1) {
            SupportingText(R.string.provider_replace_requires_remove)
        }
        TextButton(enabled = !busy, onClick = { controller.catalog(entry.provider) }) {
            Text(stringResource(R.string.provider_refresh_servers))
        }
    }
    if (advanced && installed != null) Text(stringResource(R.string.provider_identity, installed.identity),
        style = MaterialTheme.typography.bodySmall)
}

@StringRes
private fun providerMessage(code: String): Int = when (code) {
    "unauthorized" -> R.string.provider_error_unauthorized
    "not_entitled" -> R.string.provider_error_not_entitled
    "unavailable" -> R.string.provider_error_unavailable
    "stale" -> R.string.provider_error_stale
    "unsupported" -> R.string.provider_error_unsupported
    "cancelled" -> R.string.provider_error_cancelled
    "storage", "state" -> R.string.provider_error_storage
    "rate_limited" -> R.string.provider_error_rate_limited
    "binding", "malformed" -> R.string.provider_error_binding
    "revoked" -> R.string.provider_error_revoked
    "timeout" -> R.string.provider_error_timeout
    "conflict" -> R.string.provider_error_conflict
    else -> R.string.provider_error_other
}

private fun host(origin: String) = origin.removePrefix("https://")

/** Provider origins are URLs: no capitals, suggestions or autocorrection. */
private val urlKeyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

/** Leases last up to an hour, so the card says when this one ends and how to renew it. */
@Composable
private fun GrantAccessText(access: GrantAccess, expiresAt: Long?) {
    val time = expiresAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000)) }
    when {
        access == GrantAccess.Ended -> SupportingText(R.string.provider_access_ended)
        time == null -> Unit
        access == GrantAccess.Renewable -> SupportingText(stringResource(R.string.provider_access_renew, time))
        else -> SupportingText(stringResource(R.string.provider_access_until, time))
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var origin by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            // One window-bounded scroll owner includes the title and actions.
            val formWidth = minOf(560.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))
            val formHeight = (maxHeight - 48.dp).coerceAtLeast(0.dp)
            Surface(Modifier.widthIn(max = formWidth).heightIn(max = formHeight).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.provider_add), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(origin, { if (it.length <= 512) origin = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.provider_origin)) }, singleLine = true,
                        keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Next))
                    OutlinedTextField(issuer, { if (it.length <= 512) issuer = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.provider_issuer)) }, singleLine = true,
                        keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Done))
                    Text(stringResource(R.string.provider_consent))
                    Row(Modifier.fillMaxWidth().toggleable(consent, role = Role.Checkbox) { consent = it }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, onCheckedChange = null)
                        Text(stringResource(R.string.provider_accept), Modifier.weight(1f).padding(start = 12.dp))
                    }
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        TextButton(enabled = consent && origin.isNotBlank(),
                            onClick = { onAdd(origin.trim(), issuer.trim().ifEmpty { null }) }) {
                            Text(stringResource(R.string.provider_add))
                        }
                    }
                }
            }
        }
    }
}
