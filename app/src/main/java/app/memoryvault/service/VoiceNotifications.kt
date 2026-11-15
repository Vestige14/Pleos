package app.memoryvault.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.memoryvault.R

/** Status only. No transcript, partial, exception payload, Activity, or audio attachment. */
class VoiceNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private var lastReady: ReadyNotificationState? = null

    fun ensureChannels() {
        manager.createNotificationChannels(listOf(
            channel(RECORDING_CHANNEL, R.string.voice_recording_channel),
            channel(READY_CHANNEL, R.string.voice_ready_channel),
            channel(RESULT_CHANNEL, R.string.voice_result_channel),
        ))
    }

    fun availability(): VoiceAvailability = try {
        ensureChannels()
        VoiceAvailability(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notificationPermission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            notificationsEnabled = manager.areNotificationsEnabled(),
            recordingChannelEnabled = manager.getNotificationChannel(RECORDING_CHANNEL)?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE } == true,
            sdkAvailable = granted("pleos.car.permission.STT_SERVICE"),
        )
    } catch (_: RuntimeException) {
        VoiceAvailability(false, false, false, false, false)
    }

    fun active(state: VoiceServiceState): Notification {
        ensureChannels()
        val builder = builder(RECORDING_CHANNEL)
            .setContentTitle(context.getString(if (state == VoiceServiceState.Listening) R.string.voice_recording_title else R.string.app_name))
            .setContentText(context.getString(state.notice().textResource()))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.voice_cancel), action(ACTION_CANCEL, 2)).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    fun updateActive(state: VoiceServiceState): Boolean = try {
        manager.notify(ACTIVE_ID, active(state))
        true
    } catch (_: RuntimeException) { false }

    fun terminal(result: VoiceServiceResult) {
        if (!availability().notificationPermission || !manager.areNotificationsEnabled()) return
        try {
            manager.notify(RESULT_ID, builder(RESULT_CHANNEL)
                .setContentText(context.getString(VoiceServiceState.Finished(result).notice().textResource()))
                .setTimeoutAfter(8_000L).build())
        } catch (_: RuntimeException) { /* UI state remains available without exposing exception payloads. */ }
    }

    fun ready(state: ReadyNotificationState) {
        if (state == lastReady) return
        lastReady = state
        if (state == ReadyNotificationState.HIDDEN) {
            manager.cancel(READY_ID)
            return
        }
        try {
            val body = when (state) {
                ReadyNotificationState.READY -> R.string.voice_ready_driving
                ReadyNotificationState.PARKED -> R.string.p2_voice_ready_parked
                else -> R.string.voice_ready_unavailable
            }
            val builder = builder(READY_CHANNEL).setContentText(context.getString(body))
                .setDeleteIntent(action(ACTION_DISABLE_READY, 3))
                .addAction(Notification.Action.Builder(null, context.getString(R.string.voice_disable_ready), action(ACTION_DISABLE_READY, 3)).build())
            if (state == ReadyNotificationState.READY) {
                builder.addAction(Notification.Action.Builder(null, context.getString(R.string.voice_start), action(ACTION_START, 1)).build())
            }
            // No contentIntent: only the explicit start button can request a session.
            manager.notify(READY_ID, builder.build())
        } catch (_: RuntimeException) { lastReady = null }
    }

    fun clearReadyAndResult() {
        manager.cancel(READY_ID)
        manager.cancel(RESULT_ID)
        lastReady = null
    }

    private fun channel(id: String, name: Int) = NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_LOW).apply {
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun builder(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_memory_vault)
        .setContentTitle(context.getString(R.string.app_name))
        .setCategory(Notification.CATEGORY_STATUS)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(true)
    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, VoiceNotificationReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTIVE_ID = 3101
        private const val READY_ID = 3102
        private const val RESULT_ID = 3103
        const val RECORDING_CHANNEL = "voice_recording"
        private const val READY_CHANNEL = "voice_ready"
        private const val RESULT_CHANNEL = "voice_result"
        internal const val ACTION_START = "app.memoryvault.private.START_VOICE"
        internal const val ACTION_CANCEL = "app.memoryvault.private.CANCEL_VOICE"
        internal const val ACTION_DISABLE_READY = "app.memoryvault.private.DISABLE_READY"
    }
}

private fun VoiceNotice.textResource(): Int = when (this) {
    VoiceNotice.CHECKING -> R.string.p2_voice_storage_checking
    VoiceNotice.PREPARING -> R.string.voice_preparing
    VoiceNotice.RECORDING -> R.string.voice_recording
    VoiceNotice.PROCESSING -> R.string.voice_processing
    VoiceNotice.SAVED -> R.string.voice_saved
    VoiceNotice.SAVED_CLEANUP_WARNING -> R.string.voice_saved_cleanup_warning
    VoiceNotice.NOTHING_HEARD -> R.string.voice_nothing_heard
    VoiceNotice.TIMED_OUT -> R.string.voice_timeout
    VoiceNotice.INTERRUPTED -> R.string.voice_interrupted
    VoiceNotice.STORAGE_FAILED -> R.string.voice_storage_failed
    VoiceNotice.FAILED -> R.string.voice_failed
    VoiceNotice.FAILED_CLEANUP_WARNING -> R.string.voice_cleanup_failed
    VoiceNotice.UNAVAILABLE -> R.string.voice_unavailable
}
