package app.memoryvault.share.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.share.ShareDraft
import app.memoryvault.share.ShareEpoch
import app.memoryvault.share.ShareExportFailure
import app.memoryvault.share.ShareExportResult
import app.memoryvault.share.ShareFlowController
import app.memoryvault.share.ShareSafetyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun ShareFlowScreen(
    controller: ShareFlowController,
    safety: ShareSafetyGate,
    epoch: ShareEpoch,
    snapshots: StateFlow<DriveSnapshot>,
    foreground: StateFlow<Boolean>,
    onClose: () -> Unit,
    onExport: suspend (ShareDraft) -> ShareExportResult,
    thumbnail: suspend (String) -> ImageBitmap? = { null },
    loadingNotice: Int = R.string.share_loading,
) {
    val snapshot by snapshots.collectAsStateWithLifecycle()
    val active by foreground.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    var preview by remember(controller) { mutableStateOf<ShareDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val safe = active && snapshot.state == DriveState.PARKED && snapshot.invalidationGeneration == epoch.driveGeneration && safety.allows(epoch)

    LaunchedEffect(safe) {
        if (!safe) { preview = null; notice = null; focus.clearFocus(force = true); keyboard?.hide(); onClose() }
    }
    fun back() {
        focus.clearFocus(force = true); keyboard?.hide()
        if (safety.allows(epoch) && preview != null && !busy) { preview = null; notice = null } else onClose()
    }
    BackHandler(onBack = ::back)

    Scaffold { insets ->
        if (!safe) Text(stringResource(R.string.share_park_required), Modifier.padding(insets).padding(24.dp).testTag("share-locked"))
        else Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val draft = preview
            if (state.input == null) Text(stringResource(loadingNotice), Modifier.testTag("share-loading"))
            else if (draft == null) ShareSelectScreen(state, controller.preview() != null,
                onVisit = { id, included -> scope.launch { if (safety.allows(epoch)) controller.setVisit(id, included) } },
                onPhoto = { uri, selected -> scope.launch { if (safety.allows(epoch)) controller.setPhoto(uri, selected) } },
                onCaption = { id, text -> scope.launch { if (safety.allows(epoch)) controller.setCaption(id, text) } },
                onPreview = {
                    if (safety.allows(epoch)) {
                        focus.clearFocus(force = true); keyboard?.hide()
                        preview = controller.preview(); notice = null
                    }
                }, onBack = onClose,
                onRetry = { scope.launch { if (safety.allows(epoch)) controller.retrySave() } }, thumbnail = thumbnail)
            else SharePreviewScreen(draft, busy, notice,
                onExport = {
                    if (!busy && safety.allows(epoch)) {
                        busy = true
                        scope.launch {
                            try {
                                notice = when (val result = onExport(draft)) {
                                    is ShareExportResult.Saved -> if (result.receiptPersisted) R.string.share_saved else R.string.share_saved_receipt_unavailable
                                    is ShareExportResult.Failed -> when (result.reason) {
                                        ShareExportFailure.PHOTO_UNAVAILABLE -> R.string.share_photo_failed
                                        ShareExportFailure.TOO_LARGE -> R.string.share_too_large
                                        ShareExportFailure.RENDER_FAILED -> R.string.share_render_failed
                                        ShareExportFailure.STORAGE_FAILED -> R.string.share_storage_failed
                                    }
                                    ShareExportResult.Blocked -> R.string.share_park_required
                                    ShareExportResult.Busy -> R.string.share_exporting
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = R.string.share_storage_failed }
                            finally { busy = false }
                        }
                    }
                }, onBack = ::back, thumbnail = thumbnail)
        }
    }
}
