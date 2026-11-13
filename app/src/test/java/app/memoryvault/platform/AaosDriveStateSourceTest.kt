package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AaosDriveStateSourceTest {
    @Test fun `starting waits for lifecycle ready before reading vehicle data`() = runTest {
        val fixture = fixture()
        fixture.use {
            assertEquals(DriveReadinessReason.NOT_STARTED, it.snapshot.readinessReason)
            it.source.start()
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            assertEquals(DriveReadinessReason.CONNECTING, it.snapshot.readinessReason)
            assertEquals(0, it.access.totalReads)
            it.source.start()
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
            assertEquals(DriveReadinessReason.PROPERTY_ERROR, it.snapshot.readinessReason)
            assertEquals(0, it.access.liveListenerCount)
        }
    }

    @Test fun `D zero moving and reverse callbacks update the observable state`() = runTest {
        fixture().use {
            it.connect()
            it.access.emitUx(UxReading(true, 80))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(8, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.SPEED, reading(10f, timestamp = 60))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emit(DriveProperty.GEAR, reading(2, timestamp = 70))
            assertEquals(DriveState.DRIVING, it.snapshot.state)
            it.access.emitUx(UxReading(false, 0))
            assertEquals(DriveState.UNKNOWN, it.snapshot.state)
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
            assertTrue(old.closed)
            val new = it.connector.latest
            new.listener.onReady(FakeCarAccess())
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
            assertEquals(DriveReadinessReason.PERMISSION_DENIED, it.snapshot.readinessReason)
            it.connector.connectFailure = null
            it.source.start()
            it.connector.latest.listener.onReady(it.access)
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
        assertEquals(DriveState.UNKNOWN, fixture.snapshot.state)
        assertEquals(DriveReadinessReason.CLOSED, fixture.snapshot.readinessReason)
        assertTrue(fixture.snapshot.cleanupFailed)
        assertEquals(3, fixture.access.unregisterAttempts)
        assertEquals(1, fixture.connector.latest.closeCalls)
        assertEquals(0, fixture.access.liveListenerCount)
    }

    @Test fun `closing while connect is returning also closes the late connection handle`() = runTest {
        val fixture = fixture()
        fixture.connector.onConnect = { fixture.source.close() }
        fixture.source.start()
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
        ))
    }

    private class Fixture(
        val connector: FakeCarConnector,
        val access: FakeCarAccess,
        val source: AaosDriveStateSource,
    ) : AutoCloseable {
        val snapshot get() = source.snapshot.value
        fun connect() {
            source.start()
            connector.latest.listener.onReady(access)
        }
        override fun close() = source.close()
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
        var beforeReadUx: (() -> Unit)? = null
        var beforeReadProperty: ((DriveProperty) -> Unit)? = null
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
            unregisterAttempts++
            liveUx = false
            if (cleanupThrows) throw IllegalStateException()
        }
        override fun registerPropertyListener(property: DriveProperty, listener: VehicleListener): Boolean {
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

    companion object {
        private fun reading(value: Any?, timestamp: Long = 50, status: VehicleStatus = VehicleStatus.AVAILABLE) =
            VehicleReading(value, status, timestamp)
    }
}
