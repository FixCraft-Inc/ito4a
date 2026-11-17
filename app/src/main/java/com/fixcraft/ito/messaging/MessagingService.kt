package com.fixcraft.ito.messaging

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.fixcraft.ito.ItoApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Explicit opt-in connection ownership. It never starts microphone capture or bypasses app lock. */
class MessagingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as ItoApplication
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            app.ringing.stop(); app.connections.stop()
            return START_NOT_STICKY
        }
        if (intent == null || !app.backgroundRequested || !app.notifications.allowed()) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!started) try {
            app.appLock.requireAccess()
            if (Build.VERSION.SDK_INT >= 34) startForeground(MessageNotifications.READY_NOTIFICATION,
                app.notifications.ready(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(MessageNotifications.READY_NOTIFICATION, app.notifications.ready())
            started = true
            app.backgroundActive(true)
            scope.launch {
                combine(app.connections.state, app.appLock.state) { _, _ -> app.notifications.ready() }.collect {
                    getSystemService(android.app.NotificationManager::class.java).notify(MessageNotifications.READY_NOTIFICATION, it)
                }
            }
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) {
            app.backgroundActive(false)
            if (!app.appLock.isForeground() && !app.callForeground) {
                app.ringing.stop(); app.connections.stop(pause = false)
            }
        }
        super.onDestroy()
    }

    companion object { const val DISCONNECT = "com.fixcraft.ito.messaging.DISCONNECT" }
}

/** A private, immutable notification action. It can refuse, never accept, a pending call. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val token = intent?.getStringExtra("token") ?: return
        (context.applicationContext as ItoApplication).ringing.decline(token)
    }
}
