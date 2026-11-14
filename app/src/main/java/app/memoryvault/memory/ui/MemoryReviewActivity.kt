package app.memoryvault.memory.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.memoryvault.MemoryVaultApp
import app.memoryvault.domain.DriveState
import app.memoryvault.memory.*
import app.memoryvault.sync.ui.MemoryLinkSetupActivity
import app.memoryvault.ui.MemoryVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-exported. An explicit open creates the foreground capability; no launch starts a microphone. */
class MemoryReviewActivity : ComponentActivity() {
    private val container get() = (application as MemoryVaultApp).container
    private var foreground by mutableStateOf(false)
    private var epoch by mutableStateOf<MemoryReviewEpoch?>(null)
    private var result by mutableStateOf<MemoryReviewResult?>(null)
    private var manual by mutableStateOf<MemoryManualSelection?>(null)
    private var busy by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val runtime by MemoryAssociationRuntime.instance.collectAsStateWithLifecycle()
            val drive by container.driveSnapshot.collectAsStateWithLifecycle()
            val active = epoch
            val allowed = foreground && drive.state == DriveState.PARKED && active != null && runtime?.reviewAllowed(active) == true
            LaunchedEffect(allowed) { if (!allowed) { manual = null; result = null; epoch = null } }
            MemoryVaultTheme {
                val live = runtime
                if (live == null) {
                    MemoryReviewScreen(MemoryAssociationProgress(errorCode = "STORAGE_UNAVAILABLE"), false, false, null, null,
                        onOpen = {}, onClose = ::finish, onRefresh = {}, onAction = { _, _, _ -> },
                        onManual = {}, onReanalyze = { _, _, _ -> }, onPhoto = {}, onSetup = {})
                } else {
                    val progress by live.progress.collectAsStateWithLifecycle()
                    MemoryReviewScreen(progress, allowed, busy, result, if (allowed) manual else null,
                        onOpen = {
                            if (foreground && container.driveSnapshot.value.state == DriveState.PARKED) {
                                epoch = live.beginReview(); result = null
                            }
                        }, onClose = ::finish, onRefresh = { if (allowed) live.reconcile() },
                        onAction = { item, action, selected ->
                            active?.let { token -> perform { live.decide(item, action, selected, token) } }
                        }, onManual = { item ->
                            active?.let { token ->
                                lifecycleScope.launch {
                                    if (!live.reviewAllowed(token) || busy) return@launch
                                    busy = true
                                    try {
                                        val choices = withContext(Dispatchers.IO) { live.manualChoices(item, token) }
                                        if (foreground && live.reviewAllowed(token)) manual = MemoryManualSelection(item, choices)
                                    } catch (_: MemoryReviewBlocked) { }
                                    catch (_: Exception) { result = MemoryReviewResult.FAILED }
                                    finally { busy = false }
                                }
                            }
                        }, onReanalyze = { item, nextPage, date ->
                            active?.let { token -> perform { live.reanalyze(item, nextPage, date, token) } }
                        }, onPhoto = { source -> active?.let { token -> openPhoto(live, token, source) } },
                        onSetup = { active?.let { token ->
                            if (foreground && live.reviewAllowed(token)) startActivity(Intent(this, MemoryLinkSetupActivity::class.java))
                        } }, onManualSave = { selection, ids ->
                            active?.let { token -> perform { live.correct(selection.item, ids, token) } }
                        }, onManualCancel = { manual = null })
                }
            }
        }
    }
    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() {
        foreground = false; epoch = null; manual = null; result = null
        MemoryAssociationRuntime.instance.value?.endReview()
        currentFocus?.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        super.onStop()
    }
    private fun perform(action: suspend () -> MemoryReviewResult) {
        if (busy || !foreground) return
        lifecycleScope.launch {
            busy = true
            try {
                val outcome = withContext(Dispatchers.IO) { action() }
                if (foreground) { result = outcome; if (outcome == MemoryReviewResult.SAVED) manual = null }
            } finally { busy = false }
        }
    }
    private fun openPhoto(runtime: MemoryAssociationRuntime, token: MemoryReviewEpoch, source: MemorySource) {
        if (source.candidate.kind != MemoryKind.PHOTO || !foreground || !runtime.reviewAllowed(token)) return
        lifecycleScope.launch {
            val uri = Uri.parse(source.localRef)
            val mime = try { withContext(Dispatchers.IO) {
                if (!runtime.reviewAllowed(token) || uri.scheme != "content") throw MemoryReviewBlocked()
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { } ?: throw IllegalStateException()
                contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: throw IllegalStateException()
            } } catch (_: Exception) { result = MemoryReviewResult.FAILED; return@launch }
            if (foreground && runtime.reviewAllowed(token)) {
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: Exception) { result = MemoryReviewResult.FAILED }
            }
        }
    }
}
