package app.memoryvault.ui.components

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class P2HomePane { HOME, PHOTOS, SETTINGS }
internal data class P2HomeNavigationState(val pane: P2HomePane = P2HomePane.HOME, val generation: Long? = null)

/** A private pane belongs to the foreground and vehicle generation that opened it. */
internal class P2HomeNavigation {
    private val mutableState = MutableStateFlow(P2HomeNavigationState())
    val state: StateFlow<P2HomeNavigationState> = mutableState.asStateFlow()
    fun open(pane: P2HomePane, current: DriveSnapshot, foreground: Boolean, expectedGeneration: Long): Boolean {
        if (!foreground || current.state != DriveState.PARKED || current.invalidationGeneration != expectedGeneration) return false
        mutableState.value = if (pane == P2HomePane.HOME) P2HomeNavigationState()
            else P2HomeNavigationState(pane, current.invalidationGeneration)
        return true
    }

    fun revalidate(current: DriveSnapshot, foreground: Boolean) {
        if (!isCurrent(current, foreground)) home()
    }

    fun home() { mutableState.value = P2HomeNavigationState() }

    fun isCurrent(current: DriveSnapshot, foreground: Boolean): Boolean {
        val opened = mutableState.value
        return opened.pane != P2HomePane.HOME && foreground && current.state == DriveState.PARKED &&
            opened.generation == current.invalidationGeneration
    }

    fun canShowPhotos(current: DriveSnapshot, foreground: Boolean, access: PhotoAccess, epochAllowed: Boolean): Boolean =
        mutableState.value.pane == P2HomePane.PHOTOS && isCurrent(current, foreground) &&
            access != PhotoAccess.DENIED && epochAllowed
}
