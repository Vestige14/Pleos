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
    READY, UNSUPPORTED, PERMISSION_DENIED, READ_FAILED, LISTENER_REGISTRATION_FAILED,
    PROPERTY_UNAVAILABLE, PROPERTY_ERROR, INVALID_TIMESTAMP, UNSUPPORTED_GEAR,
    INVALID_SPEED, INVALID_UX, INCONSISTENT_SIGNALS, CLEANUP_FAILED, DISCONNECTED, CLOSED,
}

interface DriveStateSource : AutoCloseable {
    val snapshot: StateFlow<DriveSnapshot>

    /** Starts observation, or retries a failed connection. A closed source cannot be restarted. */
    fun start()

    override fun close()
}
