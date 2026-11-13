package app.memoryvault.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.memoryvault.AppContainer
import app.memoryvault.MemoryVaultApp
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceMemoOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface VoiceStartRequest {
    /** Dispatch only: SDK start is reported later by VoiceServiceState.Listening. */
    data object Dispatched : VoiceStartRequest
    data class Rejected(val reason: VoiceStartBlock) : VoiceStartRequest
}

/** Bounded, user-initiated service that continues while navigation owns the foreground. */
class VoiceMemoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container: AppContainer get() = (application as MemoryVaultApp).container
    private var controller: VoiceSessionController? = null
    private var recording: Job? = null
    private var foreground = false
    private var destroying = false
    private var latestStartId = 0

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action != START || !pending.get()) {
            if (recording == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (recording != null) return START_NOT_STICKY
        preflight(container)?.let {
            finish(VoiceServiceResult.NotStarted(it))
            return START_NOT_STICKY
        }
        try {
            startForeground(VoiceNotifications.ACTIVE_ID, container.notifications.active(VoiceServiceState.CheckingVehicle),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            foreground = true
        } catch (_: RuntimeException) {
            finish(VoiceServiceResult.NotStarted(VoiceStartBlock.SERVICE_UNAVAILABLE))
            return START_NOT_STICKY
        }
        container.notifications.clearReadyAndResult()
        if (cancelPending.get()) {
            finish(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)))
            return START_NOT_STICKY
        }
        val session = container.newVoiceController()
        controller = session
        recording = scope.launch {
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                session.state.collect { state ->
                    if (state != VoiceServiceState.Idle && state !is VoiceServiceState.Finished) {
                        container.publishVoiceState(state)
                        if (!container.notifications.updateActive(state)) session.interrupt(VoiceStartBlock.NOTIFICATIONS)
                    }
                }
            }
            var result: VoiceServiceResult = VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER))
            try {
                result = session.record()
            } catch (_: CancellationException) {
                // Lifecycle destruction is interruption, never a promise that no row committed.
            } catch (_: Exception) {
                result = VoiceServiceResult.Interrupted(VoiceStartBlock.SERVICE_UNAVAILABLE)
            } finally {
                observer.cancel()
                finish(result)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.cancel()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroying = true
        scope.cancel()
        removeForeground()
        // Keep duplicate protection until the cancelled coroutine has released its SDK.
        val job = recording
        if (job == null) releaseCommand() else job.invokeOnCompletion { releaseCommand() }
        super.onDestroy()
    }

    private fun finish(result: VoiceServiceResult) {
        container.publishVoiceState(VoiceServiceState.Finished(result))
        removeForeground()
        if (!destroying) container.notifications.terminal(result)
        stopSelfResult(latestStartId)
    }

    private fun removeForeground() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }

    private fun releaseCommand() {
        if (instance === this) {
            instance = null
            cancelPending.set(false)
            pending.set(false)
            container.refreshReadyNotification()
        }
    }

    companion object {
        private const val START = "app.memoryvault.private.SERVICE_START"
        private val pending = AtomicBoolean(false)
        private val cancelPending = AtomicBoolean(false)
        @Volatile private var instance: VoiceMemoService? = null

        /** Call only from an explicit user UI command or the private notification action. */
        fun requestStart(context: Context): VoiceStartRequest {
            val app = context.applicationContext as MemoryVaultApp
            val container = app.container
            if (!pending.compareAndSet(false, true)) return VoiceStartRequest.Rejected(VoiceStartBlock.BUSY)
            cancelPending.set(false)
            preflight(container)?.let {
                pending.set(false)
                val result = VoiceServiceResult.NotStarted(it)
                container.publishVoiceState(VoiceServiceState.Finished(result))
                container.notifications.terminal(result)
                return VoiceStartRequest.Rejected(it)
            }
            return try {
                context.startForegroundService(Intent(context, VoiceMemoService::class.java).setAction(START))
                container.publishVoiceState(VoiceServiceState.CheckingVehicle)
                VoiceStartRequest.Dispatched
            } catch (_: RuntimeException) {
                pending.set(false)
                val result = VoiceServiceResult.NotStarted(VoiceStartBlock.SERVICE_UNAVAILABLE)
                container.publishVoiceState(VoiceServiceState.Finished(result))
                container.notifications.terminal(result)
                VoiceStartRequest.Rejected(VoiceStartBlock.SERVICE_UNAVAILABLE)
            }
        }

        fun requestCancel() {
            if (!pending.get()) return
            cancelPending.set(true)
            instance?.controller?.cancel()
        }

        private fun preflight(container: AppContainer): VoiceStartBlock? =
            container.notifications.availability().block()
                ?: if (container.driveSnapshot.value.state == DriveState.PARKED) VoiceStartBlock.PARKED
                else if (container.driveSnapshot.value.state == DriveState.TRANSITION) VoiceStartBlock.UNKNOWN
                else if (container.storageReadiness.value == StorageReadiness.FAILED) VoiceStartBlock.STORAGE_UNAVAILABLE
                else null
    }
}
