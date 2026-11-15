package app.memoryvault

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.PhotoPermissionPolicy
import app.memoryvault.service.VoiceMemoService
import app.memoryvault.ui.AppUiBridge
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private var openingDrivingPanel = false
    private var permissionSummary by mutableStateOf(R.string.voice_permissions_needed)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionSummary()
        container.refreshReadyNotification()
    }
    private val photoPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.refreshPhotos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.driveSnapshot.collect { snapshot ->
                    if (snapshot.state == DriveState.DRIVING && !openingDrivingPanel) {
                        openingDrivingPanel = true
                        startActivity(Intent(this@MainActivity, DrivingActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                }
            }
        }
        val ui = AppUiBridge(container) { VoiceMemoService.requestStart(this) }
        setContent {
            MemoryVaultTheme {
                MemoryVaultScreen(ui, onRequestPermissions = ::requestVoicePermissions, permissionSummary = permissionSummary,
                    onRequestPhotoPermissions = ::requestPhotoPermissions, onShareDay = ::openShareDay)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
        container.onPhotoResume()
        container.retryVehicleConnection()
    }

    override fun onStop() {
        container.onPhotoStop()
        super.onStop()
    }

    private fun openShareDay() {
        if (container.driveSnapshot.value.state != DriveState.PARKED) return
        startActivity(Intent(this, app.memoryvault.share.ui.ShareFlowActivity::class.java)
            .putExtra(app.memoryvault.share.ui.ShareFlowActivity.EXTRA_DATE, container.recordDate.value))
    }

    private fun requestPhotoPermissions() {
        if (container.driveSnapshot.value.state != DriveState.PARKED) return
        // Request the read, re-selection and location grants together. Never request on launch.
        photoPermissions.launch(PhotoPermissionPolicy.requestPermissions(Build.VERSION.SDK_INT).toTypedArray())
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
