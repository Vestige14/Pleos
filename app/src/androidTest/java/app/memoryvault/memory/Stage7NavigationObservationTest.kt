package app.memoryvault.memory

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import app.memoryvault.memorycontract.NavigationEvidenceStatus
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Reads only the Application-owned provider; no SDK getter, route mutation or private payload log. */
class Stage7NavigationObservationTest {
    @Test fun reportExistingProviderTerminalStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MemoryVaultApp
        val provider = app.container.navigationEvidence
        val start = SystemClock.elapsedRealtime()
        while (provider.status() == NavigationEvidenceStatus.NOT_READY && SystemClock.elapsedRealtime() - start < 26000) {
            SystemClock.sleep(50) // bounded wait for the existing asynchronous startup, not a retry
        }
        val rows = provider.snapshot()
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stage7_navigation_status", provider.status().name)
            putInt("stage7_navigation_rows", rows.size)
            putString("stage7_navigation_kinds", rows.groupingBy { it.kind.name }.eachCount().toString())
            putLong("stage7_observation_wait_ms", SystemClock.elapsedRealtime() - start)
        })
        assertNotEquals("Provider did not finish initial connection deadline", NavigationEvidenceStatus.NOT_READY, provider.status())
    }
}
