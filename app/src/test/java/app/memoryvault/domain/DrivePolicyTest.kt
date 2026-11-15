package app.memoryvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivePolicyTest {
    // Catches allowing a route through UNKNOWN/DRIVING or losing a parked route.
    @Test fun everyDriveStateAndRequestedRoute_hasTheExplicitSafeDestination() {
        val cases = listOf(
            Triple(DriveState.UNKNOWN, Route.Locked, Route.Locked),
            Triple(DriveState.UNKNOWN, Route.Driving, Route.Locked),
            Triple(DriveState.UNKNOWN, Route.DayDraft, Route.Locked),
            Triple(DriveState.UNKNOWN, Route.ShareSelect, Route.Locked),
            Triple(DriveState.UNKNOWN, Route.SharePreview, Route.Locked),
            Triple(DriveState.TRANSITION, Route.Locked, Route.Locked),
            Triple(DriveState.TRANSITION, Route.Driving, Route.Locked),
            Triple(DriveState.TRANSITION, Route.DayDraft, Route.Locked),
            Triple(DriveState.TRANSITION, Route.ShareSelect, Route.Locked),
            Triple(DriveState.TRANSITION, Route.SharePreview, Route.Locked),
            Triple(DriveState.DRIVING, Route.Locked, Route.Driving),
            Triple(DriveState.DRIVING, Route.Driving, Route.Driving),
            Triple(DriveState.DRIVING, Route.DayDraft, Route.Driving),
            Triple(DriveState.DRIVING, Route.ShareSelect, Route.Driving),
            Triple(DriveState.DRIVING, Route.SharePreview, Route.Driving),
            Triple(DriveState.PARKED, Route.Locked, Route.DayDraft),
            Triple(DriveState.PARKED, Route.Driving, Route.DayDraft),
            Triple(DriveState.PARKED, Route.DayDraft, Route.DayDraft),
            Triple(DriveState.PARKED, Route.ShareSelect, Route.ShareSelect),
            Triple(DriveState.PARKED, Route.SharePreview, Route.SharePreview),
        )
        cases.forEach { (state, requested, expected) ->
            assertEquals("$state requesting $requested", expected, DrivePolicy.routeFor(state, requested))
        }
    }

    // Catches editing/export being enabled before a trustworthy parked classification.
    @Test fun editingAndExport_areAllowedOnlyWhenParked() {
        listOf(DriveState.UNKNOWN to false, DriveState.TRANSITION to false, DriveState.DRIVING to false, DriveState.PARKED to true)
            .forEach { (state, expected) ->
                assertEquals("edit $state", expected, DrivePolicy.canEdit(state))
                assertEquals("export $state", expected, DrivePolicy.canExport(state))
            }
    }

    // Explicit recording works without a trusted vehicle state; TRANSITION still blocks a new command.
    @Test fun explicitVoiceStart_isAllowedExceptDuringTransition() {
        listOf(DriveState.UNKNOWN to true, DriveState.TRANSITION to false, DriveState.DRIVING to true, DriveState.PARKED to true)
            .forEach { (state, expected) ->
                assertEquals(state.toString(), expected, DrivePolicy.canStartVoice(state))
            }
    }
}
