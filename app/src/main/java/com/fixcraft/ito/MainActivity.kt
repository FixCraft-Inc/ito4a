package com.fixcraft.ito

import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.fixcraft.ito.security.LockAction
import com.fixcraft.ito.security.LockChallenge
import com.fixcraft.ito.security.LockState
import com.fixcraft.ito.call.CallPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.fixcraft.ito.ui.CallsModel
import com.fixcraft.ito.ui.ItoApp
import com.fixcraft.ito.ui.ItoTheme
import com.fixcraft.ito.ui.LockedScreen
import com.fixcraft.ito.ui.Page

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val calls: CallsModel by viewModels()
    private val app get() = application as ItoApplication
    private var challenge: LockChallenge? = null
    private var authentication: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            // Keep public navigation outside the subtree removed while locked.
            var page by rememberSaveable { mutableStateOf(Page.Calls) }
            val lock by app.appLock.state.collectAsStateWithLifecycle()
            ItoTheme {
                if (lock == LockState.Disabled || lock == LockState.Unlocked) {
                    val incoming by app.ringing.incoming.collectAsStateWithLifecycle()
                    val state by model.state.collectAsStateWithLifecycle()
                    val notice by model.notice.collectAsStateWithLifecycle()
                    if (incoming != null) com.fixcraft.ito.ui.IncomingCallScreen(requireNotNull(incoming), calls)
                    else ItoApp(state, notice, model, calls, page, { page = it }, lock == LockState.Unlocked) {
                        authenticate(LockAction.Enable.takeIf { lock == LockState.Disabled } ?: LockAction.Disable)
                    }
                } else {
                    val locked = lock == LockState.Locked
                    val foregroundAudio by app.foregroundAudio.collectAsStateWithLifecycle()
                    // Stopping existing borrowers needs no new key access. The
                    // notification's End action may be hidden by Android.
                    val callActive = if (foregroundAudio) {
                        val call by app.calls.view.collectAsStateWithLifecycle()
                        call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
                    } else false
                    val background by app.backgroundForeground.collectAsStateWithLifecycle()
                    val incoming by app.ringing.incoming.collectAsStateWithLifecycle()
                    LockedScreen(locked, canStop = foregroundAudio || background, callActive = callActive,
                        incomingCall = incoming != null, onDecline = { incoming?.let { app.ringing.decline(it.actionToken) } },
                        onUnlock = {
                            if (locked) authenticate(LockAction.Unlock)
                            else lifecycleScope.launch(Dispatchers.IO) { app.appLock.reload() }
                        },
                        onEndCall = app.calls::cancel, onStop = { app.ringing.stop(); app.connections.stop() })
                }
            }
        }
    }

    private fun authenticate(action: LockAction) {
        if (Build.VERSION.SDK_INT < 30 || challenge != null) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            var candidate: LockChallenge? = null
            val prepared = try { withContext(Dispatchers.IO) { app.appLock.prepare(action).also { candidate = it } } }
            catch (cancel: CancellationException) {
                candidate?.let(app.appLock::cancel)
                throw cancel
            }
            catch (_: Exception) {
                Toast.makeText(this@MainActivity, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                app.appLock.cancel(prepared)
                return@launch
            }
            challenge = prepared
            val cancel = CancellationSignal().also { authentication = it }
            val prompt = BiometricPrompt.Builder(this@MainActivity)
                .setTitle(getString(R.string.app_lock))
                .setSubtitle(getString(R.string.app_lock_auth_description))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            try { prompt.authenticate(BiometricPrompt.CryptoObject(prepared.cipher), cancel, mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lifecycleScope.launch {
                            // Credential authentication can cover this Activity.
                            // A proof must not grant app access while it is hidden.
                            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                            try {
                                val cipher = requireNotNull(result.cryptoObject?.cipher)
                                withContext(Dispatchers.IO) { app.appLock.complete(prepared, cipher) }
                                calls.open()
                                model.connections.refresh()
                                model.services.reload()
                            } catch (cancel: CancellationException) {
                                app.appLock.cancel(prepared)
                                throw cancel
                            } catch (_: Exception) {
                                app.appLock.cancel(prepared)
                                Toast.makeText(this@MainActivity, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
                            } finally { challenge = null; authentication = null }
                        }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        app.appLock.cancel(prepared)
                        challenge = null; authentication = null
                    }
                }) } catch (_: Exception) {
                app.appLock.cancel(prepared)
                challenge = null; authentication = null
                Toast.makeText(this@MainActivity, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.activityStarted(this)
    }

    override fun onDestroy() {
        authentication?.cancel()
        challenge?.let(app.appLock::cancel)
        challenge = null
        if (isFinishing) app.activityStopped(this)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        app.activityStopped(this, challenge)
    }
}
