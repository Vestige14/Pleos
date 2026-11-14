package app.memoryvault.platform.capture

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.memoryvault.MemoryVaultApp
import app.memoryvault.domain.DriveState
import app.memoryvault.ui.MemoryVaultTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-exported parked review. No launch, resume or permission result starts a microphone. */
class CaptureReviewActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private lateinit var controller: CaptureReviewController
    private var foreground by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshLocationPermission()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = CaptureRuntime.store(applicationContext)
        controller = CaptureReviewController({ container.driveSnapshot.value },
            load = { withContext(Dispatchers.IO) { store.all() } },
            playable = { id -> withContext(Dispatchers.IO) {
                store.readPcm(id)
                File(checkNotNull(store.findBySession(id)).privateAudioPath)
            } }, player = AndroidCaptureAudioPlayer())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.driveSnapshot.collect { controller.onDriveChanged() }
            }
        }
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val drive by container.driveSnapshot.collectAsStateWithLifecycle()
            val generation = drive.invalidationGeneration
            val allowed = foreground && drive.state == DriveState.PARKED && (!state.visible || controller.contentAllowed())
            MemoryVaultTheme {
                CaptureReviewContent(state, allowed,
                    onOpen = { lifecycleScope.launch { controller.open() } },
                    onPlay = { id -> lifecycleScope.launch { controller.play(id) } },
                    onStop = controller::stop, onBack = ::finish,
                    locationGranted = locationGranted,
                    onLocationPermission = { requestLocationPermission(generation) })
            }
        }
    }
    override fun onStart() { super.onStart(); foreground = true; controller.onStart(); refreshLocationPermission() }
    override fun onStop() { foreground = false; controller.onStop(); super.onStop() }
    private fun refreshLocationPermission() { locationGranted = AndroidCaptureLocationSource(applicationContext).support().permissionGranted }
    private fun requestLocationPermission(generation: Long) {
        val current = container.driveSnapshot.value
        if (foreground && current.state == DriveState.PARKED && current.invalidationGeneration == generation) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
}
