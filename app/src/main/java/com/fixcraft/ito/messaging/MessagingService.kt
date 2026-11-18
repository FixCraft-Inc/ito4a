package com.fixcraft.ito.messaging

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.data.PreferencesStore
import com.fixcraft.ito.security.LockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Explicit opt-in connection ownership. It never starts microphone capture or bypasses app lock.
 *
 * Android may kill the process, and the person may reboot or update Ito. The
 * service is sticky and [ReachabilityReceiver] starts it after boot and after
 * an update, so reachability comes back without opening Ito. Each start checks
 * the setting, notification access and app access again before connecting.
 */
class MessagingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as ItoApplication
    private var started = false
    private var active = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            app.ringing.stop(); app.connections.stop()
            // A person's Disconnect holds after process death too.
            return START_NOT_STICKY
        }
        // Started with startForegroundService, or restarted by Android, the
        // service must enter the foreground before it decides anything else.
        if (!enterForeground()) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true
            app.serviceStarted()
            scope.launch { admit() }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) startForeground(MessageNotifications.READY_NOTIFICATION,
            app.notifications.ready(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(MessageNotifications.READY_NOTIFICATION, app.notifications.ready())
        true
    } catch (_: Exception) {
        // Android refuses a background restart without an exemption. Say so
        // rather than leave the person thinking Ito still listens.
        app.notifications.reachabilityPaused(locked = false)
        false
    }

    /** Connects only if the person still wants it and Ito may. Otherwise the service ends. */
    private suspend fun admit() {
        val wanted = try {
            withTimeoutOrNull(10_000) { PreferencesStore(app).preferences.first().backgroundMessages } ?: false
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (!wanted || !app.notifications.allowed()) { stopSelf(); return }
        try { app.appLock.requireAccess() }
        catch (_: Exception) {
            // A locked app cannot open its connection settings. Opening Ito resumes.
            app.notifications.reachabilityPaused(locked = true)
            stopSelf(); return
        }
        active = true
        app.notifications.reachabilityResumed()
        app.backgroundActive(true)
        KeepAlive.schedule(app)
        var paused = app.connections.state.value.paused
        combine(app.connections.state, app.appLock.state) { state, _ -> state.paused }.collect { now ->
            getSystemService(NotificationManager::class.java).notify(MessageNotifications.READY_NOTIFICATION, app.notifications.ready())
            // Disconnect made this service non-sticky. Connect makes it sticky again
            // through one more start command, which returns START_STICKY.
            if (paused && !now) startService(Intent(this, MessagingService::class.java))
            paused = now
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (active) {
            KeepAlive.cancel(app)
            app.backgroundActive(false)
            if (!app.appLock.isForeground() && !app.callForeground) {
                app.ringing.stop(); app.connections.stop(pause = false)
            }
        }
        super.onDestroy()
    }

    companion object {
        const val DISCONNECT = "com.fixcraft.ito.messaging.DISCONNECT"

        /** Starts reachability from a context Android lets start a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MessagingService::class.java))
        }
    }
}

/** A private, immutable notification action. It can refuse, never accept, a pending call. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val token = intent?.getStringExtra("token") ?: return
        (context.applicationContext as ItoApplication).ringing.decline(token)
    }
}

/**
 * After boot or an update of Ito, resumes background reachability that the
 * person turned on. Android lets these two broadcasts start a foreground
 * service. The service itself checks the setting and access again.
 */
class ReachabilityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as ItoApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val wanted = withTimeoutOrNull(8_000) { PreferencesStore(app).preferences.first().backgroundMessages } ?: false
                // With app lock on, the connection's settings stay sealed until the person unlocks Ito.
                val lock = app.appLock.state.value
                if (wanted && app.notifications.allowed()) when (lock) {
                    LockState.Disabled -> try { MessagingService.start(app) }
                        catch (_: Exception) { app.notifications.reachabilityPaused(locked = false) }
                    else -> app.notifications.reachabilityPaused(locked = lock == LockState.Locked)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* Unreadable settings: stay off, as the setting's default. */ }
            finally { pending.finish() }
        }
    }
}

/**
 * Wakes a sleeping phone at the mailbox PING interval while background
 * reachability runs. A phone asleep stops its timers, so without this the
 * relay's ten-minute idle limit or a network's shorter NAT timeout ends the
 * connection unnoticed, and a ring held for sixty seconds is lost.
 *
 * Inexact allow-while-idle alarms need no exact-alarm permission and work in
 * Doze. Each wake asks the mailbox for one PING and holds the CPU only until
 * the PONG, or [AWAKE_MILLIS] when the connection must recover.
 */
internal object KeepAlive {
    private const val INTERVAL_MILLIS = MailboxClient.PING_INTERVAL_NANOS / 1_000_000
    private const val AWAKE_MILLIS = 10_000L

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MILLIS, intent(context))
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))

    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 30,
        Intent(context, KeepAliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun wake(app: ItoApplication) {
        if (!app.backgroundForeground.value) return
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ito:keepalive")
        lock.setReferenceCounted(false)
        lock.acquire(AWAKE_MILLIS)
        app.messenger.keepAlive { if (lock.isHeld) lock.release() }
        schedule(app)
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        KeepAlive.wake(context.applicationContext as ItoApplication)
    }
}
