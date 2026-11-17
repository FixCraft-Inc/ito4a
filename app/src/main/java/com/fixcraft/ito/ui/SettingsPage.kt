package com.fixcraft.ito.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.fixcraft.ito.BuildConfig
import com.fixcraft.ito.R
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation

/**
 * Settings show only controls that change behaviour. Call routing and audio
 * quality are fixed in this version, so they appear as plain statements.
 * Direct calls and lower-data audio return as controls when they exist.
 * [profile] is the owner's row at the top, supplied by the app shell.
 */
@Composable
fun SettingsPage(preferences: Preferences, onPresentation: (Presentation) -> Unit = {},
                 lockEnabled: Boolean = false, onLock: () -> Unit = {}, onLicenses: () -> Unit = {},
                 onBackground: (Boolean) -> Unit = {}, onNotificationText: (Boolean) -> Unit = {},
                 profile: @Composable () -> Unit = {}) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        onBackground(allowed)
    }
    val context = LocalContext.current
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 8.dp)) {
        profile()
        ListHeading(R.string.settings_privacy)
        // Turning the lock on or off starts platform authentication. The switch
        // shows the stored state and changes only after authentication succeeds.
        SwitchRow(R.drawable.ic_lock, R.string.app_lock, R.string.app_lock_description, lockEnabled) { onLock() }
        Text(stringResource(R.string.app_lock_requirements), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        SwitchRow(R.drawable.ic_notifications, R.string.messaging_background, R.string.messaging_background_description,
            preferences.backgroundMessages) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else onBackground(enabled)
        }
        SwitchRow(R.drawable.ic_chat, R.string.messaging_text, R.string.messaging_text_description, preferences.notificationText,
            onNotificationText)
        ListHeading(R.string.calls)
        if (Build.VERSION.SDK_INT >= 34) ListRow(R.drawable.ic_call, stringResource(R.string.incoming_full_screen),
            Modifier.clickable(role = Role.Button) {
                try { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    ("package:" + context.packageName).toUri())) }
                catch (_: ActivityNotFoundException) { /* Incoming notifications remain available. */ }
            }, supporting = stringResource(R.string.incoming_full_screen_description))
        ListRow(R.drawable.ic_hub, stringResource(R.string.call_route), supporting = stringResource(R.string.call_route_value))
        ListRow(R.drawable.ic_mic, stringResource(R.string.call_audio), supporting = stringResource(R.string.call_audio_value))
        ListHeading(R.string.settings_display)
        SwitchRow(R.drawable.ic_tune, R.string.advanced_mode, R.string.advanced_mode_description,
            preferences.presentation == Presentation.Advanced) { enabled ->
            onPresentation(if (enabled) Presentation.Advanced else Presentation.Simple)
        }
        ListHeading(R.string.settings_about)
        ListRow(R.drawable.ic_info, stringResource(R.string.about_version), supporting = BuildConfig.VERSION_NAME)
        ListRow(R.drawable.ic_description, stringResource(R.string.about_licenses), Modifier.clickable(role = Role.Button, onClick = onLicenses))
        SourceLink(R.string.about_source, SOURCE_URL)
        SourceLink(R.string.about_core_source, CORE_SOURCE_URL)
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
    }
}

/** One toggle target that carries its state. The Switch has no handler of its own. */
@Composable
private fun SwitchRow(@DrawableRes icon: Int, @StringRes title: Int, @StringRes description: Int, checked: Boolean,
                      onChange: (Boolean) -> Unit) {
    ListRow(icon, stringResource(title), Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        supporting = stringResource(description)) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One place this build's source is published, with its address on screen in case no browser opens it. */
@Composable
private fun SourceLink(@StringRes label: Int, url: String) {
    val context = LocalContext.current
    ListRow(R.drawable.ic_code, stringResource(label), Modifier.clickable(role = Role.Button) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        catch (_: ActivityNotFoundException) { /* The address stays on screen. */ }
    }, supporting = url.removePrefix("https://"))
}
