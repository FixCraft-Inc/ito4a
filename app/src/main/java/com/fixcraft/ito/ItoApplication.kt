package com.fixcraft.ito

import android.app.Application
import com.fixcraft.ito.call.CallBook
import com.fixcraft.ito.call.CallController
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.integration.createServices
import com.fixcraft.ito.integration.transportServices
import com.fixcraft.ito.security.AppLockController
import com.fixcraft.ito.content.LocalContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ItoApplication : Application() {
    val appLock by lazy { AppLockController(this) }
    val content by lazy { LocalContentRepository(this, appLock) }
    private val audioForeground = MutableStateFlow(false)
    val foregroundAudio: StateFlow<Boolean> = audioForeground
    var callForeground: Boolean
        get() = audioForeground.value
        set(value) { audioForeground.value = value }
    private val serviceOwner = lazy { createServices(this, appLock::requireAccess, appLock::awaitAccess) }
    val services get() = serviceOwner.value
    val connections: ConnectionController by lazy {
        ConnectionController(this, services = transportServices(services), requireAccess = appLock::requireAccess)
    }
    /** Opened by its first user on an I/O thread: CallBook.open reads the Keystore and storage. */
    val callBook: CallBook by lazy { CallBook(this, appLock::requireAccess) }
    val calls: CallController by lazy {
        CallController(connections, callBook, requireAccess = appLock::requireAccess, profile = 2,
            canStart = { !callForeground },
            onEnded = { view ->
                check(view.peerAuthenticated)
                view.peer?.let { key ->
                content.recordCall(key, view.initiator, if (view.localFailure == null) view.result else -1000,
                    System.currentTimeMillis() / 1000, view.durationMs)
            } },
            onMessage = { id, peer, outgoing, text ->
                content.recordMessage(id, peer, outgoing, text, System.currentTimeMillis() / 1000)
            })
    }

    override fun onTerminate() {
        if (serviceOwner.isInitialized()) serviceOwner.value.close()
        super.onTerminate()
    }
}
