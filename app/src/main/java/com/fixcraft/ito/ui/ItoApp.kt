package com.fixcraft.ito.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.MainViewModel
import com.fixcraft.ito.PreferencesState
import com.fixcraft.ito.R
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.SavedService
import java.util.UUID

enum class Page(@StringRes val label: Int, val icon: Int) {
    Calls(R.string.calls, R.drawable.ic_call),
    Service(R.string.connections, R.drawable.ic_link),
    Settings(R.string.settings, R.drawable.ic_settings),
}

@Composable
fun ItoApp(state: PreferencesState, notice: Int?, model: MainViewModel, calls: CallsModel,
    page: Page, onPage: (Page) -> Unit,
    lockEnabled: Boolean = false, onLock: () -> Unit = {}) {
    val connectionState by model.connections.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val noticeText = notice?.let { stringResource(it) }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            model.dismissNotice()
        }
    }
    CallsNotice(calls) { snackbar.showSnackbar(it) }
    // The Calls tab keeps its screen while another tab is shown. Saved state
    // holds only public contact codes.
    var callsRoute by rememberSaveable { mutableStateOf(CALLS_HOME) }
    val bar = if (page == Page.Calls) callsBar(calls, callsRoute) else CallsBar(null, null)
    val select = { destination: Page ->
        // Choosing the current tab again returns it to its first screen.
        if (destination == Page.Calls && page == Page.Calls) callsRoute = CALLS_HOME
        onPage(destination)
    }
    val title = bar.title ?: if (page == Page.Calls) stringResource(R.string.app_name) else stringResource(page.label)
    ItoFrame(title, bar.back?.let { back -> { callsRoute = back } }, page, snackbar, select) {
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
            is PreferencesState.Ready -> {
                val advanced = state.preferences.presentation == Presentation.Advanced
                when (page) {
                    Page.Calls -> CallsPage(calls, callsRoute, { callsRoute = it }, advanced) { onPage(Page.Service) }
                    Page.Service -> PageColumn {
                        ServiceConnectionPanel(connectionState, model.connections, model.services, advanced)
                        // Saved addresses predate enrollment and do nothing on their own.
                        if (advanced) Section { SavedAddresses(state.preferences, model::addService, model::removeService) }
                    }
                    Page.Settings -> SettingsPage(state.preferences, model::setPresentation, lockEnabled, onLock)
                }
            }
        }
    }
}

/** The top bar, destinations and snackbar around every page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItoFrame(title: String, back: (() -> Unit)?, page: Page, snackbar: SnackbarHostState,
                      onSelect: (Page) -> Unit, content: @Composable () -> Unit) {
    // A short, wide window (a phone turned sideways) keeps its height for content.
    val rail = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
    val scroll = key(page) { TopAppBarDefaults.pinnedScrollBehavior() }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    back?.let { goBack ->
                        IconButton(onClick = goBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!rail) NavigationBar {
                Page.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { onSelect(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { insets ->
        Row(Modifier.fillMaxSize().padding(insets)) {
            if (rail) NavigationRail {
                Page.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = page == destination,
                        onClick = { onSelect(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() }
        }
    }
}

@Composable
internal fun SavedAddresses(preferences: Preferences, onAdd: (SavedService) -> Unit, onRemove: (UUID) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SavedService?>(null) }
    Column {
        SectionHeading(R.string.saved_addresses)
        SupportingText(R.string.no_connections_description)
        preferences.services.forEach { service ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                Text(service.origin.value, style = MaterialTheme.typography.bodyMedium)
                SupportingText(R.string.saved_not_enrolled)
                TextButton(onClick = { removing = service }) {
                    Text(stringResource(R.string.remove_named_service, service.name))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Saving an address has no effect on its own, so it is not a primary action.
        OutlinedButton(onClick = { adding = true }, enabled = preferences.services.size < Preferences.MAX_SERVICES,
            modifier = Modifier.heightIn(min = 48.dp)) {
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
            text = { Text(stringResource(R.string.remove_service_description), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { onRemove(service.id); removing = null }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
internal fun SupportingText(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A section heading within a page. The page title itself is in the top bar. */
@Composable
internal fun SectionTitle(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() })
}

/** How a status card reads. Each tone always comes with words, never colour alone. */
internal enum class StatusTone { Good, Neutral, Problem }

/** The one card at the top of a page that says what state it is in and what to do. */
@Composable
internal fun StatusCard(tone: StatusTone, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, onContainer) = when (tone) {
        StatusTone.Good -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.Neutral -> colors.surfaceContainerHigh to colors.onSurface
        StatusTone.Problem -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container, contentColor = onContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
