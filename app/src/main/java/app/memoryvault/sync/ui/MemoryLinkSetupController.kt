package app.memoryvault.sync.ui

import app.memoryvault.BuildConfig
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.LinkSessionException
import app.memoryvault.sync.MemoryLinkEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LinkSetupBlocked : IllegalStateException("SETUP_BLOCKED")
enum class SetupNotice { NONE, SAVED, BLOCKED, INVALID_ENDPOINT, INVALID_SESSION, STORAGE_ERROR }
data class SetupInputState(val editing: Boolean = false, val endpoint: String = "", val token: String = "", val saving: Boolean = false,
    val generation: Long = 0, val notice: SetupNotice = SetupNotice.NONE) {
    override fun toString() = "SetupInputState(editing=$editing,saving=$saving,notice=$notice,[redacted])"
}

/** Unsaved credentials are never saved/restored; every action and publication rechecks current safety. */
class MemoryLinkSetupController(private val drive: () -> DriveSnapshot,
    private val save: (String, String, () -> Boolean) -> Unit) {
    private val mutable = MutableStateFlow(SetupInputState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var epoch = 0L
    @Synchronized fun onStart() { foreground = true }
    @Synchronized fun open(): Boolean {
        val current = drive()
        if (!foreground || current.state != DriveState.PARKED || mutable.value.saving) return false
        epoch++
        mutable.value = SetupInputState(editing = true, generation = current.invalidationGeneration)
        return true
    }
    @Synchronized fun updateEndpoint(value: String) {
        if (canEdit()) mutable.value = mutable.value.copy(endpoint = value, notice = SetupNotice.NONE)
        else invalidate()
    }
    @Synchronized fun updateToken(value: String) {
        if (canEdit()) mutable.value = mutable.value.copy(token = value, notice = SetupNotice.NONE)
        else invalidate()
    }
    @Synchronized fun onDriveChanged() {
        if (drive().state != DriveState.PARKED || mutable.value.editing && drive().invalidationGeneration != mutable.value.generation)
            invalidate()
    }
    @Synchronized fun onStop() { foreground = false; invalidate() }

    fun submit(): SetupNotice {
        val submitted: SetupInputState
        val submittedEpoch: Long
        synchronized(this) {
            if (!canEdit()) { invalidate(); return SetupNotice.BLOCKED }
            submitted = mutable.value
            try { MemoryLinkEndpoint.validate(submitted.endpoint, BuildConfig.MEMORY_LINK_LOCAL_HTTP) }
            catch (_: IllegalArgumentException) { mutable.value = submitted.copy(notice = SetupNotice.INVALID_ENDPOINT); return SetupNotice.INVALID_ENDPOINT }
            if (!Regex("[A-Za-z0-9_-]{32,256}").matches(submitted.token)) {
                mutable.value = submitted.copy(notice = SetupNotice.INVALID_SESSION); return SetupNotice.INVALID_SESSION
            }
            submittedEpoch = epoch
            mutable.value = submitted.copy(saving = true)
        }
        fun allowed(): Boolean = synchronized(this) {
            foreground && epoch == submittedEpoch && drive().state == DriveState.PARKED &&
                drive().invalidationGeneration == submitted.generation
        }
        val result = try {
            if (!allowed()) throw LinkSetupBlocked()
            save(submitted.endpoint, submitted.token, ::allowed)
            if (allowed()) SetupNotice.SAVED else SetupNotice.BLOCKED
        } catch (_: LinkSetupBlocked) { SetupNotice.BLOCKED }
        catch (_: LinkSessionException) { SetupNotice.STORAGE_ERROR }
        catch (_: Exception) { SetupNotice.STORAGE_ERROR }
        synchronized(this) {
            mutable.value = SetupInputState(notice = if (foreground && epoch == submittedEpoch) result else SetupNotice.BLOCKED)
        }
        return result
    }
    private fun canEdit(): Boolean = foreground && mutable.value.editing && !mutable.value.saving &&
        drive().state == DriveState.PARKED && drive().invalidationGeneration == mutable.value.generation
    private fun invalidate() { epoch++; mutable.value = SetupInputState(notice = SetupNotice.BLOCKED) }
}
