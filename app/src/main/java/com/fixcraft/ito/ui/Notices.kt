package com.fixcraft.ito.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.R
import com.fixcraft.ito.messaging.MessengerFailure

/** Shows each notice from [CallsModel] and each messenger refusal once, through [show]. */
@Composable
fun CallsNotice(model: CallsModel, show: suspend (String) -> Unit) {
    val notice by model.notice.collectAsStateWithLifecycle()
    val text = notice?.let { stringResource(it) }
    LaunchedEffect(text) {
        if (text != null) {
            show(text)
            model.dismissNotice()
        }
    }
    val messenger by model.messenger.state.collectAsStateWithLifecycle()
    val failureText = when (messenger.failure) {
        MessengerFailure.None -> null
        MessengerFailure.Offline -> R.string.chat_error_offline
        MessengerFailure.Storage -> R.string.chat_error_storage
        MessengerFailure.PairAgain -> R.string.chat_error_pair_again
        MessengerFailure.InvalidCode -> R.string.chat_error_code
        MessengerFailure.OtherRelay -> R.string.chat_error_relay
        MessengerFailure.Unverified -> R.string.chat_error_unverified
        MessengerFailure.Capacity -> R.string.chat_error_capacity
        MessengerFailure.Transport -> R.string.chat_error_transport
        MessengerFailure.Native -> R.string.chat_error_native
    }?.let { stringResource(it) }
    LaunchedEffect(failureText) {
        if (failureText != null) { show(failureText); model.messenger.dismissFailure() }
    }
}
