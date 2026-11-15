package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AaosDriveStateSourceTest {
    @Test fun `UNKNOWN generation survives stable recovery and reconnect but not transition alone`() = runTest {
        fixture().use {
            it.connect()
            val first = it.snapshot.invalidationGeneration
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.TRANSITION, it.snapshot.state)
            assertEquals(first, it.snapshot.invalidationGeneration)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            assertEquals(first, it.snapshot.invalidationGeneration)
            it.access.emitUx(UxReading(false, 80))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(first + 1, it.snapshot.invalidationGeneration)
            it.access.emitUx(UxReading(false, 80))
            assertEquals(first + 1, it.snapshot.invalidationGeneration)
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            assertEquals(first + 1, it.snapshot.invalidationGeneration)
            it.connector.latest.listener.onDisconnected()
            assertEquals(first + 2, it.snapshot.invalidationGeneration)
            runCurrent()
            it.connector.latest.listener.onReady(FakeCarAccess())
            runCurrent()
            assertEquals(DriveState.PARKED, it.snapshot.state)
            assertEquals(first + 2, it.snapshot.invalidationGeneration)
        }
    }
    @Test fun `production bridge exposes authoritative UNKNOWN while explicit recording remains available`() = runTest {
        fixture().use { fixture ->
            fixture.connect()
            fixture.access.emitUx(UxReading(true, 80))
            fixture.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            val bridge = DriveSafetyBridge(fixture.source.snapshot)
            runCurrent()
            assertEquals(DriveState.DRIVING, bridge.state.value)
            val saved = mutableListOf<String>()
            val useCase = VoiceMemoUseCase(bridge.state, { _, text, _ -> saved += text; 31L }, driveSafety = bridge)
            fixture.access.emitUx(UxReading(false, 80))
            assertEquals(DriveState.UNKNOWN, fixture.snapshot.state)
            val input = GateInput(flowOf(SpeechEvent.Final("unknown raw")))
            assertEquals(VoiceMemoOutcome.Saved(31L), useCase.record("explicit-unknown", 1L, input))
            assertEquals(DriveState.UNKNOWN, bridge.state.value)
            assertEquals(1, input.eventsCalls)
            assertEquals(listOf("cancel", "close"), input.cleanup)
            assertEquals(listOf("unknown raw"), saved)
        }
    }

    @Test fun `production queued bridge preserves short UNKNOWN while the accepted session continues`() = runTest {
        fixture().use { fixture ->
            fixture.connect()
            fixture.access.emitUx(UxReading(true, 80))
            fixture.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            val bridge = DriveSafetyBridge(fixture.source.snapshot)
            runCurrent()
            val saved = mutableListOf<String>()
            val useCase = VoiceMemoUseCase(bridge.state, { _, text, _ -> saved += text; 31L }, driveSafety = bridge)
            val input = GateInput()
            val run = async { useCase.record("short-unknown", 1L, input) }
            runCurrent()
            fixture.access.emitUx(UxReading(false, 80))
            assertEquals(DriveState.UNKNOWN, fixture.snapshot.state)
            fixture.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.DRIVING, fixture.snapshot.state)
            input.events.trySend(SpeechEvent.Final("stale final"))
            runCurrent()
            assertEquals(VoiceMemoOutcome.Saved(31L), run.await())
            assertEquals(listOf("stale final"), saved)
            assertEquals(listOf("cancel", "close"), input.cleanup)
            assertEquals(VoiceMemoOutcome.Saved(31L), useCase.record("fresh", 2L,
                GateInput(flowOf(SpeechEvent.Final("fresh raw")))))
            assertEquals(listOf("stale final", "fresh raw"), saved)
        }
    }

    @Test fun `production queued invalidation during storage does not cancel an accepted save`() = runTest {
        fixture().use { fixture ->
            fixture.connect()
            fixture.access.emitUx(UxReading(true, 80))
            fixture.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            val bridge = DriveSafetyBridge(fixture.source.snapshot)
            runCurrent()
            val mayReturn = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()
            val useCase = VoiceMemoUseCase(bridge.state, { _, text, _ ->
                committed += text; mayReturn.await(); 31L
            }, driveSafety = bridge)
            val input = GateInput(flowOf(SpeechEvent.Final("committed")))
            val run = async { useCase.record("saving", 1L, input) }
            runCurrent()
            assertEquals(listOf("committed"), committed)
            fixture.access.emitUx(UxReading(false, 80))
            fixture.access.emitUx(UxReading(true, 80))
            mayReturn.complete(Unit)
            runCurrent()
            assertEquals(VoiceMemoOutcome.Saved(31L), run.await())
            assertEquals(listOf("committed"), committed)
            assertEquals(listOf("cancel", "close"), input.cleanup)
        }
    }

    private class GateInput(private val source: kotlinx.coroutines.flow.Flow<SpeechEvent>? = null) : SpeechInput {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        var eventsCalls = 0
        val cleanup = mutableListOf<String>()
        override fun events(): kotlinx.coroutines.flow.Flow<SpeechEvent> { eventsCalls++; return source ?: events.receiveAsFlow() }
        override fun stopCapture() = Unit
        override fun cancel() { cleanup += "cancel" }
        override fun close() { cleanup += "close" }
    }
    @Test fun `transition deadline does not wait for a blocked Binder IO queue`() = runTest {
        ThreadFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            val blocked = BlockingCall()
            fixture.access.beforeReadUx = blocked::block
            try {
                blocked.awaitEntered()
                fixture.access.emitUx(UxReading(true, 80))
                assertEquals(DriveState.TRANSITION, fixture.source.snapshot.value.state)
                advanceTimeBy(500)
                runCurrent()
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(DriveReadinessReason.TRANSITION_TIMEOUT, fixture.source.snapshot.value.readinessReason)
                assertEquals(1L, blocked.release.count)
                blocked.release.countDown()
                fixture.awaitIdle()
            } finally { blocked.release.countDown() }
        }
    }
    @Test fun `accepted voice continues across ordinary P D callback ordering`() = runTest {
        fixture().use { fixture ->
            fixture.connect()
            fixture.access.emitUx(UxReading(true, 80))
            fixture.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            val bridge = DriveSafetyBridge(fixture.source.snapshot)
            runCurrent()
            val events = Channel<SpeechEvent>(Channel.UNLIMITED)
            val input = object : SpeechInput {
                override fun events() = events.receiveAsFlow()
                override fun stopCapture() = Unit
                override fun cancel() = Unit
                override fun close() = Unit
            }
            val useCase = VoiceMemoUseCase(bridge.state, { _, _, _ -> 31L }, driveSafety = bridge)
            val run = async { useCase.record("transition", 1L, input) }
            runCurrent()
            fixture.access.emit(DriveProperty.GEAR, reading(4, timestamp = 70))
            runCurrent()
            assertFalse("Ordinary ordered P/D callbacks cancelled an accepted session", run.isCompleted)
            fixture.access.emitUx(UxReading(false, 0))
            runCurrent()
            assertEquals(DriveState.PARKED, fixture.snapshot.state)
            fixture.access.emit(DriveProperty.GEAR, reading(8, timestamp = 80))
            runCurrent()
            assertFalse(run.isCompleted)
            fixture.access.emitUx(UxReading(true, 80))
            events.trySend(SpeechEvent.Final("raw"))
            assertEquals(VoiceMemoOutcome.Saved(31L), run.await())
        }
    }

    @Test fun `eligible transition has a fixed 500ms budget that callbacks cannot extend`() = runTest {
        fixture().use {
            it.connect()
            val generation = it.snapshot.invalidationGeneration
            it.access.emitUx(UxReading(true, 80))
            assertEquals("TRANSITION", it.snapshot.state.name)
            assertEquals(generation, it.snapshot.invalidationGeneration)
            advanceTimeBy(499)
            runCurrent()
            it.access.emitUx(UxReading(true, 80))
            assertEquals("TRANSITION", it.snapshot.state.name)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(generation + 1, it.snapshot.invalidationGeneration)
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(generation + 1, it.snapshot.invalidationGeneration)
            advanceTimeBy(100)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(4, timestamp = 70))
            assertEquals("TRANSITION", it.snapshot.state.name)
        }
    }

    @Test fun `periodic reads cannot extend a transition and close invalidates its timer`() = runTest {
        fixture().use {
            it.connect()
            advanceTimeBy(750)
            it.access.ux = UxReading(true, 80)
            it.access.emitUx(UxReading(true, 80))
            advanceTimeBy(499)
            runCurrent()
            assertEquals(DriveState.TRANSITION, it.snapshot.state)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(DriveReadinessReason.TRANSITION_TIMEOUT, it.snapshot.readinessReason)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            it.access.emit(DriveProperty.GEAR, reading(4, timestamp = 70))
            assertEquals(DriveState.TRANSITION, it.snapshot.state)
            it.source.close()
            advanceTimeBy(600)
            runCurrent()
            assertEquals(DriveReadinessReason.CLOSED, it.snapshot.readinessReason)
        }
    }

    @Test fun `new connection cannot inherit previous stable transition eligibility`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            val next = FakeCarAccess().apply { ux = UxReading(true, 80) }
            it.connector.latest.listener.onReady(next)
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.INCONSISTENT_SIGNALS, it.snapshot.readinessReason)
            advanceTimeBy(600)
            runCurrent()
            assertEquals(DriveReadinessReason.INCONSISTENT_SIGNALS, it.snapshot.readinessReason)
        }
    }

    @Test fun `startup conflict is immediately unknown without a transition window`() = runTest {
        fixture().use {
            it.access.ux = UxReading(true, 80)
            it.connect()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            advanceTimeBy(501)
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
        }
    }

    @Test fun `invalid signal during transition is immediately unknown and cannot reopen grace`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            assertEquals("TRANSITION", it.snapshot.state.name)
            it.access.emit(DriveProperty.SPEED, reading(1f, timestamp = 60))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            it.access.emit(DriveProperty.SPEED, reading(0f, timestamp = 70))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            it.access.emitUx(UxReading(false, 0))
            assertEquals(DriveState.PARKED, it.snapshot.state)
        }
    }

    @Test fun `resolved and replaced sessions invalidate old transition deadlines`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            advanceTimeBy(200)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(4, timestamp = 70))
            val staleUx = it.access.savedUxListener!!
            it.connector.latest.listener.onDisconnected()
            runCurrent()
            val next = FakeCarAccess()
            it.connector.latest.listener.onReady(next)
            runCurrent()
            assertEquals(DriveState.PARKED, it.snapshot.state)
            staleUx(UxReading(true, 80))
            advanceTimeBy(600)
            runCurrent()
            assertEquals(DriveState.PARKED, it.snapshot.state)
        }
    }

    @Test fun `slow getter cannot delay disconnect UNKNOWN or callback return`() = slowGetter(Signal.DISCONNECT)
    @Test fun `slow getter cannot delay error UNKNOWN or callback return`() = slowGetter(Signal.ERROR)
    @Test fun `slow getter cannot delay close UNKNOWN or return`() = slowGetter(Signal.CLOSE)
    @Test fun `slow cleanup cannot delay disconnect UNKNOWN or callback return`() = slowCleanup(Signal.DISCONNECT)
    @Test fun `slow cleanup cannot delay error UNKNOWN or callback return`() = slowCleanup(Signal.ERROR)
    @Test fun `slow cleanup cannot delay close UNKNOWN or return`() = slowCleanup(Signal.CLOSE)

    @Test fun `new ready waits for prior slow cleanup to finish`() {
        ThreadFixture().use { fixture ->
            val blocked = BlockingCall()
            fixture.access.beforeUnregister = blocked::block
            val disconnect = AsyncCall { fixture.signal(Signal.DISCONNECT) }
            var reconnect: AsyncCall? = null
            val next = FakeCarAccess()
            try {
                blocked.awaitEntered()
                disconnect.assertReturned()
                reconnect = AsyncCall { fixture.connector.latest.listener.onReady(next) }
                reconnect.assertReturned()
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(0, next.liveListenerCount)
                assertEquals(0, next.totalReads)
                blocked.release.countDown()
                fixture.awaitIdle()
                fixture.awaitParked()
                assertEquals(0, fixture.access.liveListenerCount)
                assertEquals(DriveState.PARKED, fixture.source.snapshot.value.state)
            } finally {
                blocked.release.countDown()
                disconnect.join()
                reconnect?.join()
            }
        }
    }

    @Test fun `registration completing after close is eventually unregistered`() {
        ThreadFixture().use { fixture ->
            val blocked = BlockingCall()
            val next = FakeCarAccess().apply {
                beforeRegisterProperty = { if (it == DriveProperty.SPEED) blocked.block() }
            }
            val reconnect = AsyncCall { fixture.connector.latest.listener.onReady(next) }
            var closing: AsyncCall? = null
            try {
                blocked.awaitEntered()
                closing = AsyncCall { fixture.source.close() }
                closing.assertReturned()
                assertEquals(DriveReadinessReason.CLOSED, fixture.source.snapshot.value.readinessReason)
                blocked.release.countDown()
                reconnect.join()
                fixture.awaitIdle()
                assertEquals(0, next.liveListenerCount)
                assertEquals(3, next.unregisterAttempts)
                assertEquals(1, fixture.connector.latest.closeCalls)
                assertEquals(DriveReadinessReason.CLOSED, fixture.source.snapshot.value.readinessReason)
            } finally {
                blocked.release.countDown()
                reconnect.join()
                closing?.join()
            }
        }
    }

    private fun slowGetter(signal: Signal) {
        ThreadFixture().use { fixture ->
            val blocked = BlockingCall()
            fixture.access.beforeReadUx = blocked::block
            var invalidation: AsyncCall? = null
            try {
                blocked.awaitEntered()
                invalidation = AsyncCall { fixture.signal(signal) }
                invalidation.assertReturned()
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(signal.reason, fixture.source.snapshot.value.readinessReason)
                assertEquals(1L, blocked.release.count)
                blocked.release.countDown()
                fixture.awaitIdle()
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(signal.reason, fixture.source.snapshot.value.readinessReason)
                assertEquals(0, fixture.access.liveListenerCount)
            } finally {
                blocked.release.countDown()
                invalidation?.join()
            }
        }
    }

    private fun slowCleanup(signal: Signal) {
        ThreadFixture().use { fixture ->
            val blocked = BlockingCall()
            fixture.access.beforeUnregister = blocked::block
            val invalidation = AsyncCall { fixture.signal(signal) }
            try {
                blocked.awaitEntered()
                invalidation.assertReturned()
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(signal.reason, fixture.source.snapshot.value.readinessReason)
                assertEquals(1L, blocked.release.count)
                blocked.release.countDown()
                fixture.awaitIdle()
                assertEquals(0, fixture.access.liveListenerCount)
                assertEquals(DriveState.UNKNOWN, fixture.source.snapshot.value.state)
                assertEquals(signal.reason, fixture.source.snapshot.value.readinessReason)
            } finally {
                blocked.release.countDown()
                invalidation.join()
            }
        }
    }

    @Test fun `starting waits for lifecycle ready before reading vehicle data`() = runTest {
        val fixture = fixture()
        fixture.use {
            assertEquals(DriveReadinessReason.NOT_STARTED, it.snapshot.readinessReason)
            it.source.start()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.CONNECTING, it.snapshot.readinessReason)
            assertEquals(0, it.access.totalReads)
            it.source.start()
            runCurrent()
            assertEquals(1, it.connector.connections.size)
        }
    }

    @Test fun `current reads establish parked without waiting for callbacks`() = runTest {
        fixture().use {
            it.connect()
            assertEquals(DriveState.PARKED, it.snapshot.state)
            assertEquals(false, it.snapshot.requiresDistractionOptimization)
            assertEquals(0, it.snapshot.activeRestrictions)
            assertEquals(3, it.access.totalReads)
            assertEquals(3, it.access.liveListenerCount)
        }
    }

    @Test fun `callback values cannot grant readiness before initial reads finish`() = runTest {
        fixture().use {
            it.access.afterRegister = {
                it.access.emitUx(UxReading(false, 0))
                it.access.emit(DriveProperty.GEAR, reading(4))
                it.access.emit(DriveProperty.SPEED, reading(0f))
                assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            }
            it.access.readFailures[DriveProperty.SPEED] = IllegalStateException()
            it.connect()
            assertEquals(DriveReadinessReason.READ_FAILED, it.snapshot.readinessReason)
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `missing current inputs remain unknown and can be supplied by later reads`() = runTest {
        for (missing in listOf("ux", "gear", "speed")) {
            fixture().use {
                when (missing) {
                    "ux" -> it.access.ux = null
                    "gear" -> it.access.values[DriveProperty.GEAR] = null
                    "speed" -> it.access.values[DriveProperty.SPEED] = null
                }
                it.connect()
                assertEquals(DriveState.UNKNOWN, it.snapshot.state)
                it.access.ux = UxReading(false, 0)
                it.access.values[DriveProperty.GEAR] = reading(4)
                it.access.values[DriveProperty.SPEED] = reading(0f)
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(DriveState.PARKED, it.snapshot.state)
            }
        }
    }

    @Test fun `current vehicle support and permission failures prevent readiness`() = runTest {
        fixture().use {
            it.access.unsupported = DriveProperty.GEAR
            it.connect()
            assertEquals(DriveReadinessReason.UNSUPPORTED, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
        }
        fixture().use {
            it.access.readFailures[DriveProperty.GEAR] = SecurityException()
            it.connect()
            assertEquals(DriveReadinessReason.PERMISSION_DENIED, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `UX read failure remains latched despite cached good callbacks`() = runTest {
        fixture().use {
            it.access.uxReadFailure = IllegalStateException()
            it.connect()
            val callbacks = it.access.allCallbacks.toList()
            callbacks.forEach { callback -> callback.onChange(reading(4)) }
            it.access.savedUxListener?.invoke(UxReading(false, 0))
            assertEquals(DriveReadinessReason.READ_FAILED, it.snapshot.readinessReason)
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
        }
    }

    @Test fun `failed or throwing property registration cleans all attempted listeners`() = runTest {
        for (throwing in listOf(false, true)) {
            fixture().use {
                it.access.registrationFailure = DriveProperty.SPEED
                it.access.registrationThrows = throwing
                it.connect()
                assertEquals(DriveState.UNKNOWN, it.snapshot.state)
                assertEquals(DriveReadinessReason.LISTENER_REGISTRATION_FAILED, it.snapshot.readinessReason)
                assertEquals(0, it.access.liveListenerCount)
                assertEquals(3, it.access.unregisterAttempts)
            }
        }
    }

    @Test fun `throwing UX registration attempts its own cleanup`() = runTest {
        fixture().use {
            it.access.uxRegistrationFailure = true
            it.connect()
            assertEquals(DriveReadinessReason.LISTENER_REGISTRATION_FAILED, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
            assertEquals(1, it.access.unregisterAttempts)
        }
    }

    @Test fun `unavailable error and property error callbacks lock current connection`() = runTest {
        for (status in listOf(VehicleStatus.UNAVAILABLE, VehicleStatus.ERROR)) {
            fixture().use {
                it.connect()
                val callback = it.access.callback(DriveProperty.SPEED)
                callback.onChange(reading(0f, status = status))
                callback.onChange(reading(0f, timestamp = 60))
                runCurrent()
                assertEquals(DriveState.UNKNOWN, it.snapshot.state)
                assertEquals(
                    if (status == VehicleStatus.UNAVAILABLE) DriveReadinessReason.PROPERTY_UNAVAILABLE
                    else DriveReadinessReason.PROPERTY_ERROR,
                    it.snapshot.readinessReason,
                )
                assertEquals(0, it.access.liveListenerCount)
            }
        }
        fixture().use {
            it.connect()
            it.access.callback(DriveProperty.GEAR).onError()
            runCurrent()
            assertEquals(DriveReadinessReason.PROPERTY_ERROR, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `D zero moving and reverse callbacks update the observable state`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.TRANSITION, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.SPEED, reading(10f, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(2, timestamp = 70))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emitUx(UxReading(false, 0))
            assertEquals(DriveState.TRANSITION, it.snapshot.state)
        }
    }

    @Test fun `out of order callbacks and periodic cached values cannot replace newer signals`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            it.access.ux = UxReading(true, 80)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 70))
            it.access.emit(DriveProperty.GEAR, reading(4, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DriveState.DRIVING, it.snapshot.state)
        }
    }

    @Test fun `invalid monotonic timestamps cannot classify parked`() = runTest {
        fixture().use {
            it.access.values[DriveProperty.GEAR] = reading(4, timestamp = 0)
            it.connect()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.INVALID_TIMESTAMP, it.snapshot.readinessReason)
        }
    }

    @Test fun `different values at the same timestamp cannot establish a trusted state`() = runTest {
        fixture().use {
            it.connect()
            it.access.emit(DriveProperty.GEAR, reading(8))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.INVALID_TIMESTAMP, it.snapshot.readinessReason)
        }
    }

    @Test fun `disconnect stops refresh and old generation callbacks cannot restore state`() = runTest {
        fixture().use {
            it.connect()
            val oldGear = it.access.callback(DriveProperty.GEAR)
            val oldUx = it.access.savedUxListener!!
            it.connector.latest.listener.onDisconnected()
            runCurrent()
            val reads = it.access.totalReads
            assertEquals(DriveReadinessReason.DISCONNECTED, it.snapshot.readinessReason)
            assertEquals(null, it.snapshot.activeRestrictions)
            assertEquals(0, it.access.liveListenerCount)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(reads, it.access.totalReads)
            oldGear.onChange(reading(4, timestamp = 60))
            oldUx(UxReading(false, 0))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            val next = FakeCarAccess().apply { values[DriveProperty.SPEED] = null }
            it.connector.latest.listener.onReady(next)
            runCurrent()
            assertEquals(DriveReadinessReason.MISSING_SPEED, it.snapshot.readinessReason)
            oldGear.onChange(reading(8, timestamp = 80))
            oldUx(UxReading(true, 80))
            next.emit(DriveProperty.SPEED, reading(0f, timestamp = 80))
            assertEquals(DriveState.PARKED, it.snapshot.state)
        }
    }

    @Test fun `new lifecycle ready resets all inputs even without a disconnect callback`() = runTest {
        fixture().use {
            it.connect()
            it.connector.latest.listener.onReady(FakeCarAccess().apply { ux = null })
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.MISSING_UX, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `periodic current reads detect status errors without callbacks and stop after failure`() = runTest {
        fixture().use {
            it.connect()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(3, it.access.totalReads)
            it.access.values[DriveProperty.SPEED] = reading(0f, status = VehicleStatus.UNAVAILABLE)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(DriveReadinessReason.PROPERTY_UNAVAILABLE, it.snapshot.readinessReason)
            assertEquals(6, it.access.totalReads)
            val reads = it.access.totalReads
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(reads, it.access.totalReads)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `periodic read exception cannot be repaired by same connection callbacks`() = runTest {
        fixture().use {
            it.connect()
            val callback = it.access.callback(DriveProperty.SPEED)
            it.access.readFailures[DriveProperty.SPEED] = IllegalStateException()
            advanceTimeBy(1_000)
            runCurrent()
            callback.onChange(reading(0f, timestamp = 90))
            assertEquals(DriveReadinessReason.READ_FAILED, it.snapshot.readinessReason)
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
        }
    }

    @Test fun `reentrant newer callbacks during getters win over stale read results`() = runTest {
        fixture().use {
            it.connect()
            it.access.beforeReadUx = {
                it.access.emitUx(UxReading(true, 80))
                it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 80))
            }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            assertEquals(80, it.snapshot.activeRestrictions)
        }
    }

    @Test fun `reentrant failure during getter cannot be overwritten by the returned good sample`() = runTest {
        fixture().use {
            it.connect()
            it.access.beforeReadUx = { it.access.callback(DriveProperty.SPEED).onError() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.PROPERTY_ERROR, it.snapshot.readinessReason)
        }
    }

    @Test fun `getter status failure wins even when a good callback arrives during that getter`() = runTest {
        fixture().use {
            it.connect()
            it.access.values[DriveProperty.SPEED] = reading(0f, status = VehicleStatus.UNAVAILABLE)
            it.access.beforeReadProperty = { property ->
                if (property == DriveProperty.SPEED) it.access.emit(property, reading(0f, timestamp = 80))
            }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.PROPERTY_UNAVAILABLE, it.snapshot.readinessReason)
        }
    }

    @Test fun `explicit start retries failed connection and ignores previous connector callbacks`() = runTest {
        fixture().use {
            it.access.readFailures[DriveProperty.SPEED] = IllegalStateException()
            it.connect()
            val old = it.connector.latest
            it.source.start()
            runCurrent()
            assertTrue(old.closed)
            val new = it.connector.latest
            new.listener.onReady(FakeCarAccess())
            runCurrent()
            assertEquals(DriveState.PARKED, it.snapshot.state)
            old.listener.onDisconnected()
            old.listener.onReady(FakeCarAccess().apply { ux = UxReading(true, 80) })
            assertEquals(DriveState.PARKED, it.snapshot.state)
        }
    }

    @Test fun `connect exceptions are conservative and can be explicitly retried`() = runTest {
        fixture().use {
            it.connector.connectFailure = SecurityException()
            it.source.start()
            runCurrent()
            assertEquals(DriveReadinessReason.PERMISSION_DENIED, it.snapshot.readinessReason)
            it.connector.connectFailure = null
            it.source.start()
            runCurrent()
            it.connector.latest.listener.onReady(it.access)
            runCurrent()
            assertEquals(DriveState.PARKED, it.snapshot.state)
        }
    }

    @Test fun `close removes all listeners stops reads and disconnects exactly once`() = runTest {
        val fixture = fixture()
        fixture.connect()
        val callback = fixture.access.callback(DriveProperty.GEAR)
        fixture.source.close()
        val reads = fixture.access.totalReads
        advanceTimeBy(5_000)
        runCurrent()
        callback.onChange(reading(4, timestamp = 60))
        fixture.connector.latest.listener.onReady(FakeCarAccess())
        fixture.source.start()
        fixture.source.close()
        assertEquals(DriveReadinessReason.CLOSED, fixture.snapshot.readinessReason)
        assertEquals(0, fixture.access.liveListenerCount)
        assertEquals(3, fixture.access.unregisterAttempts)
        assertEquals(1, fixture.connector.latest.closeCalls)
        assertEquals(reads, fixture.access.totalReads)
        assertEquals(1, fixture.connector.connections.size)
    }

    @Test fun `cleanup exceptions still attempt every resource and are reported without unlocking`() = runTest {
        val fixture = fixture()
        fixture.connect()
        fixture.access.cleanupThrows = true
        fixture.connector.latest.cleanupThrows = true
        fixture.source.close()
        runCurrent()
        assertEquals(DriveState.UNKNOWN, fixture.snapshot.state)
        assertEquals(DriveReadinessReason.CLOSED, fixture.snapshot.readinessReason)
        assertTrue(fixture.snapshot.cleanupFailed)
        assertEquals(3, fixture.access.unregisterAttempts)
        assertEquals(1, fixture.connector.latest.closeCalls)
        assertEquals(0, fixture.access.liveListenerCount)
    }

    @Test fun `cleanup failure prevents a replacement session from initializing`() = runTest {
        fixture().use {
            it.connect()
            it.access.cleanupThrows = true
            val next = FakeCarAccess()
            it.connector.latest.listener.onReady(next)
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            runCurrent()
            assertEquals(DriveReadinessReason.CLEANUP_FAILED, it.snapshot.readinessReason)
            assertTrue(it.snapshot.cleanupFailed)
            assertEquals(0, next.totalReads)
            assertEquals(0, next.liveListenerCount)
        }
    }

    @Test fun `closing while connect is returning also closes the late connection handle`() = runTest {
        val fixture = fixture()
        fixture.connector.onConnect = { fixture.source.close() }
        fixture.source.start()
        runCurrent()
        assertEquals(DriveReadinessReason.CLOSED, fixture.snapshot.readinessReason)
        assertTrue(fixture.connector.latest.closed)
        assertFalse(fixture.snapshot.cleanupFailed)
    }

    @Test fun `a consumer closing on reconnect cannot leave newly registered listeners behind`() = runTest {
        val fixture = fixture()
        fixture.connect()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.source.snapshot.collect {
                if (it.readinessReason == DriveReadinessReason.CONNECTING) fixture.source.close()
            }
        }
        val next = FakeCarAccess()
        fixture.connector.latest.listener.onReady(next)
        runCurrent()
        assertEquals(DriveReadinessReason.CLOSED, fixture.snapshot.readinessReason)
        assertEquals(0, next.liveListenerCount)
        assertEquals(0, next.totalReads)
        assertTrue(fixture.connector.latest.closed)
    }

    private fun TestScope.fixture(): Fixture {
        val connector = FakeCarConnector()
        return Fixture(connector, FakeCarAccess(), AaosDriveStateSource(
            connector,
            StandardTestDispatcher(testScheduler),
            { 100 + testScheduler.currentTime * 1_000_000 },
            StandardTestDispatcher(testScheduler),
        ), { testScheduler.runCurrent() })
    }

    private class Fixture(
        val connector: FakeCarConnector,
        val access: FakeCarAccess,
        val source: AaosDriveStateSource,
        val drain: () -> Unit,
    ) : AutoCloseable {
        val snapshot get() = source.snapshot.value
        fun connect() {
            source.start()
            drain()
            connector.latest.listener.onReady(access)
            drain()
        }
        override fun close() { source.close(); drain() }
    }

    private class FakeCarConnector : CarConnector {
        val connections = mutableListOf<Connection>()
        val latest get() = connections.last()
        var connectFailure: RuntimeException? = null
        var onConnect: (() -> Unit)? = null
        override fun connect(listener: CarConnectionListener): AutoCloseable {
            connectFailure?.let { throw it }
            return Connection(listener).also { connections += it; onConnect?.invoke() }
        }
        class Connection(val listener: CarConnectionListener) : AutoCloseable {
            var closeCalls = 0
            var cleanupThrows = false
            val closed get() = closeCalls > 0
            override fun close() {
                closeCalls++
                if (cleanupThrows) throw IllegalStateException()
            }
        }
    }

    private class FakeCarAccess : CarAccess {
        var ux: UxReading? = UxReading(false, 0)
        val values = mutableMapOf<DriveProperty, VehicleReading?>(
            DriveProperty.GEAR to reading(4), DriveProperty.SPEED to reading(0f),
        )
        var unsupported: DriveProperty? = null
        val readFailures = mutableMapOf<DriveProperty, RuntimeException>()
        var uxReadFailure: RuntimeException? = null
        var uxRegistrationFailure = false
        var registrationFailure: DriveProperty? = null
        var registrationThrows = false
        var cleanupThrows = false
        @Volatile var beforeReadUx: (() -> Unit)? = null
        var beforeReadProperty: ((DriveProperty) -> Unit)? = null
        @Volatile var beforeUnregister: (() -> Unit)? = null
        var beforeRegisterProperty: ((DriveProperty) -> Unit)? = null
        var afterRegister: (() -> Unit)? = null
        var savedUxListener: ((UxReading) -> Unit)? = null
        var liveUx = false
        private val listeners = mutableMapOf<DriveProperty, VehicleListener>()
        val allCallbacks = mutableListOf<VehicleListener>()
        var totalReads = 0
        var unregisterAttempts = 0
        val liveListenerCount get() = listeners.size + if (liveUx) 1 else 0

        fun callback(property: DriveProperty) = listeners.getValue(property)
        fun emit(property: DriveProperty, value: VehicleReading) = callback(property).onChange(value)
        fun emitUx(value: UxReading) = savedUxListener!!.invoke(value)
        override fun isSupported(property: DriveProperty) = property != unsupported
        override fun registerUxListener(listener: (UxReading) -> Unit) {
            savedUxListener = listener
            liveUx = true
            if (uxRegistrationFailure) throw IllegalStateException()
        }
        override fun unregisterUxListener() {
            beforeUnregister?.invoke()
            unregisterAttempts++
            liveUx = false
            if (cleanupThrows) throw IllegalStateException()
        }
        override fun registerPropertyListener(property: DriveProperty, listener: VehicleListener): Boolean {
            beforeRegisterProperty?.invoke(property)
            listeners[property] = listener
            allCallbacks += listener
            if (property == registrationFailure) {
                if (registrationThrows) throw IllegalStateException()
                return false
            }
            if (property == DriveProperty.SPEED) afterRegister?.invoke()
            return true
        }
        override fun unregisterPropertyListener(listener: VehicleListener) {
            beforeUnregister?.invoke()
            unregisterAttempts++
            listeners.entries.removeAll { it.value === listener }
            if (cleanupThrows) throw IllegalStateException()
        }
        override fun readUx(): UxReading? {
            totalReads++
            uxReadFailure?.let { throw it }
            val current = ux
            beforeReadUx?.invoke()
            return current
        }
        override fun readProperty(property: DriveProperty): VehicleReading? {
            totalReads++
            readFailures[property]?.let { throw it }
            val current = values[property]
            beforeReadProperty?.invoke(property)
            return current
        }
    }

    private enum class Signal(val reason: DriveReadinessReason) {
        DISCONNECT(DriveReadinessReason.DISCONNECTED),
        ERROR(DriveReadinessReason.PROPERTY_ERROR),
        CLOSE(DriveReadinessReason.CLOSED),
    }

    private class BlockingCall {
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release blocked I/O" }
        }
        fun awaitEntered() = assertTrue("I/O did not reach latch", entered.await(5, TimeUnit.SECONDS))
    }

    private class AsyncCall(action: () -> Unit) {
        private val returned = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "mv03d-safety-event") {
            try { action() } catch (problem: Throwable) { failure.set(problem) }
            finally { returned.countDown() }
        }
        fun assertReturned() {
            assertTrue("Safety event waited for external I/O", returned.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Safety event threw", it) }
        }
        fun join() {
            worker.join(5_000)
            assertFalse("Safety event thread leaked", worker.isAlive)
        }
    }

    private class ThreadFixture(deadlineDispatcher: CoroutineDispatcher = Dispatchers.Default) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "mv03d-car-io") }
        private val dispatcher = executor.asCoroutineDispatcher()
        val connector = FakeCarConnector()
        val access = FakeCarAccess()
        val source = AaosDriveStateSource(connector, dispatcher, { 100 }, deadlineDispatcher)
        private lateinit var errorCallback: VehicleListener

        init {
            val connected = CountDownLatch(1)
            connector.onConnect = { connected.countDown() }
            source.start()
            assertTrue("Connector did not run", connected.await(5, TimeUnit.SECONDS))
            connector.latest.listener.onReady(access)
            awaitParked()
            errorCallback = access.callback(DriveProperty.GEAR)
        }

        fun awaitParked() = runBlocking {
            withTimeout(5_000) { source.snapshot.first { it.state == DriveState.PARKED } }
        }

        fun signal(signal: Signal) = when (signal) {
            Signal.DISCONNECT -> connector.latest.listener.onDisconnected()
            Signal.ERROR -> errorCallback.onError()
            Signal.CLOSE -> source.close()
        }

        fun awaitIdle() { executor.submit {}.get(5, TimeUnit.SECONDS) }

        override fun close() {
            try {
                source.close()
                awaitIdle()
            } finally {
                dispatcher.close()
                assertTrue("Car I/O executor leaked", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    companion object {
        private fun reading(value: Any?, timestamp: Long = 50, status: VehicleStatus = VehicleStatus.AVAILABLE) =
            VehicleReading(value, status, timestamp)
    }
}
