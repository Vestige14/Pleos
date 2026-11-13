package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveReadinessTest {
    private val parkedUx = UxReading(false, 0)
    private val drivingUx = UxReading(true, 80)
    private val park = reading(4)
    private val drive = reading(8)
    private val zero = reading(0f)

    @Test fun `lifecycle ready is required even with complete inputs`() {
        val result = evaluate(ready = false)
        assertEquals(DriveState.UNKNOWN, result.state)
        assertEquals(DriveReadinessReason.CAR_NOT_READY, result.readinessReason)
    }

    @Test fun `missing UX gear or speed independently prevents readiness`() {
        listOf(
            Triple(null, park, zero) to DriveReadinessReason.MISSING_UX,
            Triple(parkedUx, null, zero) to DriveReadinessReason.MISSING_GEAR,
            Triple(parkedUx, park, null) to DriveReadinessReason.MISSING_SPEED,
        ).forEach { (input, reason) ->
            val result = evaluate(ux = input.first, gear = input.second, speed = input.third)
            assertEquals(DriveState.UNKNOWN, result.state)
            assertEquals(reason, result.readinessReason)
        }
    }

    @Test fun `P stationary and unrestricted is parked`() {
        assertEquals(DriveState.PARKED, evaluate().state)
    }

    @Test fun `D stationary D moving and reverse with restricted UX are driving`() {
        listOf(drive to zero, drive to reading(10f), reading(2) to zero, reading(2) to reading(2f),
            reading(2) to reading(-2f), drive to reading(-1f))
            .forEach { (gear, speed) ->
                val result = evaluate(ux = drivingUx, gear = gear, speed = speed)
                assertEquals(DriveState.DRIVING, result.state)
                assertEquals(true, result.requiresDistractionOptimization)
                assertEquals(80, result.activeRestrictions)
                assertEquals(DriveReadinessReason.READY, result.readinessReason)
            }
    }

    @Test fun `vehicle signals cannot relax UX or excuse conflicts`() {
        listOf(
            Triple(parkedUx, park, reading(1f)),
            Triple(parkedUx, park, reading(-1f)),
            Triple(parkedUx, drive, zero),
            Triple(parkedUx, drive, reading(10f)),
            Triple(drivingUx, park, zero),
            Triple(UxReading(false, 80), park, zero),
            Triple(UxReading(false, 80), drive, zero),
        ).forEach { (ux, gear, speed) ->
            val result = evaluate(ux = ux, gear = gear, speed = speed)
            assertEquals(DriveState.UNKNOWN, result.state)
            assertEquals(DriveReadinessReason.INCONSISTENT_SIGNALS, result.readinessReason)
            assertEquals(ux.activeRestrictions, result.activeRestrictions)
        }
    }

    @Test fun `DO remains restricted even with baseline active restrictions`() {
        assertEquals(DriveState.DRIVING, evaluate(ux = UxReading(true, 0), gear = drive).state)
    }

    @Test fun `neutral and invalid gear values remain unknown`() {
        listOf(1, 0, 16, 12, -1, null, 4L, "4").forEach { value ->
            val result = evaluate(gear = reading(value))
            assertEquals(DriveState.UNKNOWN, result.state)
            assertEquals(DriveReadinessReason.UNSUPPORTED_GEAR, result.readinessReason)
        }
    }

    @Test fun `nonfinite or unsupported speed types remain unknown`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, 0.0, null, "0")
            .forEach { value ->
                val result = evaluate(speed = reading(value))
                assertEquals(DriveState.UNKNOWN, result.state)
                assertEquals(DriveReadinessReason.INVALID_SPEED, result.readinessReason)
            }
    }

    @Test fun `unavailable and error samples cannot supply values`() {
        listOf(
            VehicleStatus.UNAVAILABLE to DriveReadinessReason.PROPERTY_UNAVAILABLE,
            VehicleStatus.ERROR to DriveReadinessReason.PROPERTY_ERROR,
        ).forEach { (status, reason) ->
            listOf(evaluate(gear = reading(4, status)), evaluate(speed = reading(0f, status)))
                .forEach { result ->
                    assertEquals(DriveState.UNKNOWN, result.state)
                    assertEquals(reason, result.readinessReason)
                }
        }
    }

    @Test fun `timestamps must be positive and not in the monotonic future`() {
        listOf(-1L, 0L, 101L).forEach { timestamp ->
            listOf(
                evaluate(gear = reading(4, timestamp = timestamp)),
                evaluate(speed = reading(0f, timestamp = timestamp)),
            ).forEach { result ->
                assertEquals(DriveState.UNKNOWN, result.state)
                assertEquals(DriveReadinessReason.INVALID_TIMESTAMP, result.readinessReason)
            }
        }
    }

    @Test fun `unchanged ON_CHANGE values do not expire just because time passed`() {
        assertEquals(DriveState.PARKED, evaluate(now = Long.MAX_VALUE).state)
    }

    @Test fun `negative restriction masks cannot grant readiness`() {
        val result = evaluate(ux = UxReading(false, -1))
        assertEquals(DriveState.UNKNOWN, result.state)
        assertEquals(DriveReadinessReason.INVALID_UX, result.readinessReason)
    }

    private fun evaluate(
        ready: Boolean = true,
        ux: UxReading? = parkedUx,
        gear: VehicleReading? = park,
        speed: VehicleReading? = zero,
        now: Long = 100,
    ) = DriveReadiness.evaluate(ready, ux, gear, speed, now)

    private fun reading(
        value: Any?,
        status: VehicleStatus = VehicleStatus.AVAILABLE,
        timestamp: Long = 50,
    ) = VehicleReading(value, status, timestamp)
}
