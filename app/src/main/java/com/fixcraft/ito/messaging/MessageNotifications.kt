package com.fixcraft.ito.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.net.toUri
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.IncomingCallActivity
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.security.LockState
import com.fixcraft.ito.transport.ConnectionPhase

/** Notification intents carry actions only. Every label is rechecked against current access. */
class MessageNotifications(private val app: ItoApplication) {
    private val manager = app.getSystemService(NotificationManager::class.java)
    @Volatile var showText = true
    private val access get() = app.appLock.state.value in setOf(LockState.Disabled, LockState.Unlocked)

    fun channels() {
        manager.createNotificationChannel(NotificationChannel(READY, app.getString(R.string.messaging_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(MESSAGES, app.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(MISSED, app.getString(R.string.missed_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val calls = NotificationChannel(CALLS, app.getString(R.string.incoming_channel), NotificationManager.IMPORTANCE_HIGH)
        calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        manager.createNotificationChannel(calls)
    }

    fun allowed(): Boolean {
        channels()
        return (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && listOf(READY, MESSAGES, CALLS).all {
                manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
            }
    }

    fun fullScreenAllowed(): Boolean = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()

    private fun open() = PendingIntent.getActivity(app, 20,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Each kind has its own icon and public title, so the shade never shows two notices that look alike. */
    private fun base(channel: String, text: Int, icon: Int = R.drawable.ic_ito, title: Int = R.string.app_name): Notification.Builder =
        Notification.Builder(app, channel)
            .setSmallIcon(icon).setContentTitle(app.getString(title))
            .setContentText(app.getString(text)).setContentIntent(open())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(app, channel).setSmallIcon(icon)
                .setContentTitle(app.getString(title)).setContentText(app.getString(text)).build())

    fun ready(): Notification {
        val text = when {
            !access -> R.string.messaging_unlock
            app.connections.state.value.phase == ConnectionPhase.Connected -> R.string.messaging_ready
            else -> R.string.messaging_waiting
        }
        val stop = PendingIntent.getService(app, 21, Intent(app, MessagingService::class.java).setAction(MessagingService.DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE)
        return base(READY, text, R.drawable.ic_hub, R.string.messaging_service_title).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, app.getString(R.string.stop_transport), stop).build()).build()
    }

    @Synchronized fun message(peer: ByteArray, text: ByteArray) {
        if (app.appLock.isForeground() || !allowed()) return
        val builder = base(MESSAGES, R.string.messaging_new_message).setAutoCancel(true).setOnlyAlertOnce(true)
        if (access) {
            val name = (app.callBook.state.value as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(peer) }?.name
            if (name != null) builder.setContentTitle(name)
            if (showText) builder.setContentText(text.toString(Charsets.UTF_8).take(512))
        }
        manager.notify(MESSAGE_NOTIFICATION, builder.build())
    }

    @Synchronized fun sealedActivity() {
        if (app.appLock.isForeground() || !allowed()) return
        manager.notify(MESSAGE_NOTIFICATION, base(READY, R.string.messaging_unlock_activity).setOnlyAlertOnce(true).setAutoCancel(true).build())
    }

    @Synchronized fun redact() {
        manager.cancel(MESSAGE_NOTIFICATION)
        manager.cancel(MISSED_NOTIFICATION)
        incoming(app.ringing.incoming.value)
    }

    /** A call that rang here and ended unanswered. In-app, the chat's history shows it instead. */
    @Synchronized fun missed(peer: ByteArray) {
        if (app.appLock.isForeground() || !allowed()) return
        val builder = base(MISSED, R.string.ring_missed, R.drawable.ic_call_missed).setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 31) builder.setCategory(Notification.CATEGORY_MISSED_CALL)
        if (access) {
            val name = (app.callBook.state.value as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(peer) }?.name
            if (name != null) builder.setContentTitle(name)
        }
        manager.notify(MISSED_NOTIFICATION, builder.build())
    }

    @Synchronized fun incoming(call: IncomingCall?) {
        if (call == null) { manager.cancel(CALL_NOTIFICATION); return }
        if (!allowed()) return
        val builder = base(CALLS, R.string.incoming_unlock, R.drawable.ic_call).setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter(45_000)
        val openCall = PendingIntent.getActivity(app, 22,
            Intent(app, IncomingCallActivity::class.java)
                .setData(("ito-incoming:" + call.actionToken).toUri())
                .putExtra(IncomingCallActivity.TOKEN, call.actionToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(openCall)
        if (!app.appLock.isForeground() && fullScreenAllowed()) builder.setFullScreenIntent(openCall, true)
        if (access) {
            val name = (app.callBook.state.value as? BookState.Ready)?.contacts?.firstOrNull { it.key.contentEquals(call.peer) }?.name
            builder.setContentTitle(name ?: app.getString(R.string.app_name)).setContentText(app.getString(R.string.incoming_call))
        }
        val decline = PendingIntent.getBroadcast(app, 22, Intent(app, RingActionReceiver::class.java).setData(("ito-ring-action:" + call.actionToken).toUri())
                .putExtra("token", call.actionToken), PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(Notification.Action.Builder(null, app.getString(R.string.decline), decline).build())
        builder.addAction(Notification.Action.Builder(null, app.getString(R.string.open_app), openCall).build())
        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(CALL_NOTIFICATION, notification)
    }

    companion object {
        const val READY = "ito-messaging-ready"
        const val MESSAGES = "ito-messages"
        const val CALLS = "ito-incoming-calls"
        const val MISSED = "ito-missed-calls"
        const val READY_NOTIFICATION = 20
        private const val MESSAGE_NOTIFICATION = 21
        private const val CALL_NOTIFICATION = 22
        private const val MISSED_NOTIFICATION = 23
    }
}
