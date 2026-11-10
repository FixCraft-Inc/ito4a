package com.fixcraft.ito.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fixcraft.ito.BuildConfig
import com.fixcraft.ito.R
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderAppLinks
import com.fixcraft.yume.provider.ProviderCodec

/** What the panel can ask of the shared controller. The page supplies it, and only the controller changes enrollment. */
class ServiceActions(
    val add: (String, String?) -> Unit,
    val authorize: (String) -> Unit,
    val catalog: (String) -> Unit,
    /** Provider, service and server: records the choice, without the network. */
    val choose: (String, String, String) -> Unit,
    /** Selects a provider's route with the server already chosen for it. */
    val useProvider: (String) -> Unit,
    val cancel: (String) -> Unit,
    val remove: (String) -> Unit,
    val code: (String, CharArray) -> Unit,
)

/**
 * The Services section: one row per provider, its account state and the next useful
 * step, with the same labels and order as yume4a. Refreshing, removal and technical
 * facts stay in each row's menu. Advanced mode adds the server addresses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderPanel(state: ServiceUiState, actions: ServiceActions, advanced: Boolean,
                  suggestedProvider: String = BuildConfig.SUGGESTED_PROVIDER, suggestedIssuer: String = BuildConfig.SUGGESTED_ISSUER) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ServiceEntry?>(null) }
    var details by remember { mutableStateOf<ServiceEntry?>(null) }
    // A suggestion waits for an explicit Continue. Nothing is contacted before that.
    val suggestion = suggestedProvider.takeIf { it.isNotEmpty() && state.browserRegistered && state.loaded && state.entries.isEmpty() }
    // The action moves under the heading rather than splitting its word at large text.
    FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically) {
        SectionHeading(R.string.provider_services)
        when {
            suggestion != null -> Unit
            state.entries.isEmpty() -> FilledTonalButton(onClick = { adding = true }) { Text(stringResource(R.string.provider_add)) }
            else -> TextButton(onClick = { adding = true }) { Text(stringResource(R.string.provider_add)) }
        }
    }
    if (state.loaded && state.entries.isEmpty()) SupportingText(R.string.provider_scope)
    state.error?.let {
        Text(stringResource(providerMessage(it), it), color = MaterialTheme.colorScheme.error)
        if (it == "link_unverified") LinkSettingsButton()
    }
    if (state.removed) SupportingText(R.string.provider_removed)
    if (suggestion != null) SuggestedProvider(suggestion, suggestedIssuer, busy = suggestion in state.busy,
        onContinue = { actions.add(suggestion, suggestedIssuer.ifEmpty { null }) }, onOther = { adding = true })
    state.entries.forEach { entry ->
        ServiceRow(entry, state, actions, advanced, onDetails = { details = entry }, onRemove = { removing = entry })
    }
    if (adding) AddProviderDialog(suggestedProvider, suggestedIssuer, onDismiss = { adding = false }) { origin, issuer ->
        adding = false
        actions.add(origin, issuer)
    }
    removing?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.provider_remove)) },
            text = { Text(stringResource(R.string.provider_remove_notice, host(entry.provider)), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { removing = null; actions.remove(entry.provider) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    details?.let { entry -> ServiceDetailsDialog(entry, advanced) { details = null } }
    state.codePrompt?.let { prompt ->
        // Only anonymous providers deliver an opening code out of band.
        var code by remember(prompt.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { code = ""; actions.cancel(prompt.provider) },
            title = { Text(stringResource(R.string.provider_opening)) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(host(prompt.provider))
                    OutlinedTextField(code, { if (it.length <= 64) code = it },
                        label = { Text(stringResource(R.string.opening_code)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            }, confirmButton = {
                TextButton(enabled = code.length in 25..64, onClick = {
                    val owned = code.toCharArray(); code = ""; actions.code(prompt.id, owned)
                }) { Text(stringResource(R.string.save)) }
            }, dismissButton = { TextButton(onClick = { code = ""; actions.cancel(prompt.provider) }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** States what will be contacted before anything is. */
@Composable
private fun SuggestedProvider(origin: String, issuer: String, busy: Boolean, onContinue: () -> Unit, onOther: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.provider_suggested_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.provider_suggested_body, host(origin), host(issuer.ifEmpty { origin })),
                style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = onContinue, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.provider_continue))
            }
            TextButton(onClick = onOther) { Text(stringResource(R.string.provider_other)) }
        }
    }
}

@Composable
private fun ServiceRow(entry: ServiceEntry, state: ServiceUiState, actions: ServiceActions, advanced: Boolean,
                       onDetails: () -> Unit, onRemove: () -> Unit) {
    val busy = entry.provider in state.busy
    val pending = state.authorizing == entry.provider
    val inUse = !state.manual && state.selection?.provider == entry.provider
    val canAskForServers = entry.issuer == null || entry.authorized
    OutlinedCard(Modifier.fillMaxWidth(), border = if (inUse) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when {
                        entry.suspended -> R.string.provider_suspended
                        entry.issuer == null -> R.string.provider_no_account
                        entry.authorized -> R.string.provider_signed_in
                        else -> R.string.provider_signed_out
                    }), style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.suspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ServiceMenu(entry, enabled = !busy && !pending, canAskForServers, onRefresh = { actions.catalog(entry.provider) }, onDetails, onRemove)
            }
            // Before authorization, the row names what the person is about to trust.
            val issuer = entry.issuer
            if (issuer != null && !entry.authorized) {
                Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SupportingText(R.string.provider_role_relay)
                    LabeledValue(R.string.provider_label_provider, host(entry.provider))
                    LabeledValue(R.string.provider_label_sign_in, host(issuer))
                }
            }
            when {
                pending -> Working(R.string.provider_authorization, R.string.provider_cancel_sign_in) { actions.cancel(entry.provider) }
                busy -> Working(R.string.provider_busy, R.string.cancel) { actions.cancel(entry.provider) }
            }
            if (issuer != null && !entry.authorized && !pending) {
                if (state.browserRegistered) {
                    Button(onClick = { actions.authorize(entry.provider) }, enabled = !busy && state.authorizing == null,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.provider_sign_in)) }
                    SupportingText(stringResource(R.string.provider_sign_in_note, host(issuer)))
                } else SupportingText(R.string.provider_unregistered)
            }
            if (canAskForServers) ServerChoice(entry, state.catalogs[entry.provider], inUse, advanced, busy, actions)
            // A suspended row already says what to do. Leases renew on their own, so their times stay out of the card.
            if (!entry.suspended && entry.choice != null && !inUse && !busy) Button(onClick = { actions.useProvider(entry.provider) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.provider_select)) }
        }
    }
}

/** Refresh, details and removal, out of the way of the next step. */
@Composable
private fun ServiceMenu(entry: ServiceEntry, enabled: Boolean, canRefresh: Boolean, onRefresh: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.provider_more, entry.label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canRefresh) DropdownMenuItem(text = { Text(stringResource(R.string.provider_refresh_servers)) }, enabled = enabled,
                onClick = { open = false; onRefresh() })
            DropdownMenuItem(text = { Text(stringResource(R.string.provider_details)) }, onClick = { open = false; onDetails() })
            // Removal stays available during other work, because it also ends that work.
            DropdownMenuItem(text = { Text(stringResource(R.string.provider_remove), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onRemove() })
        }
    }
}

@Composable
private fun Working(@StringRes text: Int, @StringRes action: Int, onAction: () -> Unit) {
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
    SupportingText(text)
    TextButton(onClick = onAction) { Text(stringResource(action)) }
}

/** A short label and its value on one line, wrapping as one sentence at large text. */
@Composable
private fun LabeledValue(@StringRes label: Int, value: String) {
    Text("${stringResource(label)}: $value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Lists the account's servers by place, one row per country. A country with
 * several servers opens into its list. Choosing a server only records the
 * choice: the lease is requested when the relay connects, and a running relay
 * moves to the new server at once.
 */
@Composable
private fun ServerChoice(entry: ServiceEntry, catalog: ProviderCodec.Catalog?, inUse: Boolean, advanced: Boolean, busy: Boolean, actions: ServiceActions) {
    val chosen = entry.choice
    if (catalog == null) {
        // The list is not loaded yet, but the chosen server is known from the last one.
        if (chosen != null) ServerRow(chosen.server.country, chosen.server.label, chosen.server.host, chosen.server.port, advanced, inUse,
            enabled = false, showPlace = true) {}
        // With a server chosen, the list is a refresh and Connect stays the next step.
        if (chosen == null || entry.suspended) Button(onClick = { actions.catalog(entry.provider) }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.provider_servers)) }
        else TextButton(onClick = { actions.catalog(entry.provider) }, enabled = !busy) { Text(stringResource(R.string.provider_servers)) }
        return
    }
    val servers = catalog.services.flatMap { service -> service.servers.map { service.id to it } }
    if (servers.isEmpty()) SupportingText(R.string.provider_no_entitlement)
    if (chosen == null && servers.isNotEmpty()) Text(stringResource(R.string.provider_choose_server), style = MaterialTheme.typography.titleSmall)
    fun isChosen(choice: Pair<String, ProviderCodec.Server>) = chosen?.service == choice.first && chosen.server.id == choice.second.id
    // Groups by country in order of first appearance. Servers without a country form one group.
    servers.indices.groupBy { servers[it].second.country }.forEach { (country, indices) ->
        key(country) {
            val members = indices.map { servers[it] }
            fun choose(member: Pair<String, ProviderCodec.Server>) = actions.choose(entry.provider, member.first, member.second.id)
            if (members.size == 1) {
                val member = members.single()
                ServerOption(member.second, advanced, isChosen(member), inUse, busy, showPlace = true) { choose(member) }
            } else {
                var expanded by remember { mutableStateOf(members.any(::isChosen)) }
                CountryRow(country, expanded) { expanded = !expanded }
                if (expanded) Column(Modifier.padding(start = 40.dp)) {
                    members.forEach { member -> ServerOption(member.second, advanced, isChosen(member), inUse, busy, showPlace = false) { choose(member) } }
                }
            }
        }
    }
}

/** One server in the list. Choosing the server already in use does nothing. */
@Composable
private fun ServerOption(server: ProviderCodec.Server, advanced: Boolean, chosen: Boolean, inUse: Boolean, busy: Boolean,
                         showPlace: Boolean, onChoose: () -> Unit) {
    ServerRow(server.country, server.label, server.host, server.port, advanced, chosen && inUse, enabled = !busy, showPlace = showPlace) {
        if (!chosen || !inUse) onChoose()
    }
}

/** A country with several servers: its flag and name, opening into the list. */
@Composable
private fun CountryRow(country: String?, expanded: Boolean, onToggle: () -> Unit) {
    val place = serverPlace(country)
    val name = place.name ?: stringResource(R.string.provider_location_unknown)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(if (expanded) R.string.provider_servers_hide else R.string.provider_servers_show, name),
                role = Role.Button, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp))
        LocationMark(place)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerRow(country: String?, label: String, host: String, port: Int, advanced: Boolean, selected: Boolean, enabled: Boolean,
                      showPlace: Boolean, onSelect: () -> Unit) {
    val address = "$host:$port".takeIf { advanced }
    // Inside an opened country the country is already said, so the server's own name leads.
    if (!showPlace) ChoiceRow(label, address, selected, enabled, null, onSelect)
    else {
        val place = serverPlace(country)
        ChoiceRow(place.name ?: stringResource(R.string.provider_location_unknown),
            if (advanced) "$label\n$address" else label, selected, enabled, place, onSelect)
    }
}

/** The provider and its issuer are distinct identities, so each keeps its own label even when the origins match. */
@Composable
private fun ServiceDetailsDialog(entry: ServiceEntry, advanced: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(entry.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailFact(R.string.provider_label_provider, entry.provider)
                entry.issuer?.let { DetailFact(R.string.provider_label_sign_in, it) }
                entry.selection?.let { selected ->
                    DetailFact(R.string.provider_label_address, "${selected.host}:${selected.port}")
                    SupportingText(R.string.provider_server_address_note)
                    if (advanced) DetailFact(R.string.provider_label_identity, selected.identity)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
}

@Composable
private fun DetailFact(@StringRes label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Opens Android's link settings for Ito, where link handling can be turned on. */
@Composable
private fun LinkSettingsButton() {
    val context = LocalContext.current
    TextButton(onClick = {
        try { context.startActivity(ProviderAppLinks.settingsIntent(context)) }
        catch (_: ActivityNotFoundException) { }
    }) { Text(stringResource(R.string.provider_link_settings)) }
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
    "link_unverified" -> R.string.provider_error_link_unverified
    "revoked" -> R.string.provider_error_revoked
    "timeout" -> R.string.provider_error_timeout
    "conflict" -> R.string.provider_error_conflict
    else -> R.string.provider_error_other
}

private fun host(origin: String) = origin.removePrefix("https://")

/** Provider origins are URLs: no capitals, suggestions or autocorrection. */
private val urlKeyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

/** Leases last up to an hour, so the row says when this one ends and how to renew it. */
@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddProviderDialog(suggestedProvider: String, suggestedIssuer: String, onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var origin by remember { mutableStateOf(suggestedProvider) }
    var issuer by remember { mutableStateOf(suggestedIssuer) }
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
                    // External labels can wrap at large text sizes without entering the field border.
                    val originLabel = stringResource(R.string.provider_origin)
                    Text(originLabel)
                    OutlinedTextField(origin, { if (it.length <= 512) origin = it },
                        Modifier.fillMaxWidth().semantics { contentDescription = originLabel }, singleLine = true,
                        keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Next))
                    val issuerLabel = stringResource(R.string.provider_issuer)
                    Text(issuerLabel)
                    OutlinedTextField(issuer, { if (it.length <= 512) issuer = it },
                        Modifier.fillMaxWidth().semantics { contentDescription = issuerLabel }, singleLine = true,
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
