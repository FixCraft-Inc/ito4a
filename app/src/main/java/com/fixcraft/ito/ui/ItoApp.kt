package com.fixcraft.ito.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.MainViewModel
import com.fixcraft.ito.PreferencesState
import com.fixcraft.ito.R
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.SavedService
import java.util.UUID

private enum class Page(@StringRes val label: Int, val icon: Int) {
    Calls(R.string.calls, R.drawable.ic_call),
    Connections(R.string.connections, R.drawable.ic_link),
    Settings(R.string.settings, R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItoApp(state: PreferencesState, notice: Int?, model: MainViewModel) {
    val connectionState by model.connections.state.collectAsStateWithLifecycle()
    val services by model.services.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.Calls) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = notice?.let { stringResource(it) }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            model.dismissNotice()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (page == Page.Calls) stringResource(R.string.app_name) else stringResource(page.label)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            when (state) {
                PreferencesState.Loading -> Column(
                    Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.loading))
                }
                PreferencesState.Unreadable -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.storage_error))
                    TextButton(onClick = model::retryRead) { Text(stringResource(R.string.retry)) }
                }
                is PreferencesState.Ready -> when (page) {
                    Page.Calls -> CallsPage { page = Page.Connections }
                    Page.Connections -> ConnectionsPage(state.preferences, model::addService, model::removeService, connectionState, model.connections, services, model.services)
                    Page.Settings -> SettingsPage(state.preferences, model::setRouting, model::setAudio)
                }
            }
        }
    }
}

@Composable
private fun CallsPage(onConnections: () -> Unit) {
    PageColumn {
        Icon(painterResource(R.drawable.ic_ito), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.your_voice_your_way), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        SupportingText(R.string.calls_intro)
        Spacer(Modifier.height(32.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Text(stringResource(R.string.foundation_notice), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.no_calls), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        SupportingText(R.string.no_calls_description)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onConnections) { Text(stringResource(R.string.manage_connections)) }
    }
}

@Composable
private fun ConnectionsPage(preferences: Preferences, onAdd: (SavedService) -> Unit, onRemove: (UUID) -> Unit,
    connection: com.fixcraft.ito.transport.ConnectionState, controller: com.fixcraft.ito.transport.ConnectionController,
    services: com.fixcraft.yume.consumer.ServiceUiState,
    providerController: com.fixcraft.yume.consumer.ServiceController<com.fixcraft.ito.provider.ItoProviderSession>) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SavedService?>(null) }
    PageColumn {
        ConnectionPanel(connection, controller, services.selection != null && !services.manual,
            services.loaded && services.busy.isEmpty())
        ProviderPanel(services, providerController, controller)
        Spacer(Modifier.height(24.dp))
        SupportingText(R.string.connection_intro)
        Spacer(Modifier.height(24.dp))
        if (preferences.services.isEmpty()) {
            Text(stringResource(R.string.no_connections), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            SupportingText(R.string.no_connections_description)
            Spacer(Modifier.height(24.dp))
        }
        preferences.services.forEach { service ->
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                Text(service.origin.value, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SupportingText(R.string.saved_not_enrolled)
                TextButton(onClick = { removing = service }) {
                    Text(stringResource(R.string.remove_named_service, service.name))
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { adding = true }, enabled = preferences.services.size < Preferences.MAX_SERVICES) {
            Text(stringResource(R.string.add_service))
        }
    }
    if (adding) AddServiceDialog(
        onDismiss = { adding = false },
        onSave = { onAdd(it); adding = false },
    )
    removing?.let { service ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_service)) },
            text = { Text(stringResource(R.string.remove_service_description)) },
            confirmButton = {
                TextButton(onClick = { onRemove(service.id); removing = null }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun PageColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        content = content,
    )
}

@Composable
internal fun SupportingText(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PreferenceChoice(title: Int, description: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SupportingText(description)
        }
    }
}
