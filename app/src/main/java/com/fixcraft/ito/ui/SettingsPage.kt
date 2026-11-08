package com.fixcraft.ito.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
                 lockEnabled: Boolean = false, onLock: () -> Unit = {}) {
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

/** A fixed fact about this version, read as a title and its value. */
@Composable
private fun StatusRow(@StringRes title: Int, @StringRes value: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        SupportingText(value)
    }
}
