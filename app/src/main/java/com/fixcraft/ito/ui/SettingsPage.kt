package com.fixcraft.ito.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
 */
@Composable
fun SettingsPage(preferences: Preferences, onPresentation: (Presentation) -> Unit = {},
                 lockEnabled: Boolean = false, onLock: () -> Unit = {}, onLicenses: () -> Unit = {}) {
    PageColumn {
        SectionTitle(R.string.settings_privacy)
        // Turning the lock on or off starts platform authentication. The switch
        // shows the stored state and changes only after authentication succeeds.
        SwitchRow(R.string.app_lock, R.string.app_lock_description, lockEnabled) { onLock() }
        SupportingText(R.string.app_lock_requirements)
        Spacer(Modifier.height(32.dp))
        SectionTitle(R.string.calls)
        StatusRow(R.string.call_route, R.string.call_route_value)
        StatusRow(R.string.call_audio, R.string.call_audio_value)
        Spacer(Modifier.height(24.dp))
        SectionTitle(R.string.settings_display)
        SwitchRow(R.string.advanced_mode, R.string.advanced_mode_description,
            preferences.presentation == Presentation.Advanced) { enabled ->
            onPresentation(if (enabled) Presentation.Advanced else Presentation.Simple)
        }
        Spacer(Modifier.height(24.dp))
        SectionTitle(R.string.settings_about)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.about_version), style = MaterialTheme.typography.titleMedium)
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SupportingText(R.string.about_license)
        TextButton(onClick = onLicenses) { Text(stringResource(R.string.about_licenses)) }
        SourceLink(R.string.about_source, SOURCE_URL)
        SourceLink(R.string.about_core_source, CORE_SOURCE_URL)
    }
}

/** One toggle target that carries its state. The Switch has no handler of its own. */
@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            SupportingText(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One place this build's source is published, with its address on screen in case no browser opens it. */
@Composable
private fun SourceLink(@StringRes label: Int, url: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        catch (_: ActivityNotFoundException) { /* The address stays on screen. */ }
    }) { Text(stringResource(label)) }
    Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp))
}

/** A fixed fact about this version, read as a title and its value. */
@Composable
private fun StatusRow(@StringRes title: Int, @StringRes value: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        SupportingText(value)
    }
}
