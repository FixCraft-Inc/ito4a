package com.fixcraft.ito.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Runs an action once Ito may use the microphone, asking Android first when
 * needed. Call and Answer both pass through it, so audio can start as soon as
 * the call connects, and a refusal starts nothing.
 */
@Composable
internal fun rememberMicrophoneGate(onRefused: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val refused by rememberUpdatedState(onRefused)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else refused()
    }
    return remember(launcher) {
        { action ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
            else { pending = action; launcher.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
}
