package app.memoryvault.ui

import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.Visit
import app.memoryvault.data.VisitCard
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartRequest
import kotlinx.coroutines.flow.MutableStateFlow

internal class FixtureUi(initialDrive: DriveState = DriveState.UNKNOWN) : MemoryVaultUi {
    override val driveSnapshot = MutableStateFlow(DriveSnapshot(state = initialDrive))
    override val voiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    override val readyNotificationEnabled = MutableStateFlow(false)
    override val day = MutableStateFlow<DayDraftState>(DayDraftState.Ready(DayDraft(
        "2026-11-08",
        listOf(VisitCard(Visit("v1", "2026-11-08", "방문 장소", 0.0, 0.0, 0, 1), emptyList(), emptyList())),
        listOf(Note(8, null, "  저장된 음성 원문  ", NoteKind.VOICE, 0, sourceDate = "2026-11-08")),
    )))
    val saved = mutableListOf<String>()
    var cancellations = 0
    var starts = 0
    override suspend fun addTypedNote(visitId: String, text: String): TypedMemoResult {
        saved += "$visitId:$text"
        return TypedMemoResult.SAVED
    }
    override suspend fun updateTypedNote(noteId: Long, text: String) = TypedMemoResult.NOT_EDITABLE
    override fun setReadyNotificationEnabled(enabled: Boolean): Boolean {
        readyNotificationEnabled.value = enabled
        return true
    }
    override fun retryVehicleConnection() = Unit
    override fun startVoice(): VoiceStartRequest {
        starts++
        voiceState.value = VoiceServiceState.CheckingVehicle
        return VoiceStartRequest.Dispatched
    }
    override fun cancelVoice() { cancellations++ }
}
