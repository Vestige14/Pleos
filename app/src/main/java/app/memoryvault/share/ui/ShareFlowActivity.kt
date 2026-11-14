package app.memoryvault.share.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.memoryvault.DemoConfig
import app.memoryvault.MemoryVaultApp
import app.memoryvault.RepositoryState
import app.memoryvault.R
import app.memoryvault.share.AndroidShareGalleryIo
import app.memoryvault.share.AndroidSharePhotoAccess
import app.memoryvault.share.AndroidSharePngRenderer
import app.memoryvault.share.RoomShareSelectionStore
import app.memoryvault.share.ShareChooser
import app.memoryvault.share.ShareEpoch
import app.memoryvault.share.ShareExporter
import app.memoryvault.share.ShareFlowController
import app.memoryvault.share.ShareInputMapper
import app.memoryvault.share.ShareIntentFactory
import app.memoryvault.share.ShareSelectionDatabase
import app.memoryvault.share.SharedPreferencesShareReceiptStore
import app.memoryvault.share.ShareSessionGate
import app.memoryvault.ui.MemoryVaultTheme
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-exported, parked-only flow. Returning from a safety/lifecycle invalidation starts at the day draft. */
class ShareFlowActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val foreground = MutableStateFlow(false)
    private lateinit var safety: ShareSessionGate
    private lateinit var controller: ShareFlowController
    private var epoch by mutableStateOf<ShareEpoch?>(null)
    private var loadingNotice by mutableStateOf(R.string.share_loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.visibility = View.INVISIBLE
        val date = try { LocalDate.parse(intent.getStringExtra(EXTRA_DATE) ?: DemoConfig.DEMO_DATE.toString()).toString() }
        catch (_: Exception) { finish(); return }
        safety = ShareSessionGate(container.driveSnapshot)
        controller = ShareFlowController(RoomShareSelectionStore(ShareSelectionDatabase.open(applicationContext)))
        val thumbnails = ShareThumbnailLoader(contentResolver, safety)
        val exporter = ShareExporter(safety, AndroidShareGalleryIo(contentResolver), AndroidSharePngRenderer(contentResolver),
            AndroidSharePhotoAccess(contentResolver), ShareChooser { uri ->
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    val token = epoch
                    if (token == null || !safety.allows(token)) false
                    else try { startActivity(ShareIntentFactory.chooser(uri.toUri())); true }
                    catch (_: ActivityNotFoundException) { false }
                }
            }, { "memory-vault-${System.currentTimeMillis()}-${UUID.randomUUID()}.png" },
            SharedPreferencesShareReceiptStore.open(applicationContext))
        setContent {
            MemoryVaultTheme {
                val token = epoch
                if (token == null) Text(stringResource(loadingNotice))
                else ShareFlowScreen(controller, safety, token, container.driveSnapshot, foreground,
                    onClose = ::hideAndFinish,
                    onExport = { draft -> withContext(Dispatchers.IO) { exporter.export(draft) } },
                    thumbnail = { uri -> thumbnails.load(uri, token) }, loadingNotice = loadingNotice)
            }
        }
        scope.launch {
            safety.changes.collect {
                val token = epoch
                if (token != null && !safety.allows(token) && !isChangingConfigurations) hideAndFinish()
            }
        }
        scope.launch {
            container.repository.collectLatest { repository ->
                when (repository) {
                    RepositoryState.Loading -> loadingNotice = R.string.share_loading
                    RepositoryState.Failed -> loadingNotice = R.string.storage_unavailable
                    is RepositoryState.Ready -> try {
                        repository.repository.observeDay(date).collect { controller.open(ShareInputMapper.from(it)) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { loadingNotice = R.string.storage_unavailable }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::safety.isInitialized || isFinishing) return
        safety.resume()
        val current = safety.capture()
        if (current == null) { hideAndFinish(); return }
        epoch = current
        foreground.value = true
        window.decorView.visibility = View.VISIBLE
    }

    override fun onStop() {
        foreground.value = false
        if (::safety.isInitialized) safety.stop()
        hideInput()
        // Lifecycle-aware Compose collection may already be stopped, so also hide the native view synchronously.
        window.decorView.visibility = View.INVISIBLE
        if (!isChangingConfigurations) finish()
        super.onStop()
    }

    private fun hideAndFinish() {
        foreground.value = false
        if (::safety.isInitialized) safety.stop()
        hideInput()
        window.decorView.visibility = View.INVISIBLE
        finish()
    }

    private fun hideInput() {
        currentFocus?.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { const val EXTRA_DATE = "share_date" }
}
