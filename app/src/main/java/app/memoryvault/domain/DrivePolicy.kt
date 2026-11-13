package app.memoryvault.domain

/** Sources must start UNKNOWN until a supported adapter establishes a trustworthy state. */
enum class DriveState { UNKNOWN, TRANSITION, DRIVING, PARKED }

enum class Route { Locked, Driving, DayDraft, ShareSelect, SharePreview }

object DrivePolicy {
    /** Consumes a trusted classification; raw vehicle/status values do not establish readiness. */
    fun routeFor(state: DriveState, requested: Route): Route = when (state) {
        DriveState.UNKNOWN, DriveState.TRANSITION -> Route.Locked
        DriveState.DRIVING -> Route.Driving
        DriveState.PARKED -> when (requested) {
            Route.Locked, Route.Driving -> Route.DayDraft
            else -> requested
        }
    }

    fun canEdit(state: DriveState): Boolean = state == DriveState.PARKED
    fun canExport(state: DriveState): Boolean = state == DriveState.PARKED
    fun canStartVoice(state: DriveState): Boolean = state == DriveState.DRIVING
}
