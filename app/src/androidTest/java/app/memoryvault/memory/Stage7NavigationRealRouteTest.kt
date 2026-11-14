package app.memoryvault.memory

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import app.memoryvault.memorycontract.NavigationEvidenceKind
import app.memoryvault.memorycontract.NavigationEvidenceStatus
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual acceptance only: set the public test destination in the navigation app first. */
class Stage7NavigationRealRouteTest {
    @Test fun currentAndRecentTestDestinationAreObservedAndPersisted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MemoryVaultApp
        val provider = app.container.navigationEvidence
        val expected = "더현대서울"
        val required = setOf(NavigationEvidenceKind.DESTINATION_OBSERVED, NavigationEvidenceKind.UNDATED_RECENT)
        val file = File(app.noBackupFilesDir, "navigation-evidence/observations-v1.json")
        val start = SystemClock.elapsedRealtime()
        var storedKinds = emptySet<String>()
        var durable = false
        while (SystemClock.elapsedRealtime() - start < 20_000) {
            if (file.exists()) {
                val data = JSONObject(file.readText())
                val rows = data.getJSONArray("rows")
                val matching = (0 until rows.length()).map(rows::getJSONObject).filter { it.getString("placeName") == expected }
                storedKinds = matching.map { it.getString("kind") }.toSet()
                durable = storedKinds.containsAll(required.map { it.name }) && matching.all {
                    it.isNull("eventAtMillis") && !it.isNull("latitude") && !it.isNull("longitude") &&
                        it.getDouble("latitude") in -90.0..90.0 && it.getDouble("longitude") in -180.0..180.0 &&
                        it.getLong("observedAtMillis") > 0
                }
            }
            if (provider.status() == NavigationEvidenceStatus.AVAILABLE && durable &&
                provider.snapshot().filter { it.placeName == expected }.map { it.kind }.toSet().containsAll(required)) break
            SystemClock.sleep(50)
        }
        val kinds = provider.snapshot().filter { it.placeName == expected }.map { it.kind }.toSet()
        instrumentation.sendStatus(2, Bundle().apply {
            putString("navigation_status", provider.status().name)
            putString("test_destination_kinds", kinds.joinToString())
            putString("stored_test_destination_kinds", storedKinds.joinToString())
            putBoolean("durable_coordinates_and_unknown_event_time", durable)
        })
        assertTrue("Actual current and recent destination must both reach the product provider", kinds.containsAll(required))
        assertTrue("Both observations must be persisted with coordinates and no fabricated event time", durable)
    }
}
