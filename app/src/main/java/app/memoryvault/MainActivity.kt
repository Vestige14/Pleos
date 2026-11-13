package app.memoryvault

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.memoryvault.domain.DriveState
import app.memoryvault.service.VoiceMemoService
import app.memoryvault.ui.AppUiBridge
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private var permissionSummary by mutableStateOf(R.string.voice_permissions_needed)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionSummary()
        container.refreshReadyNotification()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AppUiBridge(container) { VoiceMemoService.requestStart(this) }
        setContent {
            MemoryVaultTheme {
                MemoryVaultScreen(ui, onRequestPermissions = ::requestVoicePermissions, permissionSummary = permissionSummary)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
        container.retryVehicleConnection()
    }

    private fun requestVoicePermissions() {
        if (container.driveSnapshot.value.state != DriveState.PARKED) return
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add("pleos.car.permission.STT_SERVICE")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && container.driveSnapshot.value.state == DriveState.PARKED) {
            permissions.launch(missing.toTypedArray())
        } else refreshPermissionSummary()
    }

    private fun refreshPermissionSummary() {
        permissionSummary = if (container.notifications.availability().block() == null) R.string.voice_permissions_ready
        else R.string.voice_permissions_needed
    }
}
