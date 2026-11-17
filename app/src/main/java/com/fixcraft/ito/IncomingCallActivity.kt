package com.fixcraft.ito

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.security.LockState
import com.fixcraft.ito.ui.IncomingCallContent
import com.fixcraft.ito.ui.ItoTheme
import com.fixcraft.ito.ui.LockedScreen

/** A lock-screen window for one authenticated invitation, with no access to app navigation. */
class IncomingCallActivity : ComponentActivity() {
    private val app get() = application as ItoApplication
    private var token by mutableStateOf<String?>(null)
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(TOKEN)
        if (token == null || app.ringing.incoming.value?.actionToken != token) {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val incoming by app.ringing.incoming.collectAsStateWithLifecycle()
            val lock by app.appLock.state.collectAsStateWithLifecycle()
            val book by app.callBook.state.collectAsStateWithLifecycle()
            val call = incoming?.takeIf { token != null && it.actionToken == token }
            if (call == null) {
                androidx.compose.runtime.LaunchedEffect(incoming, token) { finish() }
            } else ItoTheme {
                if (lock == LockState.Disabled || lock == LockState.Unlocked) {
                    val contact = (book as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(call.peer) }
                    IncomingCallContent(contact, { openApp(call.actionToken, accept = true) },
                        { app.ringing.decline(call.actionToken) })
                } else {
                    LockedScreen(lock == LockState.Locked, canStop = false, callActive = false,
                        incomingCall = true, onDecline = { app.ringing.decline(call.actionToken) },
                        onUnlock = { openApp(call.actionToken, accept = false) }, onEndCall = {}, onStop = {})
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val candidate = intent.getStringExtra(TOKEN)
        if (candidate == null || app.ringing.incoming.value?.actionToken != candidate) return
        setIntent(intent)
        token = candidate
    }

    override fun onStart() { super.onStart(); app.activityStarted(this) }
    override fun onStop() { super.onStop(); app.activityStopped(this) }
    override fun onDestroy() {
        if (isFinishing) app.activityStopped(this)
        super.onDestroy()
    }

    private fun openApp(expected: String, accept: Boolean) {
        if (dismissing) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            completeOpen(expected, accept)
            return
        }
        dismissing = true
        // Do not reveal the full application over the device keyguard. A canceled
        // unlock leaves the invitation pending with its original deadline.
        keyguard.requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissing = false
                    completeOpen(expected, accept)
                }
                override fun onDismissCancelled() { dismissing = false }
                override fun onDismissError() { dismissing = false }
            })
    }

    private fun completeOpen(expected: String, accept: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            app.ringing.incoming.value?.actionToken != expected || token != expected) return
        if (accept && app.appLock.state.value in setOf(LockState.Disabled, LockState.Unlocked)) {
            app.ringing.accept(expected)
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object { const val TOKEN = "token" }
}
