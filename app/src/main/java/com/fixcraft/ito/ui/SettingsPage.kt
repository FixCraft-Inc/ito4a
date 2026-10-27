package com.fixcraft.ito.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R
import com.fixcraft.ito.domain.AudioPreference
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.RoutingPreference

@Composable
fun SettingsPage(preferences: Preferences, onRouting: (RoutingPreference) -> Unit, onAudio: (AudioPreference) -> Unit) {
    var confirmingDirect by remember { mutableStateOf(false) }
    val direct = preferences.routing == RoutingPreference.DirectAllowed
    PageColumn {
        Text(stringResource(R.string.routing), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.protected_routing), style = MaterialTheme.typography.titleMedium)
        SupportingText(R.string.protected_routing_description)
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = direct,
                role = Role.Switch,
                onValueChange = { enabled ->
                    if (enabled) confirmingDirect = true else onRouting(RoutingPreference.ProtectedOnly)
                },
            ).padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.direct_routing), style = MaterialTheme.typography.titleMedium)
                SupportingText(R.string.direct_routing_description)
            }
            Switch(checked = direct, onCheckedChange = null)
        }
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.audio_quality), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        SupportingText(R.string.audio_preferences_notice)
        PreferenceChoice(R.string.audio_adaptive, R.string.audio_adaptive_description,
            preferences.audio == AudioPreference.Adaptive) { onAudio(AudioPreference.Adaptive) }
        PreferenceChoice(R.string.audio_economy, R.string.audio_economy_description,
            preferences.audio == AudioPreference.Economy) { onAudio(AudioPreference.Economy) }
        PreferenceChoice(R.string.audio_emergency, R.string.audio_emergency_description,
            preferences.audio == AudioPreference.Emergency) { onAudio(AudioPreference.Emergency) }
        SupportingText(R.string.emergency_disclaimer)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.provider_control), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        SupportingText(R.string.provider_control_description)
    }
    if (confirmingDirect) AlertDialog(
        onDismissRequest = { confirmingDirect = false },
        title = { Text(stringResource(R.string.allow_direct_question)) },
        text = { Text(stringResource(R.string.allow_direct_explanation)) },
        confirmButton = {
            TextButton(onClick = { onRouting(RoutingPreference.DirectAllowed); confirmingDirect = false }) {
                Text(stringResource(R.string.allow_direct))
            }
        },
        dismissButton = { TextButton(onClick = { confirmingDirect = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
