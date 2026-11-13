package app.memoryvault.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.memoryvault.MemoryVaultApp

/** Only immutable explicit app-created notification PendingIntents can reach this receiver. */
class VoiceNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            VoiceNotifications.ACTION_START -> VoiceMemoService.requestStart(context)
            VoiceNotifications.ACTION_CANCEL -> VoiceMemoService.requestCancel()
            VoiceNotifications.ACTION_DISABLE_READY ->
                (context.applicationContext as MemoryVaultApp).container.setReadyNotificationEnabled(false)
        }
    }
}
