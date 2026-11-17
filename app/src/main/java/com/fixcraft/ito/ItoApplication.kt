package com.fixcraft.ito

import android.app.Application
import android.app.Activity
import com.fixcraft.ito.call.CallBook
import com.fixcraft.ito.call.CallController
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.integration.createServices
import com.fixcraft.ito.integration.transportServices
import com.fixcraft.ito.security.AppLockController
import com.fixcraft.ito.content.LocalContentRepository
import com.fixcraft.ito.content.ChatRepository
import com.fixcraft.ito.messaging.Messenger
import com.fixcraft.ito.transport.MailboxLane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.fixcraft.ito.data.PreferencesStore
import com.fixcraft.ito.security.LockState
import com.fixcraft.ito.security.LockChallenge
import com.fixcraft.ito.messaging.MessageNotifications
import com.fixcraft.ito.messaging.MessagingService
import com.fixcraft.ito.messaging.Ringing
import android.content.Intent

class ItoApplication : Application() {
    val appLock by lazy { AppLockController(this) }
    val content by lazy { LocalContentRepository(this, appLock) }
    val chatHistory by lazy { ChatRepository(this, appLock) }
    val notifications by lazy { MessageNotifications(this) }
    val messenger: Messenger by lazy { Messenger(callBook, chatHistory, content, appLock::requireAccess,
        hasAccess = { appLock.state.value in setOf(LockState.Disabled, LockState.Unlocked) },
        onRing = { handle, peer -> ringing.offer(handle, peer) },
        onRingEnd = { peer, end -> ringing.ended(peer, end) },
        onMessage = { peer, text -> notifications.message(peer, text) },
        onSealedActivity = { notifications.sealedActivity() }) }
    val ringing: Ringing by lazy { Ringing(calls, messenger, appLock::requireAccess) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private val visibleActivities = mutableSetOf<Activity>()
    private val backgroundOwner = MutableStateFlow(false)
    val backgroundForeground: StateFlow<Boolean> = backgroundOwner
    var backgroundRequested = false
        private set

    fun backgroundActive(active: Boolean) { backgroundOwner.value = active; connections.background(active) }

    private fun foreground(active: Boolean) {
        visible.value = active
        if (!active) notifications.redact()
    }

    /** Main-thread activity ownership keeps an internal screen transition from locking the app. */
    fun activityStarted(activity: Activity) {
        visibleActivities.add(activity)
        appLock.foreground()
        connections.foreground(true)
        foreground(true)
    }

    fun activityStopped(activity: Activity, challenge: LockChallenge? = null) {
        visibleActivities.remove(activity)
        if (visibleActivities.isNotEmpty() || activity.isChangingConfigurations || !visible.value) return
        appLock.background(challenge)
        connections.foreground(false)
        foreground(false)
        if (!callForeground && !backgroundForeground.value) {
            ringing.stop()
            connections.stop(pause = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                combine(PreferencesStore(this@ItoApplication).preferences, visible, appLock.state) { prefs, shown, lock ->
                    Triple(prefs, shown, lock)
                }.collect { (prefs, shown, lock) ->
                    backgroundRequested = prefs.backgroundMessages
                    if (notifications.showText != prefs.notificationText || lock !in setOf(LockState.Disabled, LockState.Unlocked)) notifications.redact()
                    notifications.showText = prefs.notificationText
                    if (lock !in setOf(LockState.Disabled, LockState.Unlocked)) {
                        kotlinx.coroutines.withContext(Dispatchers.IO) { messenger.suspendAccess() }
                    }
                    if (!prefs.backgroundMessages) stopService(Intent(this@ItoApplication, MessagingService::class.java))
                    else if (shown && lock in setOf(LockState.Disabled, LockState.Unlocked) && !backgroundOwner.value) {
                        if (notifications.allowed()) try {
                            startForegroundService(Intent(this@ItoApplication, MessagingService::class.java))
                        } catch (_: Exception) { messenger.callFailed() }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) {
                backgroundRequested = false
                stopService(Intent(this@ItoApplication, MessagingService::class.java))
            }
        }
        scope.launch { ringing.incoming.collect { notifications.incoming(it) } }
    }
    private val audioForeground = MutableStateFlow(false)
    val foregroundAudio: StateFlow<Boolean> = audioForeground
    var callForeground: Boolean
        get() = audioForeground.value
        set(value) { audioForeground.value = value }
    private val serviceOwner = lazy { createServices(this, appLock::requireAccess, appLock::awaitAccess) }
    val services get() = serviceOwner.value
    val connections: ConnectionController by lazy {
        ConnectionController(this, services = transportServices(services), requireAccess = appLock::requireAccess,
            mailbox = MailboxLane("ito-mailbox", failed = messenger::transportFailed,
                stopped = messenger::suspendAccess) { stream, fingerprint, active ->
                messenger.initialize()
                messenger.run(stream, fingerprint, "ito-mailbox", active)
            })
    }
    /** Opened by its first user on an I/O thread: CallBook.open reads the Keystore and storage. */
    val callBook: CallBook by lazy { CallBook(this, appLock::requireAccess) }
    val calls: CallController by lazy {
        CallController(connections, callBook, requireAccess = appLock::requireAccess, profile = 3,
            onInvitation = { peer, invitation -> ringing.send(peer, invitation) },
            onFinished = { ringing.finished(it) },
            canStart = { !callForeground },
            onEnded = { view ->
                check(view.peerAuthenticated)
                view.peer?.let { key ->
                chatHistory.migrate(content)
                chatHistory.recordCall(key, view.initiator, if (view.localFailure == null) view.result else -1000,
                    System.currentTimeMillis() / 1000, view.durationMs)
            } },
            onMessage = { id, peer, outgoing, text ->
                chatHistory.migrate(content)
                chatHistory.recordLegacy(id, peer, outgoing, text, System.currentTimeMillis() / 1000)
            })
    }

    override fun onTerminate() {
        if (serviceOwner.isInitialized()) serviceOwner.value.close()
        super.onTerminate()
    }
}
