package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import kotlinx.coroutines.flow.StateFlow

data class DriveSnapshot(
    val state: DriveState = DriveState.UNKNOWN,
    val requiresDistractionOptimization: Boolean? = null,
    val activeRestrictions: Int? = null,
    val readinessReason: DriveReadinessReason = DriveReadinessReason.NOT_STARTED,
    val cleanupFailed: Boolean = false,
)

enum class DriveReadinessReason {
    NOT_STARTED, CONNECTING, CAR_NOT_READY, MISSING_UX, MISSING_GEAR, MISSING_SPEED,
    READY, TRANSITION, TRANSITION_TIMEOUT, UNSUPPORTED, PERMISSION_DENIED, READ_FAILED, LISTENER_REGISTRATION_FAILED,
    PROPERTY_UNAVAILABLE, PROPERTY_ERROR, INVALID_TIMESTAMP, UNSUPPORTED_GEAR,
    INVALID_SPEED, INVALID_UX, INCONSISTENT_SIGNALS, CLEANUP_FAILED, DISCONNECTED, CLOSED,
}

interface DriveStateSource : AutoCloseable {
    val snapshot: StateFlow<DriveSnapshot>

    /** Schedules observation/retry. Initial readiness is asynchronous; a closed source cannot restart. */
    fun start()

    /** Publishes UNKNOWN synchronously; external cleanup drains asynchronously after in-flight I/O. */
    override fun close()
}
