package app.memoryvault.memory

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import app.memoryvault.memorycontract.NavigationEvidenceKind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual real-SDK acceptance: operator drives only emulator GPS, never injects a callback. */
class Stage7NavigationArrivalTest {
    @Test fun realArrivalCallbackIsPersisted() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as MemoryVaultApp
        val provider = app.container.navigationEvidence
        val expected = "더현대서울"
        val beganAt = System.currentTimeMillis()
        val deadline = SystemClock.elapsedRealtime() + 180_000
        var ready = false
        var persisted = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val rows = provider.snapshot()
            val destination = rows.firstOrNull { it.placeName == expected && it.kind == NavigationEvidenceKind.DESTINATION_OBSERVED }
            if (!ready && destination != null) {
                inst.sendStatus(2, Bundle().apply {
                    putString("arrival_probe", "READY_FOR_EMULATOR_ROUTE")
                    putString("test_destination_latitude", destination.latitude.toString())
                    putString("test_destination_longitude", destination.longitude.toString())
                })
                ready = true
            }
            val arrival = rows.firstOrNull { it.placeName == expected && it.kind == NavigationEvidenceKind.ARRIVAL_OBSERVED && it.observedAtMillis >= beganAt }
            if (arrival != null) {
                val file = File(app.noBackupFilesDir, "navigation-evidence/observations-v1.json")
                if (file.exists()) {
                    val data = JSONObject(file.readText()).getJSONArray("rows")
                    persisted = (0 until data.length()).map(data::getJSONObject).any {
                        it.getString("id") == arrival.id && it.getString("kind") == "ARRIVAL_OBSERVED" &&
                        it.isNull("eventAtMillis") && it.isNull("latitude") && it.isNull("longitude") &&
                        it.getLong("observedAtMillis") >= beganAt
                    }
                }
                if (persisted) break
            }
            SystemClock.sleep(100)
        }
        inst.sendStatus(2, Bundle().apply {
            putString("arrival_provider_status", provider.status().name)
            putBoolean("actual_arrival_persisted", persisted)
        })
        assertTrue("No fresh real navigation arrival persisted within the manual scenario window", persisted)
    }
}
