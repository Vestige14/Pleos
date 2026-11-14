package app.memoryvault

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.service.VoiceServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrivingActivityEntryTest {
    @Test fun launcherEntryAndExportedIntentsNeverStartRecordingWithoutAButtonClick() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0,
        )
        val recording = entries.singleOrNull { it.activityInfo.name == "app.memoryvault.DrivingActivity" }
        assertNotNull("A normal launcher recording entry is required", recording)
        assertEquals("기억 저장소 · 녹음", recording!!.loadLabel(context.packageManager).toString())
        val container = (context.applicationContext as MemoryVaultApp).container
        val before = container.voiceState.value
        assertFalse(before == VoiceServiceState.Listening || before == VoiceServiceState.Preparing)
        val entry = ComponentName(context.packageName, "app.memoryvault.DrivingActivity")
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent("app.memoryvault.private.SERVICE_START").putExtra("start_recording", true),
        ).forEach { request ->
            ActivityScenario.launch<Activity>(request.setComponent(entry)).use { scenario ->
                scenario.onActivity { assertEquals(before, container.voiceState.value) }
                scenario.recreate()
                scenario.onActivity { assertEquals(before, container.voiceState.value) }
            }
        }
    }
}
