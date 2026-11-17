package com.fixcraft.ito.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookException
import com.fixcraft.ito.call.BookRefusal
import com.fixcraft.ito.call.CallFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen-side access to the process's CallBook and CallController. Book
 * operations read the Keystore and storage, so they run on the I/O
 * dispatcher. Refusals become a resource string in [notice].
 */
class CallsModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ItoApplication
    val book = app.callBook.state
    val view = app.calls.view
    val connection = app.connections.state
    val messenger get() = app.messenger
    val incoming get() = app.ringing.incoming
    val history get() = app.chatHistory
    private val mutableNotice = MutableStateFlow<Int?>(null)
    val notice = mutableNotice.asStateFlow()

    init { open() }

    fun open() { viewModelScope.launch(Dispatchers.IO) {
        try { app.messenger.initialize() }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { mutableNotice.value = R.string.contact_change_failed }
    } }

    fun dismissNotice() { mutableNotice.value = null }
    fun message(onSuccess: () -> Unit = {}, onFinished: () -> Unit = {}, action: com.fixcraft.ito.messaging.Messenger.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { app.messenger.action(); withContext(Dispatchers.Main) { onSuccess() } }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* Messenger publishes a typed, content-free refusal. */ }
            finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onFinished() } }
        }
    }
    fun rename(key: ByteArray, name: String) { viewModelScope.launch { change { rename(key, name) } } }

    /** A scanned text that looked like an invitation but is not one. */
    fun refuseInvitation() { mutableNotice.value = R.string.refusal_invitation_text }

    /** A code scanned to verify a contact belongs to someone else. */
    fun refuseScannedKey() { mutableNotice.value = R.string.refusal_scanned_key }

    fun createIdentity() { viewModelScope.launch { change { createIdentity() } } }

    /** The new contact's key, or null after a refusal shown in [notice]. */
    suspend fun addContact(code: String, name: String): ByteArray? = change { addContact(code, name.trim()) }

    suspend fun verify(key: ByteArray, compared: String): Boolean = change { verify(key, compared) } != null

    suspend fun replace(oldKey: ByteArray, newCode: String): ByteArray? = change { replace(oldKey, newCode) }

    fun block(key: ByteArray) { viewModelScope.launch { change { block(key) } } }

    fun call(key: ByteArray) = app.ringing.call(key)
    fun acceptRing(token: String) = app.ringing.accept(token)
    fun declineRing(token: String) = app.ringing.decline(token)

    fun decide(accept: Boolean) = app.calls.decide(accept)
    fun hangup() = app.calls.hangup()
    fun cancel() = app.calls.cancel()
    fun sendTestPayload() = app.calls.sendTestPayload()
    fun startAudio() {
        try { app.appLock.requireAccess(); com.fixcraft.ito.call.CallAudioService.start(app) }
        catch (_: Exception) { mutableNotice.value = R.string.audio_start_failed }
    }
    fun muteAudio() = com.fixcraft.ito.call.CallAudioService.action(app, com.fixcraft.ito.call.CallAudioService.MUTE)
    fun speakerAudio() = com.fixcraft.ito.call.CallAudioService.action(app, com.fixcraft.ito.call.CallAudioService.SPEAKER)
    fun dismissCall() = app.calls.dismiss()

    private suspend fun <T> change(action: com.fixcraft.ito.call.CallBook.() -> T): T? = withContext(Dispatchers.IO) {
        try {
            app.callBook.action().also { app.messenger.initialize() }
        } catch (refusal: BookException) {
            mutableNotice.value = refusalText(refusal.refusal)
            null
        } catch (_: CallFailure) {
            mutableNotice.value = R.string.contact_change_failed
            null
        } catch (_: IllegalStateException) {
            // The book is not open or its last publication is unresolved.
            mutableNotice.value = R.string.contact_change_failed
            null
        }
    }

    companion object {
        fun refusalText(refusal: BookRefusal) = when (refusal) {
            BookRefusal.InvalidCode -> R.string.refusal_invalid_code
            BookRefusal.InvalidName -> R.string.refusal_invalid_name
            BookRefusal.OwnCode -> R.string.refusal_own_code
            BookRefusal.Exists -> R.string.refusal_exists
            BookRefusal.NotVerifiedFingerprint -> R.string.refusal_fingerprint
            BookRefusal.Blocked -> R.string.refusal_blocked
            BookRefusal.Capacity -> R.string.refusal_capacity
            BookRefusal.Storage -> R.string.refusal_storage
        }
    }
}
