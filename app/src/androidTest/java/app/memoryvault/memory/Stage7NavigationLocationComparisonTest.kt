package app.memoryvault.memory

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual two-point diagnostic, paired with emulator GPS and FusedLocation service logs. */
class Stage7NavigationLocationComparisonTest {
    @Test fun compareCurrentLocationBeforeAndAfterEmulatorGpsChange() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as MemoryVaultApp
        app.container.navigationEvidence.close()
        val helper = NaviHelper(app)
        val connected = CountDownLatch(1)
        val received = LinkedBlockingQueue<CurrentLocationInfo>()
        val listener = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { received.offer(currentLocationInfo) }
        }
        try {
            helper.initialize(onConnected = { helper.addListener(listener); connected.countDown() }, onFailed = { connected.countDown() })
            assertTrue("Connection callback timed out", connected.await(10, TimeUnit.SECONDS))
            fun query(phase: String): CurrentLocationInfo? {
                received.clear()
                val requestAt = System.currentTimeMillis()
                helper.getCurrentLocationInfo()
                val value = received.poll(10, TimeUnit.SECONDS)
                inst.sendStatus(2, Bundle().apply {
                    putString("location_phase", phase)
                    putLong("request_at_ms", requestAt)
                    putLong("response_at_ms", System.currentTimeMillis())
                    putBoolean("navi_response_received", value != null)
                    if (value != null) {
                        putDouble("navi_latitude", value.latitude)
                        putDouble("navi_longitude", value.longitude)
                    }
                })
                return value
            }
            val before = query("BEFORE")
            inst.sendStatus(2, Bundle().apply { putString("location_phase", "READY_FOR_SECOND_GPS") })
            SystemClock.sleep(20_000) // Defined manual observation window, not a retry.
            val after = query("AFTER")
            inst.sendStatus(2, Bundle().apply {
                putBoolean("navi_coordinates_changed", before != null && after != null &&
                    (before.latitude != after.latitude || before.longitude != after.longitude))
            })
            assertTrue("Both read-only current-location requests must receive callbacks", before != null && after != null)
        } finally { helper.removeListener(listener); helper.release() }
    }
}
