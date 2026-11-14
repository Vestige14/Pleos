package app.memoryvault.sync.ui

import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.memoryvault.MemoryVaultApp
import app.memoryvault.domain.DriveState
import app.memoryvault.sync.LinkSessionSummary
import app.memoryvault.sync.MemoryLinkRuntime
import app.memoryvault.sync.MemoryLinkSessionStore
import app.memoryvault.ui.MemoryVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-exported parked setup only. No saved token is ever loaded into an input field. */
class MemoryLinkSetupActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private lateinit var controller: MemoryLinkSetupController
    private lateinit var sessions: MemoryLinkSessionStore
    private var foreground by mutableStateOf(false)
    private var summary by mutableStateOf(LinkSessionSummary(false, null))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sessions = MemoryLinkSessionStore(this)
        controller = MemoryLinkSetupController({ container.driveSnapshot.value }, sessions::replace)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.driveSnapshot.collect {
                    controller.onDriveChanged()
                    if (it.state != DriveState.PARKED) hideInput()
                }
            }
        }
        lifecycleScope.launch { refreshSummary() }
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val drive by container.driveSnapshot.collectAsStateWithLifecycle()
            MemoryVaultTheme {
                MemoryLinkSetupScreen(state, foreground && drive.state == DriveState.PARKED, summary,
                    { controller.open() }, controller::updateEndpoint, controller::updateToken, {
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { controller.submit() }
                            hideInput()
                            if (result == SetupNotice.SAVED) {
                                refreshSummary()
                                MemoryLinkRuntime.onSessionUpdated(applicationContext)
                            }
                        }
                    }, ::finish)
            }
        }
    }
    override fun onStart() { super.onStart(); foreground = true; controller.onStart() }
    override fun onStop() {
        foreground = false; controller.onStop(); hideInput()
        super.onStop()
    }
    private fun hideInput() {
        window.decorView.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }
    private suspend fun refreshSummary() {
        summary = withContext(Dispatchers.IO) { try { sessions.summary() } catch (_: Exception) { LinkSessionSummary(false, null) } }
    }
}
