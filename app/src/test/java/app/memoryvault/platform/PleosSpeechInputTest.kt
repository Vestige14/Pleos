package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.domain.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PleosSpeechInputTest {
    @Test fun directCancel_completesTheFlowAndInvalidatesInitialization() = runTest {
        val sdk = Sdk()
        val input = PleosSpeechInput { sdk }
        val collect = launch { input.events().collect() }
        runCurrent()
        input.cancel()
        runCurrent()
        assertTrue("Cancelled input left a live events collector", collect.isCompleted)
        sdk.succeed()
        assertFalse(sdk.calls.contains("request"))
    }

    @Test fun repeatedInitializationCallback_cannotRequestMicrophoneTwice() = runTest {
        val sdk = Sdk()
        val input = PleosSpeechInput { sdk }
        val collect = launch { input.events().collect() }
        runCurrent()
        sdk.succeed()
        sdk.succeed()
        assertEquals(1, sdk.calls.count { it == "request" })
        collect.cancel()
        collect.join()
    }
    @Test fun rejectedInput_isInertAndNeverConstructsTheSdk() = runTest {
        var creations = 0
        val input = PleosSpeechInput { creations++; Sdk() }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.PARKED), { _, _, _ -> 1L })
        assertEquals(0, creations)
        assertEquals(VoiceMemoOutcome.Blocked, useCase.record("blocked", 0L, input))
        assertEquals(0, creations)
    }

    @Test fun acceptedSdk_initializesKoreanThenRequests_andPreservesOnlyRawFinal() = runTest {
        val sdk = Sdk()
        val saved = mutableListOf<String>()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ -> saved += text; 9L })
        val active = async { useCase.record("accepted", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        assertEquals(listOf("initialize"), sdk.calls)
        assertEquals(VoiceState.Preparing, useCase.state.value)
        sdk.succeed()
        runCurrent()
        assertEquals(listOf("initialize", "korean", "add", "request"), sdk.calls)
        assertEquals(VoiceState.Preparing, useCase.state.value)
        sdk.listener!!.started()
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        sdk.listener!!.updated("private partial", false)
        runCurrent()
        assertTrue(saved.isEmpty())
        val raw = "  원문\n그대로\t "
        sdk.listener!!.updated(raw, true)
        sdk.listener!!.updated("duplicate", true)
        assertEquals(VoiceMemoOutcome.Saved(9L), active.await())
        assertEquals(listOf(raw), saved)
        assertEquals(listOf("stop", "remove", "release"), sdk.calls.takeLast(3))
    }

    @Test fun captureDeadlineBeforeInitialization_preventsAnyLateMicrophoneRequest() = runTest {
        val sdk = Sdk()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 9L })
        val active = async { useCase.record("slow-init", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        sdk.succeed()
        runCurrent()
        assertFalse(sdk.calls.contains("request"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), active.await())
        assertEquals("release", sdk.calls.last())
    }

    @Test fun cancelDuringInitialization_releasesAndInvalidatesLateCallbacks() = runTest {
        val sdk = Sdk()
        val saved = mutableListOf<String>()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ -> saved += text; 9L })
        val active = async { useCase.record("cancel", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        useCase.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        val beforeLate = sdk.calls.toList()
        sdk.succeed()
        runCurrent()
        assertEquals(beforeLate, sdk.calls)
        assertFalse(sdk.calls.contains("request"))
        assertEquals("release", sdk.calls.last())
        assertTrue(saved.isEmpty())
    }

    @Test fun initializationFailure_reportsClassifiedFailureAndReleases() = runTest {
        val sdk = Sdk()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 9L })
        val active = async { useCase.record("init-error", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        sdk.initialization!!.failed()
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), active.await())
        assertFalse(sdk.calls.contains("request"))
        assertEquals("release", sdk.calls.last())
    }

    @Test fun cleanupFailures_attemptEveryResource_andDoNotHideSuccessfulStorage() = runTest {
        val sdk = Sdk(failAt = setOf("stop", "remove", "release"))
        val saved = mutableListOf<String>()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ -> saved += text; 9L })
        val active = async { useCase.record("cleanup", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        sdk.succeed()
        runCurrent()
        val stale = sdk.listener!!
        stale.updated("saved", true)
        assertEquals(VoiceMemoOutcome.Saved(9L, cleanupFailed = true), active.await())
        assertEquals(listOf("stop", "remove", "release"), sdk.calls.takeLast(3))
        stale.started()
        stale.updated("stale", true)
        stale.failed()
        runCurrent()
        assertEquals(listOf("saved"), saved)
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Saved(9L, cleanupFailed = true)), useCase.state.value)
    }

    @Test fun partialRegistrationFailure_stillRemovesListenerAndReleases() = runTest {
        val sdk = Sdk(failAt = setOf("add"))
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 9L })
        val active = async { useCase.record("registration", 0L, PleosSpeechInput { sdk }) }
        runCurrent()
        sdk.succeed()
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), active.await())
        assertFalse(sdk.calls.contains("request"))
        assertTrue(sdk.calls.contains("remove"))
        assertEquals("release", sdk.calls.last())
    }

    @Test fun cancellingFlowCollectorWithoutCoordinator_stillReleasesSdk() = runTest {
        val sdk = Sdk()
        val input = PleosSpeechInput { sdk }
        val collect = launch { input.events().collect() }
        runCurrent()
        collect.cancel()
        collect.join()
        assertEquals("release", sdk.calls.last())
        sdk.succeed()
        assertFalse(sdk.calls.contains("request"))
    }

    @Test fun secondCollection_cannotCreateASecondSdk() = runTest {
        var creations = 0
        val sdk = Sdk()
        val input = PleosSpeechInput { creations++; sdk }
        val first = launch { input.events().collect() }
        runCurrent()
        val second = launch { input.events().collect() }
        runCurrent()
        assertEquals(1, creations)
        second.join()
        assertFalse("Rejected collection must not tear down the accepted session", sdk.calls.contains("release"))
        first.cancel()
        first.join()
    }

    private class Sdk(private val failAt: Set<String> = emptySet()) : SpeechSdk {
        val calls = mutableListOf<String>()
        var initialization: SpeechSdk.Initialization? = null
        var listener: SpeechSdk.Listener? = null
        override fun initialize(callback: SpeechSdk.Initialization) { calls += "initialize"; initialization = callback }
        override fun setKoreanLocale() { call("korean") }
        override fun addListener(listener: SpeechSdk.Listener) { this.listener = listener; call("add") }
        override fun request() { call("request") }
        override fun stop() { call("stop") }
        override fun removeListener(listener: SpeechSdk.Listener) { assertSame(this.listener, listener); call("remove") }
        override fun release() { call("release") }
        fun succeed() { initialization!!.ready() }
        private fun call(name: String) { calls += name; if (name in failAt) error("private SDK exception payload") }
    }
}
