package com.fixcraft.ito.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.MainViewModel
import com.fixcraft.ito.PreferencesState
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallOutcomes
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallView
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.SavedService
import kotlinx.coroutines.delay
import java.util.UUID

enum class Page(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Calls(R.string.chats, R.drawable.ic_chat, R.drawable.ic_chat_filled),
    Service(R.string.connections, R.drawable.ic_hub, R.drawable.ic_hub_filled),
    Settings(R.string.settings, R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

/** What every full-screen page shares: the notice host and the bar back to a minimized call. */
internal class ScreenChrome(val snackbar: SnackbarHostState, val callBar: @Composable () -> Unit)

/** Where the Settings tab is. Saved state holds only these names and packaged notice file names. */
internal sealed interface SettingsRoute {
    /** Where Back goes, or null on the first screen. */
    val parent: SettingsRoute?

    data object Home : SettingsRoute { override val parent: SettingsRoute? = null }
    data object Profile : SettingsRoute { override val parent: SettingsRoute = Home }
    data object Licenses : SettingsRoute { override val parent: SettingsRoute = Home }
    data class Notice(val file: String) : SettingsRoute { override val parent: SettingsRoute = Licenses }

    companion object {
        val Saver: Saver<SettingsRoute, String> = Saver(
            save = { route -> when (route) { Home -> "home"; Profile -> "profile"; Licenses -> "licenses"; is Notice -> "notice|" + route.file } },
            restore = { text -> when {
                text == "profile" -> Profile
                text == "licenses" -> Licenses
                text.startsWith("notice|") && text.length > 7 -> Notice(text.substring(7))
                else -> Home
            } },
        )
    }
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
    // The Chats tab keeps its screen while another tab is shown. Saved state
    // holds only public contact codes.
    var callsRoute by rememberSaveable(stateSaver = ChatsRoute.Saver) { mutableStateOf<ChatsRoute>(ChatsRoute.Home) }
    var settingsRoute by rememberSaveable(stateSaver = SettingsRoute.Saver) { mutableStateOf<SettingsRoute>(SettingsRoute.Home) }
    val call by calls.view.collectAsStateWithLifecycle()
    val book by calls.book.collectAsStateWithLifecycle()
    val contacts = (book as? BookState.Ready)?.contacts.orEmpty()
    // A minimized call leaves chats usable. A new attempt always opens its screen.
    var minimized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(call.phase.active) { if (call.phase.active) minimized = false }
    val endedWhileMinimized = if (minimized && call.phase == CallPhase.Ended) stringResource(outcomeText(CallOutcomes.of(call))) else null
    LaunchedEffect(endedWhileMinimized) {
        if (endedWhileMinimized != null) {
            calls.dismissCall()
            minimized = false
            snackbar.showSnackbar(endedWhileMinimized)
        }
    }
    val ready = state as? PreferencesState.Ready
    val advanced = ready?.preferences?.presentation == Presentation.Advanced
    val callBar: @Composable () -> Unit = {
        if (minimized && call.phase.active) ReturnToCallBar(call, contacts) { minimized = false }
    }
    val chrome = ScreenChrome(snackbar, callBar)

    if (ready != null && call.phase != CallPhase.Idle && !minimized) {
        // Back minimizes a running call and closes a finished one.
        BackHandler { if (call.phase.active) minimized = true else calls.dismissCall() }
        CallScreen(call, contacts, calls, advanced, onService = { onPage(Page.Service) },
            onMinimize = { minimized = true }, snackbar = snackbar)
        return
    }
    if (ready != null && page == Page.Calls && callsRoute != ChatsRoute.Home) {
        ChatsDetail(calls, callsRoute, { callsRoute = it }, advanced, chrome, onService = { onPage(Page.Service) })
        return
    }

    val select = { destination: Page ->
        // Choosing the current tab again returns it to its first screen.
        if (destination == Page.Calls && page == Page.Calls) callsRoute = ChatsRoute.Home
        if (destination == Page.Settings && page == Page.Settings) settingsRoute = SettingsRoute.Home
        onPage(destination)
    }
    val settingsOpen = settingsRoute.takeIf { page == Page.Settings } ?: SettingsRoute.Home
    val title = when {
        page == Page.Calls -> stringResource(R.string.app_name)
        else -> when (settingsOpen) {
            SettingsRoute.Home -> stringResource(page.label)
            SettingsRoute.Licenses -> stringResource(R.string.about_licenses)
            SettingsRoute.Profile -> stringResource(R.string.chat_your_profile)
            is SettingsRoute.Notice -> noticeTitle(settingsOpen.file)
        }
    }
    val closeSettings = { settingsRoute = settingsRoute.parent ?: SettingsRoute.Home }
    BackHandler(enabled = settingsOpen != SettingsRoute.Home, onBack = closeSettings)
    val messenger by calls.messenger.state.collectAsStateWithLifecycle()
    val identity = (book as? BookState.Ready)?.identity != null
    val chatsHome = page == Page.Calls && ready != null && identity
    ItoFrame(title, closeSettings.takeIf { settingsOpen != SettingsRoute.Home }, page, snackbar, select,
        // Like a messenger's title bar: say when chats cannot reach the service, and open its tab on tap.
        subtitle = if (!chatsHome) null else chatsReach(connectionState, messenger.online, messenger.stopped)
            .takeIf { it != ChatsReach.Online }?.let { stringResource(it.label) },
        onSubtitle = { onPage(Page.Service) },
        actions = {
            if (chatsHome) IconButton(onClick = { callsRoute = ChatsRoute.Scan() }) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner), stringResource(R.string.chat_scan))
            }
        },
        callBar = callBar) {
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
                Page.Calls -> ChatsPage(calls, { callsRoute = it }) { onPage(Page.Service) }
                Page.Service -> PageColumn {
                    ServiceConnectionPanel(connectionState, model.connections, model.services, advanced)
                    // Saved addresses predate enrollment and do nothing on their own.
                    if (advanced) Section { SavedAddresses(state.preferences, model::addService, model::removeService) }
                }
                Page.Settings -> when (settingsOpen) {
                    SettingsRoute.Home -> SettingsPage(state.preferences, model::setPresentation, lockEnabled, onLock,
                        onLicenses = { settingsRoute = SettingsRoute.Licenses }, onBackground = model::setBackgroundMessages,
                        onNotificationText = model::setNotificationText,
                        profile = { OwnProfileRow(calls) { settingsRoute = SettingsRoute.Profile } })
                    SettingsRoute.Licenses -> LicensesPage { settingsRoute = SettingsRoute.Notice(it) }
                    SettingsRoute.Profile -> OwnProfile(calls)
                    is SettingsRoute.Notice -> NoticePage(settingsOpen.file)
                }
            }
        }
    }
}

/** The tab bar, top bar and snackbar around every tab's first screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItoFrame(title: String, back: (() -> Unit)?, page: Page, snackbar: SnackbarHostState,
                      onSelect: (Page) -> Unit, subtitle: String? = null, onSubtitle: () -> Unit = {},
                      actions: @Composable RowScope.() -> Unit = {}, callBar: @Composable () -> Unit = {},
                      content: @Composable () -> Unit) {
    // A short, wide window (a phone turned sideways) keeps its height for content.
    val rail = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
    val scroll = key(page) { TopAppBarDefaults.pinnedScrollBehavior() }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(role = Role.Button, onClick = onSubtitle))
                            }
                        }
                    },
                    navigationIcon = {
                        back?.let { goBack ->
                            IconButton(onClick = goBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        }
                    },
                    actions = actions,
                    scrollBehavior = scroll,
                )
                callBar()
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!rail) NavigationBar {
                Page.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { onSelect(destination) },
                        icon = { Icon(painterResource(if (page == destination) destination.selectedIcon else destination.icon), contentDescription = null) },
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
                        icon = { Icon(painterResource(if (page == destination) destination.selectedIcon else destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() }
        }
    }
}

/** A full-screen page above the tabs, such as a chat or a contact's profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(chrome: ScreenChrome, onBack: () -> Unit, title: @Composable () -> Unit,
                            actions: @Composable RowScope.() -> Unit = {}, bottomBar: @Composable () -> Unit = {},
                            containerColor: Color = MaterialTheme.colorScheme.background,
                            content: @Composable (PaddingValues) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                    },
                    actions = actions,
                )
                chrome.callBar()
            }
        },
        snackbarHost = { SnackbarHost(chrome.snackbar) },
        bottomBar = bottomBar,
        containerColor = containerColor,
        // A custom container such as the chat background is no scheme role, so
        // Material cannot pick its ink. Pages here sit on background-like colours.
        contentColor = MaterialTheme.colorScheme.onBackground,
        content = content,
    )
}

/** Shown while a call is minimized. Tapping it opens the call again. */
@Composable
private fun ReturnToCallBar(call: CallView, contacts: List<com.fixcraft.ito.call.Contact>, onOpen: () -> Unit) {
    val name = call.peer?.let { key -> contacts.firstOrNull { it.key.contentEquals(key) }?.name } ?: stringResource(R.string.unknown_caller)
    val description = stringResource(R.string.call_return, name)
    Surface(color = MaterialTheme.ito.answer, contentColor = MaterialTheme.ito.onAnswer,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = description, onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_call), null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(description, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (call.phase == CallPhase.Media) Text(callDuration(call), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Minutes and seconds since media began, updated every second. */
@Composable
internal fun callDuration(call: CallView): String {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.connectedAt) {
        while (call.connectedAt != 0L) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }
    val seconds = if (call.connectedAt == 0L) 0 else ((now - call.connectedAt) / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
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

/**
 * One short line kept on screen, with the rest of its explanation behind an info button.
 * A warning at a decision stays fully visible instead.
 */
@Composable
internal fun SupportingTextWithInfo(@StringRes text: Int, @StringRes title: Int, @StringRes info: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) { SupportingText(text) }
        InfoButton(title, info)
    }
}

/** A button that shows [text] in a dialog titled [title]. */
@Composable
internal fun InfoButton(@StringRes title: Int, @StringRes text: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    val heading = stringResource(title)
    // Drawn in a 32 dp box so the line keeps the height of its text.
    // Compose still extends the touch target to the minimum 48 dp.
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.more_about, heading),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(heading) },
        text = { Text(stringResource(text), Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } },
    )
}

/** A section heading within a page. The page title itself is in the top bar. */
@Composable
internal fun SectionTitle(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
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

/** A row in a settings-style list: an icon, a title, an optional line below and an optional end. */
@Composable
internal fun ListRow(@DrawableRes icon: Int?, title: String, modifier: Modifier = Modifier, supporting: String? = null,
                     tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, titleColor: Color = Color.Unspecified,
                     trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Spacer(Modifier.width(16.dp)); it() }
    }
}

/** A quiet label above a group of [ListRow]s. */
@Composable
internal fun ListHeading(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp).semantics { heading() })
}
