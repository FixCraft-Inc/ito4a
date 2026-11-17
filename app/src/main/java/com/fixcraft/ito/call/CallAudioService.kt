package com.fixcraft.ito.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** User-started, nonsticky foreground audio for one already consented call.
 * It neither rings a sleeping phone nor starts new calls in the background.
 */
class CallAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as ItoApplication
    private var voice: OpusVoice? = null
    private var audioOwner: AudioDeviceOwner<OpusVoice>? = null
    private var monitor: Job? = null
    private var started = false
    private var muted = false
    private var speaker = false
    private var closing = false
    private var destroyed = false
    private var audioClosed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (closing || destroyed || (!started && app.callForeground) || intent == null || app.calls.view.value.phase != CallPhase.Media) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent.action) {
            END -> { app.calls.hangup(); if (!started) stopSelf(); return START_NOT_STICKY }
            MUTE -> {
                if (!started) stopSelf()
                else if (voice != null) { muted = !muted; voice?.mute(muted); publish() }
                return START_NOT_STICKY
            }
            SPEAKER -> {
                if (!started) { stopSelf(); return START_NOT_STICKY }
                if (voice == null) return START_NOT_STICKY
                try { voice?.speaker(!speaker); speaker = !speaker; publish() }
                catch (_: Exception) { app.calls.audioFailed() }
                return START_NOT_STICKY
            }
        }
        if (started) return START_NOT_STICKY
        try {
            app.appLock.requireAccess()
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.audio_notification_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val end = PendingIntent.getService(this, 1, Intent(this, CallAudioService::class.java).setAction(END), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_call)
                .setContentTitle(getString(R.string.audio_notification_title))
                .setContentText(getString(R.string.audio_notification_description))
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, getString(R.string.end_call), end).build()).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
            app.callForeground = true
            started = true
            app.calls.beginContent()
            monitor = scope.launch(start = CoroutineStart.LAZY) {
                app.calls.view.collect { state ->
                    if (state.phase != CallPhase.Media || !state.peerAuthenticated) {
                        finishAudio()
                    } else if (state.contentReady && audioOwner == null) {
                        val owner = AudioDeviceOwner(
                            Dispatchers.Main.immediate, Dispatchers.IO,
                            create = { OpusVoice(this@CallAudioService, app.calls::sendOpus, app.calls::audioFailed) },
                            startDevice = OpusVoice::start,
                            ready = { device ->
                                if (app.calls.view.value.phase != CallPhase.Media) finishAudio()
                                else {
                                    voice = device
                                    app.calls.attachAudio(device::receive)
                                    publish()
                                }
                            },
                            failed = app.calls::audioFailed,
                            closed = ::deviceClosed,
                        )
                        audioOwner = owner
                        owner.start()
                    }
                }
            }
            monitor?.start()
        } catch (_: Exception) { app.calls.audioFailed(); finishAudio() }
        return START_NOT_STICKY
    }

    private fun publish() { app.calls.audioState(voice != null, muted, speaker) }

    private fun finishAudio() {
        if (closing) return
        closing = true
        monitor?.cancel()
        if (started) app.calls.attachAudio(null)
        voice = null
        val owner = audioOwner
        if (owner == null) deviceClosed() else owner.close()
    }

    private fun deviceClosed() {
        audioClosed = true
        if (started) {
            app.calls.audioState(false)
            if (destroyed) app.callForeground = false
        }
        if (!destroyed) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) app.calls.attachAudio(null)
        if (started && app.calls.view.value.phase == CallPhase.Media) app.calls.cancel()
        super.onDestroy()
        destroyed = true
        if (started && !app.appLock.isForeground() && !app.backgroundForeground.value) app.connections.stop(pause = false)
        finishAudio()
        // The separate owner keeps this guard while Android setup/cleanup
        // still borrows devices or can call back into this attempt.
        if (started && audioClosed) app.callForeground = false
    }

    companion object {
        private const val CHANNEL = "ito-active-audio"
        private const val NOTIFICATION = 11
        const val END = "com.fixcraft.ito.audio.END"
        const val MUTE = "com.fixcraft.ito.audio.MUTE"
        const val SPEAKER = "com.fixcraft.ito.audio.SPEAKER"
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CallAudioService::class.java))
        }
        fun action(context: Context, action: String) {
            context.startService(Intent(context, CallAudioService::class.java).setAction(action))
        }
    }
}
