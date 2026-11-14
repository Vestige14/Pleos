package app.memoryvault.platform.capture

import app.memoryvault.domain.*
import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureVoiceContractTest {
    @Test fun acceptedSessionBindsBeforeCollectionAndFinalPersistenceWrapsTheExistingNoteSave() = runTest {
        val order = mutableListOf<String>()
        val input = object : SpeechInput {
            override fun bindSession(sessionId: String, capturedAt: Long) { order += "$sessionId:$capturedAt" }
            override fun events(): Flow<SpeechEvent> { order += "events"; return flowOf(SpeechEvent.Final(" raw ")) }
            override suspend fun persistFinal(text: String, saveNote: suspend () -> Long): Long {
                assertEquals(" raw ", text); order += "prepare"; val id = saveNote(); order += "commit"; return id
            }
            override fun stopCapture() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> order += "note"; 42 })
        assertEquals(VoiceMemoOutcome.Saved(42), voice.record("session", 99, input))
        assertEquals(listOf("session:99", "events", "prepare", "note", "commit"), order)
    }

    @Test fun audioPersistenceFailureMustPreventNoteSaveAndSavedStatus() = runTest {
        var saves = 0
        val input = object : SpeechInput {
            override fun events() = flowOf(SpeechEvent.Final("raw"))
            override suspend fun persistFinal(text: String, saveNote: suspend () -> Long): Long = error("storage unavailable")
            override fun stopCapture() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> saves++; 42 })
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE), voice.record("s", 99, input))
        assertEquals(0, saves)
    }

    @Test fun explicitCancellationInvokesIncompleteDiscardWhileNormalFinalDoesNot() = runTest {
        var discarded = 0
        val input = object : SpeechInput {
            override fun events(): Flow<SpeechEvent> = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
            override suspend fun discardIncomplete() { discarded++ }
            override fun stopCapture() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 42 })
        val result = async { voice.record("s", 99, input) }
        runCurrent(); voice.cancel(); result.await()
        assertEquals(1, discarded)
    }
}
