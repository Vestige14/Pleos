package app.memoryvault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.memoryvault.domain.DriveState
import app.memoryvault.service.VoiceMemoService
import app.memoryvault.ui.AppUiBridge
import app.memoryvault.ui.MemoryVaultTheme
import app.memoryvault.ui.VoicePanelScreen

/** Voice controls only; recording lifetime remains owned by VoiceMemoService. */
class DrivingActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AppUiBridge(container) { VoiceMemoService.requestStart(this) }
        setContent {
            MemoryVaultTheme {
                VoicePanelScreen(ui, onOpenParkedSettings = {
                    if (container.driveSnapshot.value.state == DriveState.PARKED) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.retryVehicleConnection()
    }
}
