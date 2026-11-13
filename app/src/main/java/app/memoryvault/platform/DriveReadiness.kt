package app.memoryvault.platform

import android.car.VehicleGear
import app.memoryvault.domain.DriveState

internal data class UxReading(val requiresDistractionOptimization: Boolean, val activeRestrictions: Int)
internal enum class VehicleStatus { AVAILABLE, UNAVAILABLE, ERROR }
internal data class VehicleReading(val value: Any?, val status: VehicleStatus, val timestampNanos: Long)

internal object DriveReadiness {
    /** Only valid P/0/restricted or D/R/unrestricted(mask0) ordering candidates qualify. */
    fun isTransitionCandidate(ux: UxReading?, gear: VehicleReading?, speed: VehicleReading?, nowNanos: Long): Boolean {
        val validated = evaluate(true, ux, gear, speed, nowNanos)
        if (validated.readinessReason != DriveReadinessReason.INCONSISTENT_SIGNALS || ux == null) return false
        return when (gear?.value) {
            VehicleGear.GEAR_PARK -> speed?.value == 0f && ux.requiresDistractionOptimization
            VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE -> !ux.requiresDistractionOptimization && ux.activeRestrictions == 0
            else -> false
        }
    }

    fun evaluate(
        carReady: Boolean,
        ux: UxReading?,
        gear: VehicleReading?,
        speed: VehicleReading?,
        nowNanos: Long,
    ): DriveSnapshot {
        fun result(reason: DriveReadinessReason, state: DriveState = DriveState.UNKNOWN) = DriveSnapshot(
            state = state,
            requiresDistractionOptimization = ux?.requiresDistractionOptimization,
            activeRestrictions = ux?.activeRestrictions,
            readinessReason = reason,
        )

        if (!carReady) return result(DriveReadinessReason.CAR_NOT_READY)
        if (ux == null) return result(DriveReadinessReason.MISSING_UX)
        if (gear == null) return result(DriveReadinessReason.MISSING_GEAR)
        if (speed == null) return result(DriveReadinessReason.MISSING_SPEED)
        for (reading in listOf(gear, speed)) {
            when (reading.status) {
                VehicleStatus.UNAVAILABLE -> return result(DriveReadinessReason.PROPERTY_UNAVAILABLE)
                VehicleStatus.ERROR -> return result(DriveReadinessReason.PROPERTY_ERROR)
                VehicleStatus.AVAILABLE -> Unit
            }
            // ON_CHANGE gear may legitimately have an old timestamp. It has no age deadline.
            if (reading.timestampNanos <= 0 || reading.timestampNanos > nowNanos) {
                return result(DriveReadinessReason.INVALID_TIMESTAMP)
            }
        }
        val selectedGear = gear.value as? Int
        if (selectedGear !in setOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE)) {
            return result(DriveReadinessReason.UNSUPPORTED_GEAR)
        }
        val metersPerSecond = speed.value as? Float
        // PERF_VEHICLE_SPEED is signed: backward travel may be negative regardless of gear.
        if (metersPerSecond == null || !metersPerSecond.isFinite()) {
            return result(DriveReadinessReason.INVALID_SPEED)
        }
        if (ux.activeRestrictions < 0) return result(DriveReadinessReason.INVALID_UX)
        if (!ux.requiresDistractionOptimization && ux.activeRestrictions != 0) {
            return result(DriveReadinessReason.INCONSISTENT_SIGNALS)
        }
        return when {
            selectedGear == VehicleGear.GEAR_PARK && metersPerSecond == 0f &&
                !ux.requiresDistractionOptimization -> result(DriveReadinessReason.READY, DriveState.PARKED)
            selectedGear != VehicleGear.GEAR_PARK && ux.requiresDistractionOptimization ->
                result(DriveReadinessReason.READY, DriveState.DRIVING)
            else -> result(DriveReadinessReason.INCONSISTENT_SIGNALS)
        }
    }
}
