package app.memoryvault.ui.p2

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.ui.components.P2HomeNavigation
import app.memoryvault.ui.components.P2HomePane
import org.junit.Assert.*
import org.junit.Test

/** These tests catch a private pane opened by a stale click or retained across invalidation. */
class P2HomeNavigationTest {
    @Test fun parkedForegroundCanOpenPhotosForTheDisplayedGeneration() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
        assertEquals(P2HomePane.PHOTOS, navigation.state.value.pane)
        assertTrue(navigation.isCurrent(drive, true))
        assertTrue(navigation.canShowPhotos(drive, true, PhotoAccess.FULL, true))
        assertTrue(navigation.canShowPhotos(drive, true, PhotoAccess.PARTIAL, true))
    }

    @Test fun parkedForegroundCanOpenSettingsWithoutPhotoPermission() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.SETTINGS, drive, true, 7))
        assertEquals(P2HomePane.SETTINGS, navigation.state.value.pane)
        assertTrue(navigation.isCurrent(drive, true))
        assertFalse(navigation.canShowPhotos(drive, true, PhotoAccess.FULL, true))
    }

    @Test fun everyNonParkedStateRejectsPrivatePaneEntry() {
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            val navigation = P2HomeNavigation()
            val drive = DriveSnapshot(state = state, invalidationGeneration = 7)
            assertFalse(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
            assertFalse(navigation.open(P2HomePane.SETTINGS, drive, true, 7))
            assertEquals(P2HomePane.HOME, navigation.state.value.pane)
        }
    }

    @Test fun backgroundClickCannotOpenEitherPrivatePane() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertFalse(navigation.open(P2HomePane.PHOTOS, drive, false, 7))
        assertFalse(navigation.open(P2HomePane.SETTINGS, drive, false, 7))
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
    }

    @Test fun staleParkedClickCannotCaptureANewerGeneration() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 8)
        assertFalse(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
    }

    @Test fun generationChangeClosesThePaneWithoutAutomaticallyReopening() {
        val navigation = P2HomeNavigation()
        val old = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        val changed = old.copy(invalidationGeneration = 8)
        assertTrue(navigation.open(P2HomePane.PHOTOS, old, true, 7))
        assertFalse(navigation.isCurrent(changed, true))
        navigation.revalidate(changed, true)
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
        navigation.revalidate(changed, true)
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
    }

    @Test fun stopClosesSettingsAndResumeDoesNotRestoreIt() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.SETTINGS, drive, true, 7))
        navigation.revalidate(drive, false)
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
        navigation.revalidate(drive, true)
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
    }

    @Test fun deniedPhotosCannotExposeContentEvenInsideTheAlbumPane() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
        assertFalse(navigation.canShowPhotos(drive, true, PhotoAccess.DENIED, true))
    }

    @Test fun invalidThumbnailEpochCannotExposeAlbumContent() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
        assertFalse(navigation.canShowPhotos(drive, true, PhotoAccess.PARTIAL, false))
    }

    @Test fun explicitHomeClosesThePaneAndStopsPrivateRendering() {
        val navigation = P2HomeNavigation()
        val drive = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 7)
        assertTrue(navigation.open(P2HomePane.PHOTOS, drive, true, 7))
        navigation.home()
        assertEquals(P2HomePane.HOME, navigation.state.value.pane)
        assertFalse(navigation.canShowPhotos(drive, true, PhotoAccess.FULL, true))
    }
}
